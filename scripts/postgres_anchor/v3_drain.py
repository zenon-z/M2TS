"""V3 step 3: the INSTRUMENTED drain -- measure S_drain, then rho(N).

S_drain IS SERVICE, NOT LATENCY. That distinction is the whole of this script.

    S_drain = what ONE worker spends on ONE row with NO contention.

So it is measured as the **elapsed time of the dequeue-and-stamp transaction itself**, at N=1, with
producers stopped and the queue pre-filled. Three properties, each deliberate:

  batch size 1     one dequeue IS one unit of service. A batch would give a per-batch cost wearing
                   a per-row label, and rho = lambda*S/N would then be wrong by the batch factor.
  durable commits  synchronous_commit stays ON. The minimal CLEARING drain in v3_producer.py turns
                   it off, which is fine there because it is not the measured drain -- but fsync is
                   expected to BE most of S_drain, so measuring without it would understate rho.
  N=1, no producers  isolates service from arrival. Any concurrency folds queue wait into the
                   measurement and the result stops being a service time.

WHAT NOT TO MEASURE, and why it is the obvious wrong answer:

    processed_at - enqueued_at

is queue wait PLUS service. It is trivially available -- both columns are right there -- and at a
saturated drain it is dominated by the wait. The simulator has the scar: `recovery_latency` at N=1
read 18.8 ms against a 300 us service, 62x, because the queue was saturated. The same shape here
would report S_drain = 18 ms and put rho 60x high, in a quantity that looks like a measurement.

This script therefore NEVER reads those columns for timing. It times the statement.

THE FSYNC DECOMPOSITION comes free: run with --no-durable as well and the difference is the WAL
fsync's share of S_drain. That is worth having because it says whether S_drain is a property of
PostgreSQL's durability or of this host's disk, which is what transfers to another machine.
"""
import argparse
import statistics
import sys
import time

try:
    import psycopg2
except ImportError:  # pragma: no cover - environment guard
    print("psycopg2 is required: pip install -r requirements.txt", file=sys.stderr)
    raise

DEFAULT_DSN = "host=127.0.0.1 port=15432 dbname=tgs user=tgs password=tgs"

DEQUEUE_SQL = (
    "UPDATE v3_outbox SET processed_at = clock_timestamp() WHERE id = ("
    "  SELECT id FROM v3_outbox WHERE processed_at IS NULL "
    "  ORDER BY enqueued_at FOR UPDATE SKIP LOCKED LIMIT 1)"
)


def prefill(conn, rows, k_pg):
    """Pre-fill the queue with `rows` PENDING rows on distinct targets.

    Distinct because the unique partial index allows one pending row per target: a pre-fill with
    duplicates would silently insert fewer rows than asked, and the calibration would then run out
    of queue early and time an empty-queue no-op as if it were service.
    """
    if rows > k_pg:
        raise SystemExit(f"cannot pre-fill {rows:,} pending rows: only {k_pg:,} distinct targets "
                         f"exist, and the unique partial index allows one pending row per target.")
    with conn.cursor() as cur:
        cur.execute("UPDATE v3_outbox SET processed_at = clock_timestamp() "
                    "WHERE processed_at IS NULL")
        cur.execute(
            "INSERT INTO v3_outbox (target_id) "
            "SELECT target_id FROM v3_parent WHERE target_id IS NOT NULL "
            "ORDER BY target_id LIMIT %s", (rows,))
        inserted = cur.rowcount
    conn.commit()
    if inserted != rows:
        raise SystemExit(f"pre-fill inserted {inserted:,}, asked for {rows:,}")
    return inserted


def calibrate(dsn, rows, k_pg, durable):
    """Time each dequeue-and-stamp transaction, N=1, no producers."""
    conn = psycopg2.connect(dsn)
    try:
        prefill(conn, rows, k_pg)
        conn.autocommit = True
        samples = []
        with conn.cursor() as cur:
            if not durable:
                cur.execute("SET synchronous_commit = off")
            for _ in range(rows):
                t0 = time.perf_counter()
                cur.execute(DEQUEUE_SQL)   # autocommit: this statement IS the transaction
                dt = time.perf_counter() - t0
                if cur.rowcount == 0:
                    break              # queue drained early; do not time the empty no-op
                samples.append(dt)
    finally:
        conn.close()
    return samples


SERVER_TIMER_SQL = """
CREATE OR REPLACE PROCEDURE v3_time_drain(n int, INOUT total_us double precision)
LANGUAGE plpgsql AS $proc$
DECLARE t0 timestamptz; i int; hit int;
BEGIN
  total_us := 0;
  FOR i IN 1..n LOOP
    t0 := clock_timestamp();
    UPDATE v3_outbox SET processed_at = clock_timestamp() WHERE id = (
      SELECT id FROM v3_outbox WHERE processed_at IS NULL
      ORDER BY enqueued_at FOR UPDATE SKIP LOCKED LIMIT 1);
    GET DIAGNOSTICS hit = ROW_COUNT;
    COMMIT;
    total_us := total_us + EXTRACT(EPOCH FROM (clock_timestamp() - t0)) * 1e6;
    EXIT WHEN hit = 0;
  END LOOP;
END $proc$;
"""


