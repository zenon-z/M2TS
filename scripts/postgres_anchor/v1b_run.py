"""V1b Phase 2 -- the measurement plumbing, with one interval and one clock.

THE INTERVAL is computed ONCE here, by a single `clock_timestamp()` query, and handed unchanged to
every consumer: each worker's `v1b_run_batch`, the lock sampler, and the fire sampler. No consumer
re-derives it. The three window defects in docs/LOCKING_PROTOCOL_CONFIG_INCIDENT.md all came from
per-site re-derivation, the last one a half-open interval in the fire sampler that quietly absorbed
the drain, and the fix each time was to stop computing the same thing twice.

THE CLOCK is the server's, everywhere. The lock sampler runs in this process but timestamps its
polls with `clock_timestamp()` returned by the poll query itself -- it is issuing a query anyway --
so there is no client origin to reconcile with the server's. That is deliberate: the fire sampler's
origin once drifted from the workers' and needed a barrier to fix; here the second clock simply
does not exist. Python time is used for nothing that enters a reported quantity.

Gates inherited unchanged: Little's identity, MIN_CYCLES >= 8, peak_h <= W*k, preflight isolation.
"""
import argparse
import os
import statistics
import subprocess
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2

import run_lock
from workload_client import DEFAULT_DSN, setup_v1

# Fixed at build time, not exposed as a flag: the coverage and fire-count-variance arguments
# both derive from it (6,500 of 80,000 row-slots = 8.1% at k=100; ~9% relative error on the
# fire-count sd from 65 draws). v1b_run_batch raises if it is lowered. Change it here, with
# the derivation, or not at all.
#
# ODD, and that is load-bearing, not cosmetic. Paired mode alternates arms on transaction
# parity while cycling id sets modulo POOL. With an EVEN pool, set j lands only on even
# positions or only on odd ones -- so every id set is permanently bound to one arm and the two
# arms never share a set, which destroys the pairing that is the whole point. An odd pool makes
# set j alternate parity each time it comes round, so every set is measured in BOTH arms.
# v1b_run_batch raises on an even pool in paired mode rather than trusting this comment.
# 65 is the smallest odd value clearing the pool >= 64 floor.
POOL = 65

LITTLE_TOL = 0.10
MIN_CYCLES = 8.0


def connect(dsn):
    c = psycopg2.connect(dsn)
    c.autocommit = True
    return c


def establish_window(cur, warmup_s, duration_s):
    """THE interval. One query, one clock, one pair of values. Everything else receives it."""
    cur.execute("SELECT clock_timestamp(), "
                "       clock_timestamp() + make_interval(secs => %s), "
                "       clock_timestamp() + make_interval(secs => %s)",
                (warmup_s, warmup_s + duration_s))
    now, win_start, win_end = cur.fetchone()
    return now, win_start, win_end


def lock_sampler(dsn, win_start, win_end, out, poll_s=0.01):
    """Polls pg_locks, timestamping each sample with the SERVER clock so the samples and the
    commit gate share an origin. Keeps only samples inside the same two values the workers use."""
    c = connect(dsn)
    with c.cursor() as cur:
        while True:
            cur.execute("SELECT clock_timestamp(), "
                        "count(*) FILTER (WHERE locktype = 'advisory') FROM pg_locks")
            t, h = cur.fetchone()
            if t > win_end:
                break
            if t >= win_start:
                out.append((t, int(h)))
            cur.execute("SELECT pg_sleep(%s)", (poll_s,))
    c.close()


def fire_sampler(dsn, win_start, win_end, out):
    """Two readings of sum(v) on model_b, at the window bounds and nowhere else. Marginal by
    construction -- never conditioned on per-attempt state (L5). Waits on the server clock."""
    c = connect(dsn)
    with c.cursor() as cur:
        cur.execute("SELECT * FROM v1b_read_model_b_at(%s)", (win_start,))
        out["open_at"], out["open"] = cur.fetchone()
        cur.execute("SELECT * FROM v1b_read_model_b_at(%s)", (win_end,))
        out["close_at"], out["close"] = cur.fetchone()
    c.close()


