"""Pre-pass: measure `s(D)`, the parent's cost scaling, to CHOOSE the third D for the residual test.

The three-point residual test of `CU(D) = dispatch + row_work * s(D)` only has power if the middle
point sits well away from both endpoints **on the `s` axis**. `D` is not that axis, and the mapping
between them is the shape of `s(D)`, which is unmeasured. Under a log shape the s-midpoint is at
D = 20,000; under saturation it is at D = 9,412, where D = 20,000 would land at s = 1.920 -- nearly
on top of the D = 80,000 anchor, making the fit two-point again and driving the residual to zero
whether or not the model holds.

So `s` is measured first, cheaply, and the third D is chosen from the measurement.

DESIGN, following the same rules the CU measurement follows:

  * `s(D) = (acq+work at k=100, D) / (acq+work at k=100, D=5000)` -- the same definition the
    interleaved 2.15x came from, so this pre-pass and that figure are the same quantity.
  * **D is INTERLEAVED within each rep**, shuffled, because a sequential D block is exactly the
    design that produced the 1.65x this replaced. `measure_held_fraction` takes one `--d` per
    invocation, so six calls to it would be six sequential blocks; hence this script.
  * `f = 0` throughout -- parent only, no fires. `m = 0` is the whole measurement.
  * Tables rebuilt and VACUUM ANALYZEd between points, outside the timed loop.
  * Id sets pre-sampled outside the timed loop.

LEVERAGE PRECONDITION, pre-registered before this ran: with the chosen middle point,

    min(s2 - s1, s3 - s2)  >=  0.30 * (s3 - s1)

If no candidate satisfies it, the three-point residual test is NOT performed, and the decomposition
is reported as a two-point fit with no test rather than disguised behind a near-zero residual.
"""
import argparse
import os
import random
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2

import run_lock
from workload_client import DEFAULT_DSN

SETUP = """
DROP TABLE IF EXISTS d_scaling;
CREATE TABLE d_scaling (d INT, rep INT, k INT, n INT, acq_us FLOAT,
                        work_us FLOAT, p50_us FLOAT, p95_us FLOAT);

CREATE OR REPLACE PROCEDURE measure_parent_point(
    p_d INT, p_rep INT, p_k INT, p_n INT, p_pool INT, p_seed FLOAT, p_warm INT
) AS $$
DECLARE
    v_flat BIGINT[] := '{}'; v_ids BIGINT[];
    i INT; j INT;
    t0 TIMESTAMPTZ; t1 TIMESTAMPTZ; t2 TIMESTAMPTZ;
    s_acq FLOAT := 0; s_work FLOAT := 0; cnt INT := 0; lat FLOAT[];
BEGIN
    PERFORM setseed(p_seed);
    lat := array_fill(0.0::FLOAT, ARRAY[p_n]);
    FOR j IN 1 .. p_pool LOOP
        v_flat := v_flat || ARRAY(SELECT g FROM generate_series(1, p_d) g ORDER BY random() LIMIT p_k);
    END LOOP;
    FOR i IN 1 .. p_n LOOP
        j := ((i - 1) % p_pool);
        v_ids := v_flat[j * p_k + 1 : (j + 1) * p_k];
        t0 := clock_timestamp();
        PERFORM try_acquire_all_retry(v_ids);
        t1 := clock_timestamp();
        UPDATE model_a SET v = v + 1 WHERE id = ANY(v_ids);
        COMMIT;
        t2 := clock_timestamp();
        IF i > p_warm THEN
            s_acq  := s_acq  + EXTRACT(EPOCH FROM (t1 - t0)) * 1e6;
            s_work := s_work + EXTRACT(EPOCH FROM (t2 - t1)) * 1e6;
            cnt := cnt + 1;
            lat[cnt] := EXTRACT(EPOCH FROM (t2 - t0)) * 1e6;
        END IF;
    END LOOP;
    INSERT INTO d_scaling
    SELECT p_d, p_rep, p_k, cnt, s_acq / cnt, s_work / cnt,
           percentile_cont(0.5)  WITHIN GROUP (ORDER BY u),
           percentile_cont(0.95) WITHIN GROUP (ORDER BY u)
      FROM unnest(lat[1:cnt]) u;
END;
$$ LANGUAGE plpgsql;
"""


