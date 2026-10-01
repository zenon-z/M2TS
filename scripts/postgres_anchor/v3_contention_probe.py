"""Why does the V3 drain lose 25x of its service under producer load?

TWO HYPOTHESES, and they are different findings:

  (A) SHARED-INDEX SERIALISATION. Producers and drain contend on the ONE unique partial index:
      every producer insert probes/writes it, every drain UPDATE removes a row from it. Then the
      result is "a single-index outbox serialises producer and drain" -- a real, citable systems
      result about THIS SCHEMA, and NOT about BASE propagation. The simulator has no counterpart,
      because its dirty set is a hash map with no shared write structure.

  (B) GENERAL CONTENTION. The drain degrades under concurrent load regardless of sharing the
      index. Then "the real drain's per-worker service degrades 25x under load" stands as written,
      and the simulator's N-parallel model is missing a term.

Only (B) is about BASE propagation, so the mechanism has to be named before either goes in a
write-up. The ladder runs either way -- the crossover sitting inside {1..24} does not depend on
which of these is true.

THIS SCRIPT RUNS BOTH TESTS.

  probe   Samples pg_stat_activity wait events during a loaded run, split by backend role. If the
          drain is parked on tuple/transaction locks or buffer latches against the producers, it
          says so directly -- the cheap check, run first.

  disjoint  The decisive one. Drain a PRE-FILLED table A to empty, three ways:
              idle     producers stopped                      -> the isolated rate
              shared   producers writing to A (same index)     -> the observed degraded rate
              disjoint producers writing to B (same load, NO shared index)
          If `disjoint` recovers toward `idle`, it is (A). If it stays near `shared`, it is (B).
          Same producer load in both loaded arms, so the only thing that changes is whether the
          index is shared.
"""
import argparse
import random
import statistics
import sys
import threading
import time
from collections import Counter

try:
    import psycopg2
except ImportError:  # pragma: no cover
    print("psycopg2 is required: pip install -r requirements.txt", file=sys.stderr)
    raise

DEFAULT_DSN = "host=127.0.0.1 port=15432 dbname=tgs user=tgs password=tgs"

_stop = threading.Event()

# Long enough to cover the drain pass, short enough that a leak cannot outlive the next cell.
IN_SERVER_SECONDS = 12.0

DRAIN_N_SQL = """
CREATE OR REPLACE PROCEDURE v3_drain_n(target int, INOUT done int)
LANGUAGE plpgsql AS $p$
DECLARE hit int;
BEGIN
  done := 0;
  WHILE done < target LOOP
    UPDATE v3_outbox SET processed_at = clock_timestamp() WHERE id = (
      SELECT id FROM v3_outbox WHERE processed_at IS NULL
      ORDER BY enqueued_at FOR UPDATE SKIP LOCKED LIMIT 1);
    GET DIAGNOSTICS hit = ROW_COUNT;
    COMMIT;
    EXIT WHEN hit = 0;
    done := done + 1;
  END LOOP;
END $p$;
"""

TABLE_B_DDL = """
DROP TABLE IF EXISTS v3_outbox_b CASCADE;
CREATE TABLE v3_outbox_b (
    id           BIGSERIAL PRIMARY KEY,
    target_id    INTEGER     NOT NULL,
    enqueued_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processed_at TIMESTAMPTZ NULL
);
CREATE UNIQUE INDEX v3_outbox_b_pending ON v3_outbox_b (target_id) WHERE processed_at IS NULL;
CREATE INDEX v3_outbox_b_drain ON v3_outbox_b (enqueued_at) WHERE processed_at IS NULL;
"""

# Clears B so the producers writing to it never saturate their own index and stall -- the point is
# to reproduce the LOAD, not to measure B.
B_KEEPER_SQL = "DELETE FROM v3_outbox_b"


