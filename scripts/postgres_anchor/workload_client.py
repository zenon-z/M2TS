"""TGS static-locking validation - PostgreSQL anchor (Part B). Runs the identical k-of-D
uniform-random-access workload against real Postgres in one of two modes:

  advisory   - all-or-nothing preclaim via try_acquire_all_retry() (schema.sql): ONE round trip
               issues the call, retries happen SERVER-SIDE inside the function (measurement
               correction - see schema.sql's comment: the calibrated hold/RTT ratio was measured
               uncontended and inverts near the plateau, where each success costs dozens of failed
               attempts, if every retry pays a round trip). This is the static-locking analogue
               the theory anchor is actually about - should PLATEAU under load, like the
               simulator's f=0 curve.
  update     - the SAME all-or-nothing advisory preclaim as `advisory`, plus one
               `UPDATE resources SET v = v + 1 WHERE id = ANY(ids)` issued AFTER the full set is
               held. This is V0's write-only rung.

               DEPARTURE from V0's written spec, which says to acquire via a preceding
               `SELECT ... FOR UPDATE ORDER BY id`. That is INCREMENTAL acquisition - the dynamic
               protocol V0 itself calls "deliberately the wrong protocol", the one expected to
               collapse - whereas V0's simulator arm is ACID all-or-nothing preclaim. Acquiring that
               way would compare the wrong protocol against it. The sorted order V0 asks for is a
               deadlock-avoidance property of dynamic acquisition; all-or-nothing preclaim does not
               need it, having no hold-and-wait.

               Using advisory preclaim also keeps row locks uncontended by construction: no two
               transactions can hold overlapping advisory locks, so no two can want the same row.

  for_update - plain `SELECT ... FOR UPDATE` on all k rows, sorted (deadlock-avoiding order but
               still INCREMENTAL/dynamic acquisition - see the TGS plan's B1 for why this is
               deliberately the wrong protocol, kept only as the contrasting arm that should
               COLLAPSE under load, corroborating the project's static-vs-dynamic thrashing claim
               in a real system). No retry-loop concept applies here - it blocks in place.

Both arms use the SAME calibrated hold time (pg_sleep between acquisition and commit) - see B2:
without a deliberate, nonzero critical section, achieved concurrency is ~0 regardless of worker
count and neither plateau nor collapse would ever be observable.

Achieved concurrency (H, and n=H/k) is measured DIRECTLY via a background poller thread issuing
`SELECT count(*) FROM pg_locks WHERE locktype='advisory'` every 100ms during the measurement
window - literally H, the same quantity the simulator's SS2PLLockManager.getLocksHeld() reports,
sampled the same way (time-weighted mean + peak). No normalization, no Little's Law, no dependence
on the hold/RTT ratio - see the "measure it, don't derive it" correction this was added for.

One worker thread per concurrent "connection" (W, the MPL analogue), each on its own psycopg2
connection (connections aren't thread-safe to share). A threading.Barrier synchronizes worker
start so throughput/latency are measured over a genuinely-concurrent window, not a staggered
ramp-up. Since pg_sleep-held backends are idle (not CPU-bound), max_connections (not core count)
is the relevant server-side capacity limit - sized in docker-compose.yml well above the largest W
used here.
"""
from __future__ import annotations

import argparse
import os
import random
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

import psycopg2
import psycopg2.errors

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_lock  # noqa: E402  -- needs the path insert above to resolve as a sibling

# host=127.0.0.1, not localhost: on this machine "localhost" resolves to ::1 first, and Docker
# Desktop's IPv6 port-forwarding path degraded after a large connection burst (W=512+) - every
# connection fell back to IPv4 only after a ~5s stall (measured, reproducible even after a full
# container recreation - a Windows/Docker-Desktop networking-layer issue, not a Postgres or
# harness bug). At W>=64 or so those stalls silently starved out threads/timed out the run
# entirely (see the session notes around this constant). Forcing IPv4 explicitly avoids it.
# Port 15432, not 55432 - see docker-compose.yml's comment: 55432 fell inside a Windows Hyper-V
# dynamic port reservation and could not be bound at all.
DEFAULT_DSN = "host=127.0.0.1 port=15432 dbname=tgs user=tgs password=tgs"
# Server-side retry pause inside try_acquire_all_retry() - see schema.sql's comment on why this
# is a SERVER-side sleep, not a client round trip.
RETRY_DELAY_S = 0.001


@dataclass
class WorkerResult:
    commits: int = 0
    latencies_s: list = field(default_factory=list)
    deadlocks: int = 0
    advisory_giveups: int = 0
    attempts_per_success: list = field(default_factory=list)
    commit_latencies_s: list = field(default_factory=list)


@dataclass
class LockSample:
    """One pg_locks poll: (t, H). Consumed by summarize_lock_samples() into a time-weighted
    mean + peak, matching SimulationMetrics.getMeanLocksHeldCountSampled()'s convention."""
    t_s: float
    h: int


