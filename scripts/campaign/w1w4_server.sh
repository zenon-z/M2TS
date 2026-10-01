#!/usr/bin/env bash
# The whole campaign on a host that has ONLY this script and a jar. No git, no maven, no repo.
#
# Usage:  bash w1w4_server.sh <jar> [shards] [seeds]
#   e.g.  bash w1w4_server.sh ts-simulator-campaign-a6cc95a.jar
#         bash w1w4_server.sh ts-simulator-campaign-a6cc95a.jar 64 5
#
# With no shard count it measures one, because a core count is not a throughput: a 12-core laptop
# with 8 efficiency cores returned 1.86x from 12 shards. Two minutes of measurement beats a guess
# in either direction.
set -uo pipefail

JAR="${1:?usage: w1w4_server.sh <jar> [shards] [seeds]}"
SHARDS="${2:-auto}"
SEEDS="${3:-5}"
[ -f "$JAR" ] || { echo "no such jar: $JAR" >&2; exit 1; }

DRIVER=de.tum.tssimulator.experiments.WorkloadAxisSweep
SCAN=de.tum.tssimulator.experiments.WorkloadAlphaScan
RESULTS="artifacts/experiments/results"
mkdir -p "$RESULTS" logs
FAIL=0
note() { printf '\n=== %s ===\n' "$*"; }
pass() { printf '[PASS] %s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*"; FAIL=1; }

note "0. host and jar"
java -version 2>&1 | head -1 | sed 's/^/    /'
NPROC=$(nproc 2>/dev/null || echo 1)
printf '    cores: %s\n' "$NPROC"
free -g 2>/dev/null | awk '/^Mem:/{printf "    mem  : %s GB available\n", $7}'
printf '    jar  : %s\n' "$JAR"
if unzip -p "$JAR" campaign-provenance.properties 2>/dev/null | sed 's/^/      /'; then
  if unzip -p "$JAR" campaign-provenance.properties | grep -q '^git.dirty=true'; then
    fail "jar was built from a DIRTY tree - its numbers cannot be tied to a commit"
  fi
else
  fail "jar carries no provenance stamp. Rebuild with build_campaign_jar.sh, or these rows
       cannot be tied to a commit afterwards - the analysis host's HEAD is not evidence
       about the jar that ran."
fi

# ---------------------------------------------------------------- 1. bit-identity
note "1. does this host reproduce the reference cell bit-for-bit?"
# Measured on the laptop (i7-1355U, Temurin 21.0.11), f=0.25 theta=0.5 pool 2561 MPL 192 k=10:
#   tps 1883.0   cap_share 0.785   sdr 1.203
# Reproduced there across 4 concurrent processes, a solo run, G1 and SerialGC. If this host
# disagrees, the two machines' results MUST NOT be mixed - produce every number on one of them.
java -Xmx1g -cp "$JAR" -Dts.wl.thetaRange=true -Dts.wl.thetas=0.5 -Dts.wl.thetaSeeds=1 \
  "$SCAN" > logs/ref.txt 2>&1
ROW=$(grep -E '^ +0\.50 ' logs/ref.txt | head -1)
if [ -z "$ROW" ]; then
  fail "reference cell produced no row - see logs/ref.txt"
else
  G_TPS=$(echo "$ROW" | awk '{print $7}'); G_SH=$(echo "$ROW" | awk '{print $8}')
  G_SDR=$(echo "$ROW" | awk '{print $9}')
  printf '    got      tps=%s cap_share=%s sdr=%s\n' "$G_TPS" "$G_SH" "$G_SDR"
  printf '    expected tps=1883.0 cap_share=0.785 sdr=1.203\n'
  if [ "$G_TPS" = "1883.0" ] && [ "$G_SH" = "0.785" ] && [ "$G_SDR" = "1.203" ]; then
    pass "bit-identical to the laptop - results from both machines may be mixed"
  else
    fail "DIVERGED. Usually a different JDK. Produce every number on ONE machine, and
       record which - this is a real finding, not a setup nuisance."
  fi
fi

# ---------------------------------------------------------------- 2. shard count
if [ "$SHARDS" = "auto" ]; then
  note "2. measuring the shard count (a core count is not a throughput)"
  wave() { local n="$1"; shift; local s; s=$(date +%s)
    for _ in $(seq 1 "$n"); do
      java -Xmx512m "$@" -cp "$JAR" -Dts.wl.thetaRange=true -Dts.wl.thetas=0.5 \
        -Dts.wl.thetaSeeds=1 "$SCAN" >/dev/null 2>&1 &
    done; wait; local w=$(( $(date +%s) - s )); [ "$w" -lt 1 ] && w=1; echo "$w"; }

  # The N=1 baseline is UNFLAGGED on purpose: -XX:ActiveProcessorCount penalises a solo JVM
  # badly (laptop: 31s -> 99s at APC=4), and a crippled baseline would credit sharding with a
  # speedup that is really the baseline being slowed down.
  B=$(wave 1); printf '    %-8s %-12s %s\n' "shards" "batch_wall" "aggregate"
  printf '    %-8s %-12s %s runs/10min\n' "1" "${B}s" "$(( 600 / B ))"
  BEST=1; BEST_RATE=$(( 600 / B ))
  for N in 8 32 $(( NPROC / 4 )) $(( NPROC / 2 )); do
    [ "$N" -lt 2 ] && continue
    W=$(wave "$N" -XX:ActiveProcessorCount=4); R=$(( N * 600 / W ))
    printf '    %-8s %-12s %s runs/10min\n' "$N" "${W}s" "$R"
    [ "$R" -gt "$BEST_RATE" ] && { BEST_RATE=$R; BEST=$N; }
  done
  SHARDS=$BEST
  pass "using $SHARDS shards (${BEST_RATE} runs/10min)"
fi

if [ "$FAIL" -ne 0 ]; then
  note "STOPPING - a pre-launch check failed above. Each one invalidates something different."
  exit 1
fi

# ---------------------------------------------------------------- 3. the campaign
JVM="-Xmx512m -XX:ActiveProcessorCount=4"
for AXIS in theta readonly cores txnlen; do
  note "axis $AXIS : $SHARDS shards x $SEEDS seeds"
  S=$(date +%s)
  for i in $(seq 0 $((SHARDS - 1))); do
    java $JVM -cp "$JAR" -Dts.wl.axis="$AXIS" -Dts.wl.seeds="$SEEDS" \
      -Dts.wl.shards="$SHARDS" -Dts.wl.shard="$i" "$DRIVER" \
      > "logs/wl_${AXIS}_shard${i}.log" 2>&1 &
  done
  wait
  echo "    ran in $(( $(date +%s) - S ))s"

  for KIND in raw cells skipped; do
    M="$RESULTS/wl_${AXIS}_${KIND}.csv"; FIRST=1; : > "$M"
    for i in $(seq 0 $((SHARDS - 1))); do
      F="$RESULTS/wl_${AXIS}_shard${i}_${KIND}.csv"
      [ -f "$F" ] || { echo "    WARNING: shard $i wrote no $KIND file" >&2; continue; }
      if [ "$FIRST" -eq 1 ]; then cat "$F" >> "$M"; FIRST=0; else tail -n +2 "$F" >> "$M"; fi
    done
    echo "    $M : $(( $(wc -l < "$M") - 1 )) rows"
  done

  # Equal row counts prove nothing: shards that shuffled differently give OVERLAPPING subsets
  # whose total is still the right size. Check the fingerprint, then coverage and overlap.
  FPS=$(grep -h "cell-list fingerprint" logs/wl_${AXIS}_shard*.log | awk '{print $3}' | sort -u)
  if [ "$(echo "$FPS" | wc -l)" -ne 1 ]; then
    echo "    FATAL: shards disagree on the cell list ($FPS) - they did not partition one" >&2
    echo "           grid, so some cells ran twice and others never. Merged CSV is INVALID." >&2
    exit 1
  fi
  C="$RESULTS/wl_${AXIS}_cells.csv"
  DUP=$(tail -n +2 "$C" | cut -d, -f1,2,3,5 | sort | uniq -d | wc -l)
  RAN=$(tail -n +2 "$C" | cut -d, -f1,5 | sort -u | wc -l)
  EXP=$(grep -h "cell-list fingerprint" logs/wl_${AXIS}_shard0.log | sed 's/.*(\([0-9]*\) cells.*/\1/')
  echo "    partition ok ($FPS); coverage $RAN/$EXP cells, $DUP duplicated"
  [ "$DUP" -ne 0 ] && { echo "    FATAL: shards OVERLAP." >&2; exit 1; }
  [ -n "$EXP" ] && [ "$RAN" -ne "$EXP" ] && {
    echo "    FATAL: $((EXP - RAN)) cell(s) never ran - a missing cell is a missing point on a" >&2
    echo "           figure, not a smaller sample." >&2; exit 1; }
done

note "done"
echo "Copy back:  artifacts/experiments/results/  and  logs/"
echo "Then run locally, per axis:  python scripts/campaign/check_pilot.py <axis>"
echo "  (control at capacity, C15 counters nonzero, aliasing off, pigeonhole, seeds columns)"