def producer(dsn, table, n_parent, k, seed, barrier):
    rng = random.Random(seed)
    conn = psycopg2.connect(dsn)
    try:
        with conn.cursor() as cur:
            barrier.wait()
            while not _stop.is_set():
                ids = rng.sample(range(1, n_parent + 1), k)
                cur.execute("SELECT target_id FROM v3_parent WHERE id = ANY(%s) "
                            "AND target_id IS NOT NULL", (ids,))
                targets = [r[0] for r in cur.fetchall()]
                if targets:
                    cur.execute(
                        f"INSERT INTO {table} (target_id) SELECT unnest(%s::int[]) "
                        f"ON CONFLICT (target_id) WHERE processed_at IS NULL DO NOTHING",
                        (targets,))
                conn.commit()
    finally:
        conn.close()


def b_keeper(dsn):
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            while not _stop.is_set():
                cur.execute(B_KEEPER_SQL)
                time.sleep(0.25)
    finally:
        conn.close()


def prefill(conn, rows, reset=True):
    """Pre-fill with `rows` pending rows, from an IDENTICAL starting state every time.

    TRUNCATE IS NOT HOUSEKEEPING -- it is what makes repeated measurements comparable. Without it
    the table grows by `rows` per measurement, and the drain's rate decays monotonically with table
    size: 16 repeats on a QUIESCENT host fell 55,124 -> 6,416 rows/s (8.6x, CV 72.5%) at 128,000
    rows / 14 MB, with ZERO dead tuples -- so it is table SIZE, not bloat, and autovacuum cannot
    help. With TRUNCATE the same 16 repeats give CV 2.9%.

    This invalidated every sequenced comparison made before it was found: arms were run in order
    (idle, shared, disjoint), so each arm was measured on a bigger table than the one before and the
    LAST arm was systematically penalised. That is the exact direction that produced "disjoint shows
    no recovery".
    """
    with conn.cursor() as cur:
        if reset:
            cur.execute("TRUNCATE v3_outbox")
        cur.execute("UPDATE v3_outbox SET processed_at = clock_timestamp() "
                    "WHERE processed_at IS NULL")
        cur.execute("INSERT INTO v3_outbox (target_id) SELECT target_id FROM v3_parent "
                    "WHERE target_id IS NOT NULL ORDER BY target_id LIMIT %s", (rows,))
        n = cur.rowcount
    conn.commit()
    return n


def drain_to_empty(dsn, rows):
    """Time how long ONE in-server worker takes to clear `rows`. Returns rows/s."""
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            cur.execute(DRAIN_N_SQL)
            # (procedure text lives in DRAIN_N_SQL, one definition shared by both callers)
            t0 = time.perf_counter()
            cur.execute("CALL v3_drain_n(%s, 0)", (rows,))
            done = int(cur.fetchone()[0])
            dt = time.perf_counter() - t0
    finally:
        conn.close()
    return done, dt, (done / dt if dt else float("nan"))


IN_SERVER_PRODUCER_SQL = """
CREATE OR REPLACE PROCEDURE v3_producer_loop(deadline timestamptz, n_parent int, k int)
LANGUAGE plpgsql AS $p$
DECLARE ids int[];
BEGIN
  LOOP
    EXIT WHEN clock_timestamp() >= deadline;
    SELECT array_agg((1 + floor(random() * n_parent))::int)
      INTO ids FROM generate_series(1, k);
    INSERT INTO v3_outbox (target_id)
      SELECT DISTINCT target_id FROM v3_parent
       WHERE id = ANY(ids) AND target_id IS NOT NULL
      ON CONFLICT (target_id) WHERE processed_at IS NULL DO NOTHING;
    COMMIT;
  END LOOP;
END $p$;
"""


def in_server_producer(dsn, duration_s, n_parent, k):
    """A producer with NO Python client in the loop.

    The draw is with replacement (random()) and de-duplicated by DISTINCT, where the real producer
    uses rng.sample without replacement. That difference matters for c0 and NOT for capacity, and
    this procedure is used ONLY to measure capacity -- it never produces a c0 figure.
    """
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            # The procedure is created ONCE by the caller, not here. 16 backends racing on
            # CREATE OR REPLACE PROCEDURE threw "tuple concurrently updated" and killed one
            # producer thread -- which leaves the drain competing with 15 instead of 16 and
            # biases the budget estimate UPWARD, in the one number this probe exists to produce.
            cur.execute("CALL v3_producer_loop(clock_timestamp() + %s * interval '1 second', %s, %s)",
                        (duration_s, n_parent, k))
    finally:
        conn.close()


