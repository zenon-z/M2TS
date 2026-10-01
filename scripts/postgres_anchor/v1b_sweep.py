"""V1b Phase 3 -- the PostgreSQL arm of criterion 3.

Produces `R_pg(r, f)` -- the within-batch paired ratio of treatment to baseline median latency --
per rep and per W, which is the ONLY PostgreSQL quantity entering the comparison. The simulator arm is a separate invocation
(`TgsPanelBSimCheck`, cell D=80,000 k=100) and produces `R_sim(f)` the same way from its own f=0.

EVERYTHING HERE IS FIXED AT BUILD TIME, with the derivation beside it. These are preconditions the
measurement's validity rests on, not knobs -- a value a caller can vary is a value that gets varied,
and the arithmetic behind each stops holding silently. Change them by editing this file.

DESIGN, all of it pre-registered before any sweep data existed:

  * R is formed INSIDE a cell, not across cells. Each batch alternates a TREATMENT transaction
    (ids drawn over all D, so k*f fire) with a BASELINE one (ids drawn from non-emitting rows, so
    zero fire by construction), sharing one table state, one window, one worker, one connection
    and -- because the pool is ODD -- the same id sets. The pilot forced this: a per-cell f=0
    baseline sat 3.3-5.1x away from independent measurements of the same parent-only quantity,
    so the ratio was measuring machine drift between cells rather than the cascade;
  * the (W, f=0) cell is retained and REPURPOSED as a NULL CONTROL. At f=0 both arms fire zero
    times and differ only in how their ids were drawn, so R(f=0) measures the comparator's own
    residual asymmetry. It is a gate, not a denominator -- nothing divides by it any more;
  * cells INTERLEAVED within a rep, shuffled, so no comparison spans more than one rep's drift;
  * setup + VACUUM between cells, always OUTSIDE every measurement window;
  * gates asserted per cell BEFORE any performance number is read (C10: a gate failure voids the
    campaign rather than being caveated);
  * medians for R_pg, means for Little's identity -- they are not interchangeable and both are
    recorded;
  * the emitting set is re-drawn per rep, so which rows emit is a resampled nuisance rather than a
    fixed effect -- the same correction made to the id pool, for the same reason: a fixed effect
    contributes zero variance across reps and would make the bootstrap understate uncertainty.
"""
import argparse
import csv
import os
import hashlib
import re
import math
import random
import statistics
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2

import run_lock
from v1b_run import (LITTLE_TOL, MIN_CYCLES, POOL, connect, host_snapshot,
                     run_cell)
from workload_client import DEFAULT_DSN, setup_v1

# --- the grid, fixed with its derivation (see the plan's "(W, f) grid" section) ----------------
# V1's f values were chosen when the cascade was 0.7% of the critical section; this arm makes ~61%
# measurable at f=0.5, the topology maximum under LINE at totalModels=2. Keeps four of V1's points
# for continuity, drops 0.025 (smallest effect, noise floor dominates), adds 0.5 (most material).
F_VALUES = (0.0, 0.05, 0.125, 0.25, 0.5)
# W=64 REMOVED, and this is a measurement result rather than a scope cut. Its null control --
# f=0, both arms firing zero times, true R = 1 by construction -- reads 2.681 (reps 2.681 2.726
# 2.589, spread 0.138) and independently 2.756 in the pilot. That is a stable ~2.7x instrument
# bias of the same order as the effects being measured, and its mechanism is unknown: arm
# ordering and cross-worker pool collision were both tested and rejected.
#
# It is removed rather than reported-with-a-caveat because C10 makes a gate failure VOID the
# campaign. A cell whose control is known in advance to fail its gate would void every campaign
# it appears in, so including it does not preserve the data -- it destroys the other 15 cells'.
#
# W <= 16 is validated by the same control: 0.997 / 0.979 / 1.022, spread <= 0.046, against
# f > 0 effects of 4-66%. Restore W=64 only with its bias diagnosed.
# W>1 is DESCRIPTIVE ONLY and no longer runs in the registered campaign (PREREG Amendment 3).
# The registration is W=1, and interleaving W>1 cells into the same reps would break the Latin
# square's position balance over f -- which is the thing that makes a trend in f interpretable.
# --descriptive runs them as a separate, separately-tagged campaign sharing no registration.
W_VALUES = (1,)
DESCRIPTIVE_W = (1, 4, 16)
# n=35 = seven 5x5 Latin squares (PREREG Amendment 5). Amendment 3's n=15 was sized from a power
# simulation that assumed INDEPENDENT cell noise; the four healthy plain2 reps give per-rep
# coefficient sd 1.47x larger than that model predicts, because within-rep drift is not absorbed
# by the intercept -- the Latin square maps position onto a different f in every rep. Re-powered
# on the empirical sd and on the PRIMARY test (Hotelling T^2, which spends power on a 2-df
# alternative and is therefore less powered than either marginal t): n=15 gives 0.54, n=30 gives
# 0.86, n=35 gives 0.92, taking the conservative of two noise models. n MUST remain a multiple
# of 5 or the Latin square cannot be built.
REPS = 35
# D/k^2 = 8 preserves V0's contention regime; k=100 is where B2 binds with headroom.
# PREREG Amendment 4. ln(1.5): the median-based and mean-based estimates of R must agree within
# a factor of 1.5. Outcome-INDEPENDENT -- it constrains how well two estimators of R agree, not
# what R is -- which is what makes the rep re-run below unable to bias R toward any answer.
# PREREG Amendment 5n. Every random draw in this file was keyed by REP INDEX or by a constant,
# never by the run, so a 35-rep campaign would have reproduced the 25-rep pilot's draws exactly:
# all 25 Latin square rows identical, the same emitting set per rep (12345 + 1000*rep), and the
# same id pool (run_cell was called without seed_base, so both runs used its 0.1 default). Only
# timing noise would have differed.
#
# That destroys the reason for running the campaign at all. The pilot is non-citable precisely so
# the campaign can be an INDEPENDENT test, and a campaign re-measuring the pilot's exact design
# points is not one -- it also shrinks between-rep variance in the shared draws relative to a
# fresh sample, since the draw is then a fixed effect rather than a resampled nuisance.
#
# SEED_BASE offsets all three. It is DERIVED, not chosen: int(plan_blob[:8], 16) at the commit
# that registers it, so anyone can recompute it from the committed pre-registration and it cannot
# have been picked after seeing results. Default 0 reproduces the pilot exactly, which is what
# re-analysis of pilot data needs.
SEED_BASE = 0
AGREEMENT_TOL = math.log(1.5)
# R3 (PREREG Amendment 5e, cap fixed in 5f). Two re-runs, then the rep is DROPPED and REPLACED,
# and the campaign voids on a COUNT of reps that ever tripped rather than on any rep exhausting
# its attempts. R3 exists because the previous rule referenced P(trip | previous trip), which a
# clean pilot produces no information about -- making the rule unsatisfiable by good news.
#
# The cap is a count, not a fraction, and it is NOT free. It must satisfy two sides at once:
# P(void) <= 0.10 at the upper-80% bound on p, AND P(void) no lower than the original R2c's at a
# degraded p = 0.25 -- otherwise R3 is a relaxation of the case the void exists for, which the
# 30% fraction registered in 5e turned out to be (0.245 against R2c's 0.898).
#
# Six-of-35 satisfies both only once p's upper bound falls to ~0.045, which needs 35 clean pilot
# reps: it then gives 0.004 at the bound and 0.900 at p = 0.25.
MAX_RERUNS = 2
AGREEMENT_VOID_COUNT = 6
# Structural guard, not an operative limit: a dropped rep is necessarily a tripped rep, so the
# count void at 6 is reached before a 6th replacement can ever be needed. Set to
# AGREEMENT_VOID_COUNT - 1 so the two cannot drift apart and imply a limit that never binds.
MAX_REPLACEMENTS = AGREEMENT_VOID_COUNT - 1
D, K = 80_000, 100
WARMUP_S, DURATION_S = 2.0, 10.0
# 20 cells x ~15 s => ~5 min per rep. Rep duration is what bounds drift exposure, since every
# comparison forms inside a rep.

