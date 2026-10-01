"""The V1b pilot readout, as one fixed sequence. Registered by PREREG Amendment 5l.

WHY THIS IS A SCRIPT AND NOT A NOTE. Everything it does is already registered, but across
Amendments 5c, 5f, 5h, 5j and 5k. A procedure spread over five documents is one a reader has to
reassemble, and one whose steps can be reordered after the numbers are visible without anybody
noticing. Written as an ordered program it cannot drift: the GO/NO-GO falls out of the data, and
there is nothing left to decide once the figures appear.

Run it AFTER the extension finishes. It is read-only: it takes no lock and mutates nothing.

THE SEQUENCE
  1. contamination audit; trips inside vs outside the bracket
  2. first-attempt p, over all pilot reps and (if step 1 found a trip inside) excluding reps 5-11
  3. corr from the extension's re-attempts, with its denominator stated; < 3 re-attempts -> 0.5
  4. upper-80% bound on p (the more conservative, if two), then Amendment 5f's two-sided check
  5. coefficient pooling by the gate rule, and the n re-estimate, never below 35
  6. GO or NO-GO
"""
import argparse
import csv
import importlib.util
import math
import os
import random
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

_spec = importlib.util.spec_from_file_location("v1b_sweep", os.path.join(HERE, "v1b_sweep.py"))
V = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(V)

CONTAMINATED_REPS = [5, 6, 7, 8, 9, 10, 11]     # ptrial2: Amendment 5j(b), window overlap

# plain2 rep 4 (PREREG Amendment 5n(b)). This event PREDATES run_lock and was never written into
# the repository, which is why an earlier pass could find no record of it and declined to act on
# the claim. The record, not the event, was missing. Corroborated from file timestamps:
#
#   tgs_panelb_sim_cu_k10.csv   created 11:44:28, last written 11:49:47  (the k-check JVM)
#   v1b_sweep_plain2.csv        written 11:45:44                          (plain2 finished)
#
# So a CPU-saturating simulator run overlapped plain2's final 76 seconds, which is its last rep.
# At the time, rep 4 was dropped from the W=1 medians, and the "4 clean reps" c_pg figures in
# V1b-RESULT's CURRENT STATE are those rep-4-excluded medians. But the "4 healthy plain2 reps"
# pooled for power in Amendments 5 and 5e were reps 0, 2, 3 and 4 -- rep 1 being the gate
# exclusion -- so they INCLUDED rep 4. That inconsistency is resolved here in favour of the
# stricter rule already registered in 5m(d): a rep excluded from p is excluded from the
# covariance.
PLAIN2_CONTAMINATED_REPS = [4]
TRIP = "estimator disagreement"


def load(path):
    if not path or not os.path.exists(path):
        return []
    return list(csv.DictReader(open(path)))


def attempt_of(row):
    """rep_attempt, defaulting to 0 for rows written before the column existed."""
    try:
        return int(float(row.get("rep_attempt", 0) or 0))
    except (TypeError, ValueError):
        return 0


def tripped(row):
    return TRIP in (row.get("gate_failures") or "")


def rep_key(row, source):
    """Reps are numbered per SOURCE, so the two pilot runs must not collide."""
    return (source, int(float(row["rep"])))


def clopper_upper(trips, n, conf=0.80):
    """One-sided upper bound on a binomial p. Exact for the zero-event case, bisection otherwise."""
    if n == 0:
        return 1.0
    if trips == 0:
        return 1.0 - (1.0 - conf) ** (1.0 / n)
    lo, hi = trips / n, 1.0
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        # P(X <= trips | p=mid); the bound is where this equals 1-conf
        tail = sum(math.comb(n, i) * mid ** i * (1 - mid) ** (n - i) for i in range(trips + 1))
        if tail > 1.0 - conf:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def p_void_r3(p, cap_count, nreps=35):
    """EXACT: P(Binomial(nreps, p) >= cap_count).

    Was a 40k-trial Monte Carlo. Side B is decided by a comparison of 0.904 against 0.896, and
    letting a threshold decision at that margin depend on RNG noise -- from two simulations on
    DIFFERENT seeds, so their errors do not even cancel -- is indefensible when the closed form
    is one line.
    """
    return sum(math.comb(nreps, i) * p ** i * (1 - p) ** (nreps - i)
               for i in range(cap_count, nreps + 1))


def p_void_r2c(p, corr, nreps=35):
    """EXACT: a rep fails when it trips and then trips on both re-attempts, i.e. p*corr^2, so
    P(void) = 1 - (1 - p*corr^2)^nreps. At p=0.25, corr=0.5 this is 0.8955."""
    q = p * corr * corr
    return 1.0 - (1.0 - q) ** nreps


