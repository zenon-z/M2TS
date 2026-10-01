"""Behavioural test for run_lock -- the three states, each with its failure NEGATIVE-TESTED.

The guard being tested exists because a killed run left state behind that looked identical to a
live one. So it is not enough to check that the lock excludes a second copy; the test has to show
that it does NOT exclude when the owner is gone, and that it DOES exclude when the owner is alive.
A lock that always aborts and a lock that never aborts both pass a one-sided test.

Run: python scripts/postgres_anchor/verify_run_lock.py
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_lock

HERE = os.path.dirname(os.path.abspath(__file__))
FAILURES = []


def check(name, ok, detail=""):
    print("  %-58s %s%s" % (name, "PASS" if ok else "FAIL", (" -- " + detail) if detail else ""))
    if not ok:
        FAILURES.append(name)


def child(*args):
    """A second process attempting the lock, the way a second sweep would."""
    code = ("import sys, os; sys.path.insert(0, %r); import run_lock; "
            "run_lock.acquire(%s); print('ACQUIRED'); run_lock.release()"
            % (HERE, "True" if "force" in args else "False"))
    return subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=120)


def owner_path():
    return os.path.join(run_lock.LOCK_DIR, run_lock.OWNER_FILE)


def cleanup():
    run_lock._remove_lock_dir()


def main():
    cleanup()
    print("1. free -> acquires, and records an owner that identifies THIS process")
    run_lock.acquire()
    owner = run_lock._read_owner()
    check("lock directory created", os.path.isdir(run_lock.LOCK_DIR))
    check("owner pid is this process", owner.get("pid") == os.getpid(),
          "recorded %s, actual %s" % (owner.get("pid"), os.getpid()))
    check("start token recorded (PID alone is not an owner)", bool(owner.get("start_token")))

    print("2. HELD -> a second process is REFUSED while the owner is alive")
    r = child()
    check("second acquire exits nonzero", r.returncode != 0, "rc=%d" % r.returncode)
    check("it did not acquire", "ACQUIRED" not in r.stdout)
    check("reason names the owner pid", str(os.getpid()) in (r.stdout + r.stderr))
    check("reason states WHY it matters", "server-wide" in (r.stdout + r.stderr))

    print("3. STALE by dead PID -> broken automatically, and announced")
    owner["pid"] = _dead_pid()
    with open(owner_path(), "w") as fh:
        json.dump(owner, fh)
    r = child()
    check("second acquire succeeds", r.returncode == 0 and "ACQUIRED" in r.stdout,
          r.stderr.strip()[:80])
    check("breaking the lock is announced, not silent", "STALE" in r.stdout,
          r.stdout.strip()[:80])
    check("reason given is the dead process", "process is gone" in r.stdout)

    print("4. STALE by PID RECYCLING -> a live PID with the wrong start token is not the owner")
    cleanup()
    run_lock.acquire()
    owner = run_lock._read_owner()
    owner["start_token"] = "0"          # this PID is alive, but it is not who took the lock
    with open(owner_path(), "w") as fh:
        json.dump(owner, fh)
    r = child()
    check("second acquire succeeds", r.returncode == 0 and "ACQUIRED" in r.stdout,
          r.stderr.strip()[:80])
    check("reason given is PID recycling", "recycled" in r.stdout, r.stdout.strip()[:80])

    print("5. UNPROBEABLE -> refuses rather than guessing, and says how to proceed")
    cleanup()
    os.mkdir(run_lock.LOCK_DIR)          # no owner record: pre-module lock, or a crash mid-write
    r = child()
    check("second acquire is refused", r.returncode != 0 and "ACQUIRED" not in r.stdout)
    check("tells the operator about --force-unlock", "--force-unlock" in (r.stdout + r.stderr))
    r = child("force")
    check("--force-unlock does break it", r.returncode == 0 and "ACQUIRED" in r.stdout,
          r.stderr.strip()[:80])

    print("6. release() never deletes someone else's lock")
    cleanup()
    run_lock.acquire()
    owner = run_lock._read_owner()
    owner["pid"] = os.getpid() + 1       # ownership changed under us
    with open(owner_path(), "w") as fh:
        json.dump(owner, fh)
    run_lock.release()
    check("lock left in place", os.path.isdir(run_lock.LOCK_DIR))
    cleanup()

    print("7. the context manager releases on an EXCEPTION path")
    try:
        with run_lock.held():
            raise RuntimeError("simulated sweep crash")
    except RuntimeError:
        pass
    check("lock released after exception", not os.path.exists(run_lock.LOCK_DIR))

    print("8. os.kill is not used as a liveness probe (it TERMINATES on Windows)")
    # Parsed, not grepped: run_lock's docstring NAMES os.kill to explain why it is avoided, so a
    # substring search reports a violation on the very comment warning against it. The first
    # version of this assertion did exactly that.
    import ast
    with open(os.path.join(HERE, "run_lock.py")) as fh:
        tree = ast.parse(fh.read())
    calls = [n for n in ast.walk(tree)
             if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
             and n.func.attr == "kill"
             and isinstance(n.func.value, ast.Name) and n.func.value.id == "os"]
    check("no os.kill CALL in run_lock.py", not calls,
          "found at line(s) %s" % [n.lineno for n in calls] if calls else "")

    cleanup()
    print()
    if FAILURES:
        print("%d ASSERTION(S) FAILED: %s" % (len(FAILURES), ", ".join(FAILURES)))
        return 1
    print("all run_lock assertions pass")
    return 0


def _dead_pid():
    """A PID that is certainly not running: spawn a process, wait for it, reuse its PID."""
    p = subprocess.Popen([sys.executable, "-c", "pass"])
    p.wait()
    return p.pid


if __name__ == "__main__":
    sys.exit(main())