# PREREG Amendment 5(e). Snapshotted either side of the measurement window so each cell carries
# its own I/O deltas. The registered use: the agreement gate tripped on a cell whose TREATMENT arm
# (the one doing UPDATE model_b per fire, hence WAL) degraded 4x while its baseline barely moved.
# That is the signature of fire cost under I/O pressure -- a real PostgreSQL behaviour that
# concentrates at high f -- not of host noise. Without these counters the exclusion of such a cell
# is a purely statistical act; with them it is attributable.
#
# PostgreSQL 16: pg_stat_checkpointer does not exist (added in 17), so the checkpoint counters come
# from pg_stat_bgwriter. Both views are cluster-wide and monotonic, so only DELTAS are meaningful,
# and a concurrent client would contaminate them -- which is what the run lock is for.
IO_COUNTER_SQL = """
SELECT (SELECT checkpoints_timed      FROM pg_stat_bgwriter),
       (SELECT checkpoints_req        FROM pg_stat_bgwriter),
       (SELECT checkpoint_sync_time   FROM pg_stat_bgwriter),
       (SELECT buffers_checkpoint     FROM pg_stat_bgwriter),
       (SELECT buffers_backend_fsync  FROM pg_stat_bgwriter),
       (SELECT wal_records            FROM pg_stat_wal),
       (SELECT wal_bytes              FROM pg_stat_wal),
       (SELECT wal_sync               FROM pg_stat_wal)
"""
IO_COUNTER_NAMES = ("ckpt_timed", "ckpt_req", "ckpt_sync_ms", "buf_ckpt", "buf_backend_fsync",
                    "wal_records", "wal_bytes", "wal_sync")

# wal_sync_time is DELIBERATELY ABSENT. It requires track_wal_io_timing, which is off on this
# server, so it would ship a column of zeros that reads as "no sync time" when it means "not
# measured" -- the exact C15 failure. Enabling it is not the fix: track_io_timing adds overhead
# to every I/O and would perturb the latency this campaign measures, and changing server settings
# now would also break comparability with the pilot data the gate was calibrated on. The COUNT
# (wal_sync) is free and answers the registered question -- did this cell see unusual I/O -- so
# the count is kept and the timing dropped. Verified live: a 200k-row table with three full
# UPDATEs and a CHECKPOINT moved wal_records 2213060, wal_bytes 152172162, wal_sync 38,
# buf_ckpt 12597, ckpt_req 1 and ckpt_sync_ms 1.0, so six of the eight are demonstrated nonzero.
# ckpt_timed and buf_backend_fsync read zero in that probe but are structurally able to fire
# (a timed checkpoint, and a full fsync queue respectively); wal_sync_time was not.


def io_counters(cur):
    """One row of cluster-wide I/O counters, or None if the views are unavailable.

    Returns None rather than raising: a missing view must not void a campaign over a diagnostic.
    A None reaches the CSV as empty fields, which reads as "not measured" -- distinguishable from
    a measured zero, which C15 requires.
    """
    try:
        # Flush this backend's pending stats and defeat snapshot caching BEFORE reading.
        # Without it the counters are stale: the WAL calibration's fast k=1 block measured
        # EXACTLY 0.0 records per transaction and its WAL then appeared in the k=100 window,
        # which produced a NEGATIVE fitted per-transaction overhead -- physically impossible,
        # and the tell that the reading was wrong rather than merely noisy. pg_stat_wal is
        # accumulated backend-locally and reported on a delay, and stats_fetch_consistency
        # defaults to caching a snapshot, so two reads can return the same numbers.
        cur.execute("SET LOCAL stats_fetch_consistency = 'none'")
        cur.execute("SELECT pg_stat_force_next_flush()")
        cur.execute(IO_COUNTER_SQL)
        return cur.fetchone()
    except Exception:
        cur.connection.rollback()
        return None


def io_delta(before, after):
    """Per-counter difference, keyed by name. Empty dict when either snapshot is missing."""
    if before is None or after is None:
        return {}
    out = {}
    for i, name in enumerate(IO_COUNTER_NAMES):
        b, a = before[i], after[i]
        out["io_" + name] = (a - b) if (b is not None and a is not None) else None
    return out


