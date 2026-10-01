#!/usr/bin/env bash
# Measure the HOST, then size the pool from what was measured. Run this before run_jobs.sh.
#
# WHY MEASURE RATHER THAN RESERVE. -Xmx is a cap, not a reservation: the heaviest job in these lists
# (f=0.80, 96 cores, one seed) runs to completion under -Xmx512m with a peak resident set of 324 MB
# on the reference laptop. Sizing P from a 2g cap would under-provision the pool by 4x. So the rule
# is: size P by CORES, and only let memory override it if the MEASURED peak x P exceeds free RAM.
#
# Everything printed here is measured on this host. The laptop numbers are quoted only as the
# expectation to compare against - a server whose JVM, page size or core speed differs is not
# obliged to agree, and if it disagrees that is worth knowing before 320 jobs run.
#
# Usage:  preflight.sh <jar> [jobs-total]
set -uo pipefail

JAR="${1:?usage: preflight.sh <jar> [jobs-total]}"
NJOBS="${2:-320}"
[ -f "$JAR" ] || { echo "no such jar: $JAR" >&2; exit 1; }
DRIVER=de.tum.tssimulator.experiments.WorkloadAxisSweep
mkdir -p logs preflight_out

echo "=== 0. host ==="
java -version 2>&1 | head -1 | sed 's/^/    /'
NPROC=$(nproc 2>/dev/null || echo 1)
FREE_GB=$(free -g 2>/dev/null | awk '/^Mem:/{print $7}')
[ -n "${FREE_GB:-}" ] || FREE_GB=0
printf '    cores      : %s\n    free memory: %s GB\n' "$NPROC" "$FREE_GB"

echo "=== 0. jar provenance ==="
if ! unzip -p "$JAR" campaign-provenance.properties 2>/dev/null | sed 's/^/    /'; then
  echo "REFUSING: jar carries no provenance stamp" >&2; exit 1
fi
if unzip -p "$JAR" campaign-provenance.properties | grep -q '^git.dirty=true'; then
  echo "REFUSING: jar built from a dirty tree - rows cannot be tied to a commit" >&2; exit 1
fi

# ---------------------------------------------------------------- bit-identity FIRST
# THE CHECK THAT DECIDES WHETHER THIS HOST MAY CONTRIBUTE AT ALL, and it comes before the memory
# measurement because it is cheaper and it is disqualifying. Every figure in the paper is built
# from rows produced on the reference machine; rows from a host that computes this cell even
# slightly differently CANNOT be pooled with them, and the difference would show up as a shifted
# point rather than as an error.
#
# Reference: f=0.25 theta=0.5 pool 2561 MPL 192 k=10, measured on the laptop (i7-1355U,
# Temurin 21.0.11) and reproduced there across 4 concurrent processes, a solo run, G1 and SerialGC.
# Re-verified on this jar at ef717b6, so a mismatch here is the HOST, not the build.
echo "=== 1. does this host reproduce the reference cell bit-for-bit? ==="
SCAN=de.tum.tssimulator.experiments.WorkloadAlphaScan
java -Xmx1g -cp "$JAR" -Dts.wl.thetaRange=true -Dts.wl.thetas=0.5 -Dts.wl.thetaSeeds=1 "$SCAN" > logs/ref.txt 2>&1
ROW=$(grep -E '^ +0\.50 ' logs/ref.txt | head -1)
if [ -z "$ROW" ]; then
  echo "REFUSING: the reference cell produced no row - see logs/ref.txt" >&2
  exit 1
fi
G_TPS=$(echo "$ROW" | awk '{print $7}')
G_SH=$(echo "$ROW"  | awk '{print $8}')
G_SDR=$(echo "$ROW" | awk '{print $9}')
printf '    got      tps=%s cap_share=%s sdr=%s\n' "$G_TPS" "$G_SH" "$G_SDR"
printf '    expected tps=1883.0 cap_share=0.785 sdr=1.203\n'
if [ "$G_TPS" = "1883.0" ] && [ "$G_SH" = "0.785" ] && [ "$G_SDR" = "1.203" ]; then
  echo "    [PASS] bit-identical - rows from this host may be combined with the existing figures."
else
  echo "REFUSING: this host does NOT reproduce the reference cell." >&2
  echo "          Its rows must not be mixed with the existing figures - every number in a" >&2
  echo "          figure has to come from one machine. Either run the whole campaign here" >&2
  echo "          (including regenerating what the figures already use), or run it on the" >&2
  echo "          reference machine. Do not proceed with the batch." >&2
  exit 1
fi

echo "=== 2. heaviest job: f=0.80, 96 cores, one seed, -Xmx512m ==="
TIMER=""
if /usr/bin/time -v true >/dev/null 2>&1; then TIMER="/usr/bin/time -v"; fi
if [ -z "$TIMER" ]; then
  echo "    NOTE: /usr/bin/time -v unavailable; falling back to elapsed time only, and the"
  echo "          memory check below cannot run. Install GNU time or size P by cores alone."