# The per-test rate a bare 3-SE two-sided gate would carry, kept as the FAMILY-wise target.
PER_TEST_ALPHA_3SE = 2 * (1 - statistics.NormalDist().cdf(3.0))


def family_wise_z(n_tests):
    """z for one cell such that the WHOLE campaign carries the error rate a single 3-SE test does.

    The gate is applied independently to every cell, so its false-VOID rate compounds with the
    grid. At 3 SE and 75 cells: 1 - (1 - 0.0027)^75 = 18.4%. Roughly one clean campaign in five
    voids with nothing wrong, and re-running until one comes back clean is selection by campaign
    rather than a measurement.

    Derived from the cell count alone, so it tracks the grid instead of being a pasted constant,
    and so it is independent of which cell failed -- the same number would have been written
    before any campaign ran. At 75 cells it returns 4.13.
    """
    return statistics.NormalDist().inv_cdf(1 - PER_TEST_ALPHA_3SE / n_tests / 2)


def fires_tol(f, w, n_tests):
    """Criterion 1's tolerance: `z` SE on the pool's estimate of the hypergeometric mean, where z
    is family-wise corrected for the number of cells in the campaign.

    It is f- AND W-dependent. It was a constant 0.0643, derived at f=0.25/W=1 and applied
    everywhere. The fire count's RELATIVE spread grows as f shrinks -- sd/mean is 17% at f=0.25
    but 44% at f=0.05 -- so a constant tolerance is far too tight at small f and far too loose at
    large f. Measured consequence: it failed a cell at f=0.05 that deviated 6.6% when the correct
    tolerance there is 16.3%.

    NOTE what the sample size is: `POOL * w` id SETS, not the transaction count. The batch cycles
    through a fixed pool, so a pool drawing above k*f reproduces that offset on every pass and a
    longer run does not average it out. There is no large-n regime here and no floor -- the
    tolerance spans 0.93% to 16.21% across the grid and never flattens to a constant.
    """
    if f <= 0:
        return 0.0
    em = round(f * D)
    mean = K * em / D
    sd = math.sqrt(K * (em / D) * (1 - em / D) * (D - K) / (D - 1))
    return family_wise_z(n_tests) * sd / math.sqrt(POOL * w) / mean


# The null control's tolerance is 3 SE on the median of the per-worker ratios, which needs
# spread ACROSS workers to estimate. At W=1 there is exactly one ratio and no spread, so the
# control is NOT EVALUABLE there and says so rather than inventing a constant.
NULL_CONTROL_SE = 3.0


def gates(res, w, f, emitting, n_tests):
    """Every gate, evaluated BEFORE any performance number is read. Returns (ok, [failures])."""
    bad = []
    # --- paired-mode gates ------------------------------------------------------------------
    if res["paired"]:
        nt, nb = res["counted_treat"], res["counted_base"]
        # Strict parity alternation makes this near-exact; a 2% miss means the alternation
        # broke, and then the two arms are no longer the same transactions in the same batch.
        if nt + nb == 0 or abs(nt - nb) > 0.02 * (nt + nb):
            bad.append("arms unbalanced: %d treat vs %d base" % (nt, nb))
        if f == 0.0 and w == CRITERION_W:
            # NULL CONTROL, criterion-bearing W only. W>1 is dropped from the gate: its
            # between-rep sd is ~4% while this SE is computed ACROSS WORKERS within a cell, so
            # the error model structurally cannot see a cell-level component -- it voided two
            # campaigns on that mismatch. W>1 is descriptive-only for three independent reasons
            # (workload composition, Bin(k,f) vs deterministic fire counts, D_eff/k^2 = 2.87), so
            # gating cells whose numbers are not reportable buys nothing and costs campaigns. Both arms fire zero times, so R must be 1 up to the comparator's own
            # asymmetry (uniform-over-D draw vs ORDER BY random() over non-emitting rows). A
            # displaced control is a bias in the instrument, and it would displace every f > 0
            # cell by the same factor.
            rw = res["r_workers"]
            if len(rw) >= 4:
                se = statistics.stdev(rw) / math.sqrt(len(rw))
                if se > 0 and abs(res["r_paired"] - 1.0) > NULL_CONTROL_SE * se:
                    bad.append("null control R=%.4f is %.1f SE from 1 (se %.4f)"
                               % (res["r_paired"], abs(res["r_paired"] - 1) / se, se))
        # ESTIMATOR AGREEMENT (PREREG Amendment 4). R is estimated twice from the same batch:
        # once from the two arms' medians, once from their means. At W=1 nothing contends, so
        # treatment and baseline latency distributions differ only by the cascade work added to
        # every treatment transaction, and the two estimators must agree.
        #
        # This gates MEASUREMENT VALIDITY, not R's value. A cell with R=4 whose two estimators
        # agree PASSES; a cell with R=1.05 whose estimators disagree FAILS. So conditioning a
        # re-run on it cannot push R toward any particular answer.
        #
        # Threshold ln(1.5) -- the estimators must agree within a factor of 1.5. Across the 75
        # void plain2 cells the discrepancy has median 0.019 and p90 0.089; the largest is 0.797
        # (rep 1, W=1, f=0.5: treatment median 12197 us against 2573-3913 in every other rep) and
        # the next largest anywhere is 0.230. Any threshold in (0.23, 0.79) selects that same
        # single cell, so the value is not delicately placed -- but it IS calibrated on pilot
        # data, and is registered before the campaign rather than picked after seeing results.
        rm, ra = res["r_paired"], res["r_paired_mean"]
        if rm > 0 and ra > 0:
            gap = abs(math.log(rm / ra))
            if gap > AGREEMENT_TOL:
                bad.append("estimator disagreement |ln(R_med/R_mean)|=%.3f > %.3f "
                           "(R_med=%.4f R_mean=%.4f)" % (gap, AGREEMENT_TOL, rm, ra))
        else:
            bad.append("non-positive R estimate: R_med=%.4f R_mean=%.4f" % (rm, ra))
    little = res["throughput"] * res["mean_latency_s"]          # Little's law is about MEANS
    if abs(little - w) / w > LITTLE_TOL:
        bad.append("little %.3f vs W=%d" % (little, w))
    cycles = res["length_s"] / res["mean_latency_s"] if res["mean_latency_s"] else 0
    if cycles < MIN_CYCLES:
        bad.append("cycles %.1f < %.1f" % (cycles, MIN_CYCLES))
    if not (0 < res["peak_h"] <= w * K):
        bad.append("peak_h %d outside (0, %d]" % (res["peak_h"], w * K))
    if res["attempted"] == 0:
        bad.append("NO TRANSACTIONS RAN -- workers started after the window closed")
    if res["lat_truncated"]:
        bad.append("latency buffer truncated -- median is biased to early transactions")
    # Criterion 1, per cell: fires/commit against the exact hypergeometric mean k*emitting/D.
    # The denominator is the TREATMENT count, not `counted`. Only treatment transactions draw
    # from the emitting population; baseline ones fire zero times by construction. Dividing the
    # window's fires by all counted transactions halves the rate and fails every cell against an
    # expectation that was never wrong -- which is exactly what the first paired pilot did
    # (6.130 vs 12.500, 12.353 vs 25.000: both a factor of 2, and 2 is the alternation ratio).
    fires = res["fires"].get("close", 0) - res["fires"].get("open", 0)
    denom = res["counted_treat"] if res["paired"] else res["counted"]
    fpc = fires / denom if denom else float("nan")
    exp = K * emitting / D
    if exp > 0 and abs(fpc - exp) / exp > fires_tol(f, w, n_tests):
        bad.append("fires/commit %.3f vs %.3f expected (tol %.1f%%)"
                   % (fpc, exp, fires_tol(f, w, n_tests) * 100))
    elif exp == 0 and fires != 0:
        bad.append("f=0 cell fired %d times" % fires)
    return (not bad), bad, fpc, exp


