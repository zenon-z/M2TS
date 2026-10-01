"""Synthetic fixtures with known answers for v1b_readout.py.

Three scenarios, because the earlier single fixture reached neither the verdict path nor the GO
branch: its cells were noise-free, so every rep had identical coefficients, the pooled covariance
was degenerate, and the run aborted at step 5. Steps 5-6 were therefore exercised only by real
data -- which is the one place a bug is hardest to notice, because there is no known answer.

  TRIPS   35 reps with seeded noise and three trip patterns. Exercises the exclusion branch, the
          REP-level corr estimator, re-run pooling, and reaches the verdict.
  GO      35 clean reps with noise. Must reach GO, n >= 35, exit status 0.
  NOGO    35 reps with enough first-attempt trips that side A fails. Must exit 1.
  DEGEN   noise-free, retained deliberately as the regression test for the
          degenerate-covariance guard (a ZeroDivisionError that crashed AFTER printing partial
          results, which is the worst shape of failure since it looks as though the run worked).

Two bugs lived in these paths, both biasing toward GO: corr counted per CELL rather than per REP,
and a pooling filter that dropped every rep which passed on RE-ATTEMPT. Both are asserted here.
"""
import csv
import os
import random
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
FS = [0.0, 0.05, 0.125, 0.25, 0.5]
COLS = ["rep", "W", "f", "rep_attempt", "R_paired", "R_paired_mean", "gate_failures"]
TRIP = "estimator disagreement |ln(R_med/R_mean)|=1.386 > 0.405 (R_med=1.3981 R_mean=0.3495)"


def cell(rep, f, attempt, trip, rng=None):
    """A passing cell has its two R estimators agreeing; a tripping one has them far apart.

    With rng, per-rep noise is added so the fitted coefficients VARY across reps -- without it the
    pooled covariance is singular and the readout cannot reach a verdict.
    """
    base = 1.0 + 1.4 * f - 0.9 * f * f
    if rng is not None:
        base += rng.gauss(0, 0.02 + 0.06 * f)
    return {"rep": rep, "W": 1, "f": f, "rep_attempt": attempt,
            "R_paired": round(base, 6),
            "R_paired_mean": round(base / 4.0 if trip else base * (0.995 + (rng.gauss(0, 0.004)
                                                                            if rng else 0.0)), 6),
            "gate_failures": TRIP if trip else ""}


def write(tmp, name, rows):
    p = os.path.join(tmp, name + ".csv")
    with open(p, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLS)
        w.writeheader()
        w.writerows(rows)
    return p


def build(tmp, scenario, seed=4242):
    """35 pilot reps: 10 in `ptrial`, 25 in `ptrial2`."""
    rng = random.Random(seed) if scenario != "degen" else None
    pilot, ext, ext_sup = [], [], []
    for rep in range(10):
        for f in FS:
            pilot.append(cell(rep, f, 0, False, rng))
    for rep in range(25):
        if scenario == "trips" and rep == 5:        # INSIDE the bracket: trips, then passes
            ext_sup += [cell(rep, f, 0, True, rng) for f in FS]
            ext += [cell(rep, f, 1, False, rng) for f in FS]
        elif scenario == "trips" and rep == 20:     # re-attempt trips in ONE cell of five
            ext_sup += [cell(rep, f, 0, True, rng) for f in FS]
            ext_sup += [cell(rep, f, 1, i == 2, rng) for i, f in enumerate(FS)]
            ext += [cell(rep, f, 2, False, rng) for f in FS]
        elif scenario == "trips" and rep == 21:     # passes on re-attempt
            ext_sup += [cell(rep, f, 0, True, rng) for f in FS]
            ext += [cell(rep, f, 1, False, rng) for f in FS]
        elif scenario == "nogo" and rep < 8:        # many first-attempt trips -> side A fails
            ext_sup += [cell(rep, f, 0, True, rng) for f in FS]
            ext += [cell(rep, f, 1, False, rng) for f in FS]
        else:
            ext += [cell(rep, f, 0, False, rng) for f in FS]
    return {"ptrial": write(tmp, "ptrial", pilot),
            "ptrial_sup": write(tmp, "ptrial_sup", []),
            "ptrial2": write(tmp, "ptrial2", ext),
            "ptrial2_sup": write(tmp, "ptrial2_sup", ext_sup)}


def run(paths, tmp):
    empty = write(tmp, "empty", [])
    return subprocess.run(
        [sys.executable, os.path.join(HERE, "v1b_readout.py"),
         "--pilot", paths["ptrial"], "--pilot-sup", paths["ptrial_sup"],
         "--ext", paths["ptrial2"], "--ext-sup", paths["ptrial2_sup"],
         "--plain2", empty], capture_output=True, text=True)


def main():
    tmp = tempfile.mkdtemp(prefix="v1b_fixture_")
    results = []

    out = run(build(tmp + "", "trips"), tmp)
    t = out.stdout
    results += [
        ("TRIPS  inside the bracket is [5]", "inside the bracket ..... [5]" in t),
        ("TRIPS  outside is [20, 21]", "outside ................ [20, 21]" in t),
        ("TRIPS  exclusion branch taken", "reported BOTH ways" in t),
        ("TRIPS  p all reps = 3/35", "3 trips / 35 reps" in t),
        ("TRIPS  p excluding 5-11 = 2/28", "2 trips / 28 reps" in t),
        ("TRIPS  corr REP-level 1/4 = 0.25", "1 tripped / 4 rep-attempts = 0.2500" in t),
        ("TRIPS  corr is NOT per cell (1/20)", "1 tripped / 20" not in t),
        ("TRIPS  ptrial2 pools 18 reps", "coefficients: 18" in t),
        ("TRIPS  re-attempt passers pooled", "excluded 7 contaminated" in t),
        ("TRIPS  reaches a verdict", "[6] VERDICT" in t),
        ("TRIPS  covariance not degenerate", "DEGENERATE" not in t),
    ]

    out = run(build(tmp, "go"), tmp)
    t = out.stdout
    results += [
        ("GO     no trips anywhere", "0 trips / 35 reps" in t),
        ("GO     upper bound 0.0449", "0.0449" in t),
        ("GO     verdict is GO", "\n    GO." in t),
        ("GO     n floored at 35", "n = 35" in t),
        ("GO     exit status 0", out.returncode == 0),
    ]

    out = run(build(tmp, "nogo"), tmp)
    t = out.stdout
    results += [
        ("NOGO   8 first-attempt trips seen", "8 trips / 35 reps" in t),
        ("NOGO   side A fails", "side A satisfied: False" in t),
        ("NOGO   verdict is NO-GO", "NO-GO" in t),
        ("NOGO   exit status 1", out.returncode == 1),
    ]

    out = run(build(tmp, "degen"), tmp)
    t = out.stdout
    results += [
        ("DEGEN  guard fires, no traceback", "DEGENERATE" in t and "Traceback" not in out.stderr),
        ("DEGEN  exit status 1", out.returncode == 1),
    ]

    print("=" * 78)
    print("READOUT FIXTURE  --  4 scenarios")
    print("=" * 78)
    bad = 0
    for label, ok in results:
        print("  %-42s %s" % (label, "PASS" if ok else "FAIL"))
        bad += 0 if ok else 1
    print("\n  %d of %d passed" % (len(results) - bad, len(results)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