fi
# -Xmx512m DELIBERATELY: the point is to confirm the job fits in a small heap, so a job that OOMs
# here is the finding. A generous -Xmx would make the measurement meaningless by never pressuring
# the heap at all.
set +e
$TIMER java -Xmx512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -cp "$JAR" \
  -Dts.wl.axis=cores -Dts.wl.seeds=5 -Dts.wl.axisOnly=96 "-Dts.wl.only=A/high" \
  -Dts.wl.seedIndex=0 -Dts.experiments.outputDir=preflight_out \
  "$DRIVER" > logs/preflight.log 2> logs/preflight.time
RC=$?
# DELIBERATELY NOT `set -e` HERE. An earlier version enabled errexit at this point - which the
# header (set -uo pipefail) never had - and the script then died silently on the very next line,
# because the grep for "Elapsed" finds nothing when GNU time is absent and grep returns 1. The
# symptom was a preflight that passed its checks and then printed no recommendation at all, on
# exactly the hosts that need one most. Caught by running the script, not by reading it.
if [ "$RC" -ne 0 ] || ! grep -q "^Wrote " logs/preflight.log; then
  echo "REFUSING: the heaviest job did not complete under -Xmx512m (exit $RC)." >&2
  echo "          If it died on heap, raise -Xmx in run_jobs.sh and re-measure - do NOT" >&2
  echo "          simply raise it without a new number. See logs/preflight.{log,time}" >&2
  tail -5 logs/preflight.time >&2
  exit 1
fi

# `|| true` on both: a missing line is the NORMAL case without GNU time, not an error.
ELAPSED=$(grep -E "Elapsed \(wall clock\)" logs/preflight.time | awk '{print $NF}' || true)
PEAK_KB=$(grep -E "Maximum resident set size" logs/preflight.time | awk '{print $NF}' || true)
if [ -n "${PEAK_KB:-}" ]; then
  PEAK_MB=$(( PEAK_KB / 1024 ))
  printf '    elapsed          : %s   (laptop reference: 8.2 min)\n' "${ELAPSED:-unknown}"
  printf '    peak resident    : %s MB (laptop reference: 324 MB)\n' "$PEAK_MB"
else
  PEAK_MB=324
  printf '    elapsed          : %s\n' "${ELAPSED:-unknown}"
  printf '    peak resident    : NOT MEASURED - assuming the laptop reference of %s MB\n' "$PEAK_MB"
fi

echo "=== 3. recommended pool size ==="
# Cores first. The simulator is one thread per job, so a slot is a core.
P_CORES=$NPROC
# Then the memory ceiling, from the MEASURED peak rather than from -Xmx. 80% of free RAM, so the
# page cache and the OS are not squeezed out by the last few slots.
MEM_KNOWN=1
if [ "$FREE_GB" -gt 0 ] && [ "$PEAK_MB" -gt 0 ]; then
  P_MEM=$(( FREE_GB * 1024 * 8 / 10 / PEAK_MB ))
else
  # UNKNOWN, NOT UNLIMITED. An earlier version fell back to P_MEM=P_CORES here and then printed
  # "cores are the binding constraint, as expected (memory is not close)" - a confident claim
  # derived from no memory data at all, on precisely the hosts where free(1) is missing. Say what
  # is known instead; a reassuring line built from a missing measurement is worse than no line.
  P_MEM=$P_CORES
  MEM_KNOWN=0
fi
P=$(( P_CORES < P_MEM ? P_CORES : P_MEM ))
[ "$P" -lt 1 ] && P=1
printf '    by cores          : %s\n' "$P_CORES"
if [ "$MEM_KNOWN" -eq 1 ]; then
  printf '    by measured memory: %s  (%s MB x P within 80%% of %s GB free)\n' \
    "$P_MEM" "$PEAK_MB" "$FREE_GB"
  if [ "$P_MEM" -lt "$P_CORES" ]; then
    echo "    MEMORY IS THE BINDING CONSTRAINT on this host - P is capped below the core count."
  else
    echo "    cores are the binding constraint (memory is not close)."
  fi
else
  echo "    by measured memory: NOT CHECKED - free(1) reported nothing, so the memory ceiling is"
  echo "                        UNKNOWN, not unlimited. P below is the core count alone. Confirm"
  echo "                        by hand that ${PEAK_MB} MB x P fits this host before running."
fi
printf '    ==> use P = %s\n' "$P"
echo
echo "    makespan is about max(slowest job, total work / P). With $NJOBS jobs the floor is the"
echo "    slowest single seed - splitting by seed is what makes that floor small. If the reported"
echo "    wall clock lands near the slowest job, more slots buy nothing."
echo
echo "    next:  bash run_jobs.sh $(basename "$JAR") jobs_all.txt $P --sort"
rm -rf preflight_out
