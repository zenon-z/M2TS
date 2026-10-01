"""Regression test: the MeasurementWindow refactor must be behaviour-preserving.

Replays archived raw (t,H) dumps through summarize_lock_samples() and checks the
result against mean_h/peak_h as recorded in the results CSVs -- which were
produced by the PRE-refactor code. Any drift is the refactor's doing.

This is the verification a live re-run cannot give: a fresh sweep is a new
stochastic measurement with run-to-run variance, so it can only confirm
agreement within noise. Replaying fixed inputs isolates the code change.

Tolerance is 5e-4 on mean_h because the CSVs store it to 3 decimals; peak_h is
integral and must match exactly.

Run:  python scripts/postgres_anchor/test_window_replay.py
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from workload_client import LockSample, MeasurementWindow, summarize_lock_samples

R = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 "artifacts", "experiments", "results")

CASES = [
    ("v0clean_samples_advisory_k10.csv", "v0_clean_n256.csv", "advisory", 10, 120.0, 20.0),
    ("v0clean_samples_advisory_k25.csv", "v0_clean_n256.csv", "advisory", 25, 120.0, 20.0),
    ("v0clean_samples_queued_k50.csv", "v0_clean_n256.csv", "queued", 50, 120.0, 20.0),
    ("v0k50_samples_advisory.csv", "v0_clean_k50.csv", "advisory", 50, 220.0, 20.0),
    ("v0_ladder_samples.csv", "v0_postgres_raw5.csv", "update", 25, 60.0, 10.0),
]


def split_reps(rows):
    """t_s restarts near 0 each run_once, so a decrease marks a rep boundary."""
    reps, cur, last = [], [], -1.0
    for t, h in rows:
        if t < last:
            reps.append(cur)
            cur = []
        cur.append(LockSample(t, h))
        last = t
    reps.append(cur)
    return reps


def main():
    failures, checked = [], 0
    for dump, res, mode, k, dur, warm in CASES:
        try:
            rows = [(float(r["t_s"]), int(r["h"]))
                    for r in csv.DictReader(open(os.path.join(R, dump)))
                    if int(r["W"]) == 256]
        except FileNotFoundError:
            print("skip %s (absent)" % dump)
            continue
        recorded = [r for r in csv.DictReader(open(os.path.join(R, res)))
                    if r["mode"] == mode and int(r["k"]) == k and int(r["W"]) == 256]
        win = MeasurementWindow(start_s=warm, end_s=dur)
        for i, (rep, rec) in enumerate(zip(split_reps(rows), recorded)):
            got = summarize_lock_samples(rep, win)
            checked += 1
            if abs(got["mean_h"] - float(rec["mean_h"])) >= 5e-4:
                failures.append("%s k=%d rep%d mean_h %.4f != %s"
                                % (mode, k, i, got["mean_h"], rec["mean_h"]))
            if int(got["peak_h"]) != int(float(rec["peak_h"])):
                failures.append("%s k=%d rep%d peak_h %d != %s"
                                % (mode, k, i, int(got["peak_h"]), rec["peak_h"]))
    for f in failures:
        print("DRIFT: %s" % f)
    print("%d replays checked, %d drifted" % (checked, len(failures)))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
