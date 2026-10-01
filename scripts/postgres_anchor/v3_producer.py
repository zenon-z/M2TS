"""V3 gate 1: measure c0_pg = index rejections / insert attempts, in STEADY STATE.

GATE 1 IS A COUNTING RATIO, and this script is the whole of it:

    attempts   = outbox rows a producer transaction tries to insert  (the simulator's collapse_offered)
    rejections = those the unique partial index refuses              (the simulator's collapse_skipped)
    c0_pg      = rejections / attempts                               (the simulator's collapsed_fraction)

No occupancy measurement on either side. Two counters around one ON CONFLICT DO NOTHING.

-------------------------------------------------------------------------------------------------
A CORRECTION TO THE BUILD ORDER, found while building this.

The plan was "producers only, no drain -- rejections/attempts needs no drain to be measured". That
is true of the ARITHMETIC and false of the QUANTITY.

    Without a drain, nothing ever clears `processed_at IS NULL`, so the pending set grows
    monotonically until it covers K_pg and then EVERY insert is rejected. c0_pg would ramp
    0 -> 1 and its value would be a function of how long the run was.

At this operating point that happens in well under a second: the simulator creates ~9,300 CUs/s
against K = 8,000. A producers-only run would therefore report a number that looks exactly like a
measurement, is reproducible, and means nothing -- C28.

c0 is a STEADY-STATE quantity on both arms. The simulator's dirty set clears because bookkeepers
complete; the outbox's pending set has to clear the same way. So this script runs a MINIMAL
clearing drain -- stamp `processed_at`, no instrumentation, no per-row timing. That is NOT step 3:
`S_drain` and the rho comparison still need the real instrumented drain. This one exists only to
make c0 well-defined, and `--drain-workers 0` is available to SEE the ramp rather than to measure
with it.

THE STEADY-STATE GATE. Even with a drain, an under-provisioned one leaves the pending set growing
and c0 still ramping. So the pending set is sampled throughout and the run REFUSES to report c0
unless the second half is flat. A ratio measured on a ramp is not c0, and nothing downstream can
tell the difference by looking at the number.
-------------------------------------------------------------------------------------------------

WHAT IS COPIED FROM THE EXISTING HARNESS, DELIBERATELY

  * The draw: `rng.sample(range(1, n+1), k)`, a fresh uniform k-subset per transaction, seeded per
    worker at `w * 100_003 + i` -- `collect_p1_data.py:118` exactly. The simulator was switched to
    `useRandomResourceSelection(true)` to match this; it is the axis gate 1 compares.
  * Accumulate in memory, never an audit-table INSERT per attempt. That was P1's Defect A: the
    logging's own WAL entered the concurrency being measured, and the reported rate collapsed to
    `1 - 1/retries`.
  * `127.0.0.1`, never `localhost` -- IPv6 forwarding stalls at W >= 64.

SELF-COLLISIONS are structurally impossible here (the schema generator asserts the parent->target
map is injective, so one draw's targets are distinct) and are asserted per transaction anyway. At
W=1 the simulator sees 4.87 already-marked keys and exactly 0 collapses; counting a batch against
itself would invent collisions that cannot happen.
"""
import argparse
import random
import statistics
import sys
import threading
import time

try:
    import psycopg2
except ImportError:  # pragma: no cover - environment guard
    print("psycopg2 is required: pip install -r requirements.txt", file=sys.stderr)
    raise

DEFAULT_DSN = "host=127.0.0.1 port=15432 dbname=tgs user=tgs password=tgs"

_stop = threading.Event()

# (rows drained, busy us, idle us) per in-server drain worker -> rho, measured.
_drain_stats = []
_drain_lock = threading.Lock()