def rebuild(cur, d):
    """Repopulate at size d. Outside every timed loop; f=0 so nothing emits."""
    cur.execute("TRUNCATE model_a, model_b")
    cur.execute("INSERT INTO model_a (id) SELECT generate_series(1, %s)", (d,))
    cur.execute("INSERT INTO model_b (id) SELECT generate_series(1, %s)", (d,))
    for t in ("model_a", "model_b"):
        cur.execute("ALTER TABLE %s SET (autovacuum_enabled = off)" % t)
    cur.execute("VACUUM (ANALYZE) model_a, model_b")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--k", type=int, default=100)
    ap.add_argument("--d-values", default="5000,8000,12000,20000,26000,80000")
    ap.add_argument("--n", type=int, default=400)
    ap.add_argument("--warmup", type=int, default=100)
    ap.add_argument("--pool", type=int, default=48)
    ap.add_argument("--reps", type=int, default=4)
    ap.add_argument("--anchor", type=int, default=5000)
    args = ap.parse_args()

    ds = [int(x) for x in args.d_values.split(",")]
    conn = psycopg2.connect(args.dsn)
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute(SETUP)
    print("D values %s, k=%d, %d reps, D INTERLEAVED within each rep\n" % (ds, args.k, args.reps))

    rng = random.Random(4242)
    for rep in range(args.reps):
        order = list(ds)
        rng.shuffle(order)
        for d in order:
            rebuild(cur, d)
            cur.execute("CALL measure_parent_point(%s,%s,%s,%s,%s,%s,%s)",
                        (d, rep, args.k, args.n, args.pool, (0.17 + 0.09 * rep) % 1.0, args.warmup))
        print("  rep %d done (order %s)" % (rep, order))

    cur.execute("SELECT d, rep, acq_us, work_us FROM d_scaling")
    rows = cur.fetchall()
    conn.close()

    def med(d):
        return statistics.median([r[2] + r[3] for r in rows if r[0] == d])

    base = med(args.anchor)
    print("\n%8s %14s %10s" % ("D", "acq+work us", "s(D)"))
    svals = {}
    for d in ds:
        v = med(d)
        svals[d] = v / base
        print("%8d %14.1f %10.4f" % (d, v, svals[d]))

    lo, hi = min(ds), max(ds)
    s1, s3 = svals[lo], svals[hi]
    target = (s1 + s3) / 2
    print("\ns(%d) = %.4f, s(%d) = %.4f  ->  the third point wants s ~ %.4f"
          % (lo, s1, hi, s3, target))

    mids = [d for d in ds if d not in (lo, hi)]
    print("\n%8s %10s %12s %14s %10s" % ("D", "s(D)", "gap to lo", "gap to hi", "min/span"))
    best, best_frac = None, -1.0
    for d in mids:
        s2 = svals[d]
        g1, g2 = s2 - s1, s3 - s2
        frac = min(g1, g2) / (s3 - s1)
        flag = "  <-- best" if frac > best_frac else ""
        if frac > best_frac:
            best, best_frac = d, frac
        print("%8d %10.4f %12.4f %14.4f %9.3f%s" % (d, s2, g1, g2, frac, flag))

    # The precondition is evaluated WITH ITS OWN UNCERTAINTY. The first version compared
    # medians and printed PASS -- while the per-rep spread on s was comparable to the whole
    # endpoint span, so every candidate was a coin flip (47-50% of bootstrap draws). Reading
    # a threshold off point estimates whose dispersion swamps the differences is exactly the
    # failure this pre-pass exists to catch, committed by the pre-pass itself.
    import random as _r
    rng2 = _r.Random(9)
    reps_l = sorted({x[1] for x in rows})
    per = {d: {rp: [x[2] + x[3] for x in rows if x[0] == d and x[1] == rp][0]
               for rp in reps_l} for d in ds}
    boot = {d: [] for d in mids}
    for _ in range(4000):
        draw = [rng2.choice(reps_l) for _ in reps_l]
        sv = {d: statistics.median([per[d][r] / per[lo][r] for r in draw]) for d in ds}
        span_b = sv[hi] - sv[lo]
        for d in mids:
            boot[d].append(min(sv[d] - sv[lo], sv[hi] - sv[d]) / span_b
                           if span_b > 0 else float("nan"))

    print("\nLEVERAGE PRECONDITION: min gap >= 0.30 * span, WITH its bootstrap interval")
    print("%8s %11s %24s %16s" % ("D", "min/span", "95% interval", "draws clearing"))
    any_pass = False
    for d in mids:
        v = sorted(x for x in boot[d] if x == x)
        frac = sum(1 for x in v if x >= 0.30) / len(v)
        print("%8d %11.3f   [%7.3f, %7.3f] %13.0f%%"
              % (d, statistics.median(v), v[int(.025 * len(v))],
                 v[int(.975 * len(v))], frac * 100))
        # Clears only if the precondition holds in the great majority of draws, not merely
        # at the median. 0.95 matches the convention the residual test uses.
        if frac >= 0.95:
            any_pass = True
    if any_pass:
        print("\n  => the three-point residual test IS performable.")
    else:
        print("\n  => NO candidate clears the precondition once its dispersion is included.")
        print("     The residual test is NOT performed. The decomposition is reported as a")
        print("     two-point fit with no test, per the pre-registered rule.")
    return 0


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