@dataclass(frozen=True)
class MeasurementWindow:
    """THE measurement interval -- computed once per run and shared by every accumulator.

    Three separate defects in this client all came from re-deriving this interval
    independently at each site from duration_s and warmup_s:

      1. throughput divided by duration_s rather than the counting window (-10%, uniform);
      2. the commit gate had no upper bound, so each worker's straddling final
         transaction was counted against a window it finished outside of
         (+5-53%, growing with load);
      3. the sampler used [warmup_s, warmup_s + duration_s), overshooting the workers'
         stop time by a full warmup_s and averaging through the drain
         (-1.2% to -8.9% on n_mean, growing with load).

    Each site was individually defensible and they disagreed with each other. The fix is
    structural, not three patches: there is ONE window, `contains()` is THE gate, and
    `length_s` is THE denominator. Nothing recomputes either.

    Note `end_s` is the run's TOTAL length and `start_s` the warmup inside it -- worker()
    loops `while elapsed < end_s`, so the counting window is the remainder, not end_s.
    """

    start_s: float
    end_s: float

    @property
    def length_s(self) -> float:
        """The counting window: THE throughput denominator."""
        return self.end_s - self.start_s

    def contains(self, t_s: float) -> bool:
        """THE gate. Every accumulator -- commits, latencies, attempts, lock samples --
        asks this and nothing else."""
        return self.start_s <= t_s <= self.end_s


def setup_resources(dsn: str, d: int) -> None:
    """(Re-)populate the resources table with exactly D rows (ids 1..D)."""
    conn = psycopg2.connect(dsn)
    try:
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("TRUNCATE resources")
            cur.execute("INSERT INTO resources (id) SELECT generate_series(1, %s)", (d,))
            # Autovacuum OFF on the hot table. V0's traps list requires it "disabled or
            # stated"; it was neither, and ran asynchronously mid-measurement. Worse, table
            # state drifted DURING a sweep -- and since sweeps run loads in ascending order,
            # elapsed time is monotone in W, so degradation was perfectly confounded with
            # load. Measured 2026-09-03: dead=38408 against 5000 live rows (8528 kB), and
            # W=640 throughput 41.1 in a 7-point ascending sweep vs 50.8 in a 3-point sweep
            # of the same top loads. reset_table() controls this explicitly instead.
            cur.execute("ALTER TABLE resources SET (autovacuum_enabled = off)")
        print(f"resources table populated with {d} rows (autovacuum disabled)")
    finally:
        conn.close()


def setup_v1(dsn: str, d: int, f: float, seed: int) -> int:
    """Populate model_a / model_b and flag the emitting f-fraction. Returns the MEASURED flag count.

    The flag is precomputed rather than the trigger being redefined per sweep point, so the
    f axis moves one boolean column and nothing else. Seeded so the realized graph is
    comparable to the simulator's under the same semanticOverlapSeed.

    WHY THE COUNT IS READ BACK RATHER THAN RETURNED FROM len(emit). This value reaches a
    REGISTERED GATE: v1b_sweep.gates() forms criterion 1's hypergeometric mean as
    `K * emitting / D`. Returning len(emit) would return what Python ASKED the database to
    flag, which is intent, not outcome.

    The two are equal today, and only because three things happen to hold at once:
    TRUNCATE clears any prior `emits`, generate_series(1, d) guarantees every sampled id
    exists, and sample() returns exactly n DISTINCT ids. None of the three is asserted
    anywhere, so a schema change admitting duplicate ids, or a future edit swapping TRUNCATE
    for a DELETE that leaves rows behind, breaks the equality silently and moves a gate.

    This is hygiene, not a correction -- no recorded result is affected. It converts an
    implicit invariant into one that fails loudly. It runs at setup, before any measurement,
    so it cannot perturb a cell.
    """
    conn = psycopg2.connect(dsn)
    try:
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("TRUNCATE model_a, model_b")
            cur.execute("INSERT INTO model_a (id) SELECT generate_series(1, %s)", (d,))
            cur.execute("INSERT INTO model_b (id) SELECT generate_series(1, %s)", (d,))
            emit = sorted(random.Random(seed).sample(range(1, d + 1), int(round(f * d))))
            if emit:
                cur.execute("UPDATE model_a SET emits = true WHERE id = ANY(%s)", (emit,))
            cur.execute("SELECT count(*) FROM model_a WHERE emits")
            emitting = cur.fetchone()[0]
            if emitting != len(emit):
                raise AssertionError(
                    "emitting-set mismatch: asked to flag %d rows, model_a reports %d "
                    "(d=%d, f=%s, seed=%d). The f axis is not what it says it is, and "
                    "criterion 1's expectation K*emitting/D would be computed from the "
                    "wrong count." % (len(emit), emitting, d, f, seed))
            for t in ("model_a", "model_b"):
                cur.execute(f"ALTER TABLE {t} SET (autovacuum_enabled = off)")
        print(f"model_a/model_b populated with {d} rows; {emitting} emit (f={f}, read back)")
        return emitting
    finally:
        conn.close()