def producer(dsn, n_parent, k, seed, barrier, sink, sink_lock, notify=False, period_s=0.0):
    """One closed-loop producer, optionally THROTTLED to a fixed transaction period.

    WHY THROTTLE, AND WHY PER PRODUCER. Until now W has been welded to lambda: more producers meant
    more offered load, so every measurement of "c0 rises with W" was also a measurement of "c0 rises
    with lambda", and the two could not be told apart. Setting `period_s = W / target_tps` holds
    TOTAL offered load constant while W varies, which separates them for the first time.

    The sleep is CLOSED-LOOP, not fixed: each producer sleeps `period - dt`, the remainder of its own
    transaction's period. A fixed sleep would give a different tps at each W, because service time
    itself varies with W - which would leave lambda drifting exactly along the axis the experiment
    is trying to hold still.
    """
    """One closed-loop producer connection: draw k parents, enqueue their targets, count."""
    rng = random.Random(seed)
    conn = psycopg2.connect(dsn)
    conn.autocommit = False
    local = []
    try:
        with conn.cursor() as cur:
            barrier.wait()
            while not _stop.is_set():
                t_tx = time.monotonic()
                ids = rng.sample(range(1, n_parent + 1), k)
                cur.execute(
                    "SELECT target_id FROM v3_parent "
                    "WHERE id = ANY(%s) AND target_id IS NOT NULL",
                    (ids,),
                )
                targets = [r[0] for r in cur.fetchall()]
                if not targets:
                    conn.commit()
                    continue
                # Structural, but asserted: a batch cannot collide with itself.
                assert len(set(targets)) == len(targets), (
                    "duplicate targets within one draw -- the parent->target map is not injective, "
                    "so self-collisions would be counted as rejections")
                cur.execute(
                    "INSERT INTO v3_outbox (target_id) SELECT unnest(%s::int[]) "
                    "ON CONFLICT (target_id) WHERE processed_at IS NULL DO NOTHING",
                    (targets,),
                )
                inserted = cur.rowcount
                if notify and inserted:
                    cur.execute("NOTIFY v3_outbox_new")
                conn.commit()
                local.append((time.monotonic(), len(targets), len(targets) - inserted))
                if period_s:
                    slack = period_s - (time.monotonic() - t_tx)
                    if slack > 0:
                        time.sleep(slack)
    finally:
        conn.close()
    with sink_lock:
        sink.extend(local)


def drain(dsn, durable, batch):
    """MINIMAL clearing drain, batch size 1. Not step 3 -- no timing, no S_drain.

    `synchronous_commit = off` by DEFAULT on this connection, and that is a real choice worth
    stating: one row per transaction means one WAL fsync per row, which caps the drain at a few
    thousand rows/s and leaves the pending set at 83% of K -- a SATURATED drain, which is the
    simulator's N=1 regime and not the cell gate 1 is defined at. Durability of the clearing drain
    affects nothing here because c0 is a counting ratio over the producers.

    It is NOT acceptable for step 3: `S_drain` measured with fsync off would understate the real
    per-row service and therefore rho. Pass --drain-durable there.
    """
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            if not durable:
                cur.execute("SET synchronous_commit = off")
            while not _stop.is_set():
                # BATCHED, and legitimately so: this drain is NOT the measured one. Batch size
                # here only sets how fast the pending set clears. Step 3's instrumented drain MUST
                # use batch size 1, because there one dequeue has to BE one unit of service or
                # S_drain is a per-batch cost masquerading as a per-row one.
                cur.execute(
                    "UPDATE v3_outbox SET processed_at = clock_timestamp(), visible_at = pg_xact_commit_timestamp(xmin) WHERE id IN ("
                    "  SELECT id FROM v3_outbox WHERE processed_at IS NULL "
                    "  ORDER BY enqueued_at FOR UPDATE SKIP LOCKED LIMIT %s)",
                    (batch,),
                )
    finally:
        conn.close()


