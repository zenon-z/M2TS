"""TGS static-locking validation - P1 parity plot data collection (Postgres side).

Collects (h_before, granted, success) per acquisition attempt on the same uniform-random k-of-D
workload the advisory arm runs, so measured P(blocked | H) can be plotted against the exact
hypergeometric 1 - C(D-H,k)/C(D,k) - zero free parameters.

Three passes. A and B are run in order and are deliberately separable, so the H=0 invariant can
attribute what moved. See schema.sql's P1 section for the full defect writeup; in brief:

  --stage a  CAPTURE FIX ONLY. Keeps the server-side retry loop and per-attempt logging exactly as
             they were; only h_before's capture changed (probe_attempt_once's single statement).
             At H=0 nothing is held, so any id set succeeds regardless of retry inflation, which
             makes the H=0 bin a clean read on the capture alone. Its ONLY gate is
             P(blocked | H=0) ~= 0; the old values were 0.051 / 0.023 / 0.068 and are structurally
             impossible.

             Stage A's overall parity is expected to STILL fail, and the expected failure has a
             number: the retry asymmetry pins the aggregate rate at 1 - 1/(attempts per success),
             so Stage A must reproduce 0.9569 / 0.8499 / 0.9668 (k=25 / k=10 / D=250 k=5) to within
             +-0.02. A larger move means the capture fix also perturbed the retry dynamics, which
             breaks the separability Stage B's interpretation rests on - understand it before
             running Stage B.

  --stage b  THE REAL PASS. One attempt per draw: fresh uniform k-subset every attempt, so every
             logged row is a first attempt BY CONSTRUCTION. This is the same rule the simulator has
             always enforced via SimulationMetrics' `attemptIndex == 0` gate (see
             AdmissionAttemptRow's javadoc for why repeated attempts on the same id set are not
             independent draws) - enforced here structurally rather than by a filter that can drift
             again.

             The redraw also breaks the synchronized herd. The old design had every worker retrying
             on a common 1ms tick, so h_before readings clustered in the trough between acquisition
             waves. Failed attempts here back off by Exp(mean = hold), which is memoryless, so no
             residual periodicity survives - a jitter small relative to the hold would just let
             workers re-cluster inside a single hold window and reconstitute the herd at a coarser
             grain.

  --stage g0 THE INSTRUMENT. Same redraw discipline, but through probe_attempt_once(), which
             attempts all k and reports `granted`. That yields E[k - granted | H] = k*H/D exactly -
             linear, zero free parameters, and independent of the blocking outcome - so the fitted
             slope divided by k/D is a RELIABILITY RATIO for h_before as a measure of true H at each
             load point. This is what characterises the instrument: pg_locks is a view over
             pg_lock_status(), which walks the 16 lock partitions taking each partition's LWLock in
             turn rather than snapshotting them together, so under churn h_before acquires
             measurement error and the ratio attenuates. Run separately from --stage b because the
             full-k attempt manufactures transient partial holds (measured: 24 of 27 H=0 failures at
             W=2 collided with one) that must not reach the headline data.

Round-trip cost per attempt is irrelevant to these passes: they measure P(blocked | H), and latency
enters no reported quantity. (That is the opposite of the throughput sweep, where server-side
retries exist precisely to keep RTT out of the measurement - hence the separate code path.)
"""
from __future__ import annotations

import argparse
import random
import threading
import time
from pathlib import Path

import psycopg2

from workload_client import DEFAULT_DSN, setup_resources

# STAGE A replays the PREVIOUS pass's ladder and duration EXACTLY. Its aggregate-rate check
# compares against 0.9569 / 0.8499 / 0.9668, which were measured on this load mix - the aggregate
# is a weighted average over load points, so changing the ladder or the per-point duration would
# confound the very comparison the check exists to make. Do not "modernise" these.
STAGE_A_CELLS = [
    (5000, 25, [2, 4, 8, 16, 32, 64, 128, 192]),
    (5000, 10, [2, 4, 8, 16, 32, 64, 128, 192]),
    (250, 5, [2, 4, 8, 16, 32, 48, 64]),
]
STAGE_A_DURATION_S = 8.0

# G0 PASS: its own pass, per V0 section L5's rule that instrumentation which perturbs the system
# lives in a separate function and a separate pass. probe_attempt_once()'s full-k attempt is what
# makes `granted` (and G0) available, and it is ALSO what manufactures transient partial holds - so
# it must not run inside the headline pass. It sweeps the same W ladder because its whole job is to
# measure how h_before's reliability varies WITH load; W=2 is dropped because the H range there is
# too narrow to fit a slope against.
G0_CELLS = [
    (5000, 25, [4, 8, 16, 32, 64, 128, 192, 256, 384, 512]),
    (5000, 10, [4, 8, 16, 32, 64, 128, 192, 256, 384, 512]),
    (250, 5, [4, 8, 16, 32, 48, 64, 96, 128]),
]

