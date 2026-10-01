"""Run a Java simulator command under the SAME host lock the PostgreSQL sweeps use.

WHY A WRAPPER AND NOT A JAVA-SIDE LOCK. `run_lock` already solves the hard parts, and they are
hard in non-obvious ways: the owner is `(pid, process start time)` rather than a bare PID because
operating systems recycle PIDs, and the liveness probe deliberately avoids `os.kill(pid, 0)`
because on Windows CPython maps that to TerminateProcess -- the probe would kill the process it
was asking about. A second implementation in Java would have to re-derive both, and would drift.
Reusing the module means there is exactly ONE lock, one owner format, and one probe.

WHY JAVA RUNS NEED THE LOCK AT ALL. The two arms share a host. A simulator run is CPU-saturating
and multi-threaded; a PostgreSQL campaign measures latency on that same machine, and its agreement
gate is calibrated on how that host behaves when nothing else is on it. A Java run overlapping a
campaign does not add noise that averages out -- it shifts the very quantity being measured, in
the arm-asymmetric way Amendment 5(e) describes. It also works the other way: the simulator's own
throughput figures, which the identity check reads to four decimal places, are not trustworthy
while a 64-worker sweep is hammering the same cores.

This was a live hazard, not a hypothetical: the PHASE_COMPLETED compile had to be deferred this
session purely because a simulator arm was still running against the classes it would overwrite,
and nothing would have stopped the two from colliding.

USAGE
    python scripts/run_java.py mvn -q exec:java -Dexec.mainClass=...
    python scripts/run_java.py --status
    python scripts/run_java.py --force-unlock

Everything after the first non-flag argument is passed through untouched.
"""
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "postgres_anchor"))
import run_lock


def main(argv):
    if "--status" in argv:
        state, owner = run_lock._status()
        print(state if state == "free" else "%s -- %s" % (state, run_lock._describe(owner)))
        return 0
    if "--force-unlock" in argv:
        run_lock._remove_lock_dir()
        print("lock removed")
        return 0

    force = "--force" in argv
    cmd = [a for a in argv if a != "--force"]
    if not cmd:
        print(__doc__)
        return 2

    # Resolve the executable the way a shell would. subprocess without a shell does NOT consult
    # PATHEXT on Windows, so `mvn` (which is really mvn.cmd) raises FileNotFoundError -- the
    # wrapper would refuse the single most common command it exists to run. shutil.which applies
    # PATHEXT, so `mvn`, `mvn.cmd` and an absolute path all work.
    resolved = shutil.which(cmd[0])
    if resolved is None:
        print("run_java: command not found on PATH: %s" % cmd[0])
        return 127
    cmd[0] = resolved

    with run_lock.held(force=force):
        print("run_java: lock held; running %s" % " ".join(cmd))
        try:
            # No shell. `check` is deliberately off: a nonzero exit from the Java command is the
            # command's result to report, not an exception to unwind through the lock release --
            # though `held` would release on that path too.
            env = dict(os.environ)
            # The guard inside the JVM reads this to tell "my own wrapper's lock" from a
            # foreign holder. Set to the lock OWNER's pid -- this process -- and inherited
            # through any intermediate Maven process down to the JVM.
            env["TSSIM_RUN_LOCK_PID"] = str(os.getpid())
            proc = subprocess.run(cmd, env=env)
        except FileNotFoundError:
            print("run_java: command not found: %s" % cmd[0])
            return 127
        except KeyboardInterrupt:
            # Explicit, so the reason the lock is being released is visible in the log. `held`
            # releases on this path regardless; this only makes it legible.
            print("run_java: interrupted -- releasing lock")
            return 130
        print("run_java: command exited %d; releasing lock" % proc.returncode)
        return proc.returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