def budget(dsn, w, n_parent, k, rows):
    """How much of the drain's degradation is the harness's CLIENT rather than the server?

    Same drain, same W, two producer implementations: Python client threads vs in-server loops.
    The drain's loaded share of its idle rate then estimates the EFFECTIVE SERVER BUDGET:

        C  ~  (W + 1) * R_loaded / R_idle

    reading it as "how many drain-equivalents of capacity the backends are sharing". It is an
    effective-parallelism figure, not a core count -- the backends are not perfectly CPU-bound --
    and it is the quantity the simulator's withTotalBudget should be set from, because it is what
    the producers and drain on the Postgres side ACTUALLY share.
    """
    global _stop
    # KILL LEFTOVERS FIRST. `_stop` is a Python flag and cannot interrupt a CALL already running
    # in a backend, so an in-server producer loop outlives the process that started it for the rest
    # of its deadline. The first W-sweep measured each "idle" baseline while the PREVIOUS cell's
    # producers were still looping: the baseline fell 46,702 -> 15,185 rows/s as W grew, which is
    # impossible for a measurement taken with producers stopped, and every C from that sweep is void.
    reaper = psycopg2.connect(dsn)
    reaper.autocommit = True
    with reaper.cursor() as cur:
        cur.execute("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity "
                    "WHERE datname = current_database() AND pid <> pg_backend_pid() "
                    "AND query LIKE '%%v3_producer_loop%%'")
        killed = cur.fetchone()[0]
    reaper.close()
    if killed:
        print(f"  (terminated {killed} leftover in-server producer backend(s) before measuring)")
        time.sleep(1.0)

    idle_rate = run_case(dsn, "idle (producers stopped)", rows, w, n_parent, k, None)

    py_rate = run_case(dsn, f"python-client producers (W={w})", rows, w, n_parent, k, "v3_outbox")

    _stop = threading.Event()
    setup = psycopg2.connect(dsn)
    setup.autocommit = True
    with setup.cursor() as cur:
        cur.execute(IN_SERVER_PRODUCER_SQL)     # once, before any producer starts
    setup.autocommit = False
    prefill(setup, rows); setup.close()
    threads = [threading.Thread(target=in_server_producer, daemon=True,
                                args=(dsn, IN_SERVER_SECONDS, n_parent, k)) for _ in range(w)]
    for t in threads:
        t.start()
    time.sleep(2.0)
    done, dt, srv_rate = drain_to_empty(dsn, rows)
    _stop.set()
    print(f"  {'IN-SERVER producers (W=' + str(w) + ')':34s} cleared {done:,} rows in {dt:6.2f}s "
          f"-> {srv_rate:10,.0f} rows/s ({1e6/srv_rate:7.1f} us/row)")
    for t in threads:
        t.join(timeout=IN_SERVER_SECONDS + 20)   # full join: the backend must finish, not be abandoned

    print()
    print(f"  drain share of idle, python client : {py_rate/idle_rate:.3f}")
    print(f"  drain share of idle, in-server     : {srv_rate/idle_rate:.3f}")
    print(f"  effective budget C = (W+1)*share   : python {(w+1)*py_rate/idle_rate:5.2f}   "
          f"in-server {(w+1)*srv_rate/idle_rate:5.2f}   (host has 12 cores)")
    return (w + 1) * srv_rate / idle_rate


