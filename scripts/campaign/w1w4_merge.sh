#!/usr/bin/env bash
# Merge per-shard campaign output and CHECK the merge, on a host with only this script.
#
# Needed whenever the shards were launched directly rather than through w1w4_server.sh, which
# merges inline. Safe to re-run: it rebuilds the merged files from the shard files, which are
# never deleted.
#
# Usage:  bash w1w4_merge.sh [axis ...]      (default: all four)
#
# WHY THE CHECKS. Equal row counts prove nothing. Shards that computed DIFFERENT shuffles produce
# overlapping subsets whose total is still exactly the right size, and the merged CSV then has
# some cells measured twice and others missing - which shows up in a figure as a point that is
# fine and a point that is absent, neither of which announces itself. So the merge verifies three
# independent things: that every shard partitioned the same grid, that nothing was measured twice,
# and that nothing is missing.
set -uo pipefail

RESULTS="artifacts/experiments/results"
LOGS="logs"
AXES=("$@")
[ "${#AXES[@]}" -eq 0 ] && AXES=(theta readonly cores txnlen)
RC=0

for AXIS in "${AXES[@]}"; do
  printf '\n=== %s ===\n' "$AXIS"
  shopt -s nullglob
  SHARD_FILES=("$RESULTS/wl_${AXIS}_shard"*_cells.csv)
  shopt -u nullglob
  if [ "${#SHARD_FILES[@]}" -eq 0 ]; then
    echo "  no shard files - skipping"; continue
  fi

  # Still running? Merging a live axis silently captures a partial grid.
  # The bracket is load-bearing: "[-]Dts" matches "-Dts" in other processes but NOT in this
  # grep's own command line, which ps lists alongside everything else. Without it every axis
  # reported exactly one live shard - the grep counting itself - so a finished campaign could
  # never be merged. It failed SAFE (refusing) rather than merging a partial grid, which is the
  # right direction for a guard to be wrong in, but it was still wrong.
  RUNNING=$(ps -eo args 2>/dev/null | grep -c -- "[-]Dts.wl.axis=$AXIS " || true)
  if [ "$RUNNING" -gt 0 ]; then
    echo "  REFUSING: $RUNNING shard(s) of this axis are STILL RUNNING." >&2
    echo "  A merge now would record a partial grid as if it were complete." >&2
    RC=1; continue
  fi

  # -------------------------------------------------- 0. torn writes
  # Two processes writing one path without O_APPEND leave a HOLE, which reads back as NUL bytes:
  # run B truncates to 0 and writes, run A then writes at its stale offset, and the gap between
  # them is sparse. A shard file with NULs is structurally corrupt, not merely redundant, and the
  # rows around the hole cannot be trusted.
  #
  # This happens when a campaign is launched twice over the same shard paths - killing the
  # launcher script does NOT kill the JVMs it backgrounded, so the old wave keeps writing into the
  # new one's files. It was found here only because the duplicate-key check happened to trip and
  # grep happened to say "binary file matches"; neither is a check, so this is one.
  TORN=0
  for F in "$RESULTS/wl_${AXIS}_shard"*_raw.csv "$RESULTS/wl_${AXIS}_shard"*_cells.csv; do
    [ -f "$F" ] || continue
    if [ "$(tr -cd '\0' < "$F" | wc -c)" -gt 0 ]; then
      echo "  FATAL: $(basename "$F") contains NUL bytes - torn by concurrent writers." >&2
      TORN=1
    fi
  done
  if [ "$TORN" -ne 0 ]; then
    echo "  Delete the affected shard files and RE-RUN those shard indices; do not dedupe." >&2
    echo "  The shard-to-cell map is deterministic in (shuffle seed, shard count), so re-running" >&2
    echo "  a subset reproduces exactly its own cells - and the fingerprint check below proves it." >&2
    RC=1; continue
  fi

  for KIND in raw cells skipped; do
    M="$RESULTS/wl_${AXIS}_${KIND}.csv"; FIRST=1; : > "$M"
    for F in "$RESULTS/wl_${AXIS}_shard"*_${KIND}.csv; do
      [ -f "$F" ] || continue
      if [ "$FIRST" -eq 1 ]; then cat "$F" >> "$M"; FIRST=0; else tail -n +2 "$F" >> "$M"; fi
    done
    printf '  %-34s %s rows\n' "$(basename "$M")" "$(( $(wc -l < "$M") - 1 ))"
  done

  # -------------------------------------------------- 1. one grid, one shuffle
  # Every shard prints this before its first cell, INCLUDING shards that drew no cells (a shard
  # index at or above the cell count), so all of them participate in the check.
  FPS=$(grep -ah "cell-list fingerprint" "$LOGS"/wl_${AXIS}_shard*.log 2>/dev/null \
        | awk '{print $3}' | sort -u)
  NFP=$(echo "$FPS" | grep -c . || true)
  if [ "$NFP" -ne 1 ]; then
    echo "  FATAL: shards disagree on the cell list - they did not partition one grid:" >&2
    echo "$FPS" | sed 's/^/    /' >&2
    echo "  Some cells ran twice and others never. This merged CSV is INVALID." >&2
    RC=1; continue
  fi
  EXP=$(grep -ah "cell-list fingerprint" "$LOGS"/wl_${AXIS}_shard*.log 2>/dev/null \
        | head -1 | sed 's/.*(\([0-9]*\) cells.*/\1/')
  echo "  fingerprint $FPS over $EXP cells - all shards agree"

  # -------------------------------------------------- 2. no overlap
  C="$RESULTS/wl_${AXIS}_cells.csv"
  DUP=$(tail -n +2 "$C" | cut -d, -f1,2,3,5 | sort | uniq -d | wc -l)
  if [ "$DUP" -ne 0 ]; then
    echo "  FATAL: $DUP (config_id,panel,label,x) key(s) appear more than once - shards OVERLAP:" >&2
    tail -n +2 "$C" | cut -d, -f1,2,3,5 | sort | uniq -d | head -5 | sed 's/^/    /' >&2
    RC=1
  fi

  # -------------------------------------------------- 3. full coverage
  RAN=$(tail -n +2 "$C" | cut -d, -f1,5 | sort -u | wc -l)
  SKIP=$(( $(wc -l < "$RESULTS/wl_${AXIS}_skipped.csv") - 1 ))
  if [ -n "$EXP" ] && [ "$RAN" -ne "$EXP" ]; then
    echo "  FATAL: $RAN of $EXP cells present - $((EXP - RAN)) never produced a row." >&2
    echo "  A missing cell is a MISSING POINT on a figure, not a smaller sample." >&2
    [ "$SKIP" -gt 0 ] && echo "  ($SKIP are in _skipped.csv with a reason - those are recorded, not lost)" >&2
    RC=1
  else
    echo "  coverage $RAN/$EXP cells, $DUP duplicated, $SKIP skipped"
  fi

  # -------------------------------------------------- 4. seeds_used matches the rows written
  # The column is the campaign's record of unequal n (plan 0.10's point-targeted escalation), so
  # it has to agree with the rows actually present or it is narration rather than data.
  # awk rather than python: this has to run on whatever the campaign host happens to have, and
  # "the check silently did not run" is the failure mode the whole C15 zero-vs-unwired rule is
  # about. The seeds_used column is located BY NAME from the header, not by position, so a schema
  # change moves the check instead of breaking it into a wrong answer.
  MISMATCH=$(awk -F, '
    NR == FNR { if (FNR > 1) cnt[$1]++; next }
    FNR == 1  { for (i = 1; i <= NF; i++) if ($i == "seeds_used") col = i; next }
    !seen[$1]++ { if (col && cnt[$1] + 0 != $col + 0) bad++ }
    END { if (!col) print "?"; else print bad + 0 }
  ' "$RESULTS/wl_${AXIS}_raw.csv" "$C" 2>/dev/null)
  [ -z "$MISMATCH" ] && MISMATCH="?"
  if [ "$MISMATCH" = "?" ]; then
    echo "  seeds_used vs rows: NOT CHECKED - no seeds_used column found" >&2
    RC=1
  elif [ "$MISMATCH" -ne 0 ]; then
    echo "  FATAL: $MISMATCH cell(s) where seeds_used disagrees with the raw rows present." >&2
    RC=1
  else
    echo "  seeds_used agrees with the raw rows on every cell"
  fi
done

printf '\n'
if [ "$RC" -eq 0 ]; then
  echo "All axes merged and verified. Copy back artifacts/experiments/results/ and logs/,"
  echo "then run: python scripts/campaign/check_pilot.py <axis>"
else
  echo "At least one axis FAILED a merge check - read it above. Do not plot from a merged CSV"
  echo "whose partition did not verify."
fi
exit "$RC"
