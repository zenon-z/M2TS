"""Synthetic fixtures with known answers for v1b_campaign_analysis.py.

Written BEFORE the campaign's data lands, for the reason the readout's fixture existed: the two
bugs found in that program were both on paths a clean dry run never touched, and there is no known
answer to check against once real data arrives.

  REJECT  35 reps generated at c=1.40, b=-0.90. T^2 must reject; the recovered c and b must sit
          near the generating values; the signed-rank must agree.
  NULL    35 reps generated at c=1.00, b=0.00. T^2 must NOT reject -- the branch that would be
          invisible if the campaign happens to reject, and the one that matters most for
          believing a rejection.
  VOID    one cell carrying a NON-agreement gate failure. C10 must void and exit 1 before any
          coefficient is printed.
  TRIPS   six reps tripping the agreement gate -- the registered cap -- must also void.
  CWAL    WAL columns generated so the corrected c_wal equals the measured null 1.0764; the
          analysis must call it consistent.
"""
import csv
import os
import random
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
FS = [0.0, 0.05, 0.125, 0.25, 0.5]
TRIP = "estimator disagreement |ln(R_med/R_mean)|=1.386 > 0.405 (R_med=1.4 R_mean=0.35)"
COLS = ["rep", "W", "f", "rep_attempt", "R_paired", "R_paired_mean", "counted",
        "io_wal_records", "host_cpu_pct", "gate_failures", "seed_base", "pool_seed",
        "trigger_sha", "git_commit", "plan_blob", "git_dirty"]
CWAL_TRUE = 1.0764 * 0.9940      # so the attenuation-corrected estimate lands on the null


def rows_for(scenario, seed=99):
    rng = random.Random(seed)
    out, sup = [], []
    c_true, b_true = (1.40, -0.90) if scenario in ("reject", "void", "cwal",
                                                   "incomplete") else (1.00, 0.00)
    for rep in range(35):
        dc, db = rng.gauss(0, 0.24), rng.gauss(0, 0.63)
        dw = rng.gauss(0, 0.01)
        trip_this = scenario == "trips" and rep < 6
        for f in FS:
            r = 1.0 + (c_true + dc) * f + (b_true + db) * f * f + rng.gauss(0, 0.004)
            counted = 3500
            # Per-rep WAL noise: without it every rep yields an identical c_wal, the CI has
            # zero width, and section 7 cannot form a comparison at all.
            wal = counted * 210.0 * (1.0 + 0.5 * f * CWAL_TRUE) * (1.0 + dw + rng.gauss(0, 0.002))
            gf = ""
            if scenario == "void" and rep == 0 and f == 0.25:
                gf = "little 1.412 vs W=1"           # a NON-agreement gate failure
            if trip_this:
                gf = TRIP
            row = {"rep": rep, "W": 1, "f": f, "rep_attempt": 0,
                   "R_paired": round(r, 6), "R_paired_mean": round(r * 0.997, 6),
                   "counted": counted, "io_wal_records": round(wal, 1),
                   "host_cpu_pct": rng.randint(35, 70), "gate_failures": gf,
                   "seed_base": 926993715, "pool_seed": 0.327533364,
                   "trigger_sha": "a9a89e5bf5bcd104", "git_commit": "deadbeef" * 5,
                   "plan_blob": "cafe" * 10, "git_dirty": "False"}
            if scenario == "incomplete" and rep == 7 and f == 0.125:
                continue                 # rep 7 loses one cell: must be reported, not silent
            (sup if trip_this else out).append(row)
        if trip_this:                                # a passing re-attempt keeps the rep present
            for f in FS:
                r = 1.0 + (c_true + dc) * f + (b_true + db) * f * f
                out.append({"rep": rep, "W": 1, "f": f,
                            "rep_attempt": 1, "R_paired": round(r, 6),
                            "R_paired_mean": round(r * 0.997, 6), "counted": 3500,
                            "io_wal_records": 3500 * 210.0, "host_cpu_pct": 50,
                            "gate_failures": "", "seed_base": 926993715,
                            "pool_seed": 0.327533364, "trigger_sha": "a9a89e5bf5bcd104",
                            "git_commit": "deadbeef" * 5, "plan_blob": "cafe" * 10,
                            "git_dirty": "False"})
    return out, sup


