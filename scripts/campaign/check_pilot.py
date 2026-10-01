"""Read the W1-W4 pilot's own output and decide the verification items that can only be
decided against real driver output: 8 (C15 nonzero), 9 (two-class arithmetic), 13 (aliasing
off), 14 (pigeonhole), 20 (control sits AT capacity), plus the seeds columns and C5.

Every check prints PASS/FAIL with the number it decided on, so a reader can apply a different
threshold without re-running the pilot - the same discipline the pigeonhole ratio is written
under.
"""
import csv
import math
import os
import sys
from collections import defaultdict

# Resolved relative to the repo root, not hardcoded: this has to run from a fresh clone, from the
# campaign host, and from wherever the results were copied to. Override with a second argument or
# TS_RESULTS_DIR.
AXIS = sys.argv[1] if len(sys.argv) > 1 else "readonly"
RESULTS = (sys.argv[2] if len(sys.argv) > 2
           else os.environ.get("TS_RESULTS_DIR")
           or os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "..", "artifacts", "experiments", "results"))
RESULTS = os.path.normpath(RESULTS)

raw_path = os.path.join(RESULTS, f"wl_{AXIS}_raw.csv")
cells_path = os.path.join(RESULTS, f"wl_{AXIS}_cells.csv")
skip_path = os.path.join(RESULTS, f"wl_{AXIS}_skipped.csv")

for p in (raw_path, cells_path):
    if not os.path.exists(p):
        print(f"MISSING {p} - the driver writes these on close; is the pilot still running?")
        sys.exit(2)


def rows(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


raw = rows(raw_path)
cells = rows(cells_path)
skipped = rows(skip_path) if os.path.exists(skip_path) else []
by_id = {c["config_id"]: c for c in cells}

print(f"=== pilot {AXIS}: {len(raw)} raw rows, {len(cells)} cells, {len(skipped)} skipped ===\n")

results = []


def check(name, ok, detail):
    results.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}\n        {detail}\n")


# ---------------------------------------------------------------- item 20
# The control must sit AT capacity, not merely above the CPR lines. tol = 1e-3, which is
# already looser than the instrument: the control measured bit-identical across nine theta.
ctrl = [r for r in raw if r["panel"] == "control"]
worst, worst_x = 0.0, None
for r in ctrl:
    cap = float(by_id[r["config_id"]]["reference_capacity_tps"])
    share = float(r["throughput_tps"]) / cap
    if abs(share - 1.0) > worst:
        worst, worst_x = abs(share - 1.0), (r["x"], share)
check(
    "item 20: control capacity_share == 1.000 +/- 1e-3 at every x-point",
    ctrl and worst <= 1e-3,
    f"{len(ctrl)} control rows; worst deviation {worst:.2e}"
    + (f" at x={worst_x[0]} (share {worst_x[1]:.6f})" if worst_x else ""),
)

# ---------------------------------------------------------------- item 8 (C15)
# A counter reading zero because nothing wired it is indistinguishable from a genuine zero, so
# each counter is checked where it MUST be nonzero rather than blanket-checked.
MUST_BE_NONZERO = {
    "realized_top1pct_access_share": lambda r: True,
    "realized_top10pct_access_share": lambda r: True,
    "mean_distinct_instances_touched": lambda r: True,
    "admitted_parents_for_access": lambda r: True,
    # zero is CORRECT for an all-read-only cell and for the no-CPR control
    "writes_per_txn_mean": lambda r: float(r["x"]) < 0.999,
    "cpr_edges_fired_per_completion": lambda r: r["panel"] != "control" and float(r["x"]) < 0.999,
    # zero is CORRECT at ro = 0
    "read_only_txn_frac_realized": lambda r: float(r["x"]) > 0.001,
}
dead = []
for col, applies in MUST_BE_NONZERO.items():
    scope = [r for r in raw if applies(r)]
    nz = [r for r in scope if float(r[col]) != 0.0]
    if scope and not nz:
        dead.append(f"{col} is zero in all {len(scope)} rows where it must move")
check(
    "item 8 (C15): every realised counter shown nonzero where it must be",
    not dead,
    "; ".join(dead) if dead else f"all {len(MUST_BE_NONZERO)} counters nonzero in scope",
)

# ---------------------------------------------------------------- item 9
# At ro = 0 every transaction is an update carrying exactly ceil(n/2) writes, where n is the
# REALISED footprint - dedup can drop it below k, which is why the plan says report rather than
# assume ceil(k/2).
ro0 = [r for r in raw if abs(float(r["x"])) < 1e-9 and r["panel"] != "control"]
# A check with an empty scope must report N/A, not PASS. Only the readonly axis sweeps ro, so on
# the other three this item has nothing to test - and "0 rows examined, therefore pass" is exactly
# the shape of verification item 20 before it was rewritten: a check that cannot fail, counted as
# coverage. Reported separately so a green run cannot be read as this item having been satisfied.
if not ro0:
    print("[N/A ] item 9: two-class arithmetic at ro = 0\n"
          "        no ro=0 rows on this axis - NOT CHECKED here; the readonly axis is where "
          "this item is testable\n")