# --- campaign-level null control, and criterion 3's restricted scope ------------------------
#
# The PER-CELL control needs spread across workers to build an SE, so it is NOT EVALUABLE at
# W=1 -- one worker, one ratio. W=1 is the ONLY criterion-bearing W, so leaving it ungated would
# rest the entire criterion on cells whose control is never checked. Gating ACROSS REPS works at
# every W, W=1 included.
#
# Thresholds are those pre-registered in the stability sweep, stated before that data was read:
# |median - 1| <= 0.05 and spread <= 0.10, because the effects R_pg must resolve are 10-100%.
CONTROL_TOL, CONTROL_SPREAD = 0.05, 0.10

# Criterion 3 is restricted to W=1. At W>1 the two arms measure R under different workload
# COMPOSITIONS -- R_pg's batch is half zero-cascade baseline, while the simulator cannot mix
# (semanticOverlap is a schema property, not a per-transaction one) and runs homogeneous cells.
# At W=1 no transaction interacts with another, so the compositions coincide exactly.
#
# What this costs, stated plainly so a W=1 pass is not read as more than it is: criterion 3
# becomes a statement about how the cascade's COST COMPOSES into a transaction, not about
# CONTENTION under CPRs. At W=1 there is no contention. The paper's CPR-contention claim
# therefore continues to rest on the simulator alone, exactly as it did before V1b.
CRITERION_W = 1


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return v


def control_by_rep(rows, w):
    """The f=0 control at one W, across reps. ok is None when there are too few reps."""
    v = [r["R_paired"] for r in rows if r["W"] == w and r["f"] == 0.0]
    v = [x for x in v if x == x]
    if len(v) < 3:
        return v, float("nan"), float("nan"), None
    med, spread = statistics.median(v), max(v) - min(v)
    return v, med, spread, (abs(med - 1) <= CONTROL_TOL and spread <= CONTROL_SPREAD)


def report_controls(rows, w_values):
    print()
    print("NULL CONTROLS across reps (f=0: both arms fire zero times, true R = 1)")
    print("  pre-registered: |median - 1| <= %.2f and spread <= %.2f"
          % (CONTROL_TOL, CONTROL_SPREAD))
    print("%4s %30s %9s %9s %14s" % ("W", "R by rep", "median", "spread", "verdict"))
    bad = []
    for w in w_values:
        v, med, spread, ok = control_by_rep(rows, w)
        verdict = "NOT EVALUABLE" if ok is None else ("USABLE" if ok else "FAILED")
        print("%4g %30s %9s %9s %14s"
              % (w, " ".join("%.3f" % x for x in v),
                 "%.3f" % med if med == med else "-",
                 "%.3f" % spread if spread == spread else "-", verdict))
        if ok is False:
            bad.append(w)
    return bad


def composition_fit(rows, w, f_values, reps):
    """The SELF-CONTAINED composition test: is the cascade's cost linear in the fire count?

    Every input is measured inside the same run. `base` is the baseline arm's own p50, not a
    modelled parent cost, so the 59.66 + 2.5226k fit is not needed -- and could not be had here
    anyway, since the sweep runs a single k and a slope needs two.

    THE POINT OF FITTING A SLOPE rather than taking c per cell: with c derived from one cell,
    R_pred = 1 + k*f*[(treat-base)/(k*f)]/base = treat/base = R_pg identically. The prediction
    becomes an algebraic restatement of the measurement and cannot disagree with it. Fitting ONE
    c across all f over-determines the system, so each f is a point that can fall off the line
    and the null -- cost linear in f, through the origin -- has content.

    Returns per-rep (c_origin, r2, c_free, intercept), plus the per-(rep, f) implied c.
    """
    out, implied = [], {}
    for rep in reps:
        pts = []
        for f in f_values:
            if f == 0.0:
                continue
            c = [r for r in rows if r["rep"] == rep and r["W"] == w and r["f"] == f]
            if not c:
                continue
            x = K * f          # the sweep's single k, fixed at build time
            y = c[0]["p50_treat_us"] - c[0]["p50_base_us"]
            pts.append((x, y))
            implied.setdefault(f, []).append(y / x)
        if len(pts) < 3:
            continue
        n = len(pts)
        sx = sum(x for x, _ in pts)
        sy = sum(y for _, y in pts)
        sxx = sum(x * x for x, _ in pts)
        sxy = sum(x * y for x, y in pts)
        c0 = sxy / sxx
        slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        icpt = (sy - slope * sx) / n
        ybar = sy / n
        ss_tot = sum((y - ybar) ** 2 for _, y in pts)
        ss_res = sum((y - c0 * x) ** 2 for x, y in pts)
        out.append((c0, 1 - ss_res / ss_tot if ss_tot else float("nan"), slope, icpt))
    return out, implied


def report_composition(rows, w, f_values, reps):
    fits, implied = composition_fit(rows, w, f_values, reps)
    print()
    print("COMPOSITION at W=%g, self-contained (every input measured in the same run)" % w)
    if not fits:
        print("  NOT EVALUABLE -- needs >= 3 f values per rep")
        return
    print("  null: excess latency (treat - base) is LINEAR in the fire count k*f,")
    print("        through the origin, with one slope c shared across all f.")
    c0 = [x[0] for x in fits]
    r2 = [x[1] for x in fits]
    ic = [x[3] for x in fits]
    print("  through-origin c  = %.2f us/fire   (per rep: %s)"
          % (statistics.median(c0), " ".join("%.1f" % v for v in c0)))
    print("  R^2 of that fit   = %.4f          (per rep: %s)"
          % (statistics.median(r2), " ".join("%.3f" % v for v in r2)))
    print("  free intercept    = %+.1f us       (per rep: %s)"
          % (statistics.median(ic), " ".join("%+.0f" % v for v in ic)))
    print()
    print("  implied c per f -- a CONSTANT c makes this flat; structure here is the finding:")
    print("  %8s %10s %10s %28s" % ("f", "median c", "spread", "per rep"))
    for f in sorted(implied):
        v = implied[f]
        print("  %8g %10.2f %10.2f %28s"
              % (f, statistics.median(v), max(v) - min(v), " ".join("%.1f" % x for x in v)))


def _betacf(a, b, x):
    """Continued fraction for the incomplete beta, modified Lentz. Needed for exact t p-values;
    the normal approximation overstates significance at n=15 (t.975,14 = 2.145, not 1.96)."""
    tiny = 1e-30
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    if abs(d) < tiny:
        d = tiny
    d = 1.0 / d
    h = d
    for m in range(1, 300):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if abs(d) < tiny:
            d = tiny
        c = 1.0 + aa / c
        if abs(c) < tiny:
            c = tiny
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if abs(d) < tiny:
            d = tiny
        c = 1.0 + aa / c
        if abs(c) < tiny:
            c = tiny
        d = 1.0 / d
        delt = d * c
        h *= delt
        if abs(delt - 1.0) < 3e-12:
            break
    return h


def _betai(a, b, x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    lbeta = (math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b)
             + a * math.log(x) + b * math.log1p(-x))
    if x < (a + 1.0) / (a + b + 2.0):
        return math.exp(lbeta) * _betacf(a, b, x) / a
    return 1.0 - math.exp(lbeta) * _betacf(b, a, 1.0 - x) / b


def t_sf(t, df):
    """P(T > t) for Student t with df degrees of freedom."""
    p_two = _betai(0.5 * df, 0.5, df / (df + t * t))
    return 0.5 * p_two if t > 0 else 1.0 - 0.5 * p_two


def _agree(p_param, p_robust):
    """Agreement label. A test that could not run is NOT agreement -- the signed-rank needs
    n >= 6 and returns nan below that, and printing "(agrees)" for a test that never ran would
    report the robustness check as passed when it was absent."""
    if p_robust != p_robust:          # nan
        return "(NOT EVALUABLE -- signed-rank needs n >= 6)"
    return "(agrees)" if (p_param < 0.05) == (p_robust < 0.05) else "(DISAGREES)"


def _normalise_sql(body):
    """Whitespace- and comment-insensitive form, so a reindent or a comment edit is not a
    mismatch while a semantic change is."""
    body = re.sub(r"--[^\n]*", "", body)
    return re.sub(r"\s+", " ", body).strip().lower()