# STAGE B extends the ladder upward. The redraw lowers achieved H at a given W - blocked workers now
# idle through a jitter interval instead of spinning at 1kHz, so the fraction of wall time a worker
# actually holds falls from near-1 to roughly hold/(hold + jitter + RTT) ~ 0.48 - and the high-H
# bins have to be recovered with more workers.
STAGE_B_CELLS = [
    (5000, 25, [2, 4, 8, 16, 32, 64, 128, 192, 256, 384, 512]),
    (5000, 10, [2, 4, 8, 16, 32, 64, 128, 192, 256, 384, 512]),
    (250, 5, [2, 4, 8, 16, 32, 48, 64, 96, 128]),
]

CSV_HEADER = "stage,D,k,W,h_before,granted,success\n"


# -------------------------------------------------------------------------------------------
# Stage B - one attempt per fresh draw
# -------------------------------------------------------------------------------------------

def redraw_worker(dsn, d, k, duration_s, hold_s, barrier, seed, sink, sink_lock, full_k):
    """One attempt per fresh uniform k-subset. autocommit is on: session-level advisory locks are
    unaffected by transaction boundaries, so there is no transaction to manage and MVCC is out of
    the picture entirely."""
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    rng = random.Random(seed)
    local = []
    barrier.wait()
    t0 = time.monotonic()
    try:
        with conn.cursor() as cur:
            while time.monotonic() - t0 < duration_s:
                ids = sorted(rng.sample(range(1, d + 1), k))  # fresh draw EVERY attempt
                if full_k:
                    cur.execute("SELECT h_before, granted, success FROM probe_attempt_once(%s)",
                                (ids,))
                    h_before, granted, success = cur.fetchone()
                else:
                    # Short-circuit: no `granted`, and no manufactured transient collisions.
                    cur.execute("SELECT h_before, success FROM probe_attempt_once_sc(%s)", (ids,))
                    h_before, success = cur.fetchone()
                    granted = -1
                local.append((h_before, granted, success))
                if success:
                    # Held client-side: the lock is held by the session either way, and this saves a
                    # round trip versus pg_sleep. probe_attempt_once() already released the partial
                    # set on the failure path.
                    time.sleep(hold_s)
                    cur.execute("SELECT pg_advisory_unlock_all()")
                else:
                    time.sleep(rng.expovariate(1.0 / hold_s))
            cur.execute("SELECT pg_advisory_unlock_all()")
    finally:
        conn.close()
    with sink_lock:
        sink.extend(local)