def server_side_s_drain(dsn, rows, k_pg):
    """S_drain with the CLIENT ROUND TRIP REMOVED, timed inside the server.

    WHY THIS EXISTS. The client-side figure came back at 547 us -- and `SELECT 1` on the same
    connection costs 403 us. **74% of the "service time" was Python round trip.** Feeding that into
    rho = lambda*S/N would have inflated rho ~3.8x, in a number that looks exactly like a service
    time. Same class as declaring K_pg from a row count.

    WHICH ONE THE COMPARISON USES. The simulator has no client or network layer at all: its
    S = io + cpu = 300 us models WORK. The like-for-like quantity is therefore the server-side
    service, not the client-observed cost -- charging PostgreSQL for a round trip the simulator
    never pays would be the same error in the other direction. The client figure is still reported,
    because it is what THIS harness's workers actually spend and so bounds what the harness can
    drain.

    The procedure keeps batch size 1 AND one COMMIT per row -- the definition survives; only the
    round trip is removed.
    """
    conn = psycopg2.connect(dsn)
    try:
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute(SERVER_TIMER_SQL)
        conn.autocommit = False
        prefill(conn, rows, k_pg)
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("CALL v3_time_drain(%s, 0)", (rows,))
            total_us = float(cur.fetchone()[0])
    finally:
        conn.close()
    return total_us / rows * 1e-6


def q(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    i = min(len(sorted_vals) - 1, max(0, int(round(p * (len(sorted_vals) - 1)))))
    return sorted_vals[i]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--rows", type=int, default=3000, help="pre-filled pending rows to time")
    ap.add_argument("--k-pg", type=int, required=True, help="derived K_pg from v3_schema_gen.py")
    ap.add_argument("--producer-tps", type=float, required=True,
                    help="measured producer tps from v3_producer.py (step 2)")
    ap.add_argument("--fk", type=float, default=10.0, help="f*k, outbox rows per producer txn")
    ap.add_argument("--sim-service-us", type=float, default=300.0,
                    help="the simulator's CU service, for the matched-rho table")
    ap.add_argument("--ladder", default="1,2,4,8,12,16,24")
    args = ap.parse_args()

    out = {}
    for durable in (True, False):
        s = sorted(calibrate(args.dsn, args.rows, args.k_pg, durable))
        if not s:
            print("no rows timed", file=sys.stderr)
            return 1
        out[durable] = s
        label = "durable (synchronous_commit on)" if durable else "no-durable (fsync off)"
        print(f"{label:34s} n={len(s):>5}  mean={statistics.fmean(s)*1e6:>9.1f}us  "
              f"p50={q(s,0.50)*1e6:>9.1f}  p95={q(s,0.95)*1e6:>9.1f}  p99={q(s,0.99)*1e6:>9.1f}")

    s_client = statistics.fmean(out[True])
    s_nofsync = statistics.fmean(out[False])

    # round-trip floor on the SAME connection, so it is this harness's floor and not a literature
    # value: whatever SELECT 1 costs is overhead every client-side timing carries.
    conn = psycopg2.connect(args.dsn)
    conn.autocommit = True
    floor = []
    with conn.cursor() as cur:
        for _ in range(2000):
            t0 = time.perf_counter()
            cur.execute("SELECT 1")
            floor.append(time.perf_counter() - t0)
    conn.close()
    rtt = statistics.fmean(floor)

    s_drain = server_side_s_drain(args.dsn, args.rows, args.k_pg)
    lam = args.producer_tps * args.fk
    rho1 = lam * s_drain

    print()
    print(f"client round-trip floor    : {rtt*1e6:,.1f} us   (SELECT 1, same connection)")
    print(f"client-observed per row    : {s_client*1e6:,.1f} us   "
          f"<- {100.0*rtt/s_client:.0f}% of it is the floor above")
    print(f"S_drain (SERVER-side, batch 1, durable) : {s_drain*1e6:,.1f} us   <- USED FOR rho")
    print(f"  WAL fsync share (client-side diff)    : "
          f"{(s_client - s_nofsync)*1e6:,.1f} us ({100.0*(s_client - s_nofsync)/s_client:.1f}%)")
    print(f"lambda_offered             : {lam:,.0f} rows/s  "
          f"({args.producer_tps:,.1f} tps x {args.fk:g})")
    print(f"rho(N=1) = lambda*S_drain  : {rho1:,.2f}")
    print(f"crossover N* = ceil(rho)   : {-(-rho1 // 1):,.0f}")
    print()

    ladder = [int(x) for x in args.ladder.split(",")]
    print("  N     rho_pg   stable?   | simulator N for the SAME rho (S=%.0fus)" % args.sim_service_us)
    sim_lam_s = lam * args.sim_service_us * 1e-6
    for n in ladder:
        r = rho1 / n
        n_sim = sim_lam_s / r if r > 0 else float("nan")
        print(f"{n:>3}   {r:>8.3f}   {'yes' if r < 1 else 'NO ':>5}     | "
              f"N_sim = {n_sim:,.2f}")
    print()
    print("MATCHED-RHO, NOT MATCHED-N. The simulator's S is %.0f us against Postgres's %.0f us, so "
          "equal N is unequal rho.\nPick the N on each arm giving the same rho_created; matching N "
          "would be the same class of error as\nmatching K by row count."
          % (args.sim_service_us, s_drain * 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