# Which tables each mode actually WRITES. Bloat control that names the wrong table is
# bloat control that does nothing: V1's cpr_deferred writes model_a/model_b, and the first
# V1 sweep ran with VACUUM FULL and dead-tuple logging pointed at `resources`, which it never
# touches. Measured mid-run: model_a at 119291 dead tuples against 5000 live (24x) with
# autovacuum disabled, while `resources` sat at 0 dead and was faithfully vacuumed every
# point. Loads ascend within an f block, so that put elapsed time back in step with load --
# the exact confound fixed for V0, reintroduced by a table-name mismatch.
HOT_TABLES = {
    "advisory": (),                       # advisory locks only, no row writes
    "queued": (),                         # ditto
    "update": ("resources",),
    "for_update": ("resources",),
    "cpr_deferred": ("model_a", "model_b"),
}


def table_stats(dsn: str, mode: str = "update") -> tuple[int, int, int]:
    """(live_tuples, dead_tuples, total_bytes) for the hot table.

    Size is included because dead-tuple count does NOT distinguish vacuum modes --
    plain VACUUM resets n_dead_tup just as FULL does. Only the heap size shows
    whether the table was rewritten (FULL) or merely marked reusable (plain), so
    without it the CSV cannot tell you which state a point actually ran on.
    """
    conn = psycopg2.connect(dsn)
    try:
        conn.autocommit = True
        with conn.cursor() as cur:
            tables = HOT_TABLES.get(mode, ("resources",))
            if not tables:
                return (0, 0, 0)
            cur.execute("SELECT coalesce(sum(n_live_tup), 0), coalesce(sum(n_dead_tup), 0) "
                        "FROM pg_stat_user_tables WHERE relname = ANY(%s)", (list(tables),))
            live, dead = cur.fetchone()
            cur.execute("SELECT coalesce(sum(pg_total_relation_size(relid)), 0) "
                        "FROM pg_stat_user_tables WHERE relname = ANY(%s)", (list(tables),))
            size = cur.fetchone()[0]
            return (int(live), int(dead), int(size))
    finally:
        conn.close()


def reset_table(dsn: str, vacuum_mode: str = "full", mode: str = "update") -> None:
    """Reset hot-table state between points. THIS CHOICE CHANGES WHAT IS MEASURED.

    `mode=update` writes rows, so dead tuples accumulate as a sweep proceeds.

    - "full": VACUUM FULL rewrites the heap, so every point starts from a pristine,
      perfectly packed table. Best comparability ACROSS points, and it matches what
      the simulator actually models -- a CONSTANT service cost per resource. A
      bloated comparator carries a history-dependent write cost the model has no
      representation of, exactly as PostgreSQL's transient partial holds carry a
      mechanism the all-or-nothing model excludes. Scope the resulting claim the
      same way: "matches on a cold, freshly packed table", not "matches".
      Takes ACCESS EXCLUSIVE, so it runs between points and never during one.
    - "plain": VACUUM marks space reusable without rewriting, so the table settles
      into a realistic steady state. Closer to a deployment, worse for
      cross-point comparability -- early points are cleaner than later ones until
      steady state is reached.
    - "none": no reset. Only for deliberately measuring degradation itself.

    Default is "full" because V0/V1 are VALIDATION experiments: the question is
    whether the simulator reproduces the protocol it models, not whether PostgreSQL
    is fast in production. Deployment realism is a separate experiment.

    V1 matters more here than V0: the CPR cascade updates dependent rows, so bloat
    per point is larger and the full-vs-plain gap widens.
    """
    conn = psycopg2.connect(dsn)
    try:
        conn.autocommit = True  # VACUUM cannot run inside a transaction block
        with conn.cursor() as cur:
            # Idempotent, and repeated here rather than only in setup_resources() so a
            # sweep run without --setup cannot silently reintroduce the confound.
            for table in HOT_TABLES.get(mode, ("resources",)):
                # Idempotent, and repeated here rather than only in setup so a sweep run
                # without --setup cannot silently reintroduce the confound.
                cur.execute(f"ALTER TABLE {table} SET (autovacuum_enabled = off)")
                if vacuum_mode == "full":
                    cur.execute(f"VACUUM (FULL, ANALYZE) {table}")
                elif vacuum_mode == "plain":
                    cur.execute(f"VACUUM (ANALYZE) {table}")
    finally:
        conn.close()


def _attempt_advisory_retry(cur, ids: list[int], max_attempts: int) -> int:
    """ONE round trip: the retry loop runs entirely inside try_acquire_all_retry() (schema.sql).
    Returns the attempt count on success, or -1 if max_attempts was exhausted (caller treats -1
    as a give-up, not a normal outcome)."""
    cur.execute("SELECT try_acquire_all_retry(%s, %s, %s)", (ids, max_attempts, RETRY_DELAY_S))
    return cur.fetchone()[0]


