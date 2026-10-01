#!/usr/bin/env bash
# Run a job list concurrently, one JVM per (cell, seed) job.
#
# SEPARATE PROCESSES, NOT THREADS, and that is not a preference: TransactionAggregateMetrics and
# its neighbours are process-global statics, so two simulations in one JVM would share and corrupt
# each other's counters. One JVM per job is the only safe parallelism this simulator has.
#
# MAKESPAN. With P slots and N jobs the wall clock is about max(slowest_job, total_work / P). The
# point of splitting by SEED as well as by cell is to make the first term small: a cell's five
# seeds run side by side instead of in series inside one job, so the slowest SINGLE SEED sets the
# floor rather than the slowest cell. Feeding the pool longest-job-first keeps the tail short -
# hence the --sort option, which runs the known-slow high-f cells first so nothing large starts
# late (LPT scheduling: the worst case is then within 4/3 of optimal).
#
# Usage:  run_jobs.sh <jar> <joblist> [parallel] [--sort]
#   parallel defaults to nproc. Give it explicitly if the host is shared.
set -uo pipefail

JAR="${1:?usage: run_jobs.sh <jar> <joblist> [parallel] [--sort]}"
LIST="${2:?usage: run_jobs.sh <jar> <joblist> [parallel] [--sort]}"
PAR="${3:-}"
SORT_FLAG="${4:-}"
[ -f "$JAR" ] || { echo "no such jar: $JAR" >&2; exit 1; }
[ -f "$LIST" ] || { echo "no such job list: $LIST" >&2; exit 1; }
[ -n "$PAR" ] || PAR=$(nproc 2>/dev/null || echo 4)

DRIVER=de.tum.tssimulator.experiments.WorkloadAxisSweep
LOGS="logs/$(basename "$LIST" .txt)"
mkdir -p "$LOGS"

# ONE CPU PER JVM. Left alone, each JVM sizes its GC and JIT pools from the HOST's core count, so
# P concurrent JVMs on a 64-core box each start ~40 GC threads and spend the run fighting over
# them - measured elsewhere in this campaign as the difference between linear and useless scaling.
# -Xmx MEASURED, not guessed. The heaviest job in these lists (f=0.80, 96 cores, one seed) runs to
# completion under -Xmx512m with a peak resident set of 324 MB - so 2g was a cap nobody reached and
# sizing P from it would have under-provisioned the pool by 4x. 768m leaves headroom above the
# measured heap without pretending to reserve it.
#
# SIZE P BY CORES, then check memory against the MEASURED peak: 324 MB x P must fit in free RAM
# (P=64 is ~21 GB). preflight.sh re-measures both on the actual host, because 324 MB is this
# laptop's number and the server's JVM and page size are not obliged to agree.
JVM_OPTS="-Xmx768m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC"

TOTAL=$(grep -cve '^[[:space:]]*$' "$LIST")
echo "=== $TOTAL jobs, $PAR at a time, jar $(basename "$JAR") ==="
unzip -p "$JAR" campaign-provenance.properties 2>/dev/null | sed 's/^/    /' \
  || { echo "REFUSING: jar carries no provenance stamp" >&2; exit 1; }
if unzip -p "$JAR" campaign-provenance.properties | grep -q '^git.dirty=true'; then
  echo "REFUSING: jar built from a dirty tree - its rows cannot be tied to a commit" >&2
  exit 1
fi

ORDERED="$LOGS/ordered.txt"
if [ "$SORT_FLAG" = "--sort" ]; then
  # Longest-first by the only cost proxy available without running anything: semanticOverlap, which
  # drives cascade size and dominates runtime. Sorted descending so the f=0.80 cells start first.
  # Jobs carrying no explicit f (the panel-label jobs) sort by label instead, high before low.
  awk '{
        f = 0.25;
        if (match($0, /semanticOverlap=[0-9.]+/))  f = substr($0, RSTART+17, RLENGTH-17) + 0;
        else if ($0 ~ /A\/high/)     f = 0.80;
        else if ($0 ~ /A\/low/)      f = 0.05;
        else if ($0 ~ /control/)     f = 0.00;
        printf "%.4f\t%s\n", f, $0;
      }' "$LIST" | sort -rn -k1,1 | cut -f2- > "$ORDERED"
else
  grep -ve '^[[:space:]]*$' "$LIST" > "$ORDERED"
fi

# Index each line with a TAB so the runner can name its log, then split the whole line on TAB
# into an array. No eval and no quoting rules: an argument containing a space (the control's
# "control/no CPR" label) is one field and stays one argument.
awk -v OFS='	' '{print NR, $0}' "$ORDERED" > "$ORDERED.idx"

START=$(date +%s)
# --halt never: one failed job must not cancel the other 199. Failures are counted and named at the
# end instead, because a job that died leaves a hole that is invisible once rows are concatenated.
xargs -d '
' -a "$ORDERED.idx" -P "$PAR" -I{} bash -c '
  IFS=$'"'"'	'"'"' read -ra F <<< "{}"
  n="${F[0]}"
  args=("${F[@]:1}")
  log="'"$LOGS"'/job$(printf %04d "$n").log"
  if java '"$JVM_OPTS"' -cp "'"$JAR"'" "${args[@]}" '"$DRIVER"' > "$log" 2>&1; then
    grep -q "^Wrote " "$log" || echo "NOWRITE job$n" >> "'"$LOGS"'/failures.txt"
  else
    echo "EXIT job$n" >> "'"$LOGS"'/failures.txt"
  fi
'
END=$(date +%s)

echo
echo "=== wall clock: $(( (END - START) / 60 ))m $(( (END - START) % 60 ))s for $TOTAL jobs at P=$PAR ==="
WROTE=$(grep -l "^Wrote " "$LOGS"/job*.log 2>/dev/null | wc -l)
echo "    jobs that wrote output: $WROTE / $TOTAL"
if [ -s "$LOGS/failures.txt" ]; then
  echo "    FAILURES (rerun these lines from $ORDERED):"
  sed 's/^/      /' "$LOGS/failures.txt"
  exit 1
fi
# The slowest job sets the floor; if it is close to the wall clock, more parallelism buys nothing.
echo "    slowest job (for sizing the next run):"
for L in "$LOGS"/job*.log; do
  printf '%s %s\n' "$(stat -c %Y "$L" 2>/dev/null || echo 0)" "$L"
done | sort -rn | head -1 | sed 's/^/      /'
