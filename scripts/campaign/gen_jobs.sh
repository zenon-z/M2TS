#!/usr/bin/env bash
# Emit the job list for one experiment, one line per (cell, seed) job.
#
# A job line is a complete java argument list minus the jar, so run_jobs.sh needs no knowledge of
# what any experiment is - it just runs lines. That is deliberate: the alternative, a runner that
# knows about experiments, is where a job list and its runner drift apart and a cell silently
# stops being submitted.
#
# Usage:  gen_jobs.sh <cores|fmu|fmu_alpha|fmu_fill|factorial> <output-root>
set -uo pipefail

EXP="${1:?usage: gen_jobs.sh <cores|fmu|fmu_alpha|fmu_fill|factorial> <output-root>}"
ROOT="${2:?usage: gen_jobs.sh <cores|fmu|fmu_alpha|fmu_fill|factorial> <output-root>}"
SEEDS=5

# ONE ARGUMENT PER PARAMETER, joined with TABS.
#
# The control's label is literally "control/no CPR" - it contains a space - so a space-joined line
# word-splits into "-Dts.wl.only=control/no" and "CPR" and every control job dies on an unparsable
# flag. Tabs make the argument boundaries explicit and run_jobs.sh splits on them, so no quoting or
# eval is needed anywhere. Every caller below therefore passes each -D flag as its OWN argument;
# passing pre-joined groups would put the spaces back inside a field.
emit() {
  local out="" a
  for a in "$@"; do out+="${a}"$'\t'; done
  printf '%s\n' "${out%$'\t'}"
}

