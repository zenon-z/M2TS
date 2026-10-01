"""The CU marginal -- microseconds of critical section per trigger fire -- by paired design.

METHOD, settled in the plan and reproduced here rather than re-derived:

  * `emits` is flagged ONCE at setup, on a RANDOM half of the ids, so the fire count does not
    correlate with which pages a transaction touches.
  * The fire count is controlled by POOL COMPOSITION: a transaction taking `m` ids from the
    emitting half and `k-m` from the other fires EXACTLY `m` times. The predictor is exact rather
    than Binomial(k, f), which removes the x-axis noise an f-sweep carries.
  * `m` is INTERLEAVED within a rep. Between-rep drift is what limited the original estimate to a
    44x interval, and sequential blocks are how a 1.2x ratio got read as 2.9x elsewhere in this
    harness. The slope is taken per rep, so drift cancels within it.
  * Id sets are PRE-SAMPLED outside the timed loop.

DEPARTURE, restated because it must not be reused as a workload comparison: this composition is NOT
V1's workload. V1 draws uniformly and its fire count is binomial. This is a COST measurement -- us
per fire -- and a deterministic fire count is the right instrument for one. Criterion 1 is
unaffected: it measures the uniform-draw arm.

VALIDATION: run at D=5000 first. The instrument must reproduce the established 11.10 us/fire before
its number at any other D means anything.

    *** STATUS: UNDETERMINED. Not shown defective, and not shown correct either. ***

It returned 26.02 us/fire against an established 11.10, and that was blamed on the script.
The environment had become 2.35x slower between the two measurements (verified by re-running
an identical configuration: 351.5 -> 824.9 us, server quiescent). 26.02 / 2.35 = 11.07
against 11.10 -- but that drift factor came from a DIFFERENT harness at a DIFFERENT k.
Adjusting by this harness's own m=0 baseline (130 -> 180 us, 1.38x) gives 26.57/1.38 = 19.3
instead. Two defensible adjustments, two different answers, so the instrument is neither
condemned nor cleared. Do not cite a CU marginal from it without a same-run baseline.

Use it ONLY with a same-session baseline: measure the parent at f=0 immediately before or
after, and report the CU marginal against that, never against a number from another run.
The diagnosis below is kept because both fixes it produced are worth having.

At D=5000, k=25 it returns 26.0-29.4 us/fire against an established 11.10. Two independent paths
put the true value near 10-11:

    Phase 0, paired design, run inline          11.10 us/fire
    composition check via v1b_run_batch         10.04 us/fire
        = (607.1 measured at k=100,f=0.25  -  351.5 parent at k=100,f=0) / 25.45 fires
    THIS SCRIPT                                 26.02 - 29.41 us/fire

so the script is the outlier, not the established value. The unambiguous tell: at the SAME fire
count this script reports more time with FEWER parent rows --

    v1b_run_batch   k=100, 25.45 fires ->  607 us
    this script     k= 25, 25.00 fires -> ~800-1000 us

and 75 fewer parent rows cannot cost 200-400 us more.

Two hypotheses were tested and both are dead. The m/elapsed-time confound was real and is fixed
(m is now shuffled per rep and both tables are vacuumed between points), but fixing it moved the
answer the wrong way, 23.66 -> 29.41. The model_b bloat-ramp hypothesis was tested by cutting n
4x, which cuts within-point churn from 6.0x to 1.5x of the table: the slope moved 12%, from 29.41
to 26.02, nowhere near 11. Bloat is not the driver.

The defect is not yet located. What is established is that it inflates the high-m points and
leaves m=0 roughly right (~150 us here against Phase 0's ~130), so it scales with the fire count
rather than being a constant offset.
"""
import argparse
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2

import run_lock
from workload_client import DEFAULT_DSN

SETUP = """
CREATE TABLE IF NOT EXISTS cu_marginal_result (
    tag TEXT, d INT, k INT, rep INT, m INT, n INT, med_us FLOAT, fires BIGINT
);

CREATE OR REPLACE PROCEDURE measure_cu_point(
    p_tag TEXT, p_d INT, p_k INT, p_rep INT, p_m INT,
    p_n INT, p_pool INT, p_seed FLOAT, p_warmup INT
) AS $$
DECLARE
    v_flat BIGINT[] := '{}';
    v_ids  BIGINT[];
    v_lat  FLOAT[];          -- pre-allocated below; concatenation inside the loop is O(n^2)
    i INT; j INT;
    t0 TIMESTAMPTZ; t2 TIMESTAMPTZ;
    b0 BIGINT; b1 BIGINT;
    counted INT := 0;
BEGIN
    PERFORM setseed(p_seed);
    v_lat := array_fill(0.0::FLOAT, ARRAY[p_n]);
    -- p_m ids drawn from the EMITTING half, p_k - p_m from the non-emitting half. Exactly p_m
    -- fires per transaction, by construction and verified below against the model_b delta.
    FOR j IN 1 .. p_pool LOOP
        v_ids := ARRAY(SELECT id FROM model_a WHERE emits ORDER BY random() LIMIT p_m)
              || ARRAY(SELECT id FROM model_a WHERE NOT emits ORDER BY random() LIMIT (p_k - p_m));
        IF coalesce(array_length(v_ids, 1), 0) <> p_k THEN
            RAISE EXCEPTION 'cu: pool % has % ids, expected % (is the emitting half too small?)',
                            j, coalesce(array_length(v_ids, 1), 0), p_k;
        END IF;
        v_flat := v_flat || v_ids;
    END LOOP;

    -- b0 is read AFTER warmup, below, so the fire count brackets exactly the transactions the
    -- latency does. Reading it here bracketed all p_n while n counted only the post-warmup ones,
    -- and the check dutifully reported a 1.20 ratio that was p_n/(p_n - p_warmup).
    FOR i IN 1 .. p_n LOOP
        IF i = p_warmup + 1 THEN
            SELECT coalesce(sum(v), 0) INTO b0 FROM model_b;
        END IF;
        j := ((i - 1) % p_pool);
        v_ids := v_flat[j * p_k + 1 : (j + 1) * p_k];
        t0 := clock_timestamp();
        PERFORM try_acquire_all_retry(v_ids);
        UPDATE model_a SET v = v + 1 WHERE id = ANY(v_ids);
        COMMIT;
        t2 := clock_timestamp();
        IF i > p_warmup THEN
            counted := counted + 1;
            v_lat[counted] := EXTRACT(EPOCH FROM (t2 - t0)) * 1e6;
        END IF;
    END LOOP;
    SELECT coalesce(sum(v), 0) INTO b1 FROM model_b;

    INSERT INTO cu_marginal_result
    SELECT p_tag, p_d, p_k, p_rep, p_m, counted,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY u), b1 - b0
      FROM unnest(v_lat[1:counted]) u;
END;
$$ LANGUAGE plpgsql;
"""