# PREREG Amendment 5d(b). The agreement gate's threshold was calibrated on however this host
# behaved during the plain2 pilot, and the rep-level trip rate p is a property of the host, not of
# PostgreSQL. Without a record of host state, a trip can only be ATTRIBUTED to background load by
# assumption, and "the pilot's host resembled the campaign's" cannot be checked at all.
#
# Taken OUTSIDE the measurement window -- before the window opens -- because the probe itself costs
# about 2 s and must not land inside the interval whose latency is being measured.
#
# psutil is deliberately not used: it is not installed, and adding a dependency midway through a
# registered campaign changes the environment the earlier data was collected in. PowerShell is
# already this project's probe mechanism (see run_lock, which documents why it avoids os.kill).
_HOST_PROBE = ("$c = (Get-CimInstance Win32_Processor | "
               "Measure-Object -Property LoadPercentage -Average).Average; "
               "$j = @(Get-Process java -ErrorAction SilentlyContinue | "
               "Select-Object -ExpandProperty Id); "
               "Write-Output \"$c|$($j -join ',')\"")


def host_snapshot(timeout=20):
    """Coarse host load. Returns None-valued fields rather than raising.

    A failed probe must never void a cell: this is a diagnostic, and None reaches the CSV as an
    empty field, which reads as "not measured" and is distinguishable from a measured zero -- the
    distinction C15 exists to preserve.
    """
    empty = {"host_cpu_pct": None, "host_jvm_count": None, "host_jvm_pids": None}
    try:
        if sys.platform == "win32":
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", _HOST_PROBE],
                capture_output=True, text=True, timeout=timeout)
            text = out.stdout.strip()
            if "|" not in text:
                return empty
            cpu, pids = text.split("|", 1)
            pid_list = [p for p in pids.split(",") if p.strip()]
            return {"host_cpu_pct": int(cpu) if cpu.strip().isdigit() else None,
                    "host_jvm_count": len(pid_list),
                    "host_jvm_pids": " ".join(pid_list)}
        with open("/proc/loadavg") as fh:
            one = float(fh.read().split()[0])
        return {"host_cpu_pct": round(100.0 * one / (os.cpu_count() or 1)),
                "host_jvm_count": None, "host_jvm_pids": None}
    except Exception as exc:
        # NOT silent. A bare `return empty` here hid a NameError (subprocess was never imported)
        # behind a field that reads as "not measured" -- a broken diagnostic is indistinguishable
        # from an unavailable one unless the failure is announced. Printed once per occurrence.
        print("host_snapshot: probe failed (%r); recording as not measured" % (exc,))
        return empty