case "$EXP" in
  # ---------------------------------------------------------------- 1. cores to 96
  # 10 DISTINCT configurations. panelConfigs() has 13 labels but only 10 distinct parameter sets:
  # A/mid, B/mid, C/high and D/alpha=1.0 are all (f=0.25, mu=2, m=5, alpha=1.0), the shared base.
  # A/mid stands for it here and the merge fans its rows out to the other three labels, so the base
  # cell is computed once instead of four times (75 jobs saved off the naive 325).
  cores)
    LABELS=("control/no CPR" "A/low" "A/mid" "A/high" "B/low" "B/high" "C/low" "C/mid"
            "D/alpha=0.05" "D/alpha=100.0")
    for X in 32 48 64 96; do
      for L in "${LABELS[@]}"; do
        for I in $(seq 0 $((SEEDS - 1))); do
          emit "-Dts.wl.axis=cores" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=$X" \
               "-Dts.wl.only=$L" "-Dts.wl.seedIndex=$I" \
               "-Dts.experiments.outputDir=$ROOT/cores"
        done
      done
    done
    # CROSS-CHECK: C/high is a duplicate label for the base cell. WorkloadCellId is keyed on the
    # PARAMETER VALUES and not the label, so these must come back bit-identical to A/mid at 64
    # cores. If they do not, the merge's label fan-out is wrong - and a wrong fan-out produces a
    # perfectly plausible figure, so this is the only thing that catches it.
    for I in $(seq 0 $((SEEDS - 1))); do
      emit "-Dts.wl.axis=cores" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=64" \
           "-Dts.wl.only=C/high" "-Dts.wl.seedIndex=$I" \
           "-Dts.experiments.outputDir=$ROOT/cores_xcheck"
    done
    ;;

  # ---------------------------------------------------------------- 2. f x mu
  # A CROSS, not a product: f varies at mu=2, mu varies at f=0.4. The shared (0.4, 2) point is
  # emitted ONCE, in the f group.
  fmu)
    for F in 0.2 0.4 0.6 0.8; do
      for I in $(seq 0 $((SEEDS - 1))); do
        emit "-Dts.wl.axis=theta" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=0.5" \
             "-Dts.wl.semanticOverlap=$F" "-Dts.wl.avgCprs=2" "-Dts.wl.seedIndex=$I" \
             "-Dts.experiments.outputDir=$ROOT/fmu"
      done
    done
    for MU in 1 3 4; do   # 2 omitted: the shared point, already emitted above at f=0.4
      for I in $(seq 0 $((SEEDS - 1))); do
        emit "-Dts.wl.axis=theta" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=0.5" \
             "-Dts.wl.semanticOverlap=0.4" "-Dts.wl.avgCprs=$MU" "-Dts.wl.seedIndex=$I" \
             "-Dts.experiments.outputDir=$ROOT/fmu"
      done
    done
    ;;

  # ---------------------------------------------------------------- 2b. f x mu, at both alphas
  # The same seven f*mu cells as `fmu`, run at alpha = 0.05 and alpha = 100 so the concentration
  # comparison exists ABOVE the threshold as well as below it.
  #
  # WHY IT IS NEEDED. Cells carrying both alphas today are f*mu = 0.05, 0.2, 0.5, 0.8 and 3.2 -
  # every one of them except 3.2 below f*mu = 1, and 3.2 far above it. The f*mu grid cells that sit
  # either side of the threshold (1.2 and 1.6) were run at alpha = 1 only, so there is exactly one
  # alpha comparison above 1 and none near it. These 14 cells put an alpha pair at 0.4, 0.8, 1.2
  # and 1.6, reached along BOTH the f path and the mu path.
  #
  # Two groups again, because the f*mu set is a CROSS and not a product: a single invocation with
  # all four f values and all four mu values would be 16 cells, not 7. f=0.4, mu=2 is emitted once,
  # in the f group.
  fmu_alpha)
    for A in 0.05 100; do
      for F in 0.2 0.4 0.6 0.8; do
        for I in $(seq 0 $((SEEDS - 1))); do
          emit "-Dts.wl.axis=theta" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=0.5" \
               "-Dts.wl.semanticOverlap=$F" "-Dts.wl.avgCprs=2" "-Dts.wl.cprAlpha=$A" \
               "-Dts.wl.seedIndex=$I" \
               "-Dts.experiments.outputDir=$ROOT/fmu_alpha"
        done
      done
      for MU in 1 3 4; do   # 2 omitted: the shared point, already emitted above at f=0.4
        for I in $(seq 0 $((SEEDS - 1))); do
          emit "-Dts.wl.axis=theta" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=0.5" \
               "-Dts.wl.semanticOverlap=0.4" "-Dts.wl.avgCprs=$MU" "-Dts.wl.cprAlpha=$A" \
               "-Dts.wl.seedIndex=$I" \
               "-Dts.experiments.outputDir=$ROOT/fmu_alpha"
        done
      done
    done
    ;;

  # ---------------------------------------------------------------- 2c. fill in the f x mu grid
  # THE CELLS THE ALPHA PANELS ARE MISSING, and ONLY those. The panel figure plots throughput
  # against f at both alphas, one panel per mu; today the panels carry different f values, mu=3 has
  # a single f and cannot be drawn at all, and at mu=4 the two curves cross somewhere in the gap
  # between f=0.05 and f=0.4 - so where the crossing happens is guesswork in the panel that shows
  # the effect most strongly. This completes f = 0.05, 0.2, 0.4, 0.6, 0.8 at mu = 1, 2, 3, 4.
  #
  # THE LIST IS THE COMPLEMENT OF WHAT EXISTS, computed from the merged CSVs and not from memory:
  # 20 cells in the full grid, 11 already measured at both alphas across the fmu_alpha run, the
  # factorial corners and the base cell, leaving these 9. Re-deriving the whole grid would rerun
  # 110 jobs to reproduce numbers that already exist, and - worse - would produce a SECOND set of
  # rows for the same cells, which is how a mean silently becomes an average of two campaigns.
  #
  #   mu=1: f=0.2, 0.6      (has 0.05, 0.4, 0.8)
  #   mu=2: f=0.05          (has 0.2, 0.25, 0.4, 0.6, 0.8)
  #   mu=3: f=0.05, 0.2, 0.6, 0.8   (has 0.4 only)
  #   mu=4: f=0.2, 0.6      (has 0.05, 0.4, 0.8)
  #
  # 9 cells x 2 alphas x 5 seeds = 90 jobs. Output goes to its own directory so these rows are
  # separable from the fmu_alpha run's for as long as anyone wants to check them apart.
  fmu_fill)
    CELLS="1:0.2 1:0.6 2:0.05 3:0.05 3:0.2 3:0.6 3:0.8 4:0.2 4:0.6"
    for A in 0.05 100; do
      for C in $CELLS; do
        MU="${C%%:*}"; F="${C##*:}"
        for I in $(seq 0 $((SEEDS - 1))); do
          emit "-Dts.wl.axis=theta" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=0.5" \
               "-Dts.wl.semanticOverlap=$F" "-Dts.wl.avgCprs=$MU" "-Dts.wl.cprAlpha=$A" \
               "-Dts.wl.seedIndex=$I" \
               "-Dts.experiments.outputDir=$ROOT/fmu_fill"
        done
      done
    done
    ;;

  # ---------------------------------------------------------------- 3. 2^3 factorial
  # Levels are the campaign's own low/high panel values, so every corner is a cell the rest of the
  # paper already characterises: f 0.05/0.80 (A/low, A/high), mu 1/4 (B/low, B/high),
  # alpha 0.05/100 (D's outer points). Both thetas as separate jobs, so a job is one
  # (cell, theta, seed) and the two thetas finish independently.
  factorial)
    for TH in 0.5 0.8; do
      for F in 0.05 0.80; do
        for MU in 1 4; do
          for A in 0.05 100; do
            for I in $(seq 0 $((SEEDS - 1))); do
              emit "-Dts.wl.axis=theta" "-Dts.wl.seeds=$SEEDS" "-Dts.wl.axisOnly=$TH" \
                   "-Dts.wl.semanticOverlap=$F" "-Dts.wl.avgCprs=$MU" "-Dts.wl.cprAlpha=$A" \
                   "-Dts.wl.seedIndex=$I" \
                   "-Dts.experiments.outputDir=$ROOT/factorial_th$TH"
            done
          done
        done
      done
    done
    ;;
  *)
    echo "unknown experiment: $EXP (expected cores|fmu|fmu_alpha|fmu_fill|factorial)" >&2
    exit 1
    ;;
esac
