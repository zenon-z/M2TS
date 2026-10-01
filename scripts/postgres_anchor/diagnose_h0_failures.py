"""Who holds the lock that blocks an attempt reporting h_before = 0?

Both stages measure P(blocked | h_before = 0) at ~5-7%, which is structurally impossible if
h_before is what it claims to be: nothing can block when no advisory lock is held. Stage A (capture
co-located, herd intact) and Stage B (herd broken by jitter) give the SAME value, so neither the
statement boundary nor the retry herd explains it, and the arithmetic rules out a sub-millisecond
race - at W=2 the effect would need the other worker to acquire inside the window in ~57% of
attempts.

So stop inferring and look. This reproduces the condition at low W and, on every h_before=0 failure,
immediately dumps the advisory rows in pg_locks WITH the holding pid, alongside this backend's own
pid. Three outcomes, three different bugs:

  * the conflicting lock is held by ANOTHER pid    -> genuine race; h_before was correct when read
  * the conflicting lock is held by THIS pid       -> our own lock leaked across attempts; the
                                                      release path is broken, not the capture
  * pg_locks shows NOTHING advisory                -> pg_try_advisory_lock returned false without a
                                                      corresponding visible lock (counter/refcount
                                                      semantics, not a race)
"""
from __future__ import annotations

import argparse
import collections
import random
import threading
import time

import psycopg2

from workload_client import DEFAULT_DSN


def worker(dsn, d, k, hold_s, duration_s, barrier, seed, findings, lock, jitter):
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    rng = random.Random(seed)
    barrier.wait()
    t0 = time.monotonic()
    with conn.cursor() as cur:
        cur.execute("SELECT pg_backend_pid()")
        my_pid = cur.fetchone()[0]
        while time.monotonic() - t0 < duration_s:
            ids = sorted(rng.sample(range(1, d + 1), k))
            cur.execute("SELECT h_before, granted, success FROM probe_attempt_once(%s)", (ids,))
            h, granted, ok = cur.fetchone()
            if h == 0 and not ok:
                # Look IMMEDIATELY - the point is to catch the holder while it is still there.
                cur.execute("SELECT pid, objid, classid, objsubid FROM pg_locks "
                            "WHERE locktype = 'advisory'")
                rows = cur.fetchall()
                own = sum(1 for r in rows if r[0] == my_pid)
                other = sum(1 for r in rows if r[0] != my_pid)
                with lock:
                    findings.append((granted, own, other, len(rows)))
            cur.execute("SELECT pg_advisory_unlock_all()")
            if ok:
                time.sleep(hold_s)
                cur.execute("SELECT pg_advisory_unlock_all()")
            elif jitter:
                time.sleep(rng.expovariate(1.0 / hold_s))
        cur.execute("SELECT pg_advisory_unlock_all()")
    conn.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dsn", default=DEFAULT_DSN)
    parser.add_argument("--d", type=int, default=5000)
    parser.add_argument("--k", type=int, default=25)
    parser.add_argument("--w", type=int, default=2)
    parser.add_argument("--hold-ms", type=float, default=50.0)
    parser.add_argument("--duration-s", type=float, default=45.0)
    parser.add_argument("--jitter", action="store_true", default=True)
    args = parser.parse_args()

    findings: list[tuple[int, int, int, int]] = []
    lock = threading.Lock()
    barrier = threading.Barrier(args.w)
    threads = [
        threading.Thread(target=worker,
                         args=(args.dsn, args.d, args.k, args.hold_ms / 1000.0, args.duration_s,
                               barrier, i, findings, lock, args.jitter))
        for i in range(args.w)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    print(f"D={args.d} k={args.k} W={args.w}: {len(findings)} failures at h_before=0\n")
    if not findings:
        print("none observed - the effect did not reproduce at this configuration")
        return
    hist = collections.Counter((own > 0, other > 0) for _g, own, other, _n in findings)
    print(f"{'own locks present':>20} {'other locks present':>21} {'count':>8}")
    for (own_present, other_present), count in sorted(hist.items()):
        print(f"{str(own_present):>20} {str(other_present):>21} {count:>8}")
    print()
    g = collections.Counter(f[0] for f in findings)
    print(f"granted distribution (k={args.k}): {sorted(g.items())}")
    own_counts = collections.Counter(f[1] for f in findings)
    other_counts = collections.Counter(f[2] for f in findings)
    print(f"own advisory rows at failure:   {sorted(own_counts.items())}")
    print(f"other advisory rows at failure: {sorted(other_counts.items())}")
    print()
    if hist.get((True, False), 0) + hist.get((True, True), 0) > len(findings) * 0.5:
        print("VERDICT: this backend's OWN advisory locks are present at the moment of a "
              "h_before=0 failure.\n  The release path is broken, not the capture. h_before "
              "correctly reported 0 held by\n  OTHERS, but pg_try_advisory_lock then collided with "
              "a lock this session already held.")
    elif hist.get((False, True), 0) > len(findings) * 0.5:
        print("VERDICT: another backend holds the conflicting lock -> genuine check-then-act race.")
    else:
        print("VERDICT: no advisory lock visible at all at the moment of failure -> "
              "pg_try_advisory_lock\n  returned false with no corresponding visible lock.")


if __name__ == "__main__":
    main()
