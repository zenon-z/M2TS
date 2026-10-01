"""The V1b campaign analysis, as one fixed sequence. Registered by PREREG Amendment 5q.

WHY A PROGRAM. The same reason `v1b_readout.py` is one: every step below is already registered,
but across Amendments 3, 5, 5f, 5g, 5h and 5p. A procedure spread that widely is one a reader must
reassemble, and one whose steps could be reordered after the numbers are visible. Written as an
ordered program the verdict falls out of the data.

Read-only: takes no lock, mutates nothing.

THE SEQUENCE
  1. provenance and gates -- one seed, one trigger, one commit; C10 void check
  2. trips by f (exact binomial) and the host-CPU comparison against the pilot
  3. PRIMARY: Hotelling T^2 on per-rep (c-1, b) against (0,0)
  4. SECONDARY marginals, each carrying the corr(c,b) caveat
  5. Wilcoxon signed-rank sensitivity; disagreement REPORTED, never resolved
  6. c_m(f) = c + 2bf, descriptive, t-intervals
  7. c_wal against the measured null 1.0764, corrected by attenuation 0.9940
  8. the criterion-3 statement, scoped
"""
import argparse
import csv
import importlib.util
import math
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_spec = importlib.util.spec_from_file_location("v1b_sweep", os.path.join(HERE, "v1b_sweep.py"))
V = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(V)

# From the WAL calibration (Amendment 5p(c)), measured not assumed.
CWAL_NULL = 1.0764
CWAL_ATTENUATION = 0.9940
# Pilot host reference (Amendment 5n(d)): median 52%, range 24-93% over 125 cells.
PILOT_CPU_MEDIAN, PILOT_CPU_RANGE = 52.0, (24.0, 93.0)
TRIP = "estimator disagreement"


def num(row, key, default=None):
    try:
        return float(row[key])
    except (KeyError, TypeError, ValueError):
        return default


def t_interval(vals):
    """mean +/- t.975,n-1 * sd/sqrt(n). Amendment 5h(c): a CI for the MEAN, not a percentile
    spread of per-rep values, which at n=35 is about sqrt(35) ~ 6x wider and not comparable with
    the primary test."""
    n = len(vals)
    if n < 2:
        return float("nan"), float("nan"), float("nan")
    m, sd = statistics.mean(vals), statistics.stdev(vals)
    h = V.t_ppf(0.025, n - 1) * sd / math.sqrt(n)
    return m, m - h, m + h