def _attempt_queued(cur, ids: list[int], max_attempts: int) -> int:
    """Matched-discipline arm: all-or-nothing preclaim with a FIFO QUEUEING wait, versus
    `advisory`'s opportunistic polling. See try_acquire_all_queued() in schema.sql for the contract
    and for the measured drain-latency artifact that must be reported with any result from it."""
    cur.execute("SELECT try_acquire_all_queued(%s, %s)", (ids, max_attempts))
    return cur.fetchone()[0]


def _write_rows(cur, ids: list[int]) -> None:
    """V0's write-only body: ONE statement, issued only AFTER the full advisory set is held.

    Ordering matters and is not incidental. Postgres row locks are held to commit regardless of what
    the advisory protocol does, so writing per-resource *during* acquisition would leave row locks
    behind on a failed attempt until the rollback - reintroducing exactly the transient partial state
    that has now shown up three times in this harness (H=0 blocking at W=2, the reliability ratio's
    high-W decay, and Panel B's inflated n). Writing once, after all-or-nothing acquisition has
    succeeded, means no attempt ever holds a partial row-lock set.

    It also makes the row locks uncontended by construction: no two transactions can hold overlapping
    advisory locks, so no two can want the same row. The advisory locks alone govern contention, and
    the UPDATE adds work without adding a second, unmodelled lock protocol underneath the first."""
    cur.execute("UPDATE resources SET v = v + 1 WHERE id = ANY(%s)", (ids,))


def _attempt_for_update(cur, ids: list[int]) -> None:
    """Incremental acquisition - blocks (does not raise) unless a real deadlock is detected, in
    which case psycopg2 raises psycopg2.errors.DeadlockDetected and the caller's except clause
    handles it."""
    cur.execute("SELECT id FROM resources WHERE id = ANY(%s) ORDER BY id FOR UPDATE", (ids,))


def worker(
    worker_id: int,
    dsn: str,
    d: int,
    k: int,
    hold_s: float,
    mode: str,
    window: MeasurementWindow,
    barrier: threading.Barrier,
    result: WorkerResult,
    seed: int,
    max_attempts: int,
) -> None:
    conn = psycopg2.connect(dsn)
    conn.autocommit = False
    rng = random.Random(seed)

    barrier.wait()  # all workers start their measurement window together
    t0 = time.monotonic()

    while time.monotonic() - t0 < window.end_s:
        ids = sorted(rng.sample(range(1, d + 1), k))
        attempt_start = time.monotonic()
        try:
            with conn.cursor() as cur:
                if mode in ("advisory", "update", "queued", "cpr_deferred"):
                    attempts = (_attempt_queued(cur, ids, max_attempts) if mode == "queued"
                                else _attempt_advisory_retry(cur, ids, max_attempts))
                    if attempts < 0:
                        if mode == "queued":
                            cur.execute("SELECT pg_advisory_unlock_all()")
                        conn.rollback()
                        result.advisory_giveups += 1
                        continue
                    if mode == "update":
                        _write_rows(cur, ids)
                    elif mode == "cpr_deferred":
                        # The PARENT. Advisory preclaim above, then its own write. It does NOT
                        # take model_a rows with SELECT ... FOR UPDATE: that is incremental
                        # acquisition, the protocol mismatch measured and rejected on V0's
                        # for_update arm, and it would compare a dynamic-acquisition parent
                        # against an all-or-nothing preclaim simulator at the rung whose whole
                        # purpose is the coupling point.
                        cur.execute("UPDATE model_a SET v = v + 1 WHERE id = ANY(%s)", (ids,))
                else:
                    _attempt_for_update(cur, ids)
                cur.execute("SELECT pg_sleep(%s)", (hold_s,))
                if mode == "queued":
                    # try_acquire_all_queued() takes SESSION-level advisory locks, not xact-level:
                    # the wait-then-release pattern needs a manual unlock, and xact-level advisory
                    # locks cannot be released before commit. Session locks SURVIVE commit, so they
                    # must be dropped explicitly here - otherwise every worker accumulates its whole
                    # history of footprints and the run wedges within seconds.
                    cur.execute("SELECT pg_advisory_unlock_all()")
            commit_start = time.monotonic()
            conn.commit()
            # Timed separately because the DEFERRED trigger fires inside COMMIT, so V0's
            # calibrated hold_s is no longer the whole critical section. Reporting one blended
            # latency would hide the propagation cost this rung exists to measure.
            commit_s = time.monotonic() - commit_start
        except psycopg2.errors.DeadlockDetected:
            conn.rollback()
            result.deadlocks += 1
            continue
        except psycopg2.Error:
            conn.rollback()
            raise

        latency_s = time.monotonic() - attempt_start
        elapsed = time.monotonic() - t0
        # BOTH bounds. The gate was `elapsed >= warmup_s` alone, so every worker's FINAL
        # transaction - started while elapsed < duration_s, completing after it - was counted
        # while the denominator stayed at the window length. That is one extra completion per
        # worker, i.e. throughput inflated by ~cycle/window: +5.0/+8.2/+9.7% predicted at
        # k=10/25/50, against +8/+12/+13% measured as a Little's-law violation (tps*latency vs N)
        # in the same runs. It is load-dependent because cycle grows with load, so it inflates
        # high-load throughput more than low-load - which biases any fit calibrated at low load.
        if window.contains(elapsed):
            result.commits += 1
            result.latencies_s.append(latency_s)
            if mode in ("advisory", "update", "queued", "cpr_deferred"):
                result.attempts_per_success.append(attempts)
            result.commit_latencies_s.append(commit_s)

    conn.close()