def server_drain_sql(wakeup, ordering='fifo'):
    """The in-server drain, parameterised by WAKEUP DISCIPLINE.

    WHY THIS IS A KNOB AND NOT A CONSTANT. `pg_sleep(0.0005)` on an empty queue puts a latency term
    in the Postgres arm that the simulator has NO COUNTERPART FOR: a discrete-event loop has no
    "check again later" -- a CU becomes available and a unit takes it in the same step. At W=4 the
    queue is empty most of the time, so that term dominated: measured `lat_queue` 2,282 us against
    the ~190 us the occupancy predicts, 12x. And the low-load cells are exactly the ones that give
    the only clean c0 figures.

      poll   pg_sleep(0.0005) -- the original. Cheap when busy, but adds up to 500 us at low load.
      spin   no sleep at all. Retries immediately, so the poll term is removed BY CONSTRUCTION.
             This is the closest analogue to the simulator, whose reconciler is dispatched every
             step -- a 3-tick (3 us) granularity, negligible against a 300 us service. Costs one
             busy backend per drain worker while idle.

    LISTEN/NOTIFY is NOT available here: PL/pgSQL has no blocking wait for notifications, so a
    notify-driven drain has to live in the client -- see notify_drain, which pays a round trip.
    """
    idle = "PERFORM pg_sleep(0.0005);" if wakeup == "poll" else "NULL;"
    # ORDERING IS A FLAG, and the two settings measure DIFFERENT THINGS.
    #
    #   fifo       ORDER BY enqueued_at. Faithful to the simulator's reconciler queue (addLast +
    #              pollFirst) and to a real outbox's oldest-first contract -- and it does NOT
    #              parallelise: every worker competes for the same head, and across 48 ladder cells
    #              rho stayed pinned at ~1.000 for every N >= 1 at W >= 16. That is V3's strongest
    #              systems finding and the ordered data is kept for it.
    #
    #   unordered  any pending row. Scales 3.1x, which is what makes W >= 16 reachable at all, so
    #              this is the arm gate 1 runs on.
    #
    # WHY UNORDERED IS LEGITIMATE FOR GATE 1 SPECIFICALLY: c0 is a COUNTING quantity over which
    # keys collide. Service order changes which pending row is drained NEXT, not which targets are
    # pending, so it cannot move a rejection count. It DOES move latency and backlog composition --
    # so in this arm the lat_* columns are marked NOT COMPARABLE to the simulator rather than
    # quietly reported.
    order = "ORDER BY enqueued_at " if ordering == "fifo" else ""
    # The drain REPORTS ITS OWN BUSY/IDLE SPLIT, because that is rho, measured.
    #
    # rho = lambda*S/N needs S, and the isolated S_drain is 21x optimistic under load - that
    # estimator already produced rho(N=1) = 0.39 for a cell whose queue sat at 88%. Utilisation
    # needs no S at all: a worker is either completing a dequeue or failing to find one, and the
    # ratio of those times IS its utilisation. Aggregated over the N workers it is the cell's rho,
    # with nothing assumed about service time, load-dependence, or the host.
    return f"""
CREATE OR REPLACE PROCEDURE v3_drain_loop(deadline timestamptz,
                                          INOUT done int, INOUT busy_us double precision,
                                          INOUT idle_us double precision)
LANGUAGE plpgsql AS $proc$
DECLARE hit int; t0 timestamptz;
BEGIN
  done := 0; busy_us := 0; idle_us := 0;
  LOOP
    EXIT WHEN clock_timestamp() >= deadline;
    t0 := clock_timestamp();
    UPDATE v3_outbox SET processed_at = clock_timestamp(), visible_at = pg_xact_commit_timestamp(xmin) WHERE id = (
      SELECT id FROM v3_outbox WHERE processed_at IS NULL
      {order}FOR UPDATE SKIP LOCKED LIMIT 1);
    GET DIAGNOSTICS hit = ROW_COUNT;
    COMMIT;
    IF hit = 0 THEN
      {idle}
      idle_us := idle_us + EXTRACT(EPOCH FROM (clock_timestamp() - t0)) * 1e6;
    ELSE
      done := done + 1;
      busy_us := busy_us + EXTRACT(EPOCH FROM (clock_timestamp() - t0)) * 1e6;
    END IF;
  END LOOP;
END $proc$;
"""