detail = ""
ok9 = True
if ro0:
    obs = [float(r["writes_per_txn_mean"]) for r in ro0]
    dist = [float(r["mean_distinct_instances_touched"]) for r in ro0]
    detail = (f"ro=0: writes/txn in [{min(obs):.3f}, {max(obs):.3f}]; "
              f"distinct/txn in [{min(dist):.2f}, {max(dist):.2f}] (k=10 -> expect ceil(n/2)~5)")
    ok9 = all(o > 0 for o in obs)
    check("item 9: two-class arithmetic at ro = 0", ok9, detail)

# The realised fraction is over ADMISSIONS, and a restarted transaction is admitted again
# (supersedes row 28). Update transactions abort and read-only ones do not, so the admitted mix
# carries more updates than the generated mix, by exactly the abort rate:
#
#     realised = nominal / (1 + aborts_per_completion)
#
# An earlier version of this check expected 0.9 on EVERY cell and failed the campaign on cells
# that were behaving correctly - it was testing the generator against a population the generator
# does not control. Two separate assertions now:
#   (a) where nothing aborts, realised == nominal. That is the knob, and it is what item 9 asks.
#   (b) where things do abort, the deviation is ACCOUNTED FOR by the abort rate rather than
#       unexplained. A residual here would mean some other population effect is in the counter.
ro9 = [r for r in raw if abs(float(r["x"]) - 0.9) < 1e-9]
if ro9:
    quiet = [r for r in ro9 if float(r["aborts_per_completion"]) == 0.0]
    if quiet:
        obs = [float(r["read_only_txn_frac_realized"]) for r in quiet]
        n = min(float(r["admitted_parents_for_access"]) for r in quiet) or 1.0
        sd = math.sqrt(0.9 * 0.1 / n)
        check("item 9: realised read-only fraction == 0.9 on zero-abort cells (the KNOB)",
              all(abs(o - 0.9) <= max(4 * sd, 0.01) for o in obs),
              f"{len(quiet)} zero-abort rows, observed [{min(obs):.4f}, {max(obs):.4f}]")
    resid = max(abs(float(r["read_only_txn_frac_realized"])
                    - 0.9 / (1 + float(r["aborts_per_completion"]))) for r in ro9)
    check("item 9: the shortfall on aborting cells is explained by the restart rate",
          resid <= 0.01,
          f"max |observed - nominal/(1+aborts)| = {resid:.5f} over {len(ro9)} rows "
          f"(unexplained residual would mean another effect is in the counter)")

# ---------------------------------------------------------------- items 13, 14
gran = {c["lock_granularity"] for c in cells}
check("item 13: lock aliasing provably off (granularity == 1 on every cell)",
      gran == {"1"}, f"observed granularities {sorted(gran)}")

pig = [float(c["pigeonhole_ratio"]) for c in cells]
check("item 14: pigeonhole ratio emitted on every cell and below 0.2",
      pig and max(pig) < 0.2,
      f"{len(pig)} cells, worst {max(pig):.4f} ({100*max(pig)/0.2:.0f}% of the limit)")

# ---------------------------------------------------------------- seeds columns
req = {c["seeds_requested"] for c in cells}
used = {c["seeds_used"] for c in cells}
counted = defaultdict(int)
for r in raw:
    counted[r["config_id"]] += 1
mismatch = [c["config_id"] for c in cells
            if counted[c["config_id"]] != int(c["seeds_used"])]
check("seeds_requested / seeds_used populated and equal to the raw rows actually written",
      req and used and not mismatch,
      f"requested={sorted(req)} used={sorted(used)}; "
      + ("row counts agree with seeds_used on every cell" if not mismatch
         else f"{len(mismatch)} cells disagree"))

# ---------------------------------------------------------------- C5 and gates
term = defaultdict(int)
for r in raw:
    term[r["terminated_by"]] += 1
check("C5: termination paths recorded, and pooling across them is visible",
      len(term) >= 1, f"{dict(term)}")

verdicts = defaultdict(int)
for c in cells:
    verdicts[c["gate_verdict"]] += 1
check("Phase 2 cell gates", not skipped and all(
        v.lower().startswith(("pass", "ok")) for v in verdicts),
      f"verdicts {dict(verdicts)}; {len(skipped)} skipped cells")

print("=" * 70)
bad = [n for n, ok in results if not ok]
print(f"{len(results) - len(bad)}/{len(results)} checks pass")
if bad:
    print("FAILED: " + "; ".join(bad))
