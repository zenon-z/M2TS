"""TGS static-locking validation - P1 parity harness: probe correctness gate.

Must pass before EITHER stage of the P1 data collection runs. Mirrors the verify_savepoint_release.py
precedent this repo already set: the schema's guarantees are verified empirically, not assumed from
the documentation or from a plan dump.

Two families of assertion, both against ground truth so a violation is decisive rather than
suggestive:

  RELEASE   - probe_attempt_once() releases a failed attempt's partial set immediately and
              server-side, and repeated failures do not accumulate session-lock reference counts.
              (Session-level advisory locks are counted, not boolean - taking the same id twice in
              one session requires two unlocks. pg_advisory_unlock_all() clears the whole count, but
              only if it is actually reached on every failure path.)

  CAPTURE   - h_before never includes locks taken by its OWN statement. This is what the LATERAL in
              probe_attempt_once() exists to guarantee; see schema.sql's comment on why a plain
              CROSS JOIN or a MATERIALIZED CTE would be weaker. Reading EXPLAIN would only describe
              one invocation, so the guarantee is tested behaviourally instead.

Run:  python scripts/postgres_anchor/verify_probe_capture.py
Exits nonzero on any failure.
"""
from __future__ import annotations

import argparse
import random
import sys
import threading
import time

import psycopg2

from workload_client import DEFAULT_DSN


class Failure(Exception):
    pass


def _probe(cur, ids: list[int]) -> tuple[int, int, bool]:
    cur.execute("SELECT h_before, granted, success FROM probe_attempt_once(%s)", (ids,))
    return cur.fetchone()


def _own_advisory_locks(cur) -> int:
    cur.execute("SELECT count(*) FROM pg_locks "
                "WHERE locktype = 'advisory' AND pid = pg_backend_pid()")
    return cur.fetchone()[0]


def _connect(dsn: str):
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    return conn


def _pin(conn, ids: list[int]) -> None:
    """Hold `ids` as session-level advisory locks on this connection until _release()."""
    with conn.cursor() as cur:
        for i in ids:
            cur.execute("SELECT pg_advisory_lock(%s)", (i,))


def _release(conn) -> None:
    with conn.cursor() as cur:
        cur.execute("SELECT pg_advisory_unlock_all()")


# ---------------------------------------------------------------------------------------------
# RELEASE assertions
# ---------------------------------------------------------------------------------------------

def assert_release_on_failure(dsn: str, k: int = 5, trials: int = 50) -> None:
    """A failed probe must leave this backend holding nothing, every time, with no accumulation
    across repeated failures on overlapping ids."""
    helper = _connect(dsn)
    probe_conn = _connect(dsn)
    try:
        _pin(helper, [1])  # guarantees every probe below conflicts
        with probe_conn.cursor() as cur:
            for trial in range(trials):
                ids = sorted({1} | set(random.sample(range(2, 100), k - 1)))
                h, granted, success = _probe(cur, ids)
                if success:
                    raise Failure(
                        f"trial {trial}: probe succeeded on ids overlapping a pinned lock "
                        f"(id 1 held by the helper connection) - the helper's lock was not seen"
                    )
                held = _own_advisory_locks(cur)
                if held != 0:
                    raise Failure(
                        f"trial {trial}: failed probe left {held} advisory lock(s) held by this "
                        f"backend; pg_advisory_unlock_all() did not run or did not cover them. "
                        f"A failed attempt holding locks across the retry sleep inflates H exactly "
                        f"during contention."
                    )
    finally:
        _release(helper)
        helper.close()
        probe_conn.close()
    print(f"  PASS  release-on-failure: {trials} failed probes, 0 locks retained each time")


# ---------------------------------------------------------------------------------------------
# CAPTURE assertions
# ---------------------------------------------------------------------------------------------

def assert_quiescent_reads_zero(dsn: str, k: int = 25, trials: int = 200) -> None:
    """THE decisive assertion. One worker, nothing else running: the statement takes up to k locks
    itself, so if the count interleaved with its own try-locks a lone worker would report
    h_before > 0.

    Two failure modes, opposite fixes - disambiguated here so the wrong one isn't chased:
      * h_before == the PREVIOUS attempt's granted  -> release lag (the prior unlock not yet visible
        to this read). Session locks release synchronously on the same backend, so this should not
        occur; if it does, the CTE/LATERAL is not the problem.
      * any other nonzero value                     -> self-counting: the LATERAL's ordering
        dependency is not holding.
    """
    conn = _connect(dsn)
    prev_granted = None
    try:
        with conn.cursor() as cur:
            if _own_advisory_locks(cur) != 0:
                raise Failure("backend already holds advisory locks before the quiescent test")
            for trial in range(trials):
                ids = sorted(random.sample(range(1, 5001), k))
                h, granted, success = _probe(cur, ids)
                if h != 0:
                    kind = ("RELEASE LAG (equals the previous attempt's granted)"
                            if prev_granted is not None and h == prev_granted
                            else "SELF-COUNTING (LATERAL ordering not holding)")
                    raise Failure(
                        f"trial {trial}: quiescent h_before={h}, expected 0. "
                        f"prev_granted={prev_granted} -> {kind}"
                    )
                if not success:
                    raise Failure(f"trial {trial}: probe failed on a quiescent server "
                                  f"(granted={granted}/{k})")
                prev_granted = granted
                cur.execute("SELECT pg_advisory_unlock_all()")
    finally:
        conn.close()
    print(f"  PASS  quiescent-reads-zero: {trials} probes, h_before == 0 every time")


