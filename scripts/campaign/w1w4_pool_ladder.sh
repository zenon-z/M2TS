#!/usr/bin/env bash
# The pool-ladder accounting check: infeasible on a laptop, trivial on a large-memory host.
#
# WHAT IT DECIDES. multiplier = (1 / capacity_share) / sdr. At f = 0.80 the realised sdr is ~10
# and the multiplier reads 1.57 at theta = 0. Either cascades genuinely interact at high CPR
# density, or the denominator under-counts heavy cascades and books real work as interaction cost.
# As the pool grows, conflicts vanish while sdr stays ~10, so the multiplier MUST converge to 1.00.
#   converges -> the accounting holds at sdr ~ 10 and the high multiplier is interaction
#   stays high -> the denominator mis-charges and every f = 0.80 number is suspect
#
# WHY AT THETA = 0. The pool is a weak contention knob at high skew: a Zipf head share goes as
# N^(theta-1), so x100 cuts it only 2.5x at theta = 0.8 and the test would be inconclusive by
# construction. At theta = 0 the draw is uniform, so pool size maps directly onto conflict rate.
#
# MEMORY. D = 200 * pool, and the resource and lock tables are both D-sized. x100 is D = 51.2M,
# which needs tens of GB - hence this script rather than the laptop.
#
# This is the SECOND, independent check. The first needs no run at all:
#   java ... de.tum.tssimulator.experiments.WorkloadSdrClosureCheck
# predicts sdr from the static schema and already agrees with the realised counters to 2.1% at
# f = 0.80. Two independent confirmations are the point; one of them agreeing is not.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

[ -f cp.txt ] || mvn -q -o dependency:build-classpath -Dmdep.outputFile=cp.txt
[ -d target/classes ] || mvn -q -DskipTests compile
if grep -q ';' cp.txt; then SRC_SEP=';'; else SRC_SEP=':'; fi
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac
CP="target/classes${SEP}$(tr "$SRC_SEP" "$SEP" < cp.txt)"

F="${F:-0.80}"
THETA="${THETA:-0.0}"
OUT="artifacts/experiments/results/wl_pool_ladder_f${F}.txt"
mkdir -p "$(dirname "$OUT")"

echo "=== pool ladder: f=$F theta=$THETA (one process per pool) ===" | tee "$OUT"
echo "expect: multiplier -> 1.00 as the pool grows, with sdr roughly CONSTANT" | tee -a "$OUT"
echo | tee -a "$OUT"

# x1, x10, x100. Heap is scaled with D since the resource and lock tables dominate.
for SPEC in "2561:4g" "25610:24g" "256100:160g"; do
  POOL="${SPEC%%:*}"
  HEAP="${SPEC##*:}"
  echo "--- pool $POOL (D = $((200 * POOL))), -Xmx$HEAP ---" | tee -a "$OUT"
  START=$(date +%s)
  java -Xmx"$HEAP" -XX:ActiveProcessorCount=8 -cp "$CP" \
    -Dts.wl.thetaRange=true -Dts.wl.f="$F" -Dts.wl.thetas="$THETA" -Dts.wl.thetaSeeds=3 \
    -Dts.wl.instancePool="$POOL" \
    de.tum.tssimulator.experiments.WorkloadAlphaScan 2>&1 \
    | grep -E "^ +[0-9]|over .* seeds|Exception|OutOfMemory" | tee -a "$OUT"
  echo "    ($(( $(date +%s) - START ))s)" | tee -a "$OUT"
  echo | tee -a "$OUT"
done

cat <<'EOF' | tee -a "$OUT"

READING IT
  sdr must stay ~constant across the three pools - it is a property of the SCHEMA, not of
  contention. If sdr moves materially with the pool, this test is not measuring what it claims
  and the reading should stop there.

  capacity_share must rise toward 1/sdr, and the multiplier toward 1.00. Convergence to within a
  few percent is the pass; a multiplier that plateaus well above 1.00 at x100 is the failure, and
  it would mean the f = 0.80 panel cannot be published as measured.
EOF