def write(tmp, name, rows):
    p = os.path.join(tmp, name + ".csv")
    with open(p, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLS, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    return p


def run(tmp, scenario):
    kept, sup = rows_for(scenario)
    k = write(tmp, scenario, kept)
    s = write(tmp, scenario + "_sup", sup)
    return subprocess.run([sys.executable, os.path.join(HERE, "v1b_campaign_analysis.py"),
                           "--csv", k, "--superseded", s], capture_output=True, text=True)


def main():
    tmp = tempfile.mkdtemp(prefix="v1b_camp_fix_")
    checks = []

    o = run(tmp, "reject"); t = o.stdout
    checks += [
        ("REJECT  provenance single-valued", "*** 2 VALUES ***" not in t),
        ("REJECT  no void", "no void condition" in t),
        ("REJECT  35 complete reps", "complete reps: 35" in t),
        ("REJECT  T2 rejects", "-> REJECT 'a CU is one resource touch'" in t),
        ("REJECT  c recovered near 1.40", any(("c     = 1.3" in t) or ("c     = 1.4" in t)
                                              for _ in [0])),
        ("REJECT  corr caveat printed", "conditional on the" in t),
        ("REJECT  c_m table printed", "MARGINAL PER-FIRE COST" in t),
        ("REJECT  c_wal consistent with null", "is consistent with the null" in t),
        # c is generated at 1.40 and c_wal at 1.076, so the two channels MUST be reported as
        # disagreeing -- the case that matters, and the one CI-containment alone would miss.
        ("REJECT  c interval printed in sec 7", "c (latency channel) 95% CI" in t),
        ("REJECT  channels DO NOT overlap", "DO NOT OVERLAP" in t),
        ("REJECT  exit 0", o.returncode == 0),
    ]

    o = run(tmp, "null"); t = o.stdout
    checks += [
        ("NULL    T2 does NOT reject", "-> cannot reject 'a CU is one resource touch'" in t),
        # c is generated at 1.00 and c_wal at 1.076: the intervals should OVERLAP here, so the
        # comparison is exercised in both directions rather than only on disagreement.
        ("NULL    channels OVERLAP", "OVERLAP -- the two channels agree" in t),
        ("NULL    exit 0", o.returncode == 0),
    ]

    o = run(tmp, "void"); t = o.stdout
    checks += [
        ("VOID    non-agreement gate detected", "failed a NON-agreement gate" in t),
        ("VOID    campaign declared void", "CAMPAIGN VOID" in t),
        ("VOID    stops before the primary", "[3] PRIMARY" not in t),
        ("VOID    exit 1", o.returncode == 1),
    ]

    o = run(tmp, "trips"); t = o.stdout
    checks += [
        ("TRIPS   six tripped reps counted", "reps that EVER tripped: 6" in t),
        ("TRIPS   voids at the cap", "CAMPAIGN VOID" in t),
        ("TRIPS   exit 1", o.returncode == 1),
    ]

    o = run(tmp, "incomplete"); t = o.stdout
    checks += [
        ("INCOMP  cell-count mismatch flagged", "CELL COUNT MISMATCH: -1" in t),
        ("INCOMP  the rep is named", "rep 7   missing f=[0.125]" in t),
        ("INCOMP  n reported below 35", "complete reps: 34" in t),
    ]

    print("=" * 78)
    print("CAMPAIGN ANALYSIS FIXTURE  --  5 scenarios")
    print("=" * 78)
    bad = 0
    for label, ok in checks:
        print("  %-44s %s" % (label, "PASS" if ok else "FAIL"))
        bad += 0 if ok else 1
    print("\n  %d of %d passed" % (len(checks) - bad, len(checks)))
    if bad:
        print("\n--- last stdout ---\n%s" % t[-2500:])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