def main():
    ap = argparse.ArgumentParser()
    res = os.path.join(HERE, "artifacts", "experiments", "results")
    ap.add_argument("--csv", default=os.path.join(res, "v1b_campaign.csv"))
    ap.add_argument("--superseded", default=os.path.join(res, "v1b_campaign_superseded.csv"))
    args = ap.parse_args()

    kept = list(csv.DictReader(open(args.csv)))
    sup = list(csv.DictReader(open(args.superseded))) if os.path.exists(args.superseded) else []
    print("=" * 78)
    print("V1b CAMPAIGN ANALYSIS   (PREREG Amendments 3, 5, 5f, 5g, 5h, 5p; sequence by 5q)")
    print("=" * 78)
    print("  kept %d rows, superseded %d rows" % (len(kept), len(sup)))

    # ---- 1. provenance and gates ------------------------------------------------------------
    print("\n[1] PROVENANCE AND GATES")
    for col in ("seed_base", "pool_seed", "trigger_sha", "git_commit", "plan_blob", "git_dirty"):
        vals = {r.get(col) for r in kept if col in r}
        status = "one value" if len(vals) == 1 else "*** %d VALUES ***" % len(vals)
        print("    %-12s %-44s %s"
              % (col, (list(vals)[0] if len(vals) == 1 else sorted(vals))[:44]
                 if vals else "ABSENT", status))
    if not any("seed_base" in r for r in kept):
        print("    seed_base ABSENT -- this CSV predates Amendment 5n and is not the campaign.")
    dirty = {r.get("git_dirty") for r in kept}
    if dirty - {"False", "false", None}:
        print("    *** git_dirty is not False: the recorded commit does not describe the code. ***")
    reps = sorted({int(float(r["rep"])) for r in kept})
    failures = [r for r in kept if (r.get("gate_failures") or "").strip()]
    print("    reps kept ............ %d" % len(reps))
    print("    cells with any gate failure in the KEPT file: %d" % len(failures))
    tripped_reps = sorted({int(float(r["rep"])) for r in kept + sup if TRIP in
                           (r.get("gate_failures") or "")})
    print("    reps that EVER tripped: %d %s (void at >= %d)"
          % (len(tripped_reps), tripped_reps or "", V.AGREEMENT_VOID_COUNT))
    other = [r for r in failures if TRIP not in (r.get("gate_failures") or "")]
    void = len(tripped_reps) >= V.AGREEMENT_VOID_COUNT or bool(other)
    if other:
        print("    *** %d cell(s) failed a NON-agreement gate -- C10 voids the campaign ***"
              % len(other))
        for r in other[:5]:
            print("        rep %s f=%s: %s" % (r["rep"], r["f"], r["gate_failures"][:70]))
    if void:
        print("\n    CAMPAIGN VOID. No criterion-3 result is reported (C10).")
        return 1
    print("    -> no void condition")

    # ---- 2. trips by f, and host state --------------------------------------------------------
    print("\n[2] TRIPS BY f, AND HOST STATE")
    trips = {}
    for r in kept + sup:
        if TRIP in (r.get("gate_failures") or ""):
            trips[num(r, "f")] = trips.get(num(r, "f"), 0) + 1
    total = sum(trips.values())
    if total:
        hi = trips.get(max(V.F_VALUES), 0)
        p_conc = V.binom_sf(hi - 1, total, 1.0 / len(V.F_VALUES))
        print("    %s" % ", ".join("f=%g: %d" % (f, trips[f]) for f in sorted(trips)))
        print("    at f=%g: %d of %d, one-sided p = %.4f"
              % (max(V.F_VALUES), hi, total, p_conc))
        if p_conc < 0.05:
            print("    CONCENTRATED AT THE HIGHEST f -- c_pg is scoped to steady-state I/O")
            print("    (Amendment 5e); this is NOT a clean removal of host noise.")
    else:
        print("    no trips")
    cpu = [num(r, "host_cpu_pct") for r in kept if num(r, "host_cpu_pct") is not None]
    if cpu:
        print("    host CPU: median %.0f%%, range %.0f-%.0f%%, n=%d cells"
              % (statistics.median(cpu), min(cpu), max(cpu), len(cpu)))
        print("    pilot reference: median %.0f%%, range %.0f-%.0f%%"
              % (PILOT_CPU_MEDIAN, PILOT_CPU_RANGE[0], PILOT_CPU_RANGE[1]))
        delta = statistics.median(cpu) - PILOT_CPU_MEDIAN
        print("    median difference %+.0f points -> %s"
              % (delta, "campaign host BUSIER than the pilot's; the p bound was measured under "
                        "conditions that no longer hold" if delta > 10 else
                        "comparable to the pilot's host"))
        print("    NOTE the snapshot is taken BEFORE each window (5j(g)), so load starting")
        print("    mid-window does not appear in it. Suggestive, not attributive.")

    # ---- per-rep coefficients ----------------------------------------------------------------
    by_rep = {}
    for r in kept:
        by_rep.setdefault(int(float(r["rep"])), []).append(r)
    # STRUCTURAL INTEGRITY, checked explicitly rather than inferred from the complete-rep count.
    # The loop below drops an incomplete rep with a bare `continue`; without this, a rep that lost
    # a cell would vanish from the primary in silence and the analysis would look clean.
    expected_cells = V.REPS * len(V.F_VALUES)
    print("\n[1b] CELL COUNT")
    print("    kept cells: %d, expected %d (%d reps x %d f)"
          % (len(kept), expected_cells, V.REPS, len(V.F_VALUES)))
    incomplete = []
    for rep in sorted(by_rep):
        got = sorted(num(r, "f") for r in by_rep[rep])
        if got != sorted(V.F_VALUES):
            incomplete.append((rep, [f for f in V.F_VALUES if f not in got],
                               [f for f in got if got.count(f) > 1]))
    if len(kept) != expected_cells:
        print("    *** CELL COUNT MISMATCH: %+d against the registered design ***"
              % (len(kept) - expected_cells))
    if incomplete:
        print("    *** %d REP(S) NOT COMPLETE -- these DROP OUT of the primary: ***"
              % len(incomplete))
        for rep, missing, dup in incomplete:
            print("        rep %-3d missing f=%s%s"
                  % (rep, missing, ("  DUPLICATED f=%s" % dup) if dup else ""))
        print("    A dropped rep reduces n below the registered %d and must be reported as such,"
              % V.REPS)
        print("    not absorbed into a smaller sample.")
    else:
        print("    every rep carries all %d f values" % len(V.F_VALUES))

    per_rep = []
    for rep in sorted(by_rep):
        cells = sorted(by_rep[rep], key=lambda r: num(r, "f"))
        if len(cells) != len(V.F_VALUES):
            continue
        d, c, b = V.ols_quadratic([num(r, "f") for r in cells],
                                  [num(r, "R_paired") - 1.0 for r in cells])
        per_rep.append((rep, d, c, b))
    n = len(per_rep)
    cs = [p[2] for p in per_rep]
    bs = [p[3] for p in per_rep]
    ds = [p[1] for p in per_rep]

    # ---- 3. PRIMARY ---------------------------------------------------------------------------
    print("\n[3] PRIMARY -- Hotelling T^2 on per-rep (c-1, b) against (0, 0)")
    print("    complete reps: %d (registered n = %d)" % (n, V.REPS))
    if n < 4:
        print("    NOT EVALUABLE")
        return 1
    t2, fstat, p_joint = V.hotelling_t2(cs, bs)
    print("    T^2 = %.3f, F(2,%d) = %.3f, p = %.4f" % (t2, n - 2, fstat, p_joint))
    print("    -> %s 'a CU is one resource touch'"
          % ("REJECT" if p_joint < 0.05 else "cannot reject"))

    # ---- 4. SECONDARY marginals ---------------------------------------------------------------
    print("\n[4] SECONDARY marginals (NOT the primary; each at 0.05)")
    mc, lo_c, hi_c = t_interval(cs)
    mb, lo_b, hi_b = t_interval(bs)
    md, lo_d, hi_d = t_interval(ds)
    se_c = statistics.stdev(cs) / math.sqrt(n)
    se_b = statistics.stdev(bs) / math.sqrt(n)
    p_c = 2.0 * V.t_sf(abs((mc - 1.0) / se_c), n - 1)
    p_b = 1.0 - V.t_sf(mb / se_b, n - 1)
    rho = (sum((cs[i] - mc) * (bs[i] - mb) for i in range(n)) / (n - 1)
           / (statistics.stdev(cs) * statistics.stdev(bs)))
    print("    c     = %.4f  95%% CI [%.4f, %.4f]   two-sided p = %.4f" % (mc, lo_c, hi_c, p_c))
    print("    b     = %.4f  95%% CI [%.4f, %.4f]   one-sided p = %.4f" % (mb, lo_b, hi_b, p_b))
    print("    delta = %.4f  95%% CI [%.4f, %.4f]   reported, not tested" % (md, lo_d, hi_d))
    print("    CAVEAT (5f(e)): corr(c, b) = %.3f, so each marginal is conditional on the" % rho)
    print("    quadratic being the right model. The joint test is not.")

    # ---- 5. robust sensitivity ---------------------------------------------------------------
    print("\n[5] ROBUST SENSITIVITY (signed-rank; disagreement REPORTED, not resolved)")
    zc, pwc = V.wilcoxon_signed_rank([c - 1.0 for c in cs], "two-sided")
    zb, pwb = V.wilcoxon_signed_rank(bs, "less")
    print("    c-1: z = %.3f, p = %.4f   %s" % (zc, pwc, V._agree(p_c, pwc)))
    print("    b  : z = %.3f, p = %.4f   %s" % (zb, pwb, V._agree(p_b, pwb)))
    if pwc == pwc and pwb == pwb and ((p_c < 0.05) != (pwc < 0.05)
                                      or (p_b < 0.05) != (pwb < 0.05)):
        print("    *** t-test and signed-rank DISAGREE. Reported as a disagreement; neither is")
        print("        the tie-breaker (5f(d)). The design's leverage on b from the f=0.5 cell")
        print("        is 7.34, so a single cell can drive a mean-based result.")

    # ---- 6. c_m(f) ---------------------------------------------------------------------------
    print("\n[6] MARGINAL PER-FIRE COST c_m(f) = c + 2bf  (DESCRIPTIVE, no test -- 5g(b))")
    print("    %8s %10s %22s" % ("f", "c_m", "95% CI"))
    for f in V.F_VALUES:
        if f == 0:
            continue
        vals = [per_rep[i][2] + 2 * per_rep[i][3] * f for i in range(n)]
        m, lo, hi = t_interval(vals)
        print("    %8g %10.4f   [%8.4f, %8.4f]" % (f, m, lo, hi))

    # ---- 7. c_wal ----------------------------------------------------------------------------
    print("\n[7] c_wal -- an INDEPENDENT channel (5g(c), null from 5p(c))")
    per_rep_cwal = []
    for rep in sorted(by_rep):
        cells = sorted(by_rep[rep], key=lambda r: num(r, "f"))
        base = [r for r in cells if num(r, "f") == 0.0]
        if not base or len(cells) != len(V.F_VALUES):
            continue
        w0 = num(base[0], "io_wal_records")
        n0 = num(base[0], "counted")
        if not w0 or not n0:
            continue
        xs, ys = [], []
        for r in cells:
            w, cnt = num(r, "io_wal_records"), num(r, "counted")
            if w and cnt:
                xs.append(num(r, "f"))
                ys.append((w / cnt) / (w0 / n0) - 1.0)
        if len(xs) >= 3:
            sxx = sum(x * x for x in xs)
            per_rep_cwal.append(2.0 * sum(xs[i] * ys[i] for i in range(len(xs))) / sxx)
    if len(per_rep_cwal) >= 4:
        raw, lo, hi = t_interval(per_rep_cwal)
        corr_ = raw / CWAL_ATTENUATION
        print("    fitted c_wal (per-txn WAL RECORDS, fixed intercept) .. %.4f [%.4f, %.4f]"
              % (raw, lo, hi))
        print("    corrected for attenuation %.4f ....................... %.4f"
              % (CWAL_ATTENUATION, corr_))
        print("    measured structural null (w_b/w_a) .................. %.4f" % CWAL_NULL)
        clo, chi = lo / CWAL_ATTENUATION, hi / CWAL_ATTENUATION
        # CI CONTAINMENT ALONE IS WEAK (Amendment 5r): a wide interval contains the null and gets
        # reported as "consistent" when it is merely uninformative. c_wal exists as a CROSS-CHECK
        # ON c, so the comparison that carries information is between the two channels --
        # latency (c) against write volume (c_wal). Reported as DESCRIPTIVE: they measure
        # different things, so disagreement localises a mechanism rather than refuting either.
        print("    c (latency channel) 95%% CI ......................... [%.4f, %.4f]"
              % (lo_c, hi_c))
        if chi - clo >= 1e-9:
            overlap = not (chi < lo_c or hi_c < clo)
            print("    intervals %s"
                  % ("OVERLAP -- the two channels agree on the fire's relative cost"
                     if overlap else
                     "DO NOT OVERLAP -- latency and write volume disagree about the"))
            if not overlap:
                print("      fire's relative cost. Descriptive, not a refutation: WAL volume is")
                print("      not latency, and a fire that writes like a parent row can still")
                print("      cost more or less time than one. The gap localises the mechanism.")
        if chi - clo < 1e-9:
            # Zero-width interval: every rep produced an identical estimate, so there is no
            # between-rep variance to form a CI from. Found by the fixture, whose synthetic WAL
            # was noiseless. Reporting "differs from the null" on a rounding difference at the
            # sixth decimal would be a verdict manufactured from no evidence.
            print("    -> interval is DEGENERATE (zero width): no between-rep variance in the")
            print("       WAL estimate, so no comparison with the null is made.")
        else:
            print("    -> c_wal %s the null"
                  % ("is consistent with" if clo <= CWAL_NULL <= chi else "differs from"))
        print("    Reported as a cross-check only. WAL volume is not latency, so c_wal != c is")
        print("    an expected outcome rather than a contradiction (5g(c)).")
    else:
        print("    NOT EVALUABLE: too few reps carry usable io_wal_records.")

    # ---- 8. the scoped statement -------------------------------------------------------------
    print("\n[8] CRITERION 3")
    print("    Read at W = 1 ONLY. At W=1 no transaction interacts with another, so the two")
    print("    arms' workload compositions coincide. This tests the modelling claim that a CU")
    print("    is ONE RESOURCE TOUCH. It says NOTHING about contention under CPRs.")
    print("    The simulator arm is an identity at W=1 (R_sim = 1 + E[m]/k, verified at all")
    print("    five f to within 0.10%), so the test reduces to whether c_pg = 1 in PostgreSQL.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