def coefficients(rows_by_rep):
    """Per-rep (c, b) for reps whose cells ALL pass the gate -- the Amendment 5d pooling rule."""
    out = []
    for _key, cells in sorted(rows_by_rep.items()):
        if len(cells) < 3:
            continue
        worst = 0.0
        ok = True
        for r in cells:
            try:
                a, m = float(r["R_paired"]), float(r["R_paired_mean"])
            except (TypeError, ValueError):
                ok = False
                break
            if a <= 0 or m <= 0:
                ok = False
                break
            worst = max(worst, abs(math.log(a / m)))
        if not ok or worst > V.AGREEMENT_TOL:
            continue
        cells = sorted(cells, key=lambda r: float(r["f"]))
        fs = [float(r["f"]) for r in cells]
        ys = [float(r["R_paired"]) - 1.0 for r in cells]
        _d, c, b = V.ols_quadratic(fs, ys)
        out.append((c, b))
    return out


def t2_power(cov, n, tc, tb, trials=4000, seed=3):
    (s11, s22, s12) = cov
    l11 = math.sqrt(s11)
    l21 = s12 / l11
    l22 = math.sqrt(max(s22 - l21 * l21, 1e-12))
    rng = random.Random(seed)
    hit = 0
    for _ in range(trials):
        cs, bs = [], []
        for _r in range(n):
            z1, z2 = rng.gauss(0, 1), rng.gauss(0, 1)
            cs.append(tc + l11 * z1)
            bs.append(tb + l21 * z1 + l22 * z2)
        if V.hotelling_t2(cs, bs)[2] < 0.05:
            hit += 1
    return hit / trials