def lock_sampler(dsn: str, stop_event: threading.Event, samples: list[LockSample],
                  poll_interval_s: float = 0.1) -> None:
    """Background poller: SELECT count(*) FROM pg_locks WHERE locktype='advisory' every
    poll_interval_s, timestamped relative to its own start - the direct H measurement (see this
    module's docstring). Runs for the whole run_once() call, including warmup - the caller trims
    to the measurement window when summarizing, same convention as the per-worker warmup_s trim."""
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    t0 = time.monotonic()
    try:
        with conn.cursor() as cur:
            while not stop_event.is_set():
                cur.execute("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")
                h = cur.fetchone()[0]
                samples.append(LockSample(time.monotonic() - t0, h))
                time.sleep(poll_interval_s)
    finally:
        conn.close()


def summarize_lock_samples(samples: list[LockSample], window: MeasurementWindow) -> dict:
    """Time-weighted mean + peak H over [warmup_s, duration_s], matching
    SimulationMetrics.getMeanLocksHeldCountSampled()'s Sigma(H*dt)/T convention. Falls back to 0
    if no samples fell in the window (shouldn't happen at 100ms polling over a multi-second
    window, but guarded rather than dividing by zero)."""
    # [warmup_s, duration_s], NOT [warmup_s, warmup_s+duration_s]. worker() runs
    # `while elapsed < duration_s`, so duration_s is the TOTAL run and warmup is its first
    # warmup_s -- the old upper bound overshot the workers' stop time by a full warmup_s and
    # absorbed the DRAIN phase, where workers finish their last transaction and release, so H
    # decays toward 0. That biased mean_h (and n_mean) DOWNWARD by ~0.5*cycle/window, i.e.
    # more at high load: 1.2% at W=1 rising to 8.9% at W=256 on a 60/10 window. n_mean is the
    # quantity Panel B compares, so the agreement there was measured against a depressed
    # PostgreSQL n. Matches the commit gate at worker() exactly.
    kept = [s for s in samples if window.contains(s.t_s)]
    if len(kept) < 2:
        return {"mean_h": 0.0, "peak_h": (max((s.h for s in kept), default=0))}
    kept.sort(key=lambda s: s.t_s)
    weighted_sum = 0.0
    total_dt = 0.0
    for i in range(len(kept) - 1):
        dt = kept[i + 1].t_s - kept[i].t_s
        weighted_sum += kept[i].h * dt
        total_dt += dt
    mean_h = weighted_sum / total_dt if total_dt > 0 else 0.0
    peak_h = max(s.h for s in kept)
    return {"mean_h": mean_h, "peak_h": peak_h}


def count_model_b(dsn: str) -> int:
    """sum(v) over model_b: the marginal trigger-fire counter.

    Counted as a DIFFERENCE of two totals across the window, never by conditioning on
    per-attempt state (L5) -- the same rule that the P1 parity arm violated when it logged
    per retry and ended up measuring 1 - 1/retries.
    """
    conn = psycopg2.connect(dsn)
    try:
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("SELECT coalesce(sum(v), 0) FROM model_b")
            return int(cur.fetchone()[0])
    finally:
        conn.close()


def fire_sampler(dsn: str, window: MeasurementWindow, out: dict,
                  barrier: threading.Barrier) -> None:
    """Read the model_b total at window open and at window close, and nothing in between.

    Two readings on the SAME bounds the commit gate uses, so numerator (fires) and
    denominator (commits) refer to the same interval. A seq scan every poll would be far
    too heavy, and is unnecessary: only the endpoints matter.
    """
    # Share the WORKERS' time origin. worker() sets t0 after barrier.wait(), i.e. after every
    # worker has opened its connection; a sampler timing from its own thread start runs on an
    # origin earlier by delta (measured 0.031s at W=8, 0.125s at W=64, 0.532s at W=256). The
    # numerator would then count [warmup-delta, duration-delta] while the denominator counts
    # [warmup, duration] -- equal length, so it cancels only while the rate is constant, and
    # biases the ratio whenever it drifts. Same defect family as the three window bugs in
    # LOCKING_PROTOCOL_CONFIG_INCIDENT.md: two accumulators, two origins.
    barrier.wait()
    t0 = time.monotonic()
    time.sleep(max(window.start_s - (time.monotonic() - t0), 0))
    out["open"] = count_model_b(dsn)
    time.sleep(max(window.end_s - (time.monotonic() - t0), 0))
    out["close"] = count_model_b(dsn)


