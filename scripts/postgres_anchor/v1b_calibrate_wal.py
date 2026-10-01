"""Measure o, w_a and w_b for the c_wal secondary (PREREG Amendments 5g(c), 5i(b), 5j(e)).

WHY. Amendment 5g(c) reads a fire's write cost from `W(f)/W(0) = 1 + 0.5*f*c_wal*kw/(kw+o)`.
Two quantities in that expression are not free:

  o    the fixed per-transaction WAL overhead -- commit record, and anything not tied to a row.
       It does NOT appear as an intercept (the normalisation pins f=0 at exactly 1), so a free
       intercept cannot identify it; it appears in the SLOPE, biasing c_wal DOWNWARD. It has to
       be measured.
  w_b/w_a  the structural null. A fire updates model_b, a parent row updates model_a, and if the
       two differ in WAL records per update then c_wal's null is w_b/w_a rather than 1.

HOW, and why each half is measured the way it is.

  w_a and o come from the HARNESS path -- v1b_run_batch itself at k=1 and k=100 -- because the
  campaign's transaction carries the procedure's commit and advisory preclaim, which a bare
  UPDATE does not. Differencing two k values cancels o:  W(k) = k*w_a + o.

  w_b comes from single-row `UPDATE model_b SET v = v + 1 WHERE id = ...` statements, which is
  EXACTLY what the trigger body does, batched 1 and 100 per transaction and differenced the same
  way. There is no harness path that writes model_b without the trigger, so this is the closest
  equivalent shape rather than an arbitrary statement.

  Both are run at f=0, so no trigger fires and model_b is untouched by the model_a measurement.

C17: this MEASURES the anchor's own indexes, fillfactor, HOT rates and trigger, so it runs under
run_lock against the anchor -- NOT in a separate database, which would not reproduce them.

CATALOG NOTE, checked rather than assumed: model_a and model_b carry identical index structure
(the primary key on id and nothing else). `emits` and `v` are both unindexed, so both updates are
HOT-eligible and neither adds index-insert records. The expectation is therefore w_b/w_a near 1 --
but it is measured, because "near 1" is what the calibration exists to test.
"""
import argparse
import os
import statistics
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import psycopg2

import run_lock
from v1b_run import IO_COUNTER_NAMES, connect, io_counters, io_delta
from workload_client import DEFAULT_DSN, setup_v1

D, K = 80_000, 100
POOL = 65


def wal_delta(cur, fn, repeats):
    """WAL counters consumed by `fn` run `repeats` times, per repeat."""
    before = io_counters(cur)
    fn(repeats)
    after = io_counters(cur)
    d = io_delta(before, after)
    return {k: (v / repeats if v is not None else None) for k, v in d.items()}


def bare_model_b(conn, k, repeats):
    """`repeats` transactions, each doing k single-row updates -- the trigger's exact shape."""
    def go(n):
        with conn.cursor() as cur:
            for i in range(n):
                cur.execute("BEGIN")
                for j in range(k):
                    rid = 1 + ((i * k + j) * 7919) % D
                    cur.execute("UPDATE model_b SET v = v + 1 WHERE id = %s", (rid,))
                cur.execute("COMMIT")
    return go


def harness(conn, k, repeats, tag):
    """`repeats` transactions through v1b_run_batch itself, at f=0 so nothing fires."""
    def go(n):
        with conn.cursor() as cur:
            cur.execute("DELETE FROM v1b_batch_result WHERE tag = %s", (tag,))
            cur.execute("SELECT now(), now() + make_interval(secs => 600)")
            w0, w1 = cur.fetchone()
            cur.execute("CALL v1b_run_batch(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                        (tag, 0, 0, k, D, POOL, 0.37, w0, w1, n, False))
    return go


def hot_rates(cur):
    cur.execute("""SELECT relname, n_tup_upd, n_tup_hot_upd
                   FROM pg_stat_user_tables WHERE relname IN ('model_a','model_b')""")
    return {r[0]: (r[1], r[2]) for r in cur.fetchall()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--repeats", type=int, default=200)
    args = ap.parse_args()

    print("V1b WAL CALIBRATION  (Amendments 5g(c), 5i(b), 5j(e))")
    print("  D=%d  k=%d  repeats=%d  f=0 throughout (no fires)\n" % (D, K, args.repeats))
    setup_v1(args.dsn, D, 0.0, seed=987654321)

    conn = connect(args.dsn)
    conn.autocommit = True
    cur = conn.cursor()
    hot0 = hot_rates(cur)

    out = {}
    for label, maker in (("harness", lambda k, t: harness(conn, k, args.repeats, t)),
                         ("model_b", lambda k, t: bare_model_b(conn, k, args.repeats))):
        for k in (1, K):
            cur.execute("CHECKPOINT")          # equalise full-page-write exposure per block
            time.sleep(0.2)
            d = wal_delta(cur, maker(k, "cal_%s_%d" % (label, k)), args.repeats)
            out[(label, k)] = d
            print("  %-8s k=%-4d  wal_records/txn %9.1f   wal_bytes/txn %11.1f"
                  % (label, k, d.get("io_wal_records") or 0, d.get("io_wal_bytes") or 0))

    hot1 = hot_rates(cur)
    print()
    for metric in ("io_wal_records", "io_wal_bytes"):
        wa = (out[("harness", K)][metric] - out[("harness", 1)][metric]) / (K - 1)
        oa = out[("harness", 1)][metric] - wa
        wb = (out[("model_b", K)][metric] - out[("model_b", 1)][metric]) / (K - 1)
        ob = out[("model_b", 1)][metric] - wb
        print("  %s" % metric)
        print("    w_a (model_a row, harness path) ... %10.3f" % wa)
        print("    w_b (model_b row, trigger shape) .. %10.3f" % wb)
        print("    o   (harness per-transaction) ..... %10.3f" % oa)
        print("    o   (bare per-transaction) ........ %10.3f" % ob)
        if wa:
            print("    STRUCTURAL NULL c_wal = w_b/w_a ... %10.4f" % (wb / wa))
            print("    slope attenuation k*w_a/(k*w_a+o) . %10.4f  (c_wal biased DOWN by this)"
                  % (K * wa / (K * wa + oa)) if (K * wa + oa) else "")
        print()

    print("  HOT update rates (n_tup_hot_upd / n_tup_upd) over the calibration:")
    for t in ("model_a", "model_b"):
        u = hot1[t][0] - hot0[t][0]
        h = hot1[t][1] - hot0[t][1]
        print("    %-8s %8d updates, %8d HOT  (%.1f%%)" % (t, u, h, 100.0 * h / u if u else 0.0))
    conn.close()
    return 0


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