def setup_half_emitting(cur, d, seed):
    """emits on a RANDOM half, flagged once. Random rather than a contiguous range so `m` does not
    correlate with which heap pages the transaction touches."""
    cur.execute("TRUNCATE model_a, model_b")
    cur.execute("INSERT INTO model_a (id) SELECT generate_series(1, %s)", (d,))
    cur.execute("INSERT INTO model_b (id) SELECT generate_series(1, %s)", (d,))
    cur.execute("SELECT setseed(%s)", (seed,))
    cur.execute("UPDATE model_a SET emits = true WHERE id IN "
                "(SELECT id FROM model_a ORDER BY random() LIMIT %s)", (d // 2,))
    for t in ("model_a", "model_b"):
        cur.execute("ALTER TABLE %s SET (autovacuum_enabled = off)" % t)
    cur.execute("SELECT count(*) FROM model_a WHERE emits")
    return cur.fetchone()[0]


def slope(points):
    n = len(points)
    mx = sum(p[0] for p in points) / n
    my = sum(p[1] for p in points) / n
    sxx = sum((p[0] - mx) ** 2 for p in points)
    return sum((p[0] - mx) * (p[1] - my) for p in points) / sxx


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--d", type=int, default=5000)
    ap.add_argument("--k", type=int, default=100)
    ap.add_argument("--m-values", default="")
    ap.add_argument("--n", type=int, default=1200)
    ap.add_argument("--warmup", type=int, default=200)
    ap.add_argument("--pool", type=int, default=48)
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--tag", default="cu")
    args = ap.parse_args()

    ms = ([int(x) for x in args.m_values.split(",")] if args.m_values
          else [0, args.k // 5, 2 * args.k // 5, 3 * args.k // 5, 4 * args.k // 5, args.k])

    conn = psycopg2.connect(args.dsn)
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute(SETUP)
    cur.execute("DELETE FROM cu_marginal_result WHERE tag = %s", (args.tag,))
    emitting = setup_half_emitting(cur, args.d, 0.42)
    print("D=%d, %d emitting (half), k=%d, m in %s" % (args.d, emitting, args.k, ms))

    import random as _random
    order_rng = _random.Random(20260904)
    for rep in range(args.reps):
        # SHUFFLED per rep, not ascending. Every CU updates a model_b row with autovacuum off, so
        # bloat grows monotonically through a rep -- and a fixed ascending order makes m correlate
        # with elapsed time, which inflates the slope. Measured: ascending order returned 23.66
        # us/fire against an established 11.10. Same family as the sequential-blocks error.
        order = list(ms)
        order_rng.shuffle(order)
        for m in order:
            # VACUUM removes the confound at its source rather than only randomising around it.
            # Outside the timed loop, between points, exactly as the sweep's vacuum control does.
            cur.execute("VACUUM (ANALYZE) model_a, model_b")
            cur.execute("CALL measure_cu_point(%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                        (args.tag, args.d, args.k, rep, m, args.n, args.pool,
                         (0.11 + 0.07 * rep) % 1.0, args.warmup))
        print("  rep %d done (m order %s)" % (rep, order))

    cur.execute("SELECT rep, m, med_us, n, fires FROM cu_marginal_result "
                "WHERE tag = %s ORDER BY rep, m", (args.tag,))
    rows = cur.fetchall()
    conn.close()

    print("\n%5s %s %10s" % ("rep", "".join("%9d" % m for m in ms), "slope"))
    slopes = []
    for rep in range(args.reps):
        pts = sorted((m, med) for r, m, med, _, _ in rows if r == rep)
        s = slope(pts)
        slopes.append(s)
        print("%5d %s %10.2f" % (rep, "".join("%9.0f" % p[1] for p in pts), s))

    mean_s = statistics.mean(slopes)
    sd = statistics.stdev(slopes) if len(slopes) > 1 else 0.0
    se = sd / (len(slopes) ** 0.5)
    print("\nCU marginal = %.4f us/fire   95%% CI [%.4f, %.4f]   (SE %.4f, n=%d reps)"
          % (mean_s, mean_s - 1.96 * se, mean_s + 1.96 * se, se, len(slopes)))

    # The predictor is EXACT only if the pool composition really gives m fires per transaction.
    # Verified against the model_b delta rather than assumed.
    bad = [(r, m, n, fires) for r, m, _, n, fires in rows if fires != m * n]
    # fires and n now bracket the same transactions, so this is exact rather than proportional.
    print("fire-count check: %d of %d points have fires == m*n exactly%s"
          % (len(rows) - len(bad), len(rows),
             "" if not bad else "  MISMATCHES: %s" % bad[:3]))
    return 0 if not bad else 1


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