def run_once(dsn: str, mode: str, w: int, d: int, k: int, hold_s: float, duration_s: float,
             warmup_s: float, seed_base: int = 0, max_attempts: int = 1_000_000,
             dump_samples: str | None = None) -> dict:
    """Runs one (mode, W) point for duration_s (+ warmup_s discarded), returns the aggregated
    measurement dict. This is the function calibrate.py imports for its single-worker RTT probe."""
    window = MeasurementWindow(start_s=warmup_s, end_s=duration_s)
    # +1 party when the fire sampler participates, so it releases at the same instant the
    # workers do and both windows are measured from one origin.
    barrier = threading.Barrier(w + (1 if mode == "cpr_deferred" else 0))
    results = [WorkerResult() for _ in range(w)]
    threads = []

    fires: dict = {}
    fire_thread = None
    if mode == "cpr_deferred":
        fire_thread = threading.Thread(target=fire_sampler,
                                       args=(dsn, window, fires, barrier))
        fire_thread.start()

    lock_samples: list[LockSample] = []
    stop_sampler = threading.Event()
    sampler_thread = threading.Thread(target=lock_sampler, args=(dsn, stop_sampler, lock_samples))
    sampler_thread.start()

    for i in range(w):
        t = threading.Thread(
            target=worker,
            args=(i, dsn, d, k, hold_s, mode, window, barrier, results[i],
                  seed_base + i, max_attempts),
        )
        threads.append(t)
        t.start()
    for t in threads:
        t.join()

    stop_sampler.set()
    sampler_thread.join()
    if fire_thread is not None:
        fire_thread.join()

    total_commits = sum(r.commits for r in results)
    # A point completing ZERO transactions is a harness failure, not a measurement. Unflagged
    # it writes a row of nan that averages into its cell -- the silent-failure shape this
    # harness has produced repeatedly. Observed once at f=0.25 W=1 rep 4: no traceback, no
    # give-ups, no deadlocks, peak_h=0, so the worker never held a lock. Cause undiagnosed.
    # Marked so exclusion is by STATUS rather than nan-detection, and printed loudly enough
    # for a live monitor to catch.
    run_status = "OK" if total_commits > 0 else "ZERO_COMMITS"
    if total_commits == 0:
        print(f"  WARNING: ZERO_COMMITS at mode={mode} W={w} -- harness failure, not data")
    all_latencies = [lat for r in results for lat in r.latencies_s]
    total_deadlocks = sum(r.deadlocks for r in results)
    total_giveups = sum(r.advisory_giveups for r in results)
    all_attempts = [a for r in results for a in r.attempts_per_success]
    mean_attempts = (sum(all_attempts) / len(all_attempts)) if all_attempts else float("nan")

    lock_summary = summarize_lock_samples(lock_samples, window)
    all_commit_lat = [c for r in results for c in r.commit_latencies_s]
    trigger_fires = (fires.get("close", 0) - fires.get("open", 0)) if mode == "cpr_deferred" else 0

    # Wait-DISTRIBUTION shape, not just its mean. Four mechanism hypotheses have died, three of them
    # inferred from the shape of a mean-vs-load curve; the distribution constrains without assuming
    # a mechanism. wait = latency - hold, so mean/median of wait follows from the latency quantiles.
    # A heavier tail in one arm means a subset of transactions is disadvantaged, which is where a
    # k-dependent effect must live: a k-lock transaction needs all k free at once, an extreme-value
    # quantity that the mean cannot see.
    lat_sorted = sorted(all_latencies)
    def _q(f):
        if not lat_sorted:
            return float("nan")
        return lat_sorted[min(int(f * len(lat_sorted)), len(lat_sorted) - 1)]
    lat_p50, lat_p90, lat_p99 = _q(0.50), _q(0.90), _q(0.99)

    if dump_samples:
        # The raw (t, H) series, so the time-weighting can be audited rather than trusted.
        # summarize_lock_samples() is a LEFT-ENDPOINT Riemann sum: sample i is weighted by the gap
        # that FOLLOWS it. That is unbiased only if H is flat across the gap, or if gap length is
        # independent of H. Neither is guaranteed when the poller shares a GIL with W worker
        # threads - the same load that starves the poller also raises H, so long gaps may
        # preferentially straddle rising-H periods and be weighted by their lower left endpoint.
        with open(dump_samples, "a", newline="") as f:
            if f.tell() == 0:
                f.write("mode,W,t_s,h\n")
            for smp in lock_samples:
                f.write(f"{mode},{w},{smp.t_s:.4f},{smp.h}\n")

    return {
        "mode": mode,
        "W": w,
        "D": d,
        "k": k,
        "hold_ms": hold_s * 1000.0,
        # window.length_s is THE denominator -- not re-derived here. Dividing an 18s count by
        # 20s once understated every throughput figure by 10% uniformly; see MeasurementWindow.
        "throughput_tps": total_commits / max(window.length_s, 1e-9),
        "mean_latency_ms": (sum(all_latencies) / len(all_latencies) * 1000.0) if all_latencies else float("nan"),
        "latency_p50_ms": lat_p50 * 1000.0,
        "latency_p90_ms": lat_p90 * 1000.0,
        "latency_p99_ms": lat_p99 * 1000.0,
        "commits": total_commits,
        "run_status": run_status,
        "trigger_fires": trigger_fires,
        "fires_per_commit": (trigger_fires / total_commits) if total_commits else float("nan"),
        "commit_latency_p95_ms": (sorted(all_commit_lat)[min(int(0.95 * len(all_commit_lat)),
                                  len(all_commit_lat) - 1)] * 1000.0) if all_commit_lat else float("nan"),
        "deadlock_count": total_deadlocks,
        "advisory_giveup_count": total_giveups,
        "mean_attempts_per_success": mean_attempts,
        "mean_h": lock_summary["mean_h"],
        "peak_h": lock_summary["peak_h"],
        "n_mean": lock_summary["mean_h"] / k,
    }