def drain_scaling(dsn, rows, ladder):
    """How many drain-equivalents of capacity does the SERVER have? Measured, not weighted.

    The (W+1)*share estimator counts one producer transaction as one drain-equivalent, and a
    producer does ~10x the work (sample 100 ids, join v3_parent, insert up to 10 rows). That
    weighting error is why it returned C ~ 1: nonsense, since 17 backends on 12 cores cannot share
    one unit of capacity.

    This needs no weighting at all. Run ONLY drains, N of them, on a pre-filled queue, and see where
    aggregate throughput stops rising. The knee IS the capacity, in exactly the units the simulator's
    reconciler processors are counted in.
    """
    print(f"{'N':>3} {'aggregate rows/s':>18} {'speedup vs N=1':>16}")
    base = None
    for n in ladder:
        setup = psycopg2.connect(dsn)
        setup.autocommit = True
        with setup.cursor() as cur:
            cur.execute(IN_SERVER_PRODUCER_SQL)
        setup.autocommit = False
        got = prefill(setup, rows)
        setup.close()

        # CONNECT AND PREPARE BEFORE THE CLOCK STARTS. The first version opened a psycopg2
        # connection inside each worker thread with t0 already taken, so 16 connection setups (tens
        # of ms) sat inside a 0.2 s measurement and manufactured exactly the falling curve it then
        # reported. Pre-connect, CREATE the procedure once, then release all workers on a barrier.
        conns = [psycopg2.connect(dsn) for _ in range(n)]
        for c in conns:
            c.autocommit = True
        with conns[0].cursor() as cur:
            cur.execute(DRAIN_N_SQL)
        results = []
        lock = threading.Lock()
        gate = threading.Barrier(n + 1)

        def one(c):
            with c.cursor() as cur:
                gate.wait()
                cur.execute("CALL v3_drain_n(%s, 0)", (rows,))
                d = int(cur.fetchone()[0])
            with lock:
                results.append((d, 0.0))

        ts = [threading.Thread(target=one, args=(c,), daemon=True) for c in conns]
        for t in ts:
            t.start()
        gate.wait()
        t0 = time.perf_counter()
        for t in ts:
            t.join(timeout=300)
        elapsed = time.perf_counter() - t0
        for c in conns:
            c.close()
        done = sum(d for d, _ in results)
        rate = done / elapsed if elapsed else float("nan")
        if base is None:
            base = rate
        print(f"{n:>3} {rate:>18,.0f} {rate/base:>16.2f}   ({done:,} of {got:,} rows in {elapsed:.2f}s)")
    return base


def run_case(dsn, label, rows, w, n_parent, k, producer_table):
    """producer_table None => producers idle."""
    global _stop
    _stop = threading.Event()
    setup = psycopg2.connect(dsn)
    prefill(setup, rows)
    setup.close()

    threads = []
    if producer_table:
        barrier = threading.Barrier(w)
        threads = [threading.Thread(target=producer, daemon=True,
                                    args=(dsn, producer_table, n_parent, k,
                                          w * 100_003 + i, barrier))
                   for i in range(w)]
        # NO b_keeper. The first version ran `DELETE FROM v3_outbox_b` every 250 ms to stop B
        # filling up -- which put a full-table delete into the DISJOINT arm that the SHARED arm did
        # not have, biasing the comparison AGAINST recovery, i.e. towards the "not shared-index"
        # answer. Letting B fill is also the fairer match: in the shared arm the producers are
        # mostly being REJECTED at 88% occupancy, so B should be saturated too. Now the only
        # difference between the loaded arms is whether the drain shares the producers' index.
        for t in threads:
            t.start()
        time.sleep(2.0)   # let the load reach steady state before the drain starts

    done, dt, rate = drain_to_empty(dsn, rows)
    _stop.set()
    for t in threads:
        t.join(timeout=10)
    print(f"  {label:34s} cleared {done:,} rows in {dt:6.2f}s -> {rate:10,.0f} rows/s "
          f"({1e6/rate if rate else float('nan'):7.1f} us/row)")
    return rate


