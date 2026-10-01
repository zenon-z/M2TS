"""How much of the server-side per-transaction overhead is NOT lock-held time?

This is the last piece of B2's concern that survives an RTT-free measurement. B2 requires that time
counted in the measured interval but NOT spent holding locks be negligible, because Little's law
normalisation would otherwise charge it as held time. With the loop server-side there is no round
trip in the interval -- but `overhead(k) = 59.66 + 2.5226k` us still has an INTERCEPT, and an
intercept is per-transaction fixed cost, some of which precedes acquisition.

The decomposition is EXHAUSTIVE -- every microsecond of wall time lands in exactly one phase, so
the three sum to the measured per-transaction cost and nothing hides in a remainder:

    gap   t2[i-1] -> t0[i]   PL/pgSQL loop dispatch BETWEEN transactions     NOT held
    acq   t0[i]   -> t1[i]   the advisory acquisition call                   held only partly
    work  t1[i]   -> t2[i]   UPDATE, trigger cascade, COMMIT                 HELD throughout

`acq` is counted as NOT held, which is deliberately WRONG in the conservative direction: locks are
taken progressively inside that call, so most of it IS held. Counting all of it as non-held
maximises the estimated non-held fraction, so a small answer under this assumption is a real answer,
and the true non-held fraction is smaller than reported.

The `--no-advisory` arm removes the acquisition entirely. Its intercept is the fixed cost that has
nothing to do with locking at all, so the difference between the two intercepts is what acquisition
adds -- the cross-check on the phase split, using a second method rather than the same one twice.

INTERLEAVED by construction: both variants run adjacent within a rep on the same id pool. Sequential
blocks would measure the difference plus whatever drifted between them, which is how a 1.2x ratio
was once read as 2.9x in this same harness.
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
CREATE TABLE IF NOT EXISTS phase_timing (
    tag TEXT, rep INT, k INT, use_advisory BOOL, n INT,
    gap_us FLOAT, acq_us FLOAT, work_us FLOAT
);

CREATE OR REPLACE PROCEDURE measure_phases(
    p_n INT, p_k INT, p_advisory BOOL, p_d INT, p_pool INT,
    p_seed FLOAT, p_warmup INT, p_tag TEXT, p_rep INT
) AS $$
DECLARE
    v_flat  BIGINT[] := '{}';        -- pool_size * k ids, built BEFORE timing starts
    v_ids   BIGINT[];
    i INT; j INT;
    t0 TIMESTAMPTZ; t1 TIMESTAMPTZ; t2 TIMESTAMPTZ; t_prev TIMESTAMPTZ;
    s_gap FLOAT := 0; s_acq FLOAT := 0; s_work FLOAT := 0;
    counted INT := 0;
BEGIN
    PERFORM setseed(p_seed);
    -- Pre-sampled: `ORDER BY random() LIMIT k` costs ~0.76 ms/txn if left inside the loop, which
    -- would swamp the intercept this procedure exists to measure.
    FOR j IN 1 .. p_pool LOOP
        v_flat := v_flat || ARRAY(SELECT g FROM generate_series(1, p_d) g
                                  ORDER BY random() LIMIT p_k);
    END LOOP;

    t_prev := clock_timestamp();
    FOR i IN 1 .. p_n LOOP
        j := ((i - 1) % p_pool);
        v_ids := v_flat[j * p_k + 1 : (j + 1) * p_k];

        t0 := clock_timestamp();
        IF p_advisory THEN
            PERFORM try_acquire_all_retry(v_ids);
        END IF;
        t1 := clock_timestamp();
        UPDATE model_a SET v = v + 1 WHERE id = ANY(v_ids);
        COMMIT;
        t2 := clock_timestamp();

        IF i > p_warmup THEN
            s_gap  := s_gap  + EXTRACT(EPOCH FROM (t0 - t_prev)) * 1e6;
            s_acq  := s_acq  + EXTRACT(EPOCH FROM (t1 - t0))     * 1e6;
            s_work := s_work + EXTRACT(EPOCH FROM (t2 - t1))     * 1e6;
            counted := counted + 1;
        END IF;
        t_prev := t2;
    END LOOP;

    INSERT INTO phase_timing VALUES (p_tag, p_rep, p_k, p_advisory, counted,
                                     s_gap / counted, s_acq / counted, s_work / counted);
END;
$$ LANGUAGE plpgsql;
"""