def main():
    ap = argparse.ArgumentParser()
    res = os.path.join(HERE, "artifacts", "experiments", "results")
    ap.add_argument("--pilot", default=os.path.join(res, "v1b_ptrial.csv"))
    ap.add_argument("--pilot-sup", default=os.path.join(res, "v1b_ptrial_superseded.csv"))
    ap.add_argument("--ext", default=os.path.join(res, "v1b_ptrial2.csv"))
    ap.add_argument("--ext-sup", default=os.path.join(res, "v1b_ptrial2_superseded.csv"))
    ap.add_argument("--plain2", default=os.path.join(res, "v1b_sweep_plain2.csv"))
    args = ap.parse_args()

    sources = [("ptrial", load(args.pilot), load(args.pilot_sup)),
               ("ptrial2", load(args.ext), load(args.ext_sup))]
    print("=" * 78)
    print("V1b PILOT READOUT  (PREREG Amendments 5c, 5f, 5h, 5j, 5k; sequence fixed by 5l)")
    print("=" * 78)
    for name, kept, sup in sources:
        print("  %-10s kept %4d rows   superseded %4d rows" % (name, len(kept), len(sup)))

    # ---- step 1: trips inside vs outside the contamination bracket --------------------------
    print("\n[1] CONTAMINATION (Amendment 5j/5k)")
    ext_all = sources[1][1] + sources[1][2]
    ext_trip_reps = sorted({int(float(r["rep"])) for r in ext_all if tripped(r)})
    inside = [r for r in ext_trip_reps if r in CONTAMINATED_REPS]
    outside = [r for r in ext_trip_reps if r not in CONTAMINATED_REPS]
    print("    flagged reps (window overlap, NOT evidence of effect): %s" % CONTAMINATED_REPS)
    print("    extension reps that tripped ...... %s" % (ext_trip_reps or "none"))
    print("    of those, inside the bracket ..... %s" % (inside or "none"))
    print("    of those, outside ................ %s" % (outside or "none"))
    exclude = bool(inside)
    if exclude:
        print("    -> a trip occurred inside the bracket, so p is reported BOTH ways and the")
        print("       more conservative bound governs (5j(c)).")
    else:
        print("    -> no rep inside the bracket tripped, so p over all reps and p excluding")
        print("       reps 5-11 are IDENTICAL. Contamination reduces to disclosure.")

    # ---- step 2: first-attempt p ------------------------------------------------------------
    print("\n[2] FIRST-ATTEMPT p (Amendment 5h(b): first attempts only)")

    def first_attempt_p(skip_contaminated):
        firsts, trips = set(), set()
        for name, kept, sup in sources:
            for r in kept + sup:
                if attempt_of(r) != 0:
                    continue
                rep = int(float(r["rep"]))
                if skip_contaminated and name == "ptrial2" and rep in CONTAMINATED_REPS:
                    continue
                k = rep_key(r, name)
                firsts.add(k)
                if tripped(r):
                    trips.add(k)
        return len(trips), len(firsts)

    t_all, n_all = first_attempt_p(False)
    print("    all pilot reps ........... %d trips / %d reps   p_hat = %.4f"
          % (t_all, n_all, t_all / n_all if n_all else float("nan")))
    bounds = [clopper_upper(t_all, n_all)]
    print("      upper 80%% bound ........ %.4f" % bounds[0])
    if exclude:
        t_ex, n_ex = first_attempt_p(True)
        print("    excluding reps 5-11 ...... %d trips / %d reps   p_hat = %.4f"
              % (t_ex, n_ex, t_ex / n_ex if n_ex else float("nan")))
        bounds.append(clopper_upper(t_ex, n_ex))
        print("      upper 80%% bound ........ %.4f" % bounds[1])
    p_up = max(bounds)
    print("    GOVERNING upper bound (the more conservative): %.4f" % p_up)

    # ---- step 3: corr from re-attempts ------------------------------------------------------
    print("\n[3] RE-TRIP RATE corr (Amendment 5h(b): re-attempts only)")
    # corr is a REP-LEVEL probability: does a re-attempted rep trip AGAIN? Counting it per CELL
    # understates it -- a re-attempt where 1 of 5 cells trips is 0.2 per cell but 1.0 per rep --
    # and understating corr lowers R2c's P(void) at p=0.25, which makes side B EASIER to pass.
    # The estimability check already counted distinct reps, so a per-cell numerator was also
    # inconsistent with its own denominator. Unit here is the (rep, attempt) pair.
    re_attempts = {}
    for r in ext_all:
        a = attempt_of(r)
        if a > 0:
            key = (int(float(r["rep"])), a)
            re_attempts[key] = re_attempts.get(key, False) or tripped(r)
    re_reps = {rep for rep, _a in re_attempts}
    n_re = len(re_attempts)
    n_re_trip = sum(1 for v in re_attempts.values() if v)
    print("    re-attempts observed ..... %d rep-attempts across reps %s"
          % (n_re, sorted(re_reps) or "none"))
    if len(re_reps) < 3:
        corr = 0.5
        print("    fewer than 3 re-attempted reps -> NOT ESTIMABLE; using the registered")
        print("    conservative corr = 0.50 (Amendment 5c(b))")
    else:
        corr = n_re_trip / n_re
        print("    corr = %d tripped / %d rep-attempts = %.4f  (REP-level, not per cell)"
              % (n_re_trip, n_re, corr))
    print("    NOTE the denominator is the EXTENSION's re-attempts alone; the first 10 reps ran")
    print("    under R1 and produced none (Amendment 5i(c)).")

    # ---- step 4: the 5f two-sided rule check -------------------------------------------------
    print("\n[4] RULE CHECK (Amendment 5f: six-of-35, BOTH sides)")
    cap = V.AGREEMENT_VOID_COUNT
    v_bound = p_void_r3(p_up, cap)
    v_bad = p_void_r3(0.25, cap)
    ref = p_void_r2c(0.25, corr)
    print("    cap = %d of %d reps" % (cap, V.REPS))
    print("    side A  P(void) at the governing p=%.4f ....... %.3f   (need <= 0.10)"
          % (p_up, v_bound))
    print("    side B  P(void) at a degraded p=0.25 .......... %.3f" % v_bad)
    print("            R2c's P(void) at p=0.25, corr=%.2f .... %.3f   (need side B >= this)"
          % (corr, ref))
    side_a = v_bound <= 0.10
    side_b = v_bad >= ref
    print("    side A satisfied: %-5s     side B satisfied: %s" % (side_a, side_b))

    # ---- step 5: pooling and the n re-estimate ----------------------------------------------
    print("\n[5] POOLING AND n (Amendments 5d(a), 5e(e))")
    groups = []
    for name, kept, _sup in sources + [("plain2", load(args.plain2), [])]:
        by_rep = {}
        skipped = []
        for r in kept:
            if int(float(r.get("W", 1))) != 1:
                continue
            # NO attempt filter. Under R3 a rep that passed on re-attempt is KEPT with
            # rep_attempt 1 or 2, and an `attempt_of(r) != 0` filter silently dropped exactly
            # those reps -- the ones a trip-bearing run produces. Kept rows are by definition
            # the final, passing attempt, so membership of the kept file IS the filter.
            rep = int(float(r["rep"]))
            # Contaminated reps follow the same rule as everything else (Amendment 5m): a rep
            # excluded from p is excluded from the covariance. n is floored at 35 so this
            # changes no decision, but the covariance must not be the one estimate that
            # quietly keeps data the rest of the readout drops.
            if name == "ptrial2" and rep in CONTAMINATED_REPS:
                skipped.append(rep)
                continue
            if name == "plain2" and rep in PLAIN2_CONTAMINATED_REPS:
                skipped.append(rep)
                continue
            by_rep.setdefault(rep, []).append(r)
        co = coefficients(by_rep)
        groups.append((name, co))
        print("    %-10s gate-passing reps contributing coefficients: %d%s"
              % (name, len(co),
                 "   (excluded %d contaminated)" % len(set(skipped)) if skipped else ""))
    allco = [c for _n, g in groups for c in g]
    if len(allco) < 4:
        print("    too few reps to re-estimate; n stays %d" % V.REPS)
        n_new = V.REPS
        power_ok = True
    else:
        s11 = s22 = s12 = 0.0
        df = 0
        for _n, g in groups:
            if len(g) < 2:
                continue
            mc = statistics.mean(x[0] for x in g)
            mb = statistics.mean(x[1] for x in g)
            s11 += sum((x[0] - mc) ** 2 for x in g)
            s22 += sum((x[1] - mb) ** 2 for x in g)
            s12 += sum((x[0] - mc) * (x[1] - mb) for x in g)
            df += len(g) - 1
        cov = (s11 / df, s22 / df, s12 / df)
        if cov[0] <= 0 or cov[1] <= 0:
            # Degenerate: every rep gave identical coefficients, so a coefficient has zero
            # variance. Found by the fixture, whose synthetic cells are deterministic. It crashed
            # with ZeroDivisionError AFTER printing partial results, which is the worst shape for
            # a failure -- it looks like the analysis ran. Real data will not be exactly
            # degenerate, but a crash is a crash and a NO-GO is the honest verdict when the
            # covariance the design rests on cannot be formed.
            print("    DEGENERATE covariance: sd(c)=%.4g sd(b)=%.4g -- a coefficient has zero"
                  % (math.sqrt(max(cov[0], 0.0)), math.sqrt(max(cov[1], 0.0))))
            print("    variance across reps, so the power re-estimate cannot be computed.")
            return_degenerate = True
        else:
            return_degenerate = False
            print("    pooled-within (df=%d): sd(c)=%.4f sd(b)=%.4f corr=%.3f"
                  % (df, math.sqrt(cov[0]), math.sqrt(cov[1]),
                     cov[2] / math.sqrt(cov[0] * cov[1])))
        if return_degenerate:
            n_new, power_ok = None, False
            print("\n[6] VERDICT")
            print("    NO-GO: the pooled coefficient covariance is degenerate, so n cannot be")
            print("    re-estimated. Investigate the inputs before running the campaign.")
            return 1
        n_req = None
        for n in (15, 20, 25, 30, 35, 40, 45, 50):
            pw = t2_power(cov, n, 1.262, -0.628)
            print("      n=%-3d  T2 power %.2f" % (n, pw))
            if pw >= 0.90 and n_req is None:
                n_req = n
        if n_req is None:
            # `max(n_req or V.REPS, 35)` silently returned 35 here -- the OPPOSITE of the
            # registered rule, which never shrinks but must grow when the variance demands it.
            # An unmet power requirement is a NO-GO, not a fallback to the floor.
            print("    NO n <= 50 reaches power 0.90. The registered rule requires growing n,")
            print("    so the design is not satisfiable within the searched range.")
            n_new = None
            power_ok = False
        else:
            n_new = max(n_req, 35)
            power_ok = True
            print("    smallest n with power >= 0.90: %d  ->  registered floor applies, n = %d"
                  % (n_req, n_new))
            if n_new != V.REPS:
                print("    WARNING: n = %d differs from the %d the six-of-%d cap was derived for."
                      % (n_new, V.REPS, V.REPS))
                print("    The cap does NOT carry over automatically -- both sides of the 5f")
                print("    criterion must be re-evaluated at the new n before the campaign runs.")

    # ---- step 6 -----------------------------------------------------------------------------
    print("\n[6] VERDICT")
    go = side_a and side_b and power_ok
    if go:
        print("    GO. Both sides of the 5f criterion are satisfied at the governing p,")
        print("    and the power requirement is met.")
        print("    Run the campaign at n = %d with the six-of-%d cap." % (n_new, V.REPS))
    else:
        print("    NO-GO:")
        if not power_ok:
            print("      power fails: no n within the searched range reaches 0.90, and the")
            print("      registered rule requires growing n rather than falling back to 35.")
        if not side_a:
            print("      side A fails: P(void) %.3f > 0.10 at p=%.4f -- the campaign would void"
                  % (v_bound, p_up))
            print("      too often. More clean pilot reps would tighten the bound.")
        if not side_b:
            print("      side B fails: the cap is LOOSER than R2c on a degraded host (%.3f < %.3f)"
                  % (v_bad, ref))
            print("      -- a relaxation of the case the void exists for, which 5f forbids.")
        print("    The campaign does not run, and this is the reported reason (5f).")
    return 0 if go else 1


if __name__ == "__main__":
    sys.exit(main())
