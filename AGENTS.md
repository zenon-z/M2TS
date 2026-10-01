# Working in this repository

Command reference and the invariants that are easy to break. Read the README first for what the
project is.

## Build

    mvn -DskipTests package     # target/ts-simulator-1.0.0-SNAPSHOT.jar, dependencies included
    mvn test                    # 499 tests, 2 skipped

Java 17. The figure scripts need Python 3.10 with matplotlib, numpy, scipy.

On Windows use a short checkout path; some generated filenames are long enough to hit the
260-character limit from a deep directory.

## Verifying a build

Run the base cell and compare three numbers:

    java -cp target/ts-simulator-1.0.0-SNAPSHOT.jar \
         -Dts.wl.thetaRange=true -Dts.wl.thetas=0.5 \
         de.tum.tssimulator.experiments.WorkloadAlphaScan

The `theta = 0.50` row must read `tps 1883.0`, `cap_share 0.785`, `sdr 1.203`. The engine is
deterministic given a seed, so these are exact, not approximate. A changed number means the
behaviour changed — treat it as a failure, not as noise, and find the cause before running
anything long.

This is the cheapest end-to-end check in the repository. Use it after any engine change.

## Invariants

**One simulation per JVM.** `TransactionAggregateMetrics`, `CautiousWaitingPolicy`'s counters and
`TransactionGroup.ID_GENERATOR` are process-global. Two simulations in one JVM share and corrupt
them, and because the corruption is deterministic it reads as an ordinary between-cell difference
rather than as contamination. Parallelism comes from the shard options, one process per job.

**A shard is a strided subset of the grid, not a sample.** Never draw a figure from one shard.
`w1w4_merge.sh` rebuilds the merged files from the shards and verifies the result; it refuses to
merge a partial grid rather than record it as complete.

**Never write a trial run into `artifacts/experiments/results/` under a name a real run can
have.** Use `-Dts.experiments.outputDir=<dir>`, or `-Dts.tb.diag=<label>` for the time-breakdown
driver, which otherwise refuses to overwrite an existing file.

**`EffectiveConfigHasher` is reflective over the declared parameter fields.** Adding or removing a
field in `SimulatorParameters` changes `config_hash` for every run, so rows produced before and
after are no longer comparable. `readyWaiterOrder` and `cuQueuePolicy` are retained at their
historical defaults for this reason, even though both measured as having no effect.

**A parameter reached only by `-D` is invisible in the output.** Anything that changes behaviour
belongs in `SimulatorParameters` so it reaches `config_hash`, `SeedSweepRunner.copyBuilder` and
every row. `SeedSweepRunnerCopyBuilderRoundTripTest` fails if a new field is not covered by a
fixture, which is deliberate: an uncovered field makes its own round-trip test vacuous.

## Drivers

All are main classes in the jar and take `-D` options.

| | |
|---|---|
| `WorkloadContentionCalibration` | pre-flight gate on the operating point |
| `WorkloadAxisSweep` | the four axis sweeps, and the f×μ grids and factorial in grid mode |
| `WorkloadTimeBreakdownSweep` | where transaction time goes |
| `TgsPanelBSimCheck` | simulator side of the PostgreSQL comparison |
| `WorkloadAlphaScan` | concentration-axis scan, and the reference cell above |

### Axis sweeps

    bash scripts/campaign/build_campaign_jar.sh
    bash scripts/campaign/w1w4_run.sh 64 5      # 64 shards, 5 seeds
    bash scripts/campaign/w1w4_merge.sh

One axis on its own:

    java -cp target/ts-simulator-1.0.0-SNAPSHOT.jar -Dts.wl.axis=theta -Dts.wl.seeds=5 \
         de.tum.tssimulator.experiments.WorkloadAxisSweep

`-Dts.wl.axis=theta|readonly|cores|txnlen`, `-Dts.wl.seeds=<n>`,
`-Dts.wl.shards=<n> -Dts.wl.shard=<i>`, `-Dts.wl.only=<panel>/<label>`.

Writes `wl_<axis>_raw.csv` plus a `_cells.csv` sidecar and a `_skipped.csv` for runs dropped by an
engine fault.

### Grids and factorial

Same driver; the flags are named after the parameters they set, and their presence switches it
into grid mode:

    java -cp target/ts-simulator-1.0.0-SNAPSHOT.jar -Dts.wl.axis=theta \
         -Dts.wl.semanticOverlap=0.05,0.2,0.4,0.6,0.8 -Dts.wl.avgCprs=1,2,3,4 \
         -Dts.wl.cprAlpha=0.05,100 -Dts.wl.seeds=5 \
         de.tum.tssimulator.experiments.WorkloadAxisSweep

    python scripts/campaign/fmu_grid_merge.py <output-dir> <axis> <cells> <seeds>

Grid rows are a different design from the axis sweep and carry a `_grid_...` tag in the filename
so the two cannot be concatenated by accident.

### Time breakdown

    java -cp target/ts-simulator-1.0.0-SNAPSHOT.jar \
         de.tum.tssimulator.experiments.WorkloadTimeBreakdownSweep

`-Dts.tb.arms=<list>`, `-Dts.tb.thetas=<list>`, `-Dts.tb.txnlen=<k>`, `-Dts.tb.seeds=<n>`,
`-Dts.tb.diag=<label>`.

### PostgreSQL comparison

    java -cp target/ts-simulator-1.0.0-SNAPSHOT.jar \
         de.tum.tssimulator.experiments.TgsPanelBSimCheck
    python scripts/analysis/bin_sim_first_attempts.py

Database side, from `scripts/postgres_anchor/`:

    docker compose up -d                            # postgres:16, user/password/database "tgs"
    psql -h localhost -U tgs -d tgs -f schema.sql
    python v2_sweep.py

`v2_sweep.py` is resumable and append-only: a re-run skips cells with a passing row and re-runs
ones that failed their gate.

## Figures

Run after the experiments; no arguments. Together these four produce 34 PDFs and nothing else.

    python scripts/plotting/plot_workload_axes.py
    python scripts/plotting/plot_time_breakdown.py
    python scripts/plotting/plot_factorial_16.py
    python scripts/plotting/plot_tgs_validation.py

Output in `artifacts/figures/`, except the validation panels, which go to
`artifacts/experiments/results/figures/`. `plot_lovelace_experiments.py` and `panel_style.py` are
imported by these, not run on their own.

`plot_workload_axes.py` skips an axis whose CSV is missing and names it; the others stop with a
message naming the driver to run.

Figures are authored at their printed size and included without scaling, which is why the scripts
set sizes in inches and do not save with a tight bounding box. Changing a figure size means
changing it here, not in LaTeX.

## Adding an experiment

Go through `SeedSweepRunner`, not a bare `SimulatorParameters.builder()`: it carries every field,
salts seeds per cell, and writes the raw-row schema. A partial builder silently reverts whatever
it omits to a default, which is a different simulation rather than an error.

Seeds must be set before a schema or a run is derived from a params object. A null seed is not a
default — `CprSchemaBuilder` falls back to `new Random(System.nanoTime())`, so the graph differs on
every invocation and the recorded columns describe no run. `WorkloadAxisDefaults.baseBuilder()`
deliberately leaves seeds unset; `assertSeedsAreRecorded` is the guard at the point of use.
