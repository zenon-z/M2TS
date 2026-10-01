"""Single-instance lock for anything that touches the anchor database.

Two sweeps overlapped on 2026-09-02 and corrupted eight minutes of data. H is counted SERVER-WIDE
(`pg_locks` has no session filter), so a second sweep does not add load that averages out -- it adds
its own held locks to the other run's measured H. It also produced a spurious finding ("H plateaus
at 75%") that was reported before the cause was known. `preflight.py` catches an intruder that is
ALREADY connected; this catches the second copy at launch, before it connects.

WHY A PLAIN LOCK DIRECTORY IS NOT ENOUGH, which is the whole point of this module:

`os.mkdir` is atomic, so it will not hand the lock to two processes. But a directory left behind by
a run that was killed -- and the run that caused the incident WAS killed, twice, by a `pkill -f`
that silently did nothing -- is indistinguishable from a live one. A bare mkdir lock then fails in
whichever direction its author guessed:

  * abort on any existing directory  ->  a stale lock blocks every future run until noticed by
    hand, and the obvious workaround is to delete it without looking, which is the guard being
    disabled rather than satisfied;
  * ignore an existing directory     ->  no lock at all.

So the lock records its OWNER, and the owner is checked for liveness. Three states, not two:
HELD (owner alive) aborts, STALE (owner gone) is broken with a loud notice, and free proceeds.

PID ALONE IS NOT AN OWNER. Operating systems recycle PIDs, so a dead sweep's PID can belong to
something unrelated by the time the next run starts -- which would read as HELD forever, the exact
failure the liveness check exists to prevent. The owner is therefore `(pid, process start time)`;
a recycled PID carries a later start time and is correctly read as stale.

Deliberately NOT used: `os.kill(pid, 0)`. That is the POSIX idiom, and on Windows CPython maps
`os.kill` to TerminateProcess for any signal other than CTRL_C_EVENT/CTRL_BREAK_EVENT -- so the
"liveness probe" would kill the process it was asking about. This harness runs on Windows.
"""
import json
import os
import subprocess
import sys
import time

LOCK_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".sweep.lock")
OWNER_FILE = "owner.json"

_PS_PROBE = ("$p = Get-Process -Id {pid} -ErrorAction SilentlyContinue; "
             "if ($p) {{ $p.StartTime.Ticks }} else {{ Write-Output GONE }}")


def _process_start_token(pid):
    """(alive, start_token) for `pid`. `start_token` identifies the PROCESS, not the PID slot, so a
    recycled PID cannot impersonate the previous owner. Returns (False, None) when the process is
    gone.

    On an unexpected probe FAILURE it returns (True, None) -- alive. Refusing to declare a lock
    stale on the strength of a probe that did not work is the safe direction: the cost of being
    wrong that way is one manual `--force-unlock`, against a corrupted run the other way."""
    try:
        if sys.platform == "win32":
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command",
                 _PS_PROBE.format(pid=pid)],
                capture_output=True, text=True, timeout=30)
            token = out.stdout.strip()
            if token == "GONE":
                return (False, None)
            if not token:
                return (True, None)
            return (True, token)
        if os.path.isdir("/proc"):
            try:
                with open("/proc/%d/stat" % pid) as fh:
                    stat = fh.read()
            except FileNotFoundError:
                return (False, None)
            # field 22 (starttime), counted from the ')' that closes a comm name which may
            # itself contain spaces or parentheses -- hence rsplit rather than split.
            return (True, stat.rsplit(")", 1)[1].split()[19])
        out = subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)],
                             capture_output=True, text=True, timeout=30)
        if out.returncode != 0 or not out.stdout.strip():
            return (False, None)
        return (True, out.stdout.strip())
    except Exception as exc:
        print("run_lock: liveness probe failed (%s); treating owner as ALIVE" % exc)
        return (True, None)


def _read_owner():
    try:
        with open(os.path.join(LOCK_DIR, OWNER_FILE)) as fh:
            return json.load(fh)
    except Exception:
        return None


def _describe(owner):
    if not owner:
        return "unknown (owner record missing or unreadable)"
    return "pid %s, started %s, cmd: %s" % (owner.get("pid"), owner.get("started_at"),
                                            owner.get("argv"))


def _owner_is_live(owner):
    pid = (owner or {}).get("pid")
    if pid is None:
        return (None, "no owner recorded")
    alive, token = _process_start_token(pid)
    if not alive:
        return (False, "process is gone")
    recorded = (owner or {}).get("start_token")
    if token is not None and recorded is not None and token != recorded:
        return (False, "PID was recycled (start time differs)")
    return (True, "owner alive")


