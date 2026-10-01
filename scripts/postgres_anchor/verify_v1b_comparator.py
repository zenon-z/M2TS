"""V1b Phase 1 verification -- run BEFORE any measurement, per the plan's verify-by-removal rule.

The comparator is only a comparator if it runs V1's workload. Four assertions, each with ground
truth so a violation is decisive rather than suggestive:

  1. FIRE-COUNT PARITY (criterion 1). Fires per commit must match the client-side arm at the same
     (k, f). This is the gate: if it fails, the procedure is not running V1's workload and no
     performance number taken from it means anything. Counted MARGINALLY -- sum(v) on model_b
     differenced across the batch -- never by conditioning on per-attempt state (L5), which is the
     rule the P1 parity arm broke when it ended up measuring 1 - 1/retries.

  2. DISTINCTNESS, asserted and NEGATIVE-TESTED. The procedure raises on a non-distinct id set;
     an assertion that has never fired is an assertion of unknown value, so it is also forced to
     fire (k > d makes the sample short) and the raise is confirmed.

  3. PRECLAIM UNCHANGED. Advisory locks held must peak at <= W*k. A single transaction holding
     more than k would mean the all-or-nothing preclaim is not what it was in V1.

  4. DEADLOCKS REMAIN 0. Sorted acquisition plus all-or-nothing preclaim makes them structurally
     impossible; a nonzero count means the structure changed.

Run: python scripts/postgres_anchor/verify_v1b_comparator.py
"""
import os
import statistics
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2

import run_lock
from workload_client import DEFAULT_DSN, setup_v1

D = 5000
K = 100          # the operating point: B2 binds at k>=50 and k=50 is noise-width from it
F = 0.25
POOL = 64
FAILURES = []


def check(name, ok, detail=""):
    print("  %-56s %s%s" % (name, "PASS" if ok else "FAIL", ("  " + detail) if detail else ""))
    if not ok:
        FAILURES.append(name)


def connect():
    c = psycopg2.connect(DEFAULT_DSN)
    c.autocommit = True
    return c


def model_b_total(cur):
    cur.execute("SELECT coalesce(sum(v), 0) FROM model_b")
    return int(cur.fetchone()[0])