def main():
    global RETRY_DELAY_S
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dsn", default=DEFAULT_DSN)
    parser.add_argument("--setup", action="store_true", help="(Re-)populate the resources table with D rows, then exit")
    parser.add_argument("--mode",
                         choices=["advisory", "update", "queued", "for_update", "cpr_deferred"],
                         default="advisory",
                         help="advisory = all-or-nothing preclaim, no writes (P1/A1 shape); "
                              "update = the same preclaim plus a single write after full "
                              "acquisition (V0's write-only rung); for_update = incremental "
                              "row-lock acquisition, the contrasting dynamic protocol")
    parser.add_argument("--d", type=int, default=5000)
    parser.add_argument("--k", type=int, default=25)
    parser.add_argument("--w-values", default="1,2,4,8,16,32,64",
                         help="Comma-separated worker-count (W, the MPL analogue) sweep")
    parser.add_argument("--hold-ms", type=float, required=False,
                         help="Critical-section hold time in ms - REQUIRED unless --setup; run "
                              "calibrate.py first to pick this (see the TGS plan's B2 acceptance condition)")
    parser.add_argument("--duration-s", type=float, default=20.0, help="Measurement window per (mode,W) point")
    parser.add_argument("--warmup-s", type=float, default=2.0, help="Discarded ramp-up before measuring")
    parser.add_argument("--reps", type=int, default=3)
    parser.add_argument("--prewarm-s", type=float, default=0.0,
                         help="Run the workload this long BEFORE the sweep and discard it. Needed "
                              "for write modes: on a freshly populated table the first load point "
                              "measures a bloat-free table and every later one measures a warmed "
                              "one, confounding load with table state in an ascending sweep. "
                              "Measured: RTT 5.6ms fresh vs ~12.6-18.8ms once autovacuum reaches "
                              "its dead-tuple steady state. 30s is enough at D=5000.")
    parser.add_argument("--max-attempts", type=int, default=1_000_000,
                         help="Cap on server-side retry attempts per advisory acquisition (schema.sql) "
                              "before giving up - effectively unbounded by default")
    parser.add_argument("--output", default="artifacts/experiments/results/postgres_anchor_raw.csv")
    parser.add_argument("--retry-delay-ms", type=float, default=RETRY_DELAY_S * 1000.0,
                         help="Server-side pause between advisory retry attempts. The DEFAULT 1ms "
                              "makes PostgreSQL poll the whole footprint at ~1kHz, which is a "
                              "different retry discipline from the simulator's wait-for-the-"
                              "blocking-lock: measured, PG makes ~20x more attempts per completed "
                              "transaction (1551 vs 76 at N=256). Raising this is a DIAGNOSTIC for "
                              "that asymmetry, not a tuning knob.")
    parser.add_argument("--f", type=float, default=0.0,
                         help="V1 semanticOverlap: fraction of model_a rows that EMIT a CPR. "
                              "Applied at --setup by flagging a random f-fraction, so ONE trigger "
                              "definition serves the whole sweep. Seed with --f-seed to match the "
                              "simulator's semanticOverlapSeed.")
    parser.add_argument("--f-seed", type=int, default=12345,
                         help="RNG seed for which model_a rows emit; match semanticOverlapSeed.")
    parser.add_argument("--vacuum-mode", choices=("full", "plain", "none"), default="full",
                         help="Between-point table reset. THIS CHANGES WHAT IS MEASURED - see "
                              "reset_table(). 'full' rewrites the heap (pristine table, best "
                              "cross-point comparability, matches the simulator's constant-service "
                              "-cost model); 'plain' lets the table reach a realistic steady state; "
                              "'none' measures degradation itself. Recorded in the output.")
    parser.add_argument("--dump-samples", default=None,
                         help="Append the raw (t, H) poller series here, for auditing the "
                              "time-weighting at high W where n fails a Little's-law cross-check")
    args = parser.parse_args()

    if args.setup:
        setup_resources(args.dsn, args.d)
        if args.mode == "cpr_deferred" or args.f > 0:
            setup_v1(args.dsn, args.d, args.f, args.f_seed)
        return

    if args.hold_ms is None:
        parser.error("--hold-ms is required for a sweep run - run calibrate.py first")

    w_values = [int(x) for x in args.w_values.split(",")]
    hold_s = args.hold_ms / 1000.0
    RETRY_DELAY_S = args.retry_delay_ms / 1000.0

    if args.prewarm_s > 0:
        # Discarded entirely - its only job is to bring the table to its steady dead-tuple state so
        # that point 1 of the sweep is not measured on a fresher table than point 9.
        print(f"pre-warming {args.prewarm_s:.0f}s at W=8 (discarded) ...")
        run_once(args.dsn, args.mode, 8, args.d, args.k, hold_s, args.prewarm_s, 0.0,
                 seed_base=987_654, max_attempts=args.max_attempts)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    write_header = not output_path.exists()

    with open(output_path, "a", newline="") as f:
        if write_header:
            f.write("mode,W,rep,throughput_tps,mean_latency_ms,deadlock_count,advisory_giveup_count,"
                     "mean_attempts_per_success,mean_h,peak_h,n_mean,D,k,hold_ms,"
                     "latency_p50_ms,latency_p90_ms,latency_p99_ms,duration_s,warmup_s,"
                     "dead_tuples_before,dead_tuples_after,vacuum_mode,"
                     "table_bytes_before,table_bytes_after,"
                     "f,trigger_fires,fires_per_commit,commit_latency_p95_ms,run_status\n")
        total_points = len(w_values) * args.reps
        point = 0
        for w in w_values:
            for rep in range(args.reps):
                point += 1
                print(f"[{point}/{total_points}] mode={args.mode} W={w} rep={rep} "
                      f"D={args.d} k={args.k} hold_ms={args.hold_ms}")
                # Reset table state BEFORE every point and record dead tuples either
                # side, so bloat is both CONTROLLED and VISIBLE in the output. The
                # confound that invalidated two claims on 2026-09-03 was undetectable
                # from any CSV and needed a separate control run to find.
                reset_table(args.dsn, args.vacuum_mode, args.mode)
                _, dead_before, size_before = table_stats(args.dsn, args.mode)
                result = run_once(args.dsn, args.mode, w, args.d, args.k, hold_s,
                                   args.duration_s, args.warmup_s, seed_base=rep * 10_000,
                                   max_attempts=args.max_attempts,
                                   dump_samples=args.dump_samples)
                f.write(f"{result['mode']},{result['W']},{rep},{result['throughput_tps']:.6f},"
                        f"{result['mean_latency_ms']:.3f},{result['deadlock_count']},"
                        f"{result['advisory_giveup_count']},{result['mean_attempts_per_success']:.3f},"
                        f"{result['mean_h']:.3f},{result['peak_h']},{result['n_mean']:.4f},"
                        f"{result['D']},{result['k']},{result['hold_ms']:.3f},"
                        f"{result['latency_p50_ms']:.3f},{result['latency_p90_ms']:.3f},{result['latency_p99_ms']:.3f},{args.duration_s:.1f},{args.warmup_s:.1f},"
                        f"{dead_before},{table_stats(args.dsn, args.mode)[1]},{args.vacuum_mode},"
                        f"{size_before},{table_stats(args.dsn, args.mode)[2]},"
                        f"{args.f},{result['trigger_fires']},{result['fires_per_commit']:.4f},{result['commit_latency_p95_ms']:.3f},{result['run_status']}\n")
                f.flush()
                if args.mode in ("advisory", "update", "queued") and result["deadlock_count"] > 0:
                    print(f"  WARNING: {result['deadlock_count']} deadlocks in the {args.mode.upper()} "
                          "arm - this arm should be structurally deadlock-free (all-or-nothing, no "
                          "partial holding); a nonzero count here means the harness is wrong, "
                          "not a finding. Investigate before trusting this point.\n"
                          "  For `update` specifically: advisory preclaim serialises overlapping "
                          "footprints, so the row locks the UPDATE takes are uncontended by "
                          "construction and cannot deadlock either.")

    print(f"Wrote {output_path}")


if __name__ == "__main__":
    # Single-instance lock around the WHOLE run. H is a server-wide count, so a second copy does
    # not merely add load -- its held locks land inside this run's measured H. That is what
    # happened on 2026-09-02, and the kill that was meant to prevent it reported success while
    # doing nothing. `preflight.py` catches an intruder already connected; this catches the second
    # copy before it connects. The context manager releases on every exit path, so a crashed
    # sweep leaves a lock its owner-liveness check will break automatically at the next launch.
    #
    # --setup is deliberately inside the lock too: it writes the tables a concurrent sweep reads.
    with run_lock.held(force="--force-unlock" in sys.argv):
        main()