def assert_pinned_count_exact(dsn: str, m: int = 40, k: int = 25, trials: int = 100) -> None:
    """A helper connection pins exactly m known ids, disjoint from the probe's draw pool. h_before
    must equal m on every attempt - catching self-counting (would read > m) and a stale or partial
    count (would read < m) in the same assertion."""
    helper = _connect(dsn)
    conn = _connect(dsn)
    pinned = list(range(9001, 9001 + m))
    try:
        _pin(helper, pinned)
        with conn.cursor() as cur:
            for trial in range(trials):
                ids = sorted(random.sample(range(1, 5001), k))  # disjoint from 9001..
                h, granted, success = _probe(cur, ids)
                if h != m:
                    kind = "self-counting (> m)" if h > m else "stale or partial count (< m)"
                    raise Failure(
                        f"trial {trial}: h_before={h}, expected exactly {m} pinned by the helper "
                        f"-> {kind}"
                    )
                if not success:
                    raise Failure(f"trial {trial}: probe failed against a disjoint pinned set "
                                  f"(granted={granted}/{k})")
                cur.execute("SELECT pg_advisory_unlock_all()")
    finally:
        _release(helper)
        helper.close()
        conn.close()
    print(f"  PASS  pinned-count-exact: {trials} probes, h_before == {m} every time")


def assert_bounded_under_concurrency(dsn: str, w: int = 32, k: int = 8, pool: int = 40,
                                      duration_s: float = 6.0, hold_s: float = 0.02) -> None:
    """The assertion that keeps holding under concurrency, which the two above do not exercise.

    Every worker draws from the same small id pool, so contention is near-total and h_before spans
    its whole range. Held-by-others is bounded by (W-1)*k at any instant - each of the other W-1
    backends holds at most k, including the transient partial set a failing probe holds for the
    duration of one statement. Exceeding that bound proves the statement counted locks it took
    itself, under exactly the concurrency the real pass runs at.
    """
    bound = (w - 1) * k
    violations: list[tuple[int, int]] = []
    lock = threading.Lock()
    barrier = threading.Barrier(w)

    def worker(seed: int) -> None:
        conn = _connect(dsn)
        rng = random.Random(seed)
        barrier.wait()
        t0 = time.monotonic()
        try:
            with conn.cursor() as cur:
                while time.monotonic() - t0 < duration_s:
                    ids = sorted(rng.sample(range(1, pool + 1), k))
                    h, granted, success = _probe(cur, ids)
                    if h > bound:
                        with lock:
                            violations.append((h, granted))
                    if success:
                        time.sleep(hold_s)
                        cur.execute("SELECT pg_advisory_unlock_all()")
                    else:
                        time.sleep(rng.expovariate(1.0 / hold_s))
                cur.execute("SELECT pg_advisory_unlock_all()")
        finally:
            conn.close()

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(w)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    if violations:
        worst = max(v[0] for v in violations)
        raise Failure(
            f"{len(violations)} probe(s) reported h_before above the (W-1)*k = {bound} bound "
            f"(worst {worst}) at W={w}, k={k}, pool={pool}. Held-by-others cannot exceed that, so "
            f"the statement counted locks it took itself."
        )
    print(f"  PASS  bounded-under-concurrency: W={w} k={k} pool={pool} for {duration_s}s, "
          f"h_before <= {bound} throughout")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dsn", default=DEFAULT_DSN)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()
    random.seed(args.seed)

    checks = [
        ("RELEASE", assert_release_on_failure),
        ("CAPTURE", assert_quiescent_reads_zero),
        ("CAPTURE", assert_pinned_count_exact),
        ("CAPTURE", assert_bounded_under_concurrency),
    ]
    failed = 0
    for family, check in checks:
        print(f"[{family}] {check.__name__}")
        try:
            check(args.dsn)
        except Failure as exc:
            failed += 1
            print(f"  FAIL  {exc}")

    print()
    if failed:
        print(f"{failed} of {len(checks)} probe assertions FAILED - do not run either P1 stage "
              f"until these pass.")
        return 1
    print(f"All {len(checks)} probe assertions passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
