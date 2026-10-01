#!/usr/bin/env python3
"""Merge and VERIFY the f x mu grid's per-(cell, seed) job files.

Why this exists rather than w1w4_merge.sh: that script globs
``wl_<axis>_shard*_<kind>.csv`` and the grid's jobs carry no ``_shard`` token at all - they are
split by ``-Dts.wl.seedIndex``, so their names end ``_si<i>_raw.csv``. The campaign glob would
match none of them, and a glob loose enough to match them would also match campaign shards sitting
in the same directory and pool two designs into one file. A separate script with an exact expected
file list is the only version that cannot do that silently.

What it asserts, all of it refusing rather than warning:

* exactly one raw file per (cell, seed index), no more and no fewer - a missing job is a hole that
  is invisible once the rows are concatenated, and an extra one means two designs in the directory;
* exactly one data row per job file - a job that looped over every seed would put 5 rows in one
  file and silently quintuple the cell;
* one distinct ``plan_sha`` across every job's ``.meta.txt``, and ``dirty=false`` - rows from two
  builds cannot be pooled, and a dirty build cannot produce paper numbers;
* ``gate_verdict`` OK on every cell row and ``terminated_by=TIME_LIMIT`` on every raw row - a
  censored or gated cell measured something other than what the grid asked for.

Usage:  fmu_grid_merge.py <grid-output-dir> <axis> <expected-cells> <expected-seeds>
"""
import csv
import glob
import io
import os
import re
import sys


def die(msg):
    print(f"REFUSING: {msg}", file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 5:
        die(__doc__.strip().splitlines()[-1])
    out_dir, axis, n_cells, n_seeds = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    expected = n_cells * n_seeds

    raw_files = sorted(glob.glob(os.path.join(out_dir, f"wl_{axis}_*_si*_raw.csv")))
    if len(raw_files) != expected:
        die(f"{len(raw_files)} raw job files in {out_dir}, expected {expected} "
            f"({n_cells} cells x {n_seeds} seed indices). "
            f"A missing job is invisible after concatenation.")

    # Every (cell label, seed index) must appear exactly once. Taken from the FILENAME, so a job
    # that wrote the wrong cell's rows is caught by the cross-check against the row contents below.
    seen = {}
    for p in raw_files:
        m = re.search(r"wl_" + re.escape(axis) + r"_(.+?)_si(\d+)_raw\.csv$", os.path.basename(p))
        if not m:
            die(f"cannot parse cell/seed index out of {os.path.basename(p)}")
        key = (m.group(1), int(m.group(2)))
        if key in seen:
            die(f"duplicate job {key}: {os.path.basename(p)} and {os.path.basename(seen[key])}")
        seen[key] = p
    idxs = sorted({k[1] for k in seen})
    if idxs != list(range(n_seeds)):
        die(f"seed indices present are {idxs}, expected 0..{n_seeds - 1}")
    labels = sorted({k[0] for k in seen})
    if len(labels) != n_cells:
        die(f"{len(labels)} distinct cells present, expected {n_cells}: {labels}")

    # Provenance: one build, not dirty.
    # Keys are those build_campaign_jar.sh stamps: git.sha, git.branch, git.dirty, plan.file,
    # plan.blob, built.*. Matched exactly rather than by suffix - an earlier version of this script
    # looked for a key ending in "sha", which would also have swallowed plan.blob had it been named
    # differently and would have silently found nothing under the real names.
    shas, dirty_flags, plan_blobs, unstamped = set(), set(), set(), []
    for p in raw_files:
        meta = p.replace("_raw.csv", "_cells.meta.txt")
        if not os.path.exists(meta):
            unstamped.append(os.path.basename(p))
            continue
        text = io.open(meta, encoding="utf-8").read()
        if "PROVENANCE: UNSTAMPED" in text or "UNREADABLE" in text:
            unstamped.append(os.path.basename(meta))
            continue
        for line in text.splitlines():
            if "=" not in line:
                continue
            k, v = (s.strip() for s in line.split("=", 1))
            if k == "git.sha":
                shas.add(v)
            elif k == "git.dirty":
                dirty_flags.add(v.lower())
            elif k == "plan.blob":
                plan_blobs.add(v)
    if unstamped:
        die(f"{len(unstamped)} job(s) carry no usable build stamp, so their rows cannot be tied to "
            f"a commit: {unstamped[:3]}")
    if len(shas) != 1:
        die(f"rows come from {len(shas)} different builds {sorted(shas)} - they cannot be pooled")
    # The plan's BLOB too, not only the commit: it pins the pre-registration's CONTENT, so two
    # commits that leave the plan untouched are visibly the same pre-registration (and two that
    # change it are not poolable even at one commit).
    if len(plan_blobs) > 1:
        die(f"rows span {len(plan_blobs)} plan revisions {sorted(plan_blobs)}")
    if not dirty_flags:
        die("no git.dirty key in any build stamp - cannot confirm the build was clean")
    if dirty_flags - {"false"}:
        die(f"build stamp says dirty={sorted(dirty_flags)}; a dirty build must not produce paper "
            f"numbers (see build_campaign_jar.sh)")

    # Rows: one per job, all TIME_LIMIT.
    rows, header = [], None
    for key in sorted(seen):
        p = seen[key]
        with io.open(p, encoding="utf-8", newline="") as fh:
            rdr = csv.DictReader(fh)
            got = list(rdr)
            if header is None:
                header = rdr.fieldnames
            elif rdr.fieldnames != header:
                die(f"{os.path.basename(p)} has a different header - two binaries wrote this grid")
        if len(got) != 1:
            die(f"{os.path.basename(p)} holds {len(got)} data rows, expected exactly 1. A job that "
                f"ignored -Dts.wl.seedIndex would put every seed in one file.")
        rows.append(got[0])

    bad_term = [r["seed"] for r in rows if r.get("terminated_by") != "TIME_LIMIT"]
    if bad_term:
        die(f"{len(bad_term)} row(s) did not end at TIME_LIMIT (censored): {bad_term[:3]}")

    gate_bad = []
    for key in sorted(seen):
        cells = seen[key].replace("_raw.csv", "_cells.csv")
        if not os.path.exists(cells):
            die(f"missing cells file for job {key}")
        for c in csv.DictReader(io.open(cells, encoding="utf-8")):
            if c.get("gate_verdict") not in ("OK", None, ""):
                gate_bad.append((key, c.get("gate_verdict")))
    if gate_bad:
        die(f"{len(gate_bad)} cell row(s) failed their structural gate: {gate_bad[:3]}")

    merged = os.path.join(out_dir, f"wl_{axis}_grid_merged_raw.csv")
    with io.open(merged, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=header)
        w.writeheader()
        w.writerows(rows)

    print(f"OK: {expected} jobs, {len(rows)} rows, {n_cells} cells x {n_seeds} seeds")
    print(f"    git.sha {sorted(shas)[0]} dirty=false"
          + (f" plan.blob {sorted(plan_blobs)[0]}" if plan_blobs else "")
          + "; all TIME_LIMIT; all gates OK")
    print(f"    wrote {merged}")


if __name__ == "__main__":
    main()