def deadlocks(cur):
    cur.execute("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
    return int(cur.fetchone()[0])


def main():
    conn = connect()
    cur = conn.cursor()
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "v1b_schema.sql")) as fh:
        cur.execute(fh.read())
    print("v1b_schema.sql applied\n")

    emitting = setup_v1(DEFAULT_DSN, D, F, seed=12345)
    f_actual = emitting / D
    print("")

    print("1. FIRE-COUNT PARITY -- fires per commit against the hypergeometric expectation")
    # Sampling k of D without replacement, `emitting` of which emit: E[fires] = k * emitting/D
    # EXACTLY (hypergeometric mean), zero free parameters. This is the same prediction the
    # client-side arm was scored against, so agreement here is criterion 1 for this driver.
    expected = K * emitting / D
    cur.execute("DELETE FROM v1b_batch_result WHERE tag = 'verify'")
    dl_before = deadlocks(cur)
    b0 = model_b_total(cur)
    cur.execute("SELECT clock_timestamp(), clock_timestamp() + make_interval(secs => 6)")
    w0, w1 = cur.fetchone()
    cur.execute("CALL v1b_run_batch('verify', 0, 0, %s, %s, %s, %s, %s, %s)",
                (K, D, POOL, 0.5, w0, w1))
    b1 = model_b_total(cur)
    cur.execute("SELECT counted, lat_sum_us/nullif(counted,0), retries_total "
                "FROM v1b_batch_result WHERE tag = 'verify'")
    counted, lat_us, retries = cur.fetchone()
    fires_per_commit = (b1 - b0) / counted
    dev = abs(fires_per_commit - expected) / expected
    print("     %d commits, %.1f us/txn, %d retries" % (counted, lat_us, retries))
    print("     fires/commit = %.4f   expected = %.4f   deviation = %.2f%%"
          % (fires_per_commit, expected, dev * 100))
    # The pool is 64 id sets cycled ~16 times, so the realised fire count is a fixed property of
    # the pool rather than a fresh draw per transaction: the tolerance covers pool sampling error
    # (SD of the hypergeometric mean over 64 sets), not per-transaction noise.
    sd = (K * f_actual * (1 - f_actual) * (D - K) / (D - 1)) ** 0.5
    tol = 3 * sd / (POOL ** 0.5) / expected
    check("fires/commit matches k*f within pool sampling error",
          dev <= max(tol, 0.02), "tol %.2f%%" % (max(tol, 0.02) * 100))

    print("\n2. DISTINCTNESS -- asserted in the procedure, and forced to fire")
    cur.execute("SELECT count(*) FROM v1b_batch_result WHERE tag = 'verify'")
    check("a valid batch does not raise", cur.fetchone()[0] == 1)
    raised = ""
    try:
        cur.execute("CALL v1b_run_batch('neg', 0, 0, %s, %s, %s, %s, %s, %s)",
                    (D + 10, D, 2, 0.5, w0, w1))      # k > d: the sample cannot be full-length
        conn.rollback()
    except psycopg2.Error as exc:
        raised = str(exc).strip().splitlines()[0]
    check("k > d raises rather than measuring a short id set", bool(raised), raised[:60])
    conn.close()

    print("\n3. PRECLAIM UNCHANGED -- advisory locks peak at <= W*k")
    peak = {"h": 0}
    stop = threading.Event()

    def sampler():
        c = connect()
        with c.cursor() as cc:
            while not stop.is_set():
                cc.execute("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")
                peak["h"] = max(peak["h"], int(cc.fetchone()[0]))
                time.sleep(0.002)
        c.close()

    W = 4
    conn = connect(); cur = conn.cursor()
    cur.execute("SELECT clock_timestamp(), clock_timestamp() + make_interval(secs => 6)")
    ww0, ww1 = cur.fetchone()          # ONE interval, shared by all four workers
    def worker(wid):
        c = connect()
        with c.cursor() as cc:
            cc.execute("CALL v1b_run_batch('verify_w', 0, %s, %s, %s, %s, %s, %s, %s)",
                       (wid, K, D, POOL, 0.3 + 0.05 * wid, ww0, ww1))
        c.close()

    t = threading.Thread(target=sampler, daemon=True)
    t.start()
    threads = [threading.Thread(target=worker, args=(i,)) for i in range(W)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    stop.set()
    t.join(timeout=5)
    print("     peak advisory locks = %d, bound W*k = %d" % (peak["h"], W * K))
    check("peak_h <= W*k", 0 < peak["h"] <= W * K)

    print("\n4. DEADLOCKS")
    conn = connect()
    cur = conn.cursor()
    dl_after = deadlocks(cur)
    print("     deadlocks before = %d, after = %d" % (dl_before, dl_after))
    check("no deadlocks introduced", dl_after == dl_before)

    cur.execute("SELECT worker, counted, lat_sum_us/nullif(counted,0), win_start, win_end "
                "FROM v1b_batch_result WHERE tag = 'verify_w' ORDER BY worker")
    rows = cur.fetchall()
    if rows:
        print("\n     W=%d batch latencies: %s us"
              % (W, ", ".join("%.0f" % r[2] for r in rows)))
    conn.close()

    print("\n5. ONE INTERVAL -- every worker gated on the SAME two values")
    if rows:
        starts = set(r[3] for r in rows)
        ends = set(r[4] for r in rows)
        check("all %d workers share one win_start" % len(rows), len(starts) == 1,
              "%d distinct" % len(starts))
        check("all %d workers share one win_end" % len(rows), len(ends) == 1,
              "%d distinct" % len(ends))
        check("every worker counted transactions in the window",
              all(r[1] > 0 for r in rows))
    else:
        check("worker rows present", False)

    print()
    if FAILURES:
        print("%d ASSERTION(S) FAILED: %s" % (len(FAILURES), ", ".join(FAILURES)))
        return 1
    print("all V1b Phase 1 assertions pass -- the comparator runs V1's workload")
    return 0


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