def run_cell_redraw(dsn, d, k, w_values, duration_s, hold_s, out_file, stage, full_k):
    for w in w_values:
        print(f"  [{stage.upper()}] D={d} k={k} W={w} ({duration_s}s, hold {hold_s * 1000:.0f}ms)")
        barrier = threading.Barrier(w)
        sink: list[tuple[int, int, bool]] = []
        sink_lock = threading.Lock()
        threads = [
            threading.Thread(target=redraw_worker,
                             args=(dsn, d, k, duration_s, hold_s, barrier,
                                   w * 100_003 + i, sink, sink_lock, full_k))
            for i in range(w)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        for h_before, granted, success in sink:
            out_file.write(f"{stage},{d},{k},{w},{h_before},{granted},{success}\n")
        out_file.flush()
        blocked = sum(1 for _, _, s in sink if not s)
        n = len(sink) or 1
        print(f"        {len(sink)} attempts, aggregate P(blocked)={blocked / n:.4f}")


# -------------------------------------------------------------------------------------------
# Stage A - unchanged retry loop, capture fix only
# -------------------------------------------------------------------------------------------

def logged_worker(dsn, d, k, duration_s, hold_s, barrier, seed, max_attempts):
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    rng = random.Random(seed)
    barrier.wait()
    t0 = time.monotonic()
    try:
        with conn.cursor() as cur:
            while time.monotonic() - t0 < duration_s:
                ids = sorted(rng.sample(range(1, d + 1), k))
                cur.execute("SELECT try_acquire_all_retry_logged(%s, %s, %s)",
                            (ids, max_attempts, 0.001))
                if cur.fetchone()[0] < 0:
                    cur.execute("SELECT pg_advisory_unlock_all()")
                    continue
                time.sleep(hold_s)
                cur.execute("SELECT pg_advisory_unlock_all()")
            cur.execute("SELECT pg_advisory_unlock_all()")
    finally:
        conn.close()


def run_cell_stage_a(dsn, d, k, w_values, duration_s, hold_s, max_attempts, out_file):
    for w in w_values:
        print(f"  [A] D={d} k={k} W={w} ({duration_s}s, hold {hold_s * 1000:.0f}ms)")
        barrier = threading.Barrier(w)
        threads = [
            threading.Thread(target=logged_worker,
                             args=(dsn, d, k, duration_s, hold_s, barrier,
                                   w * 100_003 + i, max_attempts))
            for i in range(w)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        # Dump and truncate per W value, not per cell: attempt_log carries no W column, and the
        # load axis is needed to read the H coverage per load point.
        dump_and_truncate(dsn, out_file, d, k, w)


def dump_and_truncate(dsn, out_file, d, k, w):
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT h_before, granted, success FROM attempt_log")
            rows = cur.fetchall()
            for h_before, granted, success in rows:
                out_file.write(f"a,{d},{k},{w},{h_before},{granted},{success}\n")
            cur.execute("TRUNCATE attempt_log")
    finally:
        conn.close()
    out_file.flush()
    blocked = sum(1 for _, _, s in rows if not s)
    n = len(rows) or 1
    print(f"        {len(rows)} attempts, aggregate P(blocked)={blocked / n:.4f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dsn", default=DEFAULT_DSN)
    parser.add_argument("--stage", choices=["a", "b", "g0"], default="b",
                        help="a = capture fix only (Defect B, gated on P(blocked|H=0)); "
                             "b = redraw per attempt, SHORT-CIRCUIT acquisition (the headline "
                             "pass); g0 = dedicated full-k pass measuring h_before's "
                             "reliability ratio per W (the instrument, kept out of b)")
    parser.add_argument("--output", default=None,
                        help="default: artifacts/experiments/results/postgres_p1_attempts_stage<S>.csv")
    parser.add_argument("--duration-s", type=float, default=None,
                        help="Per (cell, W) measurement window. Stage B defaults to 25s (raised "
                             "from the previous pass's 8s: the redraw's jitter cuts per-worker "
                             "attempt rate to ~10/s). Stage A is PINNED at 8s - see STAGE_A_CELLS")
    parser.add_argument("--hold-ms", type=float, default=50.0,
                        help="Critical-section hold. Was hard-coded at 50ms; the jitter's mean is "
                             "tied to this, so it is now explicit")
    parser.add_argument("--max-attempts", type=int, default=1_000_000,
                        help="Stage A only: server-side retry cap")
    args = parser.parse_args()

    out_path = Path(args.output) if args.output else Path(
        f"artifacts/experiments/results/postgres_p1_attempts_stage{args.stage}.csv")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    hold_s = args.hold_ms / 1000.0

    if args.stage == "a":
        cells, duration_s = STAGE_A_CELLS, STAGE_A_DURATION_S
        if args.duration_s is not None:
            duration_s = args.duration_s
            print("WARNING: overriding Stage A's pinned 8s window invalidates the aggregate-rate "
                  "check against 0.9569 / 0.8499 / 0.9668.\n")
    elif args.stage == "g0":
        cells, duration_s = G0_CELLS, (args.duration_s or 20.0)
    else:
        cells, duration_s = STAGE_B_CELLS, (args.duration_s or 25.0)

    print(f"Stage {args.stage.upper()} -> {out_path}")
    if args.stage == "a":
        print("Expected: overall parity STILL fails; aggregate P(blocked) must stay at "
              "0.9569 / 0.8499 / 0.9668 (+-0.02). Only gate is P(blocked | H=0) ~= 0.\n")

    with open(out_path, "w", newline="") as f:
        f.write(CSV_HEADER)
        for d, k, w_values in cells:
            print(f"D={d} k={k}")
            setup_resources(args.dsn, d)
            if args.stage in ("b", "g0"):
                run_cell_redraw(args.dsn, d, k, w_values, duration_s, hold_s, f,
                                args.stage, full_k=(args.stage == "g0"))
            else:
                run_cell_stage_a(args.dsn, d, k, w_values, duration_s, hold_s,
                                 args.max_attempts, f)

    print(f"\nWrote {out_path}")
    print(f"Next: python scripts/analysis/p1_parity_gates.py --stage {args.stage}")


if __name__ == "__main__":
    main()
