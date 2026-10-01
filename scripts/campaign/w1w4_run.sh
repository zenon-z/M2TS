#!/usr/bin/env bash
# Run the W1-W4 campaign sharded across processes, then merge the shards.
#
# Usage:  scripts/campaign/w1w4_run.sh <shards> [seeds] [axes...]
#   e.g.  scripts/campaign/w1w4_run.sh 64 5
#         scripts/campaign/w1w4_run.sh 64 5 theta
#
# SEPARATE PROCESSES, NOT THREADS. TransactionAggregateMetrics and friends are process-global;
# the shard flag is the supported way to get parallelism without violating that.
#
# The split is taken AFTER the driver's C30 shuffle and by position, so the union of the shards is
# exactly the unsharded cell list - verified by running both and diffing the sidecars. A single
# shard is a STRIDED SUBSET of the grid, not a sample of it: never draw a figure from one.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SHARDS="${1:?usage: w1w4_run.sh <shards> [seeds] [axes...]}"
SEEDS="${2:-5}"
shift 2 2>/dev/null || shift 1
AXES=("$@")
[ "${#AXES[@]}" -eq 0 ] && AXES=(theta readonly cores txnlen)

[ -f cp.txt ] || mvn -q -o dependency:build-classpath -Dmdep.outputFile=cp.txt
[ -d target/classes ] || mvn -q -DskipTests compile
if grep -q ';' cp.txt; then SRC_SEP=';'; else SRC_SEP=':'; fi
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac
CP="target/classes${SEP}$(tr "$SRC_SEP" "$SEP" < cp.txt)"
RESULTS="artifacts/experiments/results"
mkdir -p "$RESULTS" logs

# Each JVM otherwise sizes its GC and JIT thread pools from the HOST's core count, so N shards on
# a 500-core box would provision N x 500 threads. -Xmx512m is ample: the worst cell measured
# 234 MB peak heap, and heap barely varies across cells because the D-sized resource and lock
# tables dominate and are identical everywhere.
JVM_FLAGS="-Xmx512m -XX:ActiveProcessorCount=4"

for AXIS in "${AXES[@]}"; do
  echo "=== axis $AXIS : $SHARDS shards x $SEEDS seeds ==="
  START=$(date +%s)
  for i in $(seq 0 $((SHARDS - 1))); do
    java $JVM_FLAGS -cp "$CP" \
      -Dts.wl.axis="$AXIS" -Dts.wl.seeds="$SEEDS" \
      -Dts.wl.shards="$SHARDS" -Dts.wl.shard="$i" \
      de.tum.tssimulator.experiments.WorkloadAxisSweep \
      > "logs/wl_${AXIS}_shard${i}.log" 2>&1 &
  done
  wait
  echo "    ran in $(( $(date +%s) - START ))s"

  # Merge: keep the first header, drop the rest. Shard files are left in place so a merge can be
  # re-done and so a failed shard is identifiable rather than silently absent from the merged CSV.
  for KIND in raw cells skipped; do
    MERGED="$RESULTS/wl_${AXIS}_${KIND}.csv"
    FIRST=1
    : > "$MERGED"
    for i in $(seq 0 $((SHARDS - 1))); do
      F="$RESULTS/wl_${AXIS}_shard${i}_${KIND}.csv"
      if [ ! -f "$F" ]; then
        echo "    WARNING: shard $i produced no $KIND file - check logs/wl_${AXIS}_shard${i}.log" >&2
        continue
      fi
      if [ "$FIRST" -eq 1 ]; then cat "$F" >> "$MERGED"; FIRST=0
      else tail -n +2 "$F" >> "$MERGED"; fi
    done
    echo "    $MERGED : $(( $(wc -l < "$MERGED") - 1 )) rows"
  done

  # A shard that died leaves a hole that is invisible in the merged file, so it is checked here
  # rather than discovered in a figure with a missing point.
  MISSING=$(grep -L "Wrote " logs/wl_${AXIS}_shard*.log 2>/dev/null | wc -l)
  if [ "$MISSING" -ne 0 ]; then
    echo "    WARNING: $MISSING shard(s) did not reach completion:" >&2
    grep -L "Wrote " logs/wl_${AXIS}_shard*.log | sed 's/^/       /' >&2
  fi

  # ---- PARTITION CHECKS. Equal row counts prove nothing: a shuffle that differed between
  # processes gives overlapping subsets whose total is still the right size.
  #
  # 1. Every shard must have partitioned the SAME ordered grid.
  FPS=$(grep -h "cell-list fingerprint" logs/wl_${AXIS}_shard*.log | awk '{print $3}' | sort -u)
  if [ "$(echo "$FPS" | wc -l)" -ne 1 ]; then
    echo "    FATAL: shards disagree on the cell list - they did not partition one grid." >&2
    echo "           fingerprints seen: $(echo $FPS)" >&2
    echo "           Cause is almost always a per-process -Dts.wl.shuffle. The merged CSV is" >&2
    echo "           INVALID: some cells ran twice and others not at all. Re-run the wave." >&2
    exit 1
  fi
  echo "    partition: all shards agree on cell list $(echo $FPS)"

  # 2. FULL COVERAGE and NO OVERLAP on the actual rows, by (config_id, panel, label, x).
  CELLS="$RESULTS/wl_${AXIS}_cells.csv"
  TOTAL=$(tail -n +2 "$CELLS" | wc -l)
  UNIQ=$(tail -n +2 "$CELLS" | cut -d, -f1,2,3,5 | sort | uniq | wc -l)
  DUPES=$(tail -n +2 "$CELLS" | cut -d, -f1,2,3,5 | sort | uniq -d | wc -l)
  EXPECTED=$(grep -h "cell-list fingerprint" logs/wl_${AXIS}_shard0.log \
             | sed 's/.*(\([0-9]*\) cells.*/\1/')
  RAN=$(tail -n +2 "$CELLS" | cut -d, -f1,5 | sort -u | wc -l)
  echo "    coverage : $RAN of $EXPECTED cells ran; $TOTAL sidecar rows, $UNIQ unique, $DUPES duplicated"
  if [ "$DUPES" -ne 0 ]; then
    echo "    FATAL: $DUPES (config_id,panel,label,x) keys appear more than once - shards OVERLAP." >&2
    exit 1
  fi
  if [ -n "$EXPECTED" ] && [ "$RAN" -ne "$EXPECTED" ]; then
    echo "    FATAL: $((EXPECTED - RAN)) cell(s) of $EXPECTED never ran - coverage is INCOMPLETE." >&2
    echo "           A missing cell is a missing point on a figure, not a smaller sample." >&2
    exit 1
  fi
done

echo
echo "Merged. Next: python scripts/campaign/check_pilot.py <axis> for the driver-output checks"
echo "(control at capacity, C15 counters nonzero, aliasing off, pigeonhole, seeds columns)."