def notify_drain(dsn, duration_s):
    """Client-side drain woken by LISTEN/NOTIFY instead of polling.

    HONEST COST, STATED UP FRONT: this drain lives in the client, so every dequeue pays a round
    trip -- measured at 362 us on this host against a 24 us server-side service, i.e. 15x. It
    removes the poll term and replaces it with a network term. Measured rather than argued; if it
    lands between the poll figure and the occupancy prediction, NOTIFY delivery has its own latency
    and that gets NAMED, not absorbed.
    """
    import select as _select
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    end = time.monotonic() + duration_s
    try:
        with conn.cursor() as cur:
            cur.execute("LISTEN v3_outbox_new")
            while not _stop.is_set() and time.monotonic() < end:
                cur.execute(
                    "UPDATE v3_outbox SET processed_at = clock_timestamp(), visible_at = pg_xact_commit_timestamp(xmin) WHERE id = ("
                    "  SELECT id FROM v3_outbox WHERE processed_at IS NULL "
                    "  ORDER BY enqueued_at FOR UPDATE SKIP LOCKED LIMIT 1)")
                if cur.rowcount == 0:
                    if _select.select([conn], [], [], 0.05) == ([], [], []):
                        continue
                    conn.notifies.clear()
    finally:
        conn.close()


def server_drain(dsn, duration_s, wakeup, ordering):
    """ONE drain worker, batch size 1, durable, running INSIDE the server.

    WHY NOT A PYTHON THREAD. `S_drain` is 24 us server-side but a Python round trip is 362 us, so a
    client-side "N=1 worker" caps at ~1,880 rows/s -- 15x below the 41,000 rows/s that N=1 means in
    `rho = lambda*S/N`. Matching rho with a client-limited worker would put the arm at a rho the
    model does not describe, which is the whole error this cell exists to avoid.

    Running the loop in the backend makes N=1 mean what rho says it means: one worker, batch size 1,
    one COMMIT per row, no network in the service time.
    """
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            cur.execute(server_drain_sql(wakeup, ordering))
            cur.execute("CALL v3_drain_loop(clock_timestamp() + %s * interval '1 second', "
                        "0, 0, 0)", (duration_s,))
            done, busy_us, idle_us = cur.fetchone()
            with _drain_lock:
                _drain_stats.append((int(done), float(busy_us), float(idle_us)))
    finally:
        conn.close()


def pending_sampler(dsn, out, period_s=0.05):
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            while not _stop.is_set():
                cur.execute("SELECT count(*) FROM v3_outbox WHERE processed_at IS NULL")
                out.append((time.monotonic(), cur.fetchone()[0]))
                time.sleep(period_s)
    finally:
        conn.close()