def verify_trigger(cur, schema_path):
    """Check the INSTALLED cpr_a_to_b against the body pinned in v1b_schema.sql.

    PREREG Amendment 5i(a). Pinning the body in the schema file is necessary but not sufficient:
    schema.sql still carries a FOR UPDATE variant, and anything applying it after v1b_schema.sql
    -- V0 tooling, a setup helper, a manual psql session -- silently reinstalls it. plan_blob
    would not move, because the pre-registration did not change, yet the measured quantity would
    have. Verification catches that AND a hand edit, which deleting the other definition would
    not.

    The difference is not cosmetic: with FOR UPDATE each fire emits an XLOG_HEAP_LOCK as well as
    an XLOG_HEAP_UPDATE, so c_wal's null would be 2 rather than 1.

    Returns the sha256 of the normalised installed body, stamped into every row as trigger_sha so
    "what ran" is checkable from the CSV the way git_commit already is.
    """
    with open(schema_path, encoding="utf-8") as _fh:
        pinned_sql = _fh.read()
    m = re.search(r"FUNCTION cpr_a_to_b\(\) RETURNS TRIGGER AS \$\$(.*?)\$\$", pinned_sql, re.S)
    if not m:
        raise SystemExit("ABORT: v1b_schema.sql does not pin cpr_a_to_b. The trigger body is what "
                         "this campaign measures the cost of; it may not be left to whatever the "
                         "database was last left in.")
    cur.execute("SELECT prosrc FROM pg_proc WHERE proname = 'cpr_a_to_b'")
    found = cur.fetchall()
    if len(found) != 1:
        raise SystemExit("ABORT: expected exactly one cpr_a_to_b, found %d." % len(found))
    installed = _normalise_sql(found[0][0])
    if installed != _normalise_sql(m.group(1)):
        raise SystemExit(
            "ABORT: the installed cpr_a_to_b does not match the body pinned in v1b_schema.sql.%s"
            "  installed: %s%s  pinned   : %s%s"
            "Something re-applied a different definition (schema.sql still carries a FOR UPDATE "
            "variant). Re-apply v1b_schema.sql, or reconcile the two before measuring."
            % (chr(10), installed, chr(10), _normalise_sql(m.group(1)), chr(10)))
    return hashlib.sha256(installed.encode("utf-8")).hexdigest()[:16]


def binom_sf(k, n, p):
    """P(X > k) for X ~ Binomial(n, p). Exact; n is at most the number of cells."""
    if k < 0:
        return 1.0
    if k >= n:
        return 0.0
    return sum(math.comb(n, i) * p ** i * (1 - p) ** (n - i) for i in range(k + 1, n + 1))


def t_ppf(p, df):
    """Upper-tail quantile: the t with P(T > t) = p. Bisection on t_sf, which is already
    validated against published critical values.

    Replaces a hardcoded 2.145 (t.975,14). That constant silently produced a WRONG interval
    whenever n was not 15 -- the n=4 dry run printed a 95% CI built from 2.145 where 3.182 was
    required, understating the width by 33%. A confidence interval whose coverage depends on a
    constant matching the sample size is not a confidence interval.
    """
    lo, hi = 0.0, 1e3
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        if t_sf(mid, df) > p:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def f_sf(x, d1, d2):
    """P(F > x) for the F distribution, via the same incomplete beta."""
    if x <= 0:
        return 1.0
    return 1.0 - _betai(d1 / 2.0, d2 / 2.0, d1 * x / (d1 * x + d2))


def hotelling_t2(cs, bs):
    """One-sample Hotelling T^2 of (c, b) against (1, 0). Returns (T2, F, p).

    PRIMARY test from PREREG Amendment 5. "A CU is one resource touch" is the JOINT claim
    c = 1 AND b = 0, so testing the two coefficients separately at 0.05 each gives a family-wise
    rate near 0.10, and the one-sided b < 0 test ignores b > 0 -- which refutes the claim just as
    surely. T^2 tests the pair at one stated level and accounts for their correlation, which is
    strong here: f and f^2 have VIF 14.4 on this design, so c-hat and b-hat are far from
    independent and two marginal tests misstate the joint evidence in both directions.
    """
    n = len(cs)
    if n < 4:
        return float("nan"), float("nan"), 1.0
    mc, mb = statistics.mean(cs), statistics.mean(bs)
    d = (mc - 1.0, mb)
    s11 = statistics.variance(cs)
    s22 = statistics.variance(bs)
    s12 = sum((cs[i] - mc) * (bs[i] - mb) for i in range(n)) / (n - 1)
    det = s11 * s22 - s12 * s12
    if det <= 0:
        return float("inf"), float("inf"), 0.0
    inv = ((s22 / det, -s12 / det), (-s12 / det, s11 / det))
    t2 = n * (d[0] * (inv[0][0] * d[0] + inv[0][1] * d[1])
              + d[1] * (inv[1][0] * d[0] + inv[1][1] * d[1]))
    fstat = (n - 2) / (2.0 * (n - 1)) * t2
    return t2, fstat, f_sf(fstat, 2, n - 2)


def wilcoxon_signed_rank(vals, alternative):
    """Signed-rank test of median == 0. alternative is 'less', 'greater' or 'two-sided'.

    ROBUST SENSITIVITY ANALYSIS from PREREG Amendment 5, registered because this design has
    extreme leverage: the OLS weights on f=0.5 are -1.78 for c and +7.34 for b, so a single
    cell off by e shifts that rep's b by 7.34e. The agreement gate catches only the failure mode
    where R's two estimators disagree; a cell wrong by a comparable amount with both estimators
    agreeing passes the gate and still dominates a mean-based fit.

    Registered in advance: a DISAGREEMENT between this and the t/T^2 result is REPORTED, not
    resolved in favour of whichever is more convenient. Normal approximation with a continuity
    correction; at n >= 15 its error is small against the 0.05 threshold.
    """
    nz = [v for v in vals if v != 0]
    n = len(nz)
    if n < 6:
        # nan for BOTH: a p of 1.0 here would read as "ran, found nothing" rather than
        # "did not run", and _agree would then report the robustness check as agreeing.
        return float("nan"), float("nan")
    order = sorted(range(n), key=lambda i: abs(nz[i]))
    ranks = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j + 1 < n and abs(nz[order[j + 1]]) == abs(nz[order[i]]):
            j += 1
        r = (i + j + 2) / 2.0
        for q in range(i, j + 1):
            ranks[order[q]] = r
        i = j + 1
    w_pos = sum(ranks[i] for i in range(n) if nz[i] > 0)
    mu = n * (n + 1) / 4.0
    sd = math.sqrt(n * (n + 1) * (2 * n + 1) / 24.0)
    if sd == 0:
        return float("nan"), float("nan")
    if alternative == "less":
        z = (w_pos - mu + 0.5) / sd
        return z, 0.5 * (1 + math.erf(z / math.sqrt(2)))
    if alternative == "greater":
        z = (w_pos - mu - 0.5) / sd
        return z, 1.0 - 0.5 * (1 + math.erf(z / math.sqrt(2)))
    z = (w_pos - mu) / sd
    return z, 2.0 * min(0.5 * (1 + math.erf(z / math.sqrt(2))),
                        1.0 - 0.5 * (1 + math.erf(z / math.sqrt(2))))


def ols_quadratic(fs, ys):
    """Least squares fit of y = d + c*f + b*f^2. Returns (d, c, b)."""
    X = [[1.0, f, f * f] for f in fs]
    A = [[sum(X[i][r] * X[i][q] for i in range(len(fs))) for q in range(3)] for r in range(3)]
    v = [sum(X[i][r] * ys[i] for i in range(len(fs))) for r in range(3)]
    for i in range(3):
        p = max(range(i, 3), key=lambda r: abs(A[r][i]))
        A[i], A[p] = A[p], A[i]
        v[i], v[p] = v[p], v[i]
        if A[i][i] == 0:
            raise ValueError("singular design matrix -- an f value is missing")
        for r in range(3):
            if r != i:
                fct = A[r][i] / A[i][i]
                A[r] = [A[r][q] - fct * A[i][q] for q in range(3)]
                v[r] -= fct * v[i]
    return tuple(v[i] / A[i][i] for i in range(3))