def run_cell(dsn, tag, rep, w, k, d, pool, warmup_s, duration_s, seed_base=0.1,
             paired=False):
    conn = connect(dsn)
    cur = conn.cursor()
    cur.execute("DELETE FROM v1b_batch_result WHERE tag = %s AND rep = %s", (tag, rep))
    _, win_start, win_end = establish_window(cur, warmup_s, duration_s)

    io_before = io_counters(cur)
    locks, fires = [], {}
    threads = [threading.Thread(target=lock_sampler, args=(dsn, win_start, win_end, locks)),
               threading.Thread(target=fire_sampler, args=(dsn, win_start, win_end, fires))]

    errors = []

    def worker(wid):
        # A thread that raises dies silently and run_cell proceeds with whatever rows exist.
        # Collected and re-raised after the join so a failed worker cannot be mistaken
        # for a slow one.
        try:
            _worker_body(wid)
        except Exception as exc:
            errors.append((wid, repr(exc)))

    def _worker_body(wid):
        c = connect(dsn)
        with c.cursor() as cc:
            cc.execute("CALL v1b_run_batch(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                       (tag, rep, wid, k, d, pool,
                        # The pool seed depends on (rep, worker) but NOT on f. Two
                        # consequences, both wanted:
                        #   WITHIN a rep, the f and f=0 cells share id sets, so R_pg's
                        #   numerator and denominator touch the same physical rows and
                        #   per-id-set latency offsets largely cancel in the ratio.
                        #   ACROSS reps the pool RESAMPLES. It previously did not -- rep
                        #   was a parameter of run_cell but never reached the seed -- so
                        #   every rep drew the identical pool. That made the pool a fixed
                        #   effect contributing ZERO variance across reps, which would
                        #   have made the aggregation rule's bootstrap-over-reps
                        #   understate uncertainty by omitting a real source of it.
                        (seed_base + 0.013 * wid + 0.101 * rep) % 1.0,
                        win_start, win_end, 1000000, paired))
        c.close()

    threads += [threading.Thread(target=worker, args=(i,)) for i in range(w)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    if errors:
        raise RuntimeError("worker(s) failed: %s" % errors[:3])

    io_after = io_counters(cur)
    cur.execute("SELECT worker, attempted, counted, lat_sum_us, lat_min_us, lat_max_us, "
                "       win_start, win_end, retries_total, lat_p50_us, lat_truncated, "
                "       lat_p25_us, lat_p75_us, counted_treat, counted_base, "
                "       p50_treat_us, p50_base_us, "
                "       p50_treat_h1, p50_treat_h2, p50_base_h1, p50_base_h2, "
                "       lat_sum_treat, lat_sum_base "
                "FROM v1b_batch_result WHERE tag = %s AND rep = %s ORDER BY worker", (tag, rep))
    rows = cur.fetchall()
    conn.close()

    # THE denominator: the window's own length, not the wall time of the slowest worker and not
    # warmup+duration recomputed here. Read back from a row so it is the same value the workers
    # gated on rather than a second computation that happens to agree.
    length_s = (rows[0][7] - rows[0][6]).total_seconds()
    counted = sum(r[2] for r in rows)
    lat_us = sum(r[3] for r in rows) / counted if counted else float("nan")
    return {
        # Splatted in rather than restructuring the return: the deltas are plain scalars that
        # belong beside the other per-cell measurements, and an empty dict (views unavailable)
        # simply contributes nothing.
        **io_delta(io_before, io_after),
        "rows": rows, "locks": locks, "fires": fires,
        "win_start": win_start, "win_end": win_end, "length_s": length_s,
        "counted": counted, "attempted": sum(r[1] for r in rows),
        "throughput": counted / length_s if length_s else float("nan"),
        "mean_latency_s": lat_us / 1e6 if counted else float("nan"),
        # Little needs the MEAN; the aggregation rule needs the MEDIAN. Both kept.
        "median_latency_s": (statistics.median([r[9] for r in rows if r[9] is not None])
                             / 1e6) if counted else float("nan"),
        "lat_truncated": any(r[10] for r in rows),
        # IQR per worker -> SE(median) ~ 1.253 * (IQR/1.349) / sqrt(n). The pilot needs a
        # FLOOR on R_pg's interval; across-rep dispersion needs reps and one rep has none.
        "iqr_us": statistics.median([r[12] - r[11] for r in rows if r[11] is not None]),
        "retries": sum(r[8] for r in rows),
        "peak_h": max((h for _, h in locks), default=0),
        "mean_h": statistics.mean([h for _, h in locks]) if locks else float("nan"),
        # Paired mode: R is formed INSIDE the worker, from two arms alternating in one batch on
        # one table state, then aggregated across workers as a median of ratios. This is the
        # same median-of-ratios rule the rep-level design used, moved down one level -- the
        # move the pilot forced when a per-cell f=0 baseline turned out to be minutes of machine
        # drift away from its numerator rather than a comparable measurement.
        **_paired_summary(rows, paired),
    }


def _paired_summary(rows, paired):
    """Per-arm medians and the within-worker ratio. All NaN when not paired, so the caller's
    columns exist either way and a paired number can never be silently read off an unpaired run."""
    if not paired:
        nan = float("nan")
        return {"paired": False, "counted_treat": 0, "counted_base": 0,
                "p50_treat_us": nan, "p50_base_us": nan,
                "mean_treat_us": nan, "mean_base_us": nan, "r_paired_mean": nan,
                "r_paired": nan, "r_h1": nan, "r_h2": nan, "r_workers": []}

    def med(idx):
        vals = [r[idx] for r in rows if r[idx] is not None]
        return statistics.median(vals) if vals else float("nan")

    def ratios(ti, bi):
        out = [r[ti] / r[bi] for r in rows
               if r[ti] is not None and r[bi] not in (None, 0)]
        return out

    per_worker = ratios(15, 16)
    # Per-arm MEANS, and the mean-based ratio. Recorded alongside the median-based one because
    # the simulator arm can only supply a mean (1/throughput at MPL=1) -- criterion 3 must
    # compare like with like, and which estimator is used is then a stated choice rather than
    # an artifact of what each arm happened to publish.
    st = sum(x[21] or 0.0 for x in rows)
    sb = sum(x[22] or 0.0 for x in rows)
    nt = sum(x[13] or 0 for x in rows)
    nb = sum(x[14] or 0 for x in rows)
    mean_t = st / nt if nt else float("nan")
    mean_b = sb / nb if nb else float("nan")
    return {
        "paired": True,
        "counted_treat": sum(r[13] or 0 for r in rows),
        "counted_base": sum(r[14] or 0 for r in rows),
        "p50_treat_us": med(15), "p50_base_us": med(16),
        "r_paired": statistics.median(per_worker) if per_worker else float("nan"),
        "mean_treat_us": mean_t, "mean_base_us": mean_b,
        "r_paired_mean": mean_t / mean_b if mean_b else float("nan"),
        # By batch HALF. Monotone within-batch drift (bloat is the candidate) shows up as
        # r_h2 != r_h1; a design-falsifying signature is either half below 1.
        "r_h1": statistics.median(ratios(17, 19)) if ratios(17, 19) else float("nan"),
        "r_h2": statistics.median(ratios(18, 20)) if ratios(18, 20) else float("nan"),
        "r_workers": per_worker,
    }


def report(res, w, k):
    print("  window %.3f s, %d counted / %d attempted (%.1f%% dropped as warmup+drain)"
          % (res["length_s"], res["counted"], res["attempted"],
             100 * (1 - res["counted"] / res["attempted"]) if res["attempted"] else 0))
    print("  throughput %.1f tps, mean latency %.3f ms, retries %d"
          % (res["throughput"], res["mean_latency_s"] * 1e3, res["retries"]))
    print("  mean_h %.1f, peak_h %d (bound W*k = %d), %d lock samples"
          % (res["mean_h"], res["peak_h"], w * k, len(res["locks"])))
    little = res["throughput"] * res["mean_latency_s"]
    cycles = res["length_s"] / res["mean_latency_s"] if res["mean_latency_s"] else 0
    f = res["fires"]
    if f:
        fires = f["close"] - f["open"]
        print("  fires %d over the window -> %.4f per commit"
              % (fires, fires / res["counted"] if res["counted"] else float("nan")))
    print("  GATES  little = %.3f vs W = %d (tol %.0f%%)   %s"
          % (little, w, LITTLE_TOL * 100,
             "PASS" if abs(little - w) / w <= LITTLE_TOL else "FAIL"))
    print("         cycles = %.1f (min %.1f)                %s"
          % (cycles, MIN_CYCLES, "PASS" if cycles >= MIN_CYCLES else "FAIL"))
    print("         peak_h <= W*k                          %s"
          % ("PASS" if 0 < res["peak_h"] <= w * k else "FAIL"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--w", type=int, default=4)
    ap.add_argument("--k", type=int, default=100)
    ap.add_argument("--d", type=int, default=5000)
    ap.add_argument("--f", type=float, default=0.25)
    ap.add_argument("--warmup-s", type=float, default=2.0)
    ap.add_argument("--duration-s", type=float, default=10.0)
    ap.add_argument("--tag", default="cell")
    ap.add_argument("--no-setup", action="store_true")
    ap.add_argument("--paired", action="store_true")
    args = ap.parse_args()

    conn = connect(args.dsn)
    with conn.cursor() as cur:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               "v1b_schema.sql")) as fh:
            cur.execute(fh.read())
    conn.close()
    if not args.no_setup:
        setup_v1(args.dsn, args.d, args.f, seed=12345)

    print("\nW=%d k=%d d=%d f=%s" % (args.w, args.k, args.d, args.f))
    res = run_cell(args.dsn, args.tag, 0, args.w, args.k, args.d, POOL,
                   args.warmup_s, args.duration_s, paired=args.paired)
    report(res, args.w, args.k)
    if res["paired"]:
        print("  PAIRED  treat p50 %.1f us (n=%d), base p50 %.1f us (n=%d)"
              % (res["p50_treat_us"], res["counted_treat"],
                 res["p50_base_us"], res["counted_base"]))
        print("          R = %.4f   (h1 %.4f -> h2 %.4f)"
              % (res["r_paired"], res["r_h1"], res["r_h2"]))
    return 0


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