def slope(points):
    """Least-squares slope of pending-count vs time, in keys per second."""
    if len(points) < 3:
        return float("nan")
    xs = [p[0] for p in points]
    ys = [float(p[1]) for p in points]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    denom = sum((x - mx) ** 2 for x in xs)
    return float("nan") if denom == 0 else sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / denom


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("-w", "--workers", type=int, default=16, help="W closed-loop producers")
    ap.add_argument("--k", type=int, default=100)
    ap.add_argument("--parent-rows", type=int, default=80_000)
    ap.add_argument("--k-pg", type=int, required=True,
                    help="the DERIVED K_pg from v3_schema_gen.py (not the target table row count)")
    ap.add_argument("--duration-s", type=float, default=20.0)
    ap.add_argument("--target-tps", type=float, default=0.0,
                    help="hold TOTAL producer tps here by sleeping W/target between each "
                         "producer's transactions. 0 = unthrottled. This is what lets W vary "
                         "INDEPENDENTLY of offered load.")
    ap.add_argument("--drain-ordering", choices=["fifo", "unordered"], default="fifo",
                    help="fifo matches the simulator's queue and does NOT parallelise; unordered "
                         "scales 3.1x and is the arm gate 1 runs on. RECORDED per row.")
    ap.add_argument("--drain-wakeup", choices=["poll", "spin", "notify"], default="spin",
                    help="how an idle drain waits for work. RECORDED with the run: poll adds up to "
                         "500us of latency the simulator has no counterpart for; spin removes it; "
                         "notify moves the drain to the client and pays a round trip.")
    ap.add_argument("--s-drain-us", type=float, default=24.0,
                    help="measured server-side S_drain (v3_drain.py), used to split total latency")
    ap.add_argument("--warmup-frac", type=float, default=0.5,
                    help="leading fraction discarded before c0 is computed (default 0.5)")
    ap.add_argument("--drain-durable", action="store_true",
                    help="keep synchronous_commit on in the clearing drain (required for S_drain)")
    ap.add_argument("--server-drain", type=int, default=0,
                    help="N in-server drain workers (batch 1, durable, no round trip). Use this "
                         "for the matched-rho cell; --drain-workers is the client-side clearing "
                         "drain and is NOT rho-faithful.")
    ap.add_argument("--clear-batch", type=int, default=50,
                    help="rows per clearing UPDATE; this drain is NOT the measured one (step 3 "
                         "uses batch size 1)")
    ap.add_argument("--drain-workers", type=int, default=8,
                    help="minimal clearing drain; 0 shows the ramp instead of measuring c0")
    args = ap.parse_args()

    sink, sink_lock, pending = [], threading.Lock(), []
    barrier = threading.Barrier(args.workers)
    threads = [threading.Thread(target=producer, daemon=True,
                                args=(args.dsn, args.parent_rows, args.k,
                                      args.workers * 100_003 + i, barrier, sink, sink_lock,
                                      args.drain_wakeup == "notify",
                                      (args.workers / args.target_tps) if args.target_tps else 0.0))
               for i in range(args.workers)]
    threads += [threading.Thread(target=drain, args=(args.dsn, args.drain_durable, args.clear_batch), daemon=True)
                for _ in range(args.drain_workers)]
    if args.drain_wakeup == "notify":
        threads += [threading.Thread(target=notify_drain, args=(args.dsn, args.duration_s),
                                     daemon=True) for _ in range(args.server_drain)]
        args.server_drain = 0
    threads += [threading.Thread(target=server_drain, args=(args.dsn, args.duration_s, args.drain_wakeup, args.drain_ordering), daemon=True)
                for _ in range(args.server_drain)]
    threads.append(threading.Thread(target=pending_sampler, args=(args.dsn, pending), daemon=True))

    for t in threads:
        t.start()
    time.sleep(args.duration_s)
    _stop.set()
    for t in threads:
        t.join(timeout=15)

    if not sink:
        print("no producer transactions completed", file=sys.stderr)
        return 1

    t0 = min(r[0] for r in sink)
    t1 = max(r[0] for r in sink)
    cut = t0 + (t1 - t0) * args.warmup_frac
    steady = [r for r in sink if r[0] >= cut]
    attempts = sum(r[1] for r in steady)
    rejections = sum(r[2] for r in steady)
    c0 = rejections / attempts if attempts else float("nan")

    second_half = [p for p in pending if p[0] >= cut]
    growth = slope(second_half)
    mean_pending = statistics.fmean([float(p[1]) for p in second_half]) if second_half else float("nan")
    txns = len(steady)
    tps = txns / (t1 - cut) if t1 > cut else float("nan")

    print(f"W                      : {args.workers}" + (f"   throttled to {args.target_tps:g} tps total" if args.target_tps else ""))
    print(f"drain wakeup / ordering: {args.drain_wakeup} / {args.drain_ordering}")
    print(f"K_pg (derived, passed in): {args.k_pg:,}")
    print(f"producer transactions  : {txns:,} in steady state  ({tps:,.1f} tps)")
    print(f"attempts / rejections  : {attempts:,} / {rejections:,}")
    print(f"offered per txn        : {attempts / txns:.4f}" if txns else "")
    print(f"pending set (mean)     : {mean_pending:,.1f}  = {100.0 * mean_pending / args.k_pg:.3f}% of K_pg")
    print(f"pending growth         : {growth:+,.1f} keys/s over the measured window")
    print()
    print(f"c0_pg                  : {c0:.5f}")

    # ---- rho, MEASURED as drain utilisation --------------------------------------------------
    with _drain_lock:
        stats = list(_drain_stats)
    if stats:
        tot_busy = sum(b for _, b, _ in stats)
        tot_idle = sum(i for _, _, i in stats)
        drained = sum(d for d, _, _ in stats)
        rho_measured = tot_busy / (tot_busy + tot_idle) if (tot_busy + tot_idle) else float("nan")
        per_row_us = tot_busy / drained if drained else float("nan")
        print(f"drain workers          : {len(stats)}")
        print(f"drained rows           : {drained:,}")
        print(f"service under load     : {per_row_us:,.2f} us/row  "
              f"(vs {args.s_drain_us:,.2f} us isolated)")
        print(f"rho_measured           : {rho_measured:.5f}   "
              f"(drain busy / (busy + idle); no S assumed)")

    # ---- LATENCY PARTITION, the Postgres counterpart of the simulator's ------------------------
    # total = processed_at - enqueued_at, which is queue wait PLUS service -- the quantity this
    # harness refuses to call S_drain. Split it by SUBTRACTING the separately measured service:
    #     queue = total - processing,  processing = S_drain from v3_drain.py (server-side, batch 1)
    # That is the only honest split available, because Postgres does not record when a row's
    # dequeue transaction began -- only when it ended.
    lat = psycopg2.connect(args.dsn)
    lat.autocommit = True
    with lat.cursor() as cur:
        # TWO totals, and both are kept.
        #   wall    = enqueued_at -> processed_at. What an APPLICATION observes.
        #   visible = pg_xact_commit_timestamp(xmin) -> processed_at. What the DRAIN could have
        #             started on, because the row does not exist for it until the producer commits.
        # The simulator's markDirty timestamp corresponds to VISIBLE: a CU exists the instant it is
        # created, with no commit gap. enqueued_at is stamped by clock_timestamp() DURING the
        # insert, so wall carries the producer's own commit latency - a term with no counterpart on
        # the other arm. Replacing either with the other loses a real quantity.
        cur.execute("""
            SELECT count(*),
                   avg(EXTRACT(EPOCH FROM (processed_at - enqueued_at)) * 1e6),
                   percentile_cont(0.50) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (processed_at - enqueued_at)) * 1e6),
                   percentile_cont(0.95) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (processed_at - enqueued_at)) * 1e6),
                   avg(EXTRACT(EPOCH FROM (processed_at - visible_at)) * 1e6),
                   avg(EXTRACT(EPOCH FROM (visible_at - enqueued_at)) * 1e6)
              FROM v3_outbox
             WHERE processed_at IS NOT NULL
               AND visible_at IS NOT NULL
        """)
        _r = cur.fetchone()
        # psycopg2 returns numeric as Decimal; mixing it with float raises TypeError.
        n_lat = _r[0]
        mean_us, p50_us, p95_us, visible_us, commit_gap_us = (
            float(x) if x is not None else float('nan') for x in _r[1:])
    lat.close()
    if n_lat:
        proc = args.s_drain_us
        print()
        print(f"LATENCY PARTITION (per drained row, n={n_lat:,})")
        print(f"  lat_total_wall_us    : {mean_us:10,.2f} us   (enqueued_at -> processed_at; "
              f"p50 {p50_us:,.2f}, p95 {p95_us:,.2f})")
        print(f"  lat_total_visible_us : {visible_us:10,.2f} us   (commit_ts -> processed_at) "
              f"<- the latency partition uses THIS")
        print(f"  commit gap           : {commit_gap_us:10,.2f} us   (enqueued_at -> commit_ts; "
              f"the producer's own commit latency)")
        print(f"  processing : {proc:10,.2f} us   (S_drain, measured separately at N=1, no load)")
        print(f"  queue      : {visible_us - proc:10,.2f} us   (= visible - processing)")
        print(f"  blocking   : {0.0:10,.2f} us   (no lock waits in this path; emitted so the "
              f"three always sum)")

    # --- steady-state gate: a ratio measured on a ramp is not c0 ---
    budget = 0.02 * args.k_pg   # 2% of K per second: flat to within the noise of a 10 s window
    if not (abs(growth) < budget):
        print(f"\nREFUSED: the pending set is growing at {growth:+,.1f} keys/s (budget "
              f"{budget:,.1f}). c0 is a STEADY-STATE quantity; on a ramp this ratio is a function "
              f"of run length, not of the dedup. Add --drain-workers.", file=sys.stderr)
        return 1
    if mean_pending > 0.5 * args.k_pg:
        print(f"\nREFUSED: the pending set holds {100.0 * mean_pending / args.k_pg:.1f}% of K_pg. "
              f"Collisions are near-forced regardless of the dedup mechanism.", file=sys.stderr)
        return 1
    print("\nOK -- steady state held; c0_pg is comparable to the simulator's collapsed_fraction.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