def registered_test(rows):
    """The PREREG Amendment 3 test. Fit per rep, t-test the per-rep coefficients.

    The model is QUADRATIC and that was fixed in advance, on a ground independent of the data:
    the simulator predicts R - 1 = f exactly (delta=0, c=1, b=0), and the quadratic is the
    smallest model expressing all three ways PostgreSQL can depart from that line. This matters
    because c is model-dependent -- on the void plain2 data the linear fit gave c=0.88 and the
    quadratic c=1.52, which straddle the null. Leaving the model open would have left the
    VERDICT'S DIRECTION selectable after the data arrived.

    f=0 is IN the fit: it pins delta. It is also the cell the W=1 null control gates, so those
    are the same measurements, not a separate control -- which is why a gate failure voids the
    campaign upstream of here rather than being reported as a caveat on the intercept.
    """
    f_values = sorted({r["f"] for r in rows})
    reps = sorted({r["rep"] for r in rows})
    if len(f_values) < 3:
        print("  registered test NOT EVALUABLE: %d f values, the fit needs >= 3." % len(f_values))
        return None
    per_rep = []
    for rep in reps:
        ys, fs = [], []
        for f in f_values:
            c = [r for r in rows if r["rep"] == rep and r["f"] == f]
            if c:
                fs.append(f)
                ys.append(c[0]["R_paired"] - 1.0)
        if len(fs) == len(f_values):
            per_rep.append((rep,) + ols_quadratic(fs, ys))
    n = len(per_rep)
    print()
    print("REGISTERED TEST (PREREG Amendment 3): per-rep OLS  R - 1 = delta + c*f + b*f^2")
    if n < 3:
        print("  NOT EVALUABLE: only %d complete reps." % n)
        return None
    if n != REPS:
        print("  WARNING: %d complete reps, registered n = %d. Power is below the registered"
              % (n, REPS))
        print("           0.92; report this rather than the registered figure.")
    print("  %d complete reps, %d f values (f=0 included, it pins delta)" % (n, len(f_values)))
    print()
    print("  %5s %12s %12s %12s" % ("rep", "delta", "c", "b"))
    for rep, d, c, b in per_rep:
        print("  %5g %12.4f %12.4f %12.4f" % (rep, d, c, b))
    df = n - 1
    out = {}
    print()
    print("  %-8s %10s %10s %10s %10s %-30s" % ("coef", "mean", "sd", "SE", "t", "test"))
    for idx, name in ((1, "delta"), (2, "c"), (3, "b")):
        vals = [p[idx] for p in per_rep]
        mean, sd = statistics.mean(vals), statistics.stdev(vals)
        se = sd / math.sqrt(n)
        if name == "delta":
            tstat, label = float("nan"), "reported, not tested"
        elif name == "c":
            tstat = (mean - 1.0) / se if se else float("inf")
            label = "null c = 1, two-sided"
        else:
            tstat = mean / se if se else float("-inf")
            label = "alternative b < 0, one-sided"
        out[name] = (mean, sd, se, tstat)
        print("  %-8s %10.4f %10.4f %10.4f %10s %-30s"
              % (name, mean, sd, se, "--" if name == "delta" else "%.3f" % tstat, label))
    print()
    cs = [p[2] for p in per_rep]
    bs = [p[3] for p in per_rep]
    tcrit = t_ppf(0.025, df)          # df-dependent, NOT a constant
    t2, fstat, p_joint = hotelling_t2(cs, bs)
    print("  PRIMARY -- Hotelling T^2 on (c-1, b) against (0, 0), the JOINT claim:")
    print("    T^2 = %.3f,  F(2,%d) = %.3f,  p = %.4f  -> %s"
          % (t2, n - 2, fstat, p_joint,
             "REJECT 'a CU is one resource touch'" if p_joint < 0.05
             else "cannot reject 'a CU is one resource touch'"))
    print()
    print("  SECONDARY, directional and marginal (each at 0.05; the pair is NOT the primary):")
    p_c = 2.0 * t_sf(abs(out["c"][3]), df)
    p_b = 1.0 - t_sf(out["b"][3], df)
    print("    c = %.4f  (95%% CI %.4f to %.4f, t.975,%d = %.3f),  p = %.4f  -> %s"
          % (out["c"][0], out["c"][0] - tcrit * out["c"][2], out["c"][0] + tcrit * out["c"][2],
             df, tcrit, p_c, "reject c = 1" if p_c < 0.05 else "cannot reject c = 1"))
    print("    b = %.4f,  one-sided p = %.4f  -> %s"
          % (out["b"][0], p_b,
             "curvature b < 0" if p_b < 0.05 else "no curvature detected"))
    print("    delta = %.4f  (harness offset, absorbed by the free intercept; not tested)"
          % out["delta"][0])
    print()
    zc, pwc = wilcoxon_signed_rank([c - 1.0 for c in cs], "two-sided")
    zb, pwb = wilcoxon_signed_rank(bs, "less")
    print("  ROBUST SENSITIVITY (signed-rank; registered -- disagreement is REPORTED, not resolved):")
    print("    c-1: z = %.3f, two-sided p = %.4f   %s" % (zc, pwc, _agree(p_c, pwc)))
    print("    b  : z = %.3f, one-sided p = %.4f   %s" % (zb, pwb, _agree(p_b, pwb)))
    if pwc == pwc and pwb == pwb and (
            (p_c < 0.05) != (pwc < 0.05) or (p_b < 0.05) != (pwb < 0.05)):
        print()
        print("    *** t-test and signed-rank DISAGREE at alpha=0.05. Per PREREG Amendment 5 this")
        print("        is reported as a disagreement. The design's leverage (weight 7.34 on b from")
        print("        the f=0.5 cell alone) means a single cell can drive the mean-based result,")
        print("        and neither test is designated the tie-breaker.")
    out["p_joint"] = p_joint
    print()
    if p_joint >= 0.05:
        print("  SENSITIVITY LIMIT (registered in advance, PREREG Amendment 5). Hotelling T^2")
        print("  power at n=35, against fractions of the departure seen in the void campaigns:")
        print("    full effect (c=1.52, b=-1.26) ...... 1.00")
        print("    half effect (c=1.26, b=-0.63) ...... 0.91 - 0.93")
        print("    quarter     (c=1.13, b=-0.31) ...... 0.35 - 0.40")
        print("  So a null bounds the effect at roughly HALF the observed size. It is NOT")
        print("  evidence of no effect, and a quarter-sized departure would be missed about")
        print("  two times in three.")
    return out