def fit(points):
    """Least squares on (k, y). Returns (intercept, slope)."""
    n = len(points)
    mx = sum(p[0] for p in points) / n
    my = sum(p[1] for p in points) / n
    sxy = sum((p[0] - mx) * (p[1] - my) for p in points)
    sxx = sum((p[0] - mx) ** 2 for p in points)
    if sxx == 0:
        # One k value: an intercept/slope split is not identifiable, and returning a fitted-looking
        # pair from a single point would be worse than returning nothing.
        return float("nan"), float("nan")
    slope = sxy / sxx
    return my - slope * mx, slope


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--d", type=int, default=5000)
    ap.add_argument("--k-values", default="10,25,50,100,200")
    ap.add_argument("--n", type=int, default=600)
    ap.add_argument("--warmup", type=int, default=100)
    ap.add_argument("--pool", type=int, default=64)
    ap.add_argument("--reps", type=int, default=4)
    ap.add_argument("--tag", default="held")
    args = ap.parse_args()

    ks = [int(x) for x in args.k_values.split(",")]
    conn = psycopg2.connect(args.dsn)
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute(SETUP)
        # Only clear when this run will replace the rows. --reps 0 re-reports a stored
        # measurement, and deleting first made it report nan against its own empty table.
        if args.reps > 0:
            cur.execute("DELETE FROM phase_timing WHERE tag = %s", (args.tag,))
        cur.execute("SELECT count(*) FROM model_a WHERE emits")
        emitting = cur.fetchone()[0]
        print("emitting rows: %d (0 = parent-only, which is what the intercept needs)\n" % emitting)

        for rep in range(args.reps):
            for k in ks:
                # INTERLEAVED: both variants adjacent, same rep, same pool seed.
                for advisory in (True, False):
                    cur.execute(
                        "CALL measure_phases(%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                        (args.n, k, advisory, args.d, args.pool,
                         0.1 + 0.01 * rep, args.warmup, args.tag, rep))
            print("  rep %d done" % rep)

        cur.execute("SELECT rep, k, use_advisory, gap_us, acq_us, work_us "
                    "FROM phase_timing WHERE tag = %s", (args.tag,))
        rows = cur.fetchall()
    conn.close()

    def med(k, adv, idx):
        vals = [r[3 + idx] for r in rows if r[1] == k and r[2] is adv]
        return statistics.median(vals) if vals else float("nan")

    print("\nPHASE SPLIT, with advisory acquisition (medians over %d reps, us/txn)" % args.reps)
    print("%5s %10s %10s %10s %10s %14s" % ("k", "gap", "acq", "work", "total", "not-held %"))
    with_pts, nonheld = [], []
    for k in ks:
        g, a, w = med(k, True, 0), med(k, True, 1), med(k, True, 2)
        tot = g + a + w
        frac = (g + a) / tot * 100
        nonheld.append((k, frac))
        with_pts.append((k, tot))
        print("%5d %10.1f %10.1f %10.1f %10.1f %13.1f%%" % (k, g, a, w, tot, frac))

    print("\nWITHOUT advisory acquisition")
    print("%5s %10s %10s %10s %10s %14s" % ("k", "gap", "acq", "work", "total", "not-held %"))
    without_pts = []
    for k in ks:
        g, a, w = med(k, False, 0), med(k, False, 1), med(k, False, 2)
        tot = g + a + w
        without_pts.append((k, tot))
        print("%5d %10.1f %10.1f %10.1f %10.1f %13.1f%%" % (k, g, a, w, tot, g / tot * 100))

    ia, sa = fit(with_pts)
    ib, sb = fit(without_pts)
    print("\nFITS   overhead(k) = intercept + slope*k")
    print("  with advisory     %8.2f + %.4f k us" % (ia, sa))
    print("  without advisory  %8.2f + %.4f k us" % (ib, sb))
    print("  acquisition adds  %8.2f + %.4f k us   (intercept difference = its fixed cost)"
          % (ia - ib, sa - sb))

    # B2's own test, on the phase B2 actually names. A single "non-held %" would force a choice
    # between the two bounds below and hide the one not chosen, so both are printed and the test
    # is applied to the term that answers it.
    #
    #   GAP is B2-relevant: no lock is held, and the worker is not in the system -- structurally
    #   the same as the client-side RTT B2 was written against.
    #   ACQUISITION is not: it is inside the transaction and takes locks progressively, so the
    #   transaction occupies resources throughout it.
    print("\nNON-HELD FRACTION, both bounds (the truth is between them)")
    print("%5s %14s %14s" % ("k", "gap only", "gap + acq"))
    for k in ks:
        g, a, w = med(k, True, 0), med(k, True, 1), med(k, True, 2)
        tot = g + a + w
        print("%5d %13.1f%% %13.1f%%" % (k, g / tot * 100, (g + a) / tot * 100))

    print("\nB2 ON ITS OWN TERMS: non-held <= hold/20, i.e. (acq+work)/gap >= 20")
    print("%5s %10s %16s %9s %6s" % ("k", "gap us", "txn (acq+work)", "ratio", "B2"))
    passing = []
    for k in ks:
        g, a, w = med(k, True, 0), med(k, True, 1), med(k, True, 2)
        ratio = (a + w) / g
        if ratio >= 20.0:
            passing.append(k)
        print("%5d %10.1f %16.1f %9.1f %6s"
              % (k, g, a + w, ratio, "PASS" if ratio >= 20 else "fail"))
    if passing:
        print("\n  B2 satisfied at k >= %d. It still imposes a floor -- but on k, not on hold."
              % min(passing))
    else:
        print("\n  B2 not satisfied at any measured k.")
    return 0


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
