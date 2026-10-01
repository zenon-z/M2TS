"""TGS static-locking validation - PostgreSQL anchor, B2 calibration.

Picks a critical-section hold time (pg_sleep between lock acquisition and commit) satisfying the
TGS plan's B2 ACCEPTANCE CONDITION - hold_ms >= 20 * RTT - rather than treating hold time as a
free parameter chosen however it falls out. Without this, normalized throughput (throughput /
throughput at W=1) doesn't equal achieved concurrency in the sense B4's balance-equation plateau
prediction assumes (Little's Law: achieved concurrency = throughput * total transaction time, and
total transaction time = RTT + hold - if RTT isn't negligible next to hold, the normalization
picks up round-trip overhead as if it were held-lock time).

Method: run a single worker (W=1, so contention is structurally absent - nothing else is
attempting a lock at the same time) at a small candidate hold time, measure mean latency. Since
at W=1, mean_latency ~= RTT + hold (no queueing/blocking possible), RTT = mean_latency - hold.
Compute the hold time the 20x bound actually requires (hold_needed = 20 * RTT), then CONFIRM at
that hold time (RTT should be roughly hold-independent - it's round-trip/parsing/planning
overhead, not related to how long the connection sleeps - so one confirmation pass should
converge).
"""
from __future__ import annotations

import argparse
from pathlib import Path

from workload_client import run_once, setup_resources, DEFAULT_DSN

ACCEPTANCE_RATIO = 20.0


def calibrate(dsn: str, d: int, k: int, probe_hold_ms: float, duration_s: float, warmup_s: float,
              max_iterations: int = 5, safety_margin: float = 1.15) -> dict:
    """Iterates rather than confirming once - RTT has measurement noise (observed ~10% run-to-run
    variance), so a single confirmation pass can land just under the 20x bound even when the true
    RTT is stable. Each iteration recomputes the required hold from the LATEST measured RTT, with
    a small safety margin on top of the raw 20x so noise doesn't flip a converged result back
    under the bound on the actual sweep run."""
    hold_ms = probe_hold_ms
    history = []
    for iteration in range(max_iterations):
        trial = run_once(dsn, "advisory", w=1, d=d, k=k, hold_s=hold_ms / 1000.0,
                          duration_s=duration_s, warmup_s=warmup_s)
        rtt_ms = trial["mean_latency_ms"] - hold_ms
        if rtt_ms <= 0:
            raise RuntimeError(
                f"Measured RTT is non-positive ({rtt_ms:.3f}ms) at hold_ms={hold_ms:.3f} - "
                "lower --probe-hold-ms and retry."
            )
        ratio = hold_ms / rtt_ms
        history.append({"iteration": iteration, "hold_ms": hold_ms, "mean_latency_ms": trial["mean_latency_ms"],
                         "rtt_ms": rtt_ms, "ratio": ratio})
        if ratio >= ACCEPTANCE_RATIO:
            return {
                "probe_hold_ms": probe_hold_ms,
                "iterations": history,
                "final_hold_ms": hold_ms,
                "final_rtt_ms": rtt_ms,
                "final_ratio": ratio,
                "acceptance_ratio_required": ACCEPTANCE_RATIO,
                "passed": True,
            }
        # Recompute from the latest RTT estimate, with a margin so noise on the NEXT (sweep) run
        # doesn't dip back under the bound.
        hold_ms = ACCEPTANCE_RATIO * rtt_ms * safety_margin

    return {
        "probe_hold_ms": probe_hold_ms,
        "iterations": history,
        "final_hold_ms": hold_ms,
        "final_rtt_ms": history[-1]["rtt_ms"],
        "final_ratio": history[-1]["ratio"],
        "acceptance_ratio_required": ACCEPTANCE_RATIO,
        "passed": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dsn", default=DEFAULT_DSN)
    parser.add_argument("--d", type=int, default=5000, help="Resource pool size for the probe (D)")
    parser.add_argument("--k", type=int, default=25, help="Transaction size for the probe (k)")
    parser.add_argument("--probe-hold-ms", type=float, default=1.0,
                         help="Small initial hold time used to estimate RTT (W=1, no contention)")
    parser.add_argument("--duration-s", type=float, default=10.0)
    parser.add_argument("--warmup-s", type=float, default=1.0)
    parser.add_argument("--output", default="artifacts/experiments/results/postgres_calibration.txt")
    parser.add_argument("--skip-setup", action="store_true",
                         help="Skip (re-)populating the resources table (assumes it's already sized >= D)")
    args = parser.parse_args()

    if not args.skip_setup:
        setup_resources(args.dsn, args.d)

    result = calibrate(args.dsn, args.d, args.k, args.probe_hold_ms, args.duration_s, args.warmup_s)

    lines = ["TGS Postgres anchor - B2 calibration", f"D={args.d} k={args.k}", ""]
    for it in result["iterations"]:
        lines.append(f"  iteration {it['iteration']}: hold_ms={it['hold_ms']:.3f}  "
                      f"mean_latency_ms={it['mean_latency_ms']:.3f}  rtt_ms={it['rtt_ms']:.3f}  "
                      f"ratio={it['ratio']:.2f}")
    lines += [
        "",
        f"final_hold_ms={result['final_hold_ms']:.3f}  final_rtt_ms={result['final_rtt_ms']:.3f}",
        f"final_ratio={result['final_ratio']:.2f}  (acceptance requires >= {ACCEPTANCE_RATIO:.0f})",
        f"PASSED: {result['passed']}",
    ]
    report = "\n".join(lines)
    print(report)

    if not result["passed"]:
        print(f"\nFAILED to reach the >=20x acceptance condition within {len(result['iterations'])} "
              "iterations - RTT may be unusually noisy (server/network load); investigate before "
              "using this hold time for the sweep.")

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report + "\n")
    print(f"\nWrote {output_path}")
    print(f"\nUse --hold-ms {result['final_hold_ms']:.1f} with workload_client.py's sweep once this passes.")


if __name__ == "__main__":
    main()