def probe(dsn, w, n_parent, k, seconds):
    """Sample pg_stat_activity wait events during a loaded run, split by role."""
    global _stop
    _stop = threading.Event()
    setup = psycopg2.connect(dsn)
    prefill(setup, 7000)
    setup.close()

    barrier = threading.Barrier(w)
    threads = [threading.Thread(target=producer, daemon=True,
                               args=(dsn, "v3_outbox", n_parent, k, w * 100_003 + i, barrier))
               for i in range(w)]
    drain_t = threading.Thread(target=lambda: drain_to_empty(dsn, 10**9), daemon=True)
    for t in threads:
        t.start()
    time.sleep(1.0)
    drain_t.start()

    mon = psycopg2.connect(dsn)
    mon.autocommit = True
    drain_waits, prod_waits = Counter(), Counter()
    blks = []
    with mon.cursor() as cur:
        cur.execute("SELECT blks_read, blks_hit FROM pg_stat_database WHERE datname = current_database()")
        blks.append(cur.fetchone())
        t_end = time.time() + seconds
        while time.time() < t_end:
            cur.execute("""
                SELECT query, wait_event_type, wait_event, state
                  FROM pg_stat_activity
                 WHERE datname = current_database() AND pid <> pg_backend_pid()
            """)
            for q, wt, we, st in cur.fetchall():
                q = (q or "").strip()
                key = f"{wt or 'RUNNING'}:{we or '-'}" if st == "active" else f"idle:{st}"
                if "v3_drain_until_empty" in q or "UPDATE v3_outbox SET processed_at" in q:
                    drain_waits[key] += 1
                elif "INSERT INTO v3_outbox" in q or "FROM v3_parent" in q:
                    prod_waits[key] += 1
            time.sleep(0.005)
        cur.execute("SELECT blks_read, blks_hit FROM pg_stat_database WHERE datname = current_database()")
        blks.append(cur.fetchone())
    mon.close()
    _stop.set()
    for t in threads:
        t.join(timeout=10)
    drain_t.join(timeout=10)

    print("  DRAIN backend, wait events (samples):")
    for kk, v in drain_waits.most_common(8):
        print(f"      {kk:38s} {v:6,}  {100.0*v/max(1,sum(drain_waits.values())):5.1f}%")
    print("  PRODUCER backends, wait events (samples):")
    for kk, v in prod_waits.most_common(8):
        print(f"      {kk:38s} {v:6,}  {100.0*v/max(1,sum(prod_waits.values())):5.1f}%")
    dr = blks[1][0] - blks[0][0]
    dh = blks[1][1] - blks[0][1]
    print(f"  pg_stat_database: blks_read +{dr:,}  blks_hit +{dh:,}  "
          f"(hit ratio {100.0*dh/max(1,dr+dh):.3f}% -- disk is not the story if this is ~100%)")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--mode", choices=["probe", "disjoint", "budget", "scaling", "both"], default="both")
    ap.add_argument("-w", "--workers", type=int, default=16)
    ap.add_argument("--k", type=int, default=100)
    ap.add_argument("--parent-rows", type=int, default=80_000)
    ap.add_argument("--rows", type=int, default=7000, help="pre-filled rows the drain must clear")
    ap.add_argument("--probe-seconds", type=float, default=10.0)
    args = ap.parse_args()

    if args.mode == "scaling":
        print("=== DRAIN-ONLY SCALING: where does aggregate throughput stop rising? ===")
        drain_scaling(args.dsn, args.rows, [1, 2, 3, 4, 6, 8, 12, 16])
        return 0

    if args.mode == "budget":
        print("=== EFFECTIVE SERVER BUDGET (client removed from the loop) ===")
        budget(args.dsn, args.workers, args.parent_rows, args.k, args.rows)
        return 0

    if args.mode in ("probe", "both"):
        print("=== WAIT-EVENT PROBE (cheap check first) ===")
        probe(args.dsn, args.workers, args.parent_rows, args.k, args.probe_seconds)
        print()

    if args.mode in ("disjoint", "both"):
        conn = psycopg2.connect(args.dsn)
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute(TABLE_B_DDL)
        conn.close()
        print("=== DISJOINT-INDEX TEST (decisive) ===")
        idle = run_case(args.dsn, "idle (producers stopped)", args.rows, args.workers,
                        args.parent_rows, args.k, None)
        shared = run_case(args.dsn, "shared index (producers -> A)", args.rows, args.workers,
                          args.parent_rows, args.k, "v3_outbox")
        disjoint = run_case(args.dsn, "DISJOINT index (producers -> B)", args.rows, args.workers,
                            args.parent_rows, args.k, "v3_outbox_b")
        print()
        print(f"  recovery = (disjoint - shared) / (idle - shared) = "
              f"{(disjoint - shared) / (idle - shared) if idle != shared else float('nan'):.3f}")
        print("  ~1.0 => SHARED-INDEX SERIALISATION (finding is about the schema, not BASE)")
        print("  ~0.0 => GENERAL CONTENTION (the 25x stands as a drain property)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