def latin_square_orders(items, n_reps, rng):
    """One cell order per rep, from independent Latin squares of side len(items).

    Amendment 1 found the per-rep shuffle LOAD-BEARING: the f values sit in separate cells minutes
    apart within a rep, so a monotone within-rep drift correlated with cell order forges exactly
    the trend being tested. A shuffle decorrelates that only in expectation, and over a handful of
    reps the realised imbalance can be large. A Latin square BALANCES it by construction: across
    each block of len(items) reps every f occupies every position exactly once, so a position
    effect cancels inside the per-rep coefficients rather than merely averaging out across reps.

    Randomised by permuting the rows, columns and symbols of the cyclic square -- adequate here,
    and it cannot accidentally produce a non-Latin arrangement. Verified anyway, per column.
    """
    k = len(items)
    if n_reps % k:
        raise ValueError("n_reps=%d is not a multiple of %d, so the Latin square cannot be built. "
                         "PREREG Amendment 3 registers n as a multiple of 5." % (n_reps, k))
    orders = []
    for _ in range(n_reps // k):
        rows_p = list(range(k)); rng.shuffle(rows_p)
        cols_p = list(range(k)); rng.shuffle(cols_p)
        syms = list(items);      rng.shuffle(syms)
        sq = [[syms[(rows_p[i] + cols_p[j]) % k] for j in range(k)] for i in range(k)]
        for j in range(k):
            col = sorted(str(sq[i][j]) for i in range(k))
            assert col == sorted(str(x) for x in items), "not a Latin square: column %d" % j
        orders.extend(sq)
    return orders


def aggregate(rows, w_values, f_values):
    """Controls, then R_pg. One implementation shared by a live campaign and by --analyze, so a
    gate can never apply to one path and not the other."""
    bad = report_controls(rows, w_values)
    if CRITERION_W in bad:
        print()
        print("CRITERION 3 NOT REPORTABLE: the W=%g null control FAILED. Per C10 that voids it."
              % CRITERION_W)
        return 1
    # A control that could not be evaluated is not a control that passed. Reporting R_pg as
    # criterion-bearing while its control is unevaluable is exactly the hole the across-rep gate
    # was added to close, so the label is withdrawn rather than the number quietly kept.
    crit_ok = control_by_rep(rows, CRITERION_W)[3] is not None
    reps = sorted({r["rep"] for r in rows})
    crit_rows = [r for r in rows if r["W"] == CRITERION_W]
    if crit_ok and crit_rows:
        registered_test(crit_rows)
    elif crit_rows:
        print()
        print("REGISTERED TEST SKIPPED: the W=%g control is not evaluable, so the fit's intercept"
              % CRITERION_W)
        print("  -- which f=0 pins, and which the same control gates -- is not interpretable.")
    print()
    print("R_pg(r,f): treatment/baseline median, paired WITHIN the batch")
    print("%4s %8s %10s %10s %26s" % ("W", "f", "R_pg med", "sd", "95% bootstrap interval"))
    boot_rng = random.Random(7)
    for w in w_values:
        if w == CRITERION_W:
            scope = ("CRITERION 3" if crit_ok else
                     "NOT criterion-bearing -- control NOT EVALUABLE (needs >= 3 reps)")
        else:
            scope = "descriptive only -- composition differs from the simulator at W>1"
        print("  W=%-3g %s" % (w, scope))
        for f in f_values:
            if f == 0.0:
                continue
            per_rep = []
            for rep in reps:
                c = [r for r in rows if r["rep"] == rep and r["W"] == w and r["f"] == f]
                if c:
                    per_rep.append(c[0]["R_paired"])
            if not per_rep:
                continue
            # C3: bootstrap over reps, never normal-approx on a ratio. The SAME interval serves
            # the primary overlap test and the secondary difference, so they cannot disagree.
            draws = sorted(statistics.median([boot_rng.choice(per_rep) for _ in per_rep])
                           for _ in range(4000))
            print("%4g %8g %10.4f %10.4f   [%8.4f, %8.4f]"
                  % (w, f, statistics.median(per_rep),
                     statistics.stdev(per_rep) if len(per_rep) > 1 else 0.0,
                     draws[100], draws[3900]))
    if crit_ok:
        report_composition(rows, CRITERION_W, f_values, reps)
    print()
    print("R_sim comes from TgsPanelBSimCheck at D=80000 k=100, formed the same way -- but from")
    print("HOMOGENEOUS runs, since semanticOverlap is a schema property and the simulator cannot")
    print("mix. Criterion 3 is therefore read at W=%g ONLY, where no transaction interacts with"
          % CRITERION_W)
    print("another and the two compositions coincide. A W=%g pass says the cascade COST composes"
          % CRITERION_W)
    print("as the simulator says; it says NOTHING about contention under CPRs.")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--tag", default="v1b")
    ap.add_argument("--out", default="scripts/postgres_anchor/artifacts/experiments/results/v1b_sweep.csv")
    # A plumbing check, NOT a campaign: one rep, two cells, short windows, and it refuses to
    # report R_pg. The grid stays fixed at build time; this exists so the loop can be exercised
    # without editing the constants, and it says loudly that its output is not a measurement.
    ap.add_argument("--smoke", action="store_true")
    # PILOT: the full grid at ONE rep. Answers criterion 1 definitively (a count, no
    # reps needed) and gives a FLOOR on R_pg's interval from within-cell median SE.
    # It cannot give the pre-registered interval, which is a bootstrap over reps.
    ap.add_argument("--descriptive", action="store_true",
                    help="Run the W>1 grid with a per-rep shuffle. NOT the registered campaign: "
                         "its rows share no registration and cannot be read as criterion 3.")
    ap.add_argument("--seed-base", type=int, default=0,
                    help="Offset every random draw: Latin squares, emitting sets and id pools. "
                         "0 reproduces the pilot. The campaign's value is registered in PREREG "
                         "Amendment 5n as int(plan_blob[:8], 16) and is stamped into every row.")
    ap.add_argument("--reps", type=int, default=None,
                    help="Override the registered rep count. For the Amendment 5c PILOT that "
                         "estimates the agreement-gate trip rate p on a locked host. Must stay a "
                         "multiple of 5 so the Latin square still builds.")
    ap.add_argument("--pilot", action="store_true")
    # Re-run the aggregation and gates over an EXISTING CSV, without touching the database.
    # A campaign already in flight has this module's older code loaded, so a gate added
    # afterwards cannot reach it; this applies the current gates to the data it produced.
    # Analysis only -- it re-derives R_pg from the recorded per-cell ratios, measures nothing.
    ap.add_argument("--analyze", metavar="CSV")
    # Provenance escape hatch. Stamped into every row so a run made with it is
    # identifiable from the data alone.
    ap.add_argument("--allow-dirty", action="store_true")
    args = ap.parse_args()

    if args.analyze:
        with open(args.analyze) as fh:
            rows = [{k: (v if k == "gate_failures" else _num(v)) for k, v in r.items()}
                    for r in csv.DictReader(fh)]
        failed = [r for r in rows if str(r["gates_ok"]).lower() not in ("true", "1")]
        print("Analysis of %s: %d cells, %d gate failures"
              % (args.analyze, len(rows), len(failed)))
        for r in failed[:10]:
            print("  rep %g W=%g f=%g: %s" % (r["rep"], r["W"], r["f"], r["gate_failures"]))
        if failed:
            print()
            print("CAMPAIGN VOID per C10. No R_pg reported.")
            return 1
        return aggregate(rows, sorted({r["W"] for r in rows}), sorted({r["f"] for r in rows}))

    global REPS, WARMUP_S, DURATION_S, W_VALUES, SEED_BASE
    cells = [(w, f) for w in W_VALUES for f in F_VALUES]
    SEED_BASE = args.seed_base
    if SEED_BASE:
        print("*** SEED_BASE = %d -- draws are OFFSET from the pilot's (Amendment 5n) ***"
              % SEED_BASE)
    if args.reps is not None:
        REPS = args.reps
        print("*** REPS OVERRIDDEN to %d. Not the registered n=35 campaign. ***\n" % REPS)
    if args.pilot:
        REPS = 1
        print("*** PILOT: full grid, ONE rep. Sizes intervals; not a campaign. ***\n")
    if args.descriptive:
        W_VALUES = DESCRIPTIVE_W
        cells = [(w, f) for w in W_VALUES for f in F_VALUES]
        REPS = 5
        print("*** DESCRIPTIVE: W>1 included, per-rep shuffle. NOT the registered campaign.  ***")
        print("*** These rows carry no registration and cannot be read as criterion 3.       ***\n")
    if args.smoke:
        cells = [(2, 0.0), (2, 0.25)]
        REPS, WARMUP_S, DURATION_S = 1, 1.0, 4.0
        print("*** SMOKE: plumbing check only, 1 rep, 2 cells. NOT a campaign. ***\n")
    print("V1b Phase 3, PostgreSQL arm.  D=%d k=%d pool=%d" % (D, K, POOL))
    print("  W = %s" % (W_VALUES,))
    print("  f = %s" % (F_VALUES,))
    print("  %d cells x %d reps = %d\n" % (len(cells), REPS, len(cells) * REPS))

    # Apply the schema HERE. The sweep called run_cell directly and never applied it, so a
    # column added to v1b_schema.sql existed in the file and not in the database -- the run died
    # on the first cell. The definition and the table must be brought into step by the thing that
    # uses them, not by whichever script happened to run last.
    _schema_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "v1b_schema.sql")
    c0 = connect(args.dsn)
    with c0.cursor() as cur0:
        cur0.execute(open(_schema_path).read())
        _trigger_sha = verify_trigger(cur0, _schema_path)
    c0.close()
    print("  trigger cpr_a_to_b verified against the pinned body, sha %s" % _trigger_sha)

    # Record the commit in every row, so which code produced a row is answerable from the CSV
    # rather than from memory.
    _rev = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    # Tracked modifications only: untracked DATA files (result CSVs, frozen class dirs) do not
    # change the code that ran, and counting them makes every campaign abort -- at which point
    # --allow-dirty gets passed reflexively and the check means nothing. Untracked files under
    # scripts/ or src/ DO change behaviour, so those are still refused.
    _mod = subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=no"], text=True).strip()
    _untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "scripts", "src"],
        text=True).strip()
    _dirty = bool(_mod or _untracked)

    # A dirty tree means the recorded commit does NOT describe the code that ran, and the
    # working-tree content may not exist in history at all. Refuse rather than record-and-continue.
    if _dirty and not args.allow_dirty:
        print("ABORT: working tree is dirty, so git_commit would not describe the code that ran.")
        print("       Commit first, or pass --allow-dirty (stamped into every row).")
        return 1
    print("  provenance: commit %s%s"
          % (_rev[:12], "  DIRTY (--allow-dirty)" if _dirty else ""))

    rows, voided, discarded = [], [], []
    rng = random.Random(20260909 + SEED_BASE)
    # Latin square only when the grid IS the registered one: a single W, the five f values, and a
    # rep count divisible by 5. Any other grid is not square, keeps the shuffle, and says so --
    # rather than silently running an unbalanced design under a balanced design's banner.
    square = len(W_VALUES) == 1 and len(cells) == len(F_VALUES) and REPS % len(F_VALUES) == 0
    if square:
        # Extra squares beyond REPS supply orders for REPLACEMENT reps, so a replacement is still
        # a balanced square row rather than an arbitrary ordering appended to a balanced design.
        spare = ((MAX_REPLACEMENTS + len(F_VALUES) - 1) // len(F_VALUES)) * len(F_VALUES)
        rep_orders = latin_square_orders(cells, REPS + spare, rng)
        print("  randomisation: %d independent %dx%d Latin squares (PREREG Amendment 3)\n"
              % (REPS // len(F_VALUES), len(F_VALUES), len(F_VALUES)))
    else:
        rep_orders = [random.Random(rng.random()).sample(cells, len(cells)) for _ in range(REPS)]
        print("  randomisation: per-rep SHUFFLE -- grid is not square, DESCRIPTIVE only\n")
    # A REP is the unit of re-run, not a cell. The one observed failure is rep-correlated: rep 1
    # of plain2 declines monotonically across its five cells (2116 2104 1224 1319 613 completions
    # per arm) while every other rep stays flat, so the cause is a sustained host degradation
    # spanning the rep, not an independent per-cell event. Re-running a single cell would also
    # move it to a different position in time and destroy the Latin square's position balance for
    # that rep; re-running the square's ROW preserves the balance intact.
    #
    # Exactly ONE re-run per rep. A rep failing the agreement gate twice VOIDS the campaign under
    # C10 -- as does a failure of any OTHER gate, which is never re-run.
    queue = [(rep, rep_orders[rep], 0) for rep in range(REPS)]
    qi = 0
    tripped_reps = set()        # every rep that EVER tripped, including dropped ones
    dropped_reps = []           # (dropped_rep, replacement_rep)
    next_spare = REPS
    while qi < len(queue):
        rep, order, attempt = queue[qi]
        qi += 1
        rep_rows = []
        print("rep %d  (%d cells)%s"
              % (rep, len(order), "   [RE-RUN: agreement gate failed]" if attempt else ""))
        for w, f in order:
            # The emitting set is re-drawn per rep: a resampled nuisance, not a fixed effect.
            emitting = setup_v1(args.dsn, D, f, seed=12345 + 1000 * rep + SEED_BASE)
            c = connect(args.dsn)
            with c.cursor() as cur:
                cur.execute("VACUUM (ANALYZE) model_a, model_b")
            c.close()
            # Before the window opens: the probe costs ~2 s and must not fall inside the
            # interval whose latency is measured.
            host = host_snapshot()
            # seed_base was never passed, so run_cell silently used its 0.1 default and the
            # id pool was identical across runs. The golden-ratio step spreads successive
            # SEED_BASE values across [0,1) rather than clustering them.
            _pool_seed = (0.1 + SEED_BASE * 0.6180339887498949) % 1.0
            res = run_cell(args.dsn, "%s_w%d_f%s" % (args.tag, w, f), rep, w, K, D, POOL,
                           WARMUP_S, DURATION_S, seed_base=_pool_seed, paired=True)
            ok, bad, fpc, exp = gates(res, w, f, emitting, len(cells) * REPS)
            rep_rows.append({"rep": rep, "W": w, "f": f, "rep_attempt": attempt,
                         # PREREG Amendment 5(e): per-cell I/O deltas, so a gate trip can be
                         # attributed to I/O pressure rather than only to host noise.
                         **{key: val for key, val in res.items() if key.startswith("io_")},
                         **host,
                         "emitting": emitting,
                         "throughput_tps": res["throughput"],
                         "mean_latency_us": res["mean_latency_s"] * 1e6,
                         "median_latency_us": res["median_latency_s"] * 1e6,
                         "counted": res["counted"], "retries": res["retries"],
                         "mean_h": res["mean_h"], "peak_h": res["peak_h"],
                         "fires_per_commit": fpc, "fires_expected": exp,
                         "iqr_us": res["iqr_us"],
                         "counted_treat": res["counted_treat"],
                         "counted_base": res["counted_base"],
                         "p50_treat_us": res["p50_treat_us"],
                         "p50_base_us": res["p50_base_us"],
                         "R_paired": res["r_paired"],
                         "mean_treat_us": res["mean_treat_us"],
                         "mean_base_us": res["mean_base_us"],
                         "R_paired_mean": res["r_paired_mean"],
                         "R_h1": res["r_h1"], "R_h2": res["r_h2"],
                         "window_s": res["length_s"], "gates_ok": ok,
                         "gate_failures": "; ".join(bad),
                         "seed_base": SEED_BASE, "pool_seed": round(_pool_seed, 9),
                         "trigger_sha": _trigger_sha,
                         "git_commit": _rev, "git_dirty": _dirty,
                         "allow_dirty": args.allow_dirty, "plan_blob": _blob})
            print("   W=%-3d f=%-6s  treat %8.1f  base %8.1f  R %6.3f (%.3f->%.3f)  "
                  "f/c %6.3f  %s"
                  % (w, f, res["p50_treat_us"], res["p50_base_us"], res["r_paired"],
                     res["r_h1"], res["r_h2"], fpc,
                     "OK" if ok else "GATE FAIL: " + "; ".join(bad)))
            if f > 0 and res["r_paired"] < 1.0:
                # Pre-registered as a FALSIFICATION of the design, not a bloat signature:
                # adding work to a transaction cannot make it faster than its own paired
                # control in the same batch. Flagged loudly, never quietly averaged in.
                print("        *** R < 1 AT f=%s -- DESIGN FALSIFIED, not a drift signature ***"
                      % f)
            if not ok:
                # An agreement-gate trip is NOT a campaign-voiding cell failure -- R3 handles it
                # by re-running and, if that fails, dropping the rep, with the campaign voiding
                # only on a COUNT of tripped reps. Without this exclusion the C10 per-cell path
                # voided the campaign on the first trip and R3 could never take effect: the
                # forced-trip test voided with 6 cells listed, every one of them an injected
                # agreement trip. Any OTHER gate failure still voids immediately and is never
                # re-run.
                other_bad = [x for x in bad if "estimator disagreement" not in x]
                if other_bad:
                    voided.append((rep, w, f, other_bad))


        # --- close the rep -------------------------------------------------------------------
        agree_only = [r for r in rep_rows
                      if "estimator disagreement" in (r["gate_failures"] or "")]
        other = [r for r in rep_rows
                 if r["gate_failures"] and "estimator disagreement" not in r["gate_failures"]]
        if agree_only:
            # Counted once per REP, however many attempts it takes. This set drives the void
            # condition and is reported by f, and it deliberately includes reps that are later
            # dropped -- see below.
            tripped_reps.add(rep)
            if len(tripped_reps) >= AGREEMENT_VOID_COUNT:
                # PREREG Amendment 5h(d). The count void was previously evaluated only after the
                # whole loop, so a campaign that reached the cap at rep 15 still ran to rep 35 --
                # up to an hour of measurement on a host already known to be too degraded to
                # report. The verdict cannot change once the cap is reached, because tripped_reps
                # only grows, so stopping early is equivalent and not an early-stopping bias.
                #
                # These rows go to DISCARDED, not to rows. This attempt tripped the gate, so it is
                # a superseded attempt like any other; putting it in the kept file would break the
                # rule that tripped attempts are superseded, and would make the trips-by-f report
                # count a gate-failed cell as a SURVIVOR. (The earlier version did exactly that.)
                discarded.extend(rep_rows)
                print("   STOPPING EARLY: %d reps have tripped, cap is %d -- the campaign is "
                      "already void." % (len(tripped_reps), AGREEMENT_VOID_COUNT))
                break
        if agree_only and not other:
            # The superseded attempt is WRITTEN OUT to a separate file tagged with rep_attempt,
            # never deleted. What prevents cherry-picking is the pre-registered rule that the
            # re-run's result is used whatever it is; destroying the evidence adds nothing and
            # contradicts the audit discipline the rest of this campaign follows.
            discarded.extend(rep_rows)
            if attempt < MAX_RERUNS:
                print("   rep %d: agreement gate tripped in %d of %d cells -- re-run %d of %d"
                      % (rep, len(agree_only), len(rep_rows), attempt + 1, MAX_RERUNS))
                queue.append((rep, order, attempt + 1))
                continue
            # Exhausted every attempt: DROP the rep and replace it. Licensed by the gate being
            # outcome-independent (unit-tested: R=4.0 with agreeing estimators passes, R=1.05
            # with disagreeing ones fails), so removing a rep on it cannot bias R.
            if len(dropped_reps) >= MAX_REPLACEMENTS:
                print("   rep %d: tripped %d times AND the replacement cap (%d) is exhausted"
                      % (rep, MAX_RERUNS + 1, MAX_REPLACEMENTS))
                voided.append((rep, CRITERION_W, -1, ["replacement cap exhausted"]))
                continue
            repl = next_spare
            next_spare += 1
            dropped_reps.append((rep, repl))
            print("   rep %d: tripped all %d attempts -- DROPPED, replaced by rep %d"
                  % (rep, MAX_RERUNS + 1, repl))
            queue.append((repl, rep_orders[repl], 0))
            continue
        rows.extend(rep_rows)

    if discarded:
        sup = args.out.replace(".csv", "_superseded.csv")
        with open(sup, "w", newline="") as fh:
            wr = csv.DictWriter(fh, fieldnames=list(discarded[0].keys()))
            wr.writeheader()
            wr.writerows(discarded)
        print("\nWrote %d SUPERSEDED rows (agreement-gate re-runs) to %s" % (len(discarded), sup))
        print("  These are excluded from analysis by rule, not by deletion. Reps re-run: %s"
              % sorted({r["rep"] for r in discarded}))

    # Gate trips BY f (PREREG Amendment 5). Registered because the agreement gate is not
    # f-neutral in its mechanism: the one observed trip was at f=0.5, where each fire does an
    # UPDATE on model_b and therefore drives WAL. If trips concentrate at high f, the gate is
    # removing I/O-pressure cells rather than host noise, and c_pg's scope is narrower than
    # "PostgreSQL" -- it is PostgreSQL under steady-state I/O. Reported either way.
    # Amendment 5f(d): DROPPED and SUPERSEDED rows are included here, not just surviving ones.
    # A dropped rep is replaced by one that ran later under different host conditions, so if trips
    # arise from I/O pressure at high f -- the live hypothesis -- dropping removes exactly the
    # reps where the mechanism was active. Reporting trips only for survivors would hide the
    # pattern this report exists to detect.
    trips = {}
    for r in rows + discarded:
        gf = r.get("gate_failures") or ""
        if "estimator disagreement" in gf:
            trips[r["f"]] = trips.get(r["f"], 0) + 1
    if dropped_reps:
        print("\nDROPPED reps (tripped every attempt), replaced: %s"
              % ", ".join("%d->%d" % d for d in dropped_reps))
    print("Reps that ever tripped: %d of %d (void at >= %d)"
          % (len(tripped_reps), REPS, AGREEMENT_VOID_COUNT))
    if len(tripped_reps) >= AGREEMENT_VOID_COUNT:
        voided.append((-1, CRITERION_W, -1,
                       ["%d reps tripped the agreement gate, cap is %d -- sustained host "
                        "degradation" % (len(tripped_reps), AGREEMENT_VOID_COUNT)]))
    print("\nAgreement-gate trips by f: %s"
          % (", ".join("f=%g: %d" % (f, trips[f]) for f in sorted(trips)) if trips else "none"))
    # PREREG Amendment 5h(a). The previous test was `max(trips, key=trips.get) == max(F_VALUES)`,
    # which on a TIE returns whichever key was inserted first -- so the flag depended on cell
    # order, not on the data. Both R3 tests fired it on trips of 3 and 3. Since this flag IS the
    # registered scope restriction on what c_pg measures, a false positive misstates the result.
    #
    # Replaced by a one-sided exact binomial test: under the null that trips are independent of f,
    # each trip lands at f=0.5 with probability 1/len(F_VALUES), since every f gets equally many
    # cells by the Latin square. Registered at alpha = 0.05.
    total_trips = sum(trips.values())
    hi = trips.get(max(F_VALUES), 0)
    if total_trips:
        p_conc = binom_sf(hi - 1, total_trips, 1.0 / len(F_VALUES))
        print("  trips at f=%g: %d of %d (expected %.1f under independence), one-sided p = %.4f"
              % (max(F_VALUES), hi, total_trips, total_trips / len(F_VALUES), p_conc))
        if p_conc < 0.05:
            print("  CONCENTRATED AT THE HIGHEST f (p < 0.05). Per PREREG Amendment 5(e) this is")
            print("  a scope restriction -- c_pg is measured under steady-state I/O -- and must")
            print("  NOT be presented as a clean removal of host noise.")
        else:
            print("  Not concentrated at the highest f at alpha=0.05.")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    if rows:
        with open(args.out, "w", newline="") as fh:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            wr.writerows(rows)
        print("\nWrote %s" % args.out)
    else:
        # Reachable since the early stop was added: if the first AGREEMENT_VOID_COUNT
        # reps all trip, every attempt is superseded and nothing is kept. rows[0] then
        # raised IndexError AFTER the superseded file was written but BEFORE the void was
        # reported, so the run died with its diagnosis unprinted, looking like a crash
        # rather than a void. Every measurement taken is still in the superseded file.
        print("\nNo kept rows: every rep tripped the gate. Nothing written to %s;"
              % args.out)
        print("  all measurements are in the superseded file.")

    # C10: a campaign with gate failures is VOID rather than caveated. No R_pg is computed.
    if args.smoke:
        print("\nSMOKE complete. No R_pg reported -- this was not a campaign.")
        return 1 if voided else 0

    if voided:
        print("\nCAMPAIGN VOID -- %d cells failed their gates:" % len(voided))
        for rep, w, f, bad in voided[:10]:
            print("  rep %d W=%d f=%s: %s" % (rep, w, f, "; ".join(bad)))
        print("\nNo R_pg reported. Per C10 the campaign is void, not caveated.")
        return 1

    if not rows:
        return 1
    return aggregate(rows, W_VALUES, F_VALUES)


if __name__ == "__main__":
    # --analyze reads a CSV and touches no database, so it must NOT take the measurement lock.
    # Taking it would block on a running campaign -- and worse, if the lock were ever made
    # non-blocking, analysis could take the lock a real measurement needs. The lock guards H,
    # a server-wide count; work that never reads H has no business holding it.
    if "--analyze" in sys.argv:
        sys.exit(main())
    with run_lock.held():
        sys.exit(main())