def _write_owner():
    _, token = _process_start_token(os.getpid())
    with open(os.path.join(LOCK_DIR, OWNER_FILE), "w") as fh:
        json.dump({"pid": os.getpid(), "start_token": token,
                   "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                   "argv": " ".join(sys.argv)}, fh, indent=2)


def _remove_lock_dir():
    if not os.path.exists(LOCK_DIR):
        return
    try:
        path = os.path.join(LOCK_DIR, OWNER_FILE)
        if os.path.exists(path):
            os.remove(path)
        os.rmdir(LOCK_DIR)
    except OSError as exc:
        # Loud: a lock that cannot be removed will refuse every later run, and the operator
        # needs to know that now rather than at the next launch.
        print("run_lock: could not remove %s (%s)" % (LOCK_DIR, exc))


def _held_by_our_parent(owner):
    """True when the lock belongs to the process that spawned us and told us so.

    A driver that holds the lock for a whole sweep and runs each cell as a CHILD PROCESS would
    otherwise deadlock against itself: the parent owns the lock, the child asks for it, and the
    child aborts. `run_java.py` already solved this for the JVM by exporting TSSIM_RUN_LOCK_PID,
    which RunLockGuard reads to tell "my own wrapper's lock" from a foreign one. This is the Python
    half of the same convention.

    Deliberately NOT a general re-entrancy escape: the child must have been told the pid explicitly
    by whoever holds the lock, and it must MATCH the recorded owner. An unset variable, a stale
    value, or any other process's lock all fall through to the normal abort.
    """
    declared = os.environ.get("TSSIM_RUN_LOCK_PID")
    if not declared or not owner:
        return False
    return str(owner.get("pid")) == str(declared)


def acquire(force=False):
    """Take the lock, or exit(1). A lock whose owner is gone is broken, loudly."""
    try:
        os.mkdir(LOCK_DIR)
    except FileExistsError:
        owner = _read_owner()
        if _held_by_our_parent(owner):
            print("run_lock: lock already held by our own parent (pid %s); proceeding without "
                  "re-acquiring." % owner.get("pid"))
            return False
        live, reason = _owner_is_live(owner)
        if force:
            print("run_lock: --force-unlock, breaking lock held by %s" % _describe(owner))
        elif live is None:
            # Either a lock predating this module, or a crash between mkdir and the owner
            # write. Unprobeable, so it is NOT broken automatically -- silently breaking a
            # lock we cannot identify is the same hole as having no lock.
            sys.exit("ABORT: lock directory %s exists with no owner record. If no run is "
                     "active, re-run with --force-unlock." % LOCK_DIR)
        elif live:
            sys.exit("ABORT: another run holds the lock -- %s.\nH is a server-wide count, so two "
                     "concurrent runs corrupt BOTH, not just the later one." % _describe(owner))
        else:
            print("run_lock: breaking STALE lock (%s), previously held by %s"
                  % (reason, _describe(owner)))
        _remove_lock_dir()
        os.mkdir(LOCK_DIR)
    _write_owner()
    return True


def release():
    """Drop the lock, but ONLY if we still own it -- never delete another run's lock."""
    owner = _read_owner()
    if _held_by_our_parent(owner):
        return          # we never acquired it; deleting our parent's lock would be worse
    if owner and owner.get("pid") not in (None, os.getpid()):
        print("run_lock: not releasing -- lock is now owned by pid %s" % owner.get("pid"))
        return
    _remove_lock_dir()


class held:
    """`with run_lock.held():` -- releases on every exit path, exceptions included."""

    def __init__(self, force=False):
        self.force = force

    def __enter__(self):
        acquire(self.force)
        return self

    def __exit__(self, *exc):
        release()
        return False


def _status():
    if not os.path.exists(LOCK_DIR):
        return "free", None
    owner = _read_owner()
    live, reason = _owner_is_live(owner)
    if live is None:
        return "UNKNOWN", owner
    return ("HELD" if live else "STALE (%s)" % reason), owner


if __name__ == "__main__":
    if "--status" in sys.argv:
        state, owner = _status()
        print(state if state == "free" else "%s -- %s" % (state, _describe(owner)))
    elif "--force-unlock" in sys.argv:
        _remove_lock_dir()
        print("lock removed")
    else:
        print(__doc__)
