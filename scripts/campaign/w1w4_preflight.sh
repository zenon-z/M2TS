#!/usr/bin/env bash
# W1-W4 pre-launch checks for a many-core host. Run this BEFORE the campaign.
#
# Three things are checked, in the order that makes a failure cheapest to act on:
#
#   1. CROSS-MACHINE BIT-IDENTITY against the laptop reference cell. If this fails, laptop and
#      server results MUST NOT be mixed and everything has to be produced on one machine. It is
#      checked first because it invalidates the most work.
#   2. SCALING, so the shard count is chosen from a measurement rather than from the core count.
#      A core count is not a throughput: the laptop has 12 logical cores and 12 shards bought
#      1.86x, because 8 of them are efficiency cores under a 15 W budget. A server should do far
#      better - two minutes confirms it instead of assuming it.
#   3. CLEAN TREE at the committed SHA, so the campaign manifest's plan_sha / dirty=false describe
#      the code that produced the numbers (CONVENTIONS C1).
#
# Usage:  scripts/campaign/w1w4_preflight.sh [max_shards]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
MAX_SHARDS="${1:-64}"
OUT="${TMPDIR:-/tmp}/w1w4_preflight.$$"
mkdir -p "$OUT"
FAIL=0

note() { printf '\n=== %s ===\n' "$*"; }
pass() { printf '[PASS] %s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*"; FAIL=1; }

# ---------------------------------------------------------------- classpath
if [ ! -f cp.txt ]; then
  mvn -q -o dependency:build-classpath -Dmdep.outputFile=cp.txt \
    || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
fi
[ -d target/classes ] || mvn -q -DskipTests compile
# mvn writes the classpath with the separator of the machine that GENERATED it, which need not be
# the one running now: a cp.txt built on Windows is ';'-separated. Detect the separator IN THE
# FILE rather than from the platform - on Windows the paths also contain ':' in every drive
# letter, so splitting on ':' would shred them.
if grep -q ';' cp.txt; then SRC_SEP=';'; else SRC_SEP=':'; fi
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac
CP="target/classes${SEP}$(tr "$SRC_SEP" "$SEP" < cp.txt)"

note "0. environment"
java -version 2>&1 | sed 's/^/    /'
printf '    cores: %s\n' "$(nproc 2>/dev/null || echo '?')"
printf '    free : %s\n' "$(free -g 2>/dev/null | awk '/^Mem:/{print $7" GB available"}' || echo '?')"

# ---------------------------------------------------------------- 1. bit-identity
note "1. cross-machine bit-identity (laptop reference cell)"
# Reference measured on the laptop, f=0.25, theta=0.5, pool 2561, MPL 192, k=10, order OFF:
#   tps 1883.0   cap_share 0.785   sdr 1.203   mult 1.06
# Reproduced bit-for-bit there across 4 concurrent processes, a solo run, G1 and SerialGC.
EXPECTED_TPS="1883.0"
EXPECTED_SHARE="0.785"
EXPECTED_SDR="1.203"

java -Xmx1g -cp "$CP" -Dts.wl.thetaRange=true -Dts.wl.thetas=0.5 -Dts.wl.thetaSeeds=1 \
  de.tum.tssimulator.experiments.WorkloadAlphaScan > "$OUT/ref.txt" 2>&1

ROW="$(grep -E '^ +0\.50 ' "$OUT/ref.txt" | head -1)"
if [ -z "$ROW" ]; then
  fail "reference cell produced no row - see $OUT/ref.txt"
else
  GOT_TPS="$(echo "$ROW" | awk '{print $7}')"
  GOT_SHARE="$(echo "$ROW" | awk '{print $8}')"
  GOT_SDR="$(echo "$ROW" | awk '{print $9}')"
  printf '    got      tps=%s cap_share=%s sdr=%s\n' "$GOT_TPS" "$GOT_SHARE" "$GOT_SDR"
  printf '    expected tps=%s cap_share=%s sdr=%s\n' "$EXPECTED_TPS" "$EXPECTED_SHARE" "$EXPECTED_SDR"
  if [ "$GOT_TPS" = "$EXPECTED_TPS" ] && [ "$GOT_SHARE" = "$EXPECTED_SHARE" ] \
     && [ "$GOT_SDR" = "$EXPECTED_SDR" ]; then
    pass "bit-identical to the laptop - results from the two machines may be mixed"
  else
    fail "DIVERGED from the laptop reference. Do NOT mix machines; produce every number on one.
       The usual cause is a different JDK. This is a real finding either way: record which
       machine each figure came from before doing anything else."
  fi
fi

# ---------------------------------------------------------------- 2. scaling
note "2. scaling (shard count from measurement, not from nproc)"
# ActiveProcessorCount matters MORE here than on a small box, not less: each JVM sizes
# ParallelGCThreads and CICompilerCount from the machine's core count, so N JVMs on a 500-core
# host would each provision for 500 cores. On the laptop this flag was worth 4%; at this scale it
# is the difference between N shards and N x 500 threads.
APC="${APC:-4}"
run_wave() {  # run_wave <n> <extra jvm flags>; echoes batch wall-clock in seconds
  local n="$1"; shift
  local start; start=$(date +%s)
  for _ in $(seq 1 "$n"); do
    java -Xmx512m "$@" -cp "$CP" \
      -Dts.wl.thetaRange=true -Dts.wl.thetas=0.5 -Dts.wl.thetaSeeds=1 \
      de.tum.tssimulator.experiments.WorkloadAlphaScan > /dev/null 2>&1 &
  done
  wait
  local w=$(( $(date +%s) - start ))
  [ "$w" -lt 1 ] && w=1
  echo "$w"
}

# The N=1 baseline is measured WITHOUT -XX:ActiveProcessorCount, because that flag penalises a
# solo JVM badly (measured on the laptop: 31s -> 54s at APC=1, and 31s -> 99s at APC=4). Using a
# flagged baseline would credit sharding with a speedup that is really the baseline being crippled
# - the scaling number would look good for the wrong reason.
printf '    baseline (1 shard, no APC flag):\n'
BASE_W=$(run_wave 1)
printf '    %-10s %-12s %-14s %s\n' "shards" "batch_wall" "per_run" "aggregate"
printf '    %-10s %-12s %-14s %s\n' "1 (plain)" "${BASE_W}s" "${BASE_W}.0s" \
       "$(( 600 / BASE_W )) runs/10min"

BEST_N=1; BEST_RATE=$(( 600 / BASE_W ))
for N in 8 32 "$MAX_SHARDS"; do
  [ "$N" -gt "$MAX_SHARDS" ] && continue
  W=$(run_wave "$N" "-XX:ActiveProcessorCount=$APC")
  RATE=$(( N * 600 / W ))
  printf '    %-10s %-12s %-14s %s runs/10min\n' "$N" "${W}s" \
         "$(( W / N )).$(( (W * 10 / N) % 10 ))s" "$RATE"
  if [ "$RATE" -gt "$BEST_RATE" ]; then BEST_RATE=$RATE; BEST_N=$N; fi
done

# Whether the flag helps AT ALL is host-specific and must be measured, not assumed. On a 12-core
# laptop it was worth 4% and cost the solo case 74%; on a 500-core host each unflagged JVM sizes
# ParallelGCThreads and CICompilerCount for 500 cores, so N shards provision N x 500 threads and
# the flag should matter far more. Opposite regimes, same two-minute check.
if [ "$BEST_N" -gt 1 ]; then
  printf '    --- does -XX:ActiveProcessorCount=%s actually help at %s shards? ---\n' "$APC" "$BEST_N"
  W_ON=$(run_wave "$BEST_N" "-XX:ActiveProcessorCount=$APC")
  W_OFF=$(run_wave "$BEST_N")
  printf '    with APC=%s: %ss     without: %ss\n' "$APC" "$W_ON" "$W_OFF"
  if [ "$W_ON" -lt "$W_OFF" ]; then
    pass "APC=$APC helps at this scale - keep it in w1w4_run.sh's JVM_FLAGS"
  else
    fail "APC=$APC is NOT helping here (${W_ON}s vs ${W_OFF}s unflagged). Drop it from
       w1w4_run.sh, or try a larger value - do not carry a flag that costs throughput."
  fi
fi
pass "best aggregate throughput at ${BEST_N} concurrent shards - use that for -Dts.wl.shards"

# ---------------------------------------------------------------- 3. clean tree
note "3. clean tree at a committed SHA (C1)"
SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
DIRTY="$(git status --porcelain 2>/dev/null | wc -l)"
printf '    HEAD %s\n' "$SHA"
if [ "$DIRTY" -eq 0 ]; then
  pass "working tree clean - plan_sha/dirty=false will describe the code that ran"
else
  fail "$DIRTY modified/untracked files. The manifest would record dirty=true, so the numbers
       could not be tied to a committed plan. Commit, push, and clone fresh on this host."
  git status --porcelain | head -10 | sed 's/^/       /'
fi

note "verdict"
if [ "$FAIL" -eq 0 ]; then
  echo "All three checks pass. Launch with scripts/campaign/w1w4_run.sh ${BEST_N}"
else
  echo "At least one check FAILED - read it above before launching. Each of the three"
  echo "invalidates a different thing, and none of them is cosmetic."
fi
exit "$FAIL"
