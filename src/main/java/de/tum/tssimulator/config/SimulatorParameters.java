package de.tum.tssimulator.config;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import de.tum.tssimulator.locking.policy.LockingProtocol;

/**
 * Centralized configuration for simulator parameters.
 * Supports both properties file and programmatic configuration.
 */
public class SimulatorParameters {
    /*
     * TIME UNIT - defined once, for the whole simulator.
     *
     * Every duration and every timestamp in this engine is an integer count of simulation TICKS.
     * One tick = 1 microsecond.
     *
     * Internal quantities carry NO unit suffix, because there is only one unit: virtualTime,
     * completionTime, ioTimePerResource. A suffix only earns its keep where units genuinely
     * differ, which in this codebase is exactly one place - wall-clock values read from
     * System.currentTimeMillis(), which keep an explicit *Ms and mark the boundary between
     * simulated and real time.
     *
     * Changing the unit means editing this comment and the constants at the boundaries where
     * simulation time meets the real world: config input, CSV output, and "simulate for N
     * seconds". Nothing internal needs to move.
     *
     * The unit has been changed twice - seconds could not express milliseconds, milliseconds
     * could not express a latch - and it will need changing again the moment something faster is
     * modelled. The structural fix is to stop encoding absolute durations at all and express
     * costs as multiples of a base operation (IO_COST = 2000 * LATCH), so the ratios that drive
     * the simulation never change and only one scale factor lives at the output boundary. A bare
     * literal carries no unit, so it is invisible to every tool - which is exactly how a "500ms"
     * backoff silently became 500us during the microsecond migration.
     */

    private static final String DEFAULT_CONFIG_FILE = "simulator.properties";

    // Canonical per-resource cost, in one place. The Dandamudi/Au (1991) baseline (200ms IO /
    // 100ms CPU, for both lock-acquisition and general resource-touch cost - see
    // BaselineComparisonExperiment, the only experiment that ever actually compared against that
    // paper) is retired: no experiment compares against it anymore. This is now the single
    // canonical setting for a fresh SimulatorParameters, matching the "cheap lock" convention the
    // TGS validation experiments established (acquisition/touch cost negligible, so it doesn't
    // dominate whatever the experiment is actually trying to isolate). Any experiment that needs
    // a different cost (e.g. TgsPanelBSimCheck/TgsStepQuantumDiagnostic, which need a real,
    // non-trivial IO+CPU service time for their step-quantum and Postgres-anchor calibration to
    // mean anything) must set it explicitly at its own call site with a comment saying why -
    // silently inheriting a value you didn't choose is exactly the bug this constant exists to
    // make impossible.
    public static final int CANONICAL_IO_TIME_PER_RESOURCE = 1000;
    public static final int CANONICAL_CPU_TIME_PER_RESOURCE = 1000;
    // Lock service is a hash lookup in an in-memory lock table plus a few atomic operations, against
    // a data access of 2000 (an uncached random page read from disk-resident storage, plus the CPU
    // to process the record), i.e. a ratio of 1:1000 rather than 1:1 or 1:100.
    //
    // These were 1000 apiece, equal to a data access, and NOT because anyone chose that: the old
    // integer-millisecond base made 1 the smallest expressible non-zero cost, so a latch and a disk
    // read could not be told apart. At k=25 that charged 50,000 of lock service against 50,000 of
    // data access - half of every transaction's service demand spent in the ACT of taking locks,
    // which penalises claim-as-needed for no physical reason.
    //
    // This does NOT make locking negligible. Contention here comes from lock HOLD DURATION - how
    // long locks are kept while executing and while waiting for a group - which is driven by data
    // access and is untouched. What becomes negligible is acquisition OVERHEAD.
    public static final int CANONICAL_LOCK_IO_TIME_PER_RESOURCE = 1;
    public static final int CANONICAL_LOCK_CPU_TIME_PER_RESOURCE = 1;

    /**
     * A rollback costs exactly the work it undoes.
     *
     * <p>Undoing a resource's update is the same order of work as making it, so the per-resource
     * rollback cost is DERIVED from the data-access cost rather than restated as a literal - if the
     * data access ever changes, this follows automatically instead of silently drifting out of
     * proportion. There is no fixed term: a rollback with no resources undoes nothing.
     *
     * <p>These were {@code 50,000} fixed plus {@code 10,000} per resource, which at k=25 made a
     * rollback `50,000 + 25 x 10,000 = 300,000` against a transaction's total service of 50,000 -
     * **six times the transaction it undoes**, at 42-190 aborts per run, examined by nobody and
     * echoed in no output.
     *
     * <p>The asymmetry mattered more than the ratio: {@code IOModel.getRollbackTime} charges for the
     * union of ALL group members' resources, so the dynamic arm paid for its whole closure while the
     * static arm paid for one transaction - a per-arm difference in abort cost, on a study that
     * compares arms partly by abort behaviour. With the cost equal to the work undone, that same
     * union scaling becomes correct rather than distorting: undoing a group IS undoing every
     * member's work.
     */
    private static final long DEFAULT_ROLLBACK_FIXED = 0;
    private static final long DEFAULT_ROLLBACK_CPU_TIME_PER_RESOURCE =
            CANONICAL_IO_TIME_PER_RESOURCE + CANONICAL_CPU_TIME_PER_RESOURCE;
    // Default property count assigned to every model when propertiesPerModel isn't explicitly
    // set (only reachable via the Builder). Chosen so that totalModels=1/totalResources=5000
    // (today's SimulatorParameters.defaultParameters()) derives instancePoolSize=1000,
    // propertiesPerModel=[5] -> 1000*5=5000, i.e. the same resource count as a flat sizing.
    private static final int DEFAULT_PROPERTIES_PER_MODEL_COUNT = 5;

    // Model configuration
    public final int totalModels;
    public final int totalResources;
    // New first-class knobs for the object-based resource model: one property count per model,
    // and one global instance id pool shared across every model. When constructed via a legacy
    // (totalModels, totalResources, ...) constructor, both are derived from those two values
    // (see defaultPropertiesPerModel/defaultInstancePoolSize) so every existing call site keeps
    // compiling and keeps a comparable resource-space magnitude. Only the Builder can override
    // them explicitly.
    public final List<Integer> propertiesPerModel;
    public final int instancePoolSize;
    public final int ioTimePerResource;
    public final int cpuTimePerResource;
    public final int lockIOTimePerResource;
    public final int lockCPUTimePerResource;
    public final long rollbackFixed;
    public final long rollbackCPUTimePerResource;

    // Transaction configuration
    public final int initialTransactions;
    public final int totalTransactionSteps;
    public final int maxTransactionModels;
    public final int maxTransactionResources;
    public final boolean useConstantTransactionSize; // If true, all transactions have exactly constantTransactionSize
                                                     // resources
    public final int constantTransactionSize; // Fixed transaction size when useConstantTransactionSize is true
    public final TransactionSizeProfile transactionSizeProfile; // Uniform, fixed-size, or bimodal size sampling
    public final int smallTransactionSize; // Resource count for the "small" transaction class
    public final int largeTransactionSize; // Resource count for the "large" transaction class
    public final double largeTransactionProbability; // Probability of sampling the large class in bimodal mode
    public final boolean useRandomResourceSelection; // If true, resources selected randomly; if false, sequential (default)

    // Bookkeeper configuration
    public final double semanticOverlap; // 0.0 to 1.0: percentage of resources with cross-model constraints
    public final int maxCprsPerResource; // Maximum outgoing CPRs per resource with at least one CPR
    public final double avgCprsPerResource; // Expected outgoing CPRs under the global fan-out model
    public final CprTopologyMode cprTopologyMode; // Controls how valid CPR targets are selected
    public final ReferentialAction referentialAction; // ON_CASCADE or ON_RESTRICT

    // Concurrency configuration
    public final ConcurrencyModel concurrencyModel; // ACID or BASE
    // On group completion/abort, CapacityWaitManager.wakeNext() drains up to this many waiters
    // (ready-queue first, then pending) instead of exactly one. Default 1 preserves today's
    // single-wake behavior for every existing caller that doesn't set it explicitly.
    public final int admissionBatchSize;
    public final DeadlockStrategy deadlockStrategy; // Deadlock handling strategy

    // Simulation configuration
    // F4 (VLDB reviewer response): seconds of simulated time to discard as warm-up before
    // steady-state metrics start accumulating (SimulationMetrics.markWarmupBoundary()). 0 (the
    // default for every legacy constructor/call site) means no discard - identical to
    // pre-F4 behavior. Only the Builder can set this to a nonzero value.
    public final int warmupSeconds;
    public final int simulationDurationSeconds;
    public final int numberOfIoProcessors;
    public final int numberOfCpuProcessors;
    public final int numberOfReconcilerProcessors; // For BASE: number of dedicated reconciliation processors
    public final int totalLocks;
    public final long stepTimeSlice;
    public final TransactionMode transactionMode;
    public final int numTerminals;
    public final long meanInterArrivalDelay;
    public final TransactionGenerationMode transactionGenerationMode;
    // RETIRED - V2 (z60). These three are accepted and stored, and NOTHING IN THE ENGINE
    // READS ANY OF THEM. That was true at cf3dcd8 too, the commit whose message says the
    // Simulator main loop was refactored to monitor the beta metric: at that commit the
    // threshold appears only in SimulatorConfig, SimulatorParameters and
    // WfgAdaptiveBetaExperiment. The reader was never added.
    //
    // WfgAdaptiveBetaExperiment swept betaThreshold across seven scenarios and produced a CSV
    // in which 58 of 59 measured columns are IDENTICAL - only wall_clock_ms moved, and
    // non-monotonically, so that output measures host jitter and nothing else.
    //
    // Retired rather than deleted, following the Dandamudi/Au cost-constant precedent above:
    // still accepted so existing call sites compile, documented as inert so nobody reads a
    // result off them. They are exempted BY NAME in SimulatorParameterReachabilityTest, which
    // fails if any OTHER field becomes equally unreachable.
    public final long deadlockDetectionInterval;
    public final double deadlockDetectionBetaThreshold;
    public final long deadlockDetectionMinCooldown;

    // Transaction sampling configuration
    public final Long transactionSamplingSeed; // null = non-deterministic (System.nanoTime), non-null = deterministic
    public final Long semanticOverlapSeed; // null = non-deterministic (System.nanoTime), non-null = deterministic for
                                           // bookkeeper creation

    // D-oracle arm (VLDB reviewer response, dynamic-footprint follow-up): when true, each
    // RANDOM_OPERATIONS-mode transaction's resource set is expanded, at creation time, from its
    // sampled seed resources to the FULL CPR transitive closure of those seeds (CprSchema.closure()
    // - the same schema SchemaCprFactory would otherwise discover hop-by-hop via the bookkeeper
    // cascade), then preclaimed atomically via the existing ACID whole-footprint lock acquisition
    // - the same C2PL primitive the static arm already uses for its fixed k. This isolates the
    // acquisition PROTOCOL (atomic upfront vs. incremental cascade) from the footprint SIZE: same
    // schema, same eventual resource set as the dynamic arm converges to, different admission-time
    // commitment. Default false preserves every existing caller's behavior unchanged.
    public final boolean oracleClosurePreclaim;

    /**
     * Where the inlined arm puts the closure inside the parent's operation list. Assigned
     * post-construction like {@code workloadMix}, not threaded through the terminal constructor, so
     * the dozens of existing direct-constructor callers keep compiling and keep AT_END - which is
     * the behaviour every one of them was written against. See {@link OracleClosurePlacement}.
     */
    public OracleClosurePlacement oracleClosurePlacement = OracleClosurePlacement.AT_END;

    /**
     * LOCK processor pool size. Null keeps the historical behaviour,
     * {@code min(numberOfIoProcessors, numberOfCpuProcessors)} - the last live remnant of the
     * MPL = cores coupling, whose stated rationale ("the same admission cap already bounding how
     * many transactions can be RUNNING at once", DefaultExecutionBundle:152) stopped being true
     * when MPL became an independent parameter. Set it to decouple the lock stage's width from the
     * core count.
     */
    public Integer lockPoolSize = null;
    /** Model indices ORIGINAL transactions may draw from; empty = all. See
     *  SimulatorConfig.transactionModelPool(). CPR targets are unaffected.
     *
     *  <p>NOT final only because the terminal constructor is delegated to by every legacy
     *  positional constructor; adding a parameter there would touch all of them for a field
     *  they all default. {@link Builder#build()} assigns it once, immediately after
     *  construction, and nothing else ever writes it. */
    public List<Integer> transactionModelIndices;

    /**
     * How a transaction's footprint is drawn from the resource space; default
     * {@link ResourceAccessDistribution#UNIFORM}, which is the historical behaviour.
     *
     * <p>NOT final, for the same reason as {@link #transactionModelIndices} above: the terminal
     * constructor is delegated to by every legacy positional constructor, and adding parameters
     * there would touch all six for fields they all default - the positional-argument
     * mis-ordering that has already cost this repo four silent-corruption incidents through
     * {@code SeedSweepRunner.copyBuilder}. {@link Builder#build()} assigns it once, immediately
     * after construction, and nothing else ever writes it.
     */
    public ResourceAccessDistribution accessDistribution;

    /**
     * Zipf skew exponent over instance ids; default {@code 0.0}, meaningful only when
     * {@link #accessDistribution} is {@code ZIPF}.
     *
     * <p><b>{@code 0.0} short-circuits to the UNIFORM code path verbatim</b>, so a ZIPF run at
     * theta 0 is bit-identical to a UNIFORM run rather than merely equal in distribution. Higher
     * values concentrate access on low instance ids.
     *
     * <p>This is a <em>knob</em>, not a measurement: drawing distinct resources truncates the head
     * of the distribution, so the realised skew is flatter than theta suggests and the gap grows
     * with footprint size. Report {@code realized_top1pct_access_share}, not theta, as the
     * independent variable.
     *
     * <p>NOT final - see {@link #accessDistribution}.
     */
    public double zipfTheta;

    /**
     * How operation types are assigned to sampled resources; default
     * {@link WorkloadMix#PER_OPERATION}, which is the historical behaviour.
     *
     * <p>NOT final - see {@link #accessDistribution}.
     */
    public WorkloadMix workloadMix;

    /**
     * Probability a transaction is drawn as the read-only class under
     * {@link WorkloadMix#TWO_CLASS}; default {@code 0.0}. Ignored under {@code PER_OPERATION}.
     *
     * <p>NOT final - see {@link #accessDistribution}.
     */
    public double readOnlyTransactionFraction;

    /**
     * Cap on CONCURRENTLY RUNNING transactions, i.e. admission capacity. {@code null} means
     * "derive from the processor count" ({@code numCpuProcessors}), which is the historical
     * behaviour and keeps every existing caller unchanged.
     *
     * <p><b>This exists because the derived form conflates three things</b> - service parallelism,
     * admission pressure, and (via the ROLLBACK priority gate in {@code DefaultExecutionBundle})
     * cleanup capacity. Sweeping processors therefore sweeps offered concurrency at the same
     * time, which is what setting this parameter explicitly separates.
     *
     * <p><b>It is a PARAMETER and not a system property on purpose.</b> The pre-existing
     * {@code -Dts.simulator.disableMplLimit} escape hatch does the same job but reaches neither
     * {@code EffectiveConfigHasher} nor {@code SeedSweepRunner.copyBuilder} nor any output row, so
     * runs made under it are indistinguishable afterwards from capped runs. A params field is
     * recorded everywhere by construction.
     *
     * <p><b>Bounded mode depends on ROLLBACK being ungated.</b> Gating the ROLLBACK priority queue
     * on {@code admissionCapacity - count(RUNNING)} starves rollback the moment the system
     * saturates, and an un-rolled-back ABORTED transaction is then dispatched as ordinary work. A
     * cap EQUAL to the live population never binds, so it hides this; a cap BELOW the population -
     * which is the point of a bounded cell - hits it immediately. Rollback is therefore ungated,
     * restarts re-entering through PENDING where the cap applies. Reinstating that gate would make
     * bounded mode unsafe again; see {@code DefaultExecutionBundle}.
     *
     * <p>NOT final - see {@link #accessDistribution}.
     */
    public Integer maxConcurrentRunning;

    /**
     * Price-style preferential attachment on CPR in-degree: a target is drawn with probability
     * proportional to {@code (inDegree + alpha)}. {@code null} = the historical uniform target
     * draw, bit-identical.
     *
     * <p>Known in the literature as the <b>initial attractiveness</b> constant (Dorogovtsev,
     * Mendes and Samukhin 2000; the directed/in-degree setting is Price 1976). It must be
     * {@code > 0}: every in-degree starts at zero, so a purely multiplicative rule could never
     * pick a first target - which is exactly why the additive constant exists.
     *
     * <p>Large alpha swamps the in-degree term and converges back to uniform; small alpha lets
     * already-targeted properties keep being chosen, so hotspots emerge. This replaces the named
     * topologies as a CONTINUOUS axis - see {@code CprSchemaBuilder}'s constructor javadoc for why
     * that also means running {@code UNIFORM} as the model graph.
     *
     * <p>NOT final - see {@link #accessDistribution}.
     */
    public Double cprInitialAttractiveness;

    /**
     * Multiplier on BOTH exponential-backoff base delays - lock-retry and abort-retry alike.
     * Default 1.0 leaves the 500 ms bases exactly as they were.
     *
     * <p>WHY THIS EXISTS. The time-breakdown figure shows backoff as 44% of transaction time at
     * alpha=0.05/theta=0.8, and backoff is a POLICY constant, not a workload property: at ~0.10
     * restarts per completion, 705k ticks of backoff is ~7 s per restart, which is the schedule
     * (500 ms x 2^retry) and nothing else. So a reviewer can reasonably ask whether the high-skew
     * collapse is an artifact of the retry settings. This knob answers that with a 0.5 / 1 / 2
     * sweep on one cell.
     *
     * <p>ONE SCALE, NOT TWO BASES. Backoff feeds the figure twice - the abort schedule drives the
     * backoff segment and the lock-retry schedule drives lock wait - so parameterising only the
     * abort base would answer half the question and invite the same objection about the other
     * half. Two independent fields would invite a 3x3 grid for no extra insight; one scale keeps
     * the sensitivity sweep one-dimensional. The CAPS are deliberately left alone: they bound the
     * worst case, and moving them would change the shape of the schedule rather than its scale.
     *
     * <p>A PARAMS FIELD, NOT A SYSTEM PROPERTY, and that distinction is load-bearing here. A
     * {@code -D} switch reaches neither {@code EffectiveConfigHasher} nor
     * {@code SeedSweepRunner.copyBuilder}, so three runs at different backoff scales would carry
     * IDENTICAL config_hash values - three configurations indistinguishable in the output, which
     * is also what {@code -Dts.simulator.disableMplLimit} does to a capped run.
     *
     * <p>The constant this scales has moved results before without anyone choosing it: see
     * BackoffQueue, where "500ms" silently became 500us in the microsecond migration - a 1000x
     * more aggressive retry that collapsed throughput 6x with no compile error and no test naming
     * it. Which is why the default must be shown to reproduce the reference cell bit-for-bit
     * rather than assumed to.
     */
    public double backoffScale;

    /**
     * Wake order for READY groups waiting on admission capacity. See
     * {@link ReadyWaiterOrder}.
     *
     * <p>A PARAMS FIELD, NOT A SYSTEM PROPERTY, for the reason backoffScale records: a -D
     * switch reaches neither EffectiveConfigHasher nor SeedSweepRunner.copyBuilder, so the
     * two arms of the ordering experiment would carry IDENTICAL config_hash values and be
     * indistinguishable in the output rows - visible only in a filename.
     */
    public ReadyWaiterOrder readyWaiterOrder;

    /**
     * Where a triggered CU is placed in PENDING. See {@link CuQueuePolicy}.
     *
     * <p>A params field for the backoffScale reason: the arms must differ in config_hash.
     */
    public CuQueuePolicy cuQueuePolicy;

    /**
     * When true, CPR links go only from lower- to higher-ordered properties under a seeded total
     * order, making the schema a DAG BY CONSTRUCTION regardless of the model graph.
     *
     * <p>Restores the property-level ordering {@code SimpleCPRFactory} had, deleted with it in
     * {@code 1fababf}. It is what makes {@code UNIFORM} usable as the single model graph: UNIFORM
     * is cyclic at the model level, so without the order, cascade depth is bounded only by the
     * runtime visited set rather than being a well-defined property of the schema.
     *
     * <p>NOT final - see {@link #accessDistribution}.
     */
    public boolean cprAcyclicTotalOrder;

    // Which lock-acquisition protocol this simulation runs under (see LockingProtocol's
    // javadoc). Default C2PL preserves every existing caller's behavior unchanged - including
    // the many experiment classes that instead toggle ACIDAdmissionController.ENABLE_C2PL
    // directly, which SimulatorContext.create() still syncs from this field for backward
    // compatibility (see its javadoc) rather than requiring every one of those call sites to
    // migrate immediately.
    public final LockingProtocol lockingProtocol;

    /**
     * Probability that a sampled SEED resource becomes a WRITE operation under
     * {@code RANDOM_OPERATIONS} generation. Derived, not drawn - see
     * {@code TransactionFactory.operationTypeFor}.
     *
     * <p><b>Default 1.0 is legacy-compatibility, not a modelling recommendation.</b> All-WRITE
     * means every resource takes an EXCLUSIVE lock (identical to the per-transaction lock mode this
     * replaced) and every resource is a CPR source, so a default-configured run reproduces
     * pre-synthesis behaviour exactly. That identity is what the operation-level validation ladder
     * uses as its rung-0 control; it is not a claim that real workloads are all-write. Set it
     * explicitly (e.g. 0.5) for any run that is meant to exercise mixed read/write behaviour.
     */
    public final double writeFraction;

    /**
     * Operation type attached to every resource of a cascade-generated CU.
     *
     * <p>WRITE by construction, matching what {@code BookkeeperTransaction}'s constructor already
     * hardcoded - a CU exists to propagate a CPR, and a CU with no WRITE operation produces nothing
     * once CPR triggering filters on WRITE, which would kill a cascade after its first hop.
     * Promoted from a constructor comment to config so the consequence is on record rather than
     * incidental: C are all-WRITE while parents are mixed, so a closure's read/write mix is a
     * function of cascade depth by design.
     */
    public final OperationType cuOperationType;

    /**
     * Whether only WRITE resources seed CPR bookkeepers.
     *
     * <p><b>Default true, because that is what preserves current behaviour everywhere.</b> Program
     * mode already filtered to WRITE (its transactions always had typed operations), so a false
     * default would silently start producing bookkeepers for read-only programs - caught by
     * {@code SchemaCprFactoryTest}. Under {@code RANDOM_OPERATIONS} at the default
     * {@code writeFraction = 1.0} every operation is a WRITE, so the filter selects every resource
     * and the flag is a no-op there; it only begins to bite once {@code writeFraction < 1.0}.
     *
     * <p>The flag is explicit rather than inferred from "does this transaction have operations"
     * because synthesis makes operations always present. Left implicit, CPR fan-out would become a
     * function of {@code writeFraction} - coupling cascade width to a parameter meant to control
     * lock modes, and moving two rungs of the validation ladder for one underlying reason. Set it
     * false to hold fan-out fixed while varying modes.
     */
    public final boolean writeOnlyCprSources;

    /**
     * Whether a transaction group's members share ONE processor unit (default {@code true}).
     *
     * <p>This is a MODELLING CLAIM about what a cascade is, not a fairness adjustment. A
     * bookkeeper (CU) is a continuation of its parent's work on resources the parent already
     * touched - the same logical unit of execution, not an independent transaction that merely
     * happens to be related. So a group occupies one unit and its members run one after another,
     * which is also what makes the dynamic arm comparable to the oracle arm, whose closure is
     * folded into a single monolithic transaction at creation
     * ({@code TransactionFactory.expandToOracleClosure}) and therefore cannot occupy more than one
     * unit by construction.
     *
     * <p>Set {@code false} and a group's members are dispatched to whatever units are free, running
     * concurrently. That is a DIFFERENT MODEL, not a bug fix: it claims CUs are independent
     * transactions. It is worth roughly +41-70% dynamic-arm throughput on the ladder cell, which is
     * why the flag exists at all - a value that large must be visible in the run output rather than
     * inherited silently. Static cells are unaffected either way (single-member groups have nothing
     * to pin), which is the invariant to check when changing it.
     *
     * <p>See {@code TransactionGroup.pinnedIoUnitIndex} for the mechanism. It is echoed into
     * every output row because it is not neutral between arms.
     */
    public final boolean groupUnitAffinity;

    /**
     * Operations whose locks an S2PL transaction acquires before it starts executing them.
     *
     * <p><b>0 = the whole footprint, and is the default</b>, which is the pre-interleaving
     * behaviour: one contiguous LOCK phase over every lock the transaction will ever need. At that
     * setting {@code S2PLTransactionLockCoordinator.acquireBatchLocks} delegates literally to
     * {@code acquireAllLocks} and the operation cursor is never engaged, so nothing downstream
     * fires. 1 is full interleaving; anything between is a partial batch.
     *
     * <p>Ignored under C2PL, whose defining property is atomic preclaim of the whole footprint.
     */
    public final int operationBatchSize;

    /**
     * Primary constructor with all parameters including referentialAction
     */
    public SimulatorParameters(int totalModels, int totalResources, int ioTimePerResource, int cpuTimePerResource,
            int lockIOTimePerResource, int lockCPUTimePerResource, int initialTransactions,
            int totalTransactionSteps, int maxTransactionModels, int maxTransactionResources,
            boolean useConstantTransactionSize, int constantTransactionSize,
            TransactionSizeProfile transactionSizeProfile, int smallTransactionSize, int largeTransactionSize,
            double largeTransactionProbability,
            boolean useRandomResourceSelection,
            double semanticOverlap, int maxCprsPerResource, double avgCprsPerResource, CprTopologyMode cprTopologyMode,
            ReferentialAction referentialAction,
            ConcurrencyModel concurrencyModel,
            DeadlockStrategy deadlockStrategy,
            int simulationDurationSeconds, int numberOfIoProcessors, int numberOfCpuProcessors,
            int numberOfReconcilerProcessors, int totalLocks, Long transactionSamplingSeed,
            Long semanticOverlapSeed, long stepTimeSlice,
            TransactionMode transactionMode, int numTerminals, long meanInterArrivalDelay,
            long rollbackFixed, long rollbackCPUTimePerResource,
            TransactionGenerationMode transactionGenerationMode,
            long deadlockDetectionInterval, double deadlockDetectionBetaThreshold, long deadlockDetectionMinCooldown) {
        // Delegates to the canonical constructor with propertiesPerModel/instancePoolSize
        // derived from (totalModels, totalResources) - every legacy positional constructor
        // funnels through here, so they all keep compiling and get a comparable resource-space
        // magnitude without needing to know about the two new fields.
        this(totalModels, totalResources, ioTimePerResource, cpuTimePerResource,
                lockIOTimePerResource, lockCPUTimePerResource, initialTransactions,
                totalTransactionSteps, maxTransactionModels, maxTransactionResources,
                useConstantTransactionSize, constantTransactionSize,
                transactionSizeProfile, smallTransactionSize, largeTransactionSize,
                largeTransactionProbability,
                useRandomResourceSelection,
                semanticOverlap, maxCprsPerResource, avgCprsPerResource, cprTopologyMode,
                referentialAction,
                concurrencyModel,
                deadlockStrategy,
                simulationDurationSeconds, numberOfIoProcessors, numberOfCpuProcessors,
                numberOfReconcilerProcessors, totalLocks, transactionSamplingSeed,
                semanticOverlapSeed, stepTimeSlice,
                transactionMode, numTerminals, meanInterArrivalDelay,
                rollbackFixed, rollbackCPUTimePerResource,
                transactionGenerationMode,
                defaultPropertiesPerModel(totalModels),
                defaultInstancePoolSize(totalResources, totalModels),
                1, 0, // admissionBatchSize: 1, warmupSeconds: 0
                deadlockDetectionInterval, deadlockDetectionBetaThreshold, deadlockDetectionMinCooldown);
    }

    /**
     * Legacy 14-argument constructor for backward compatibility with simple tests.
     */
    public SimulatorParameters(int totalModels, int totalResources, int ioTimePerResource, int cpuTimePerResource,
            int lockIOTimePerResource, int lockCPUTimePerResource, int initialTransactions,
            int totalTransactionSteps, int maxTransactionModels, int maxTransactionResources,
            int numberOfIoProcessors, int numberOfCpuProcessors,
            int numberOfReconcilerProcessors, int totalLocks) {
        this(totalModels, totalResources, ioTimePerResource, cpuTimePerResource,
                lockIOTimePerResource, lockCPUTimePerResource, initialTransactions,
                totalTransactionSteps, maxTransactionModels, maxTransactionResources,
                false, 50,
                TransactionSizeProfile.UNIFORM, 10, 100, 0.5,
                false,
                0.05, 1, 1.0, CprTopologyMode.UNIFORM,
                ReferentialAction.ON_CASCADE,
                ConcurrencyModel.ACID,
                DeadlockStrategy.CAUTIOUS_WAITING,
                /* simulationDurationSeconds */ 10000, numberOfIoProcessors, numberOfCpuProcessors,
                numberOfReconcilerProcessors, totalLocks, /* transactionSamplingSeed */ null,
                /* semanticOverlapSeed */ null, /* stepTimeSlice */ 1_000_000L,
                TransactionMode.EQUILIBRIUM, /* numTerminals */ 200,
                /* meanInterArrivalDelay */ 1_000_000L,
                /* rollbackFixed */ 50_000L, /* rollbackCPUTimePerResource */ 10_000L,
                TransactionGenerationMode.RANDOM_OPERATIONS,
                // Durations scaled x1000 with the base unit; the beta threshold is unitless and is
                // NOT scaled. These were bare positional literals - no name at the call site at all,
                // which is the same invisible-unit hazard the *Us rename exists to remove, in its
                // worst form. Named inline so the next unit change cannot silently miss them.
                /* deadlockDetectionInterval */ 5_000_000L,
                /* deadlockDetectionBetaThreshold */ 0.2,
                /* deadlockDetectionMinCooldown */ 1_000_000L);
    }

    /**
     * Canonical constructor: every other constructor - legacy and Builder alike - funnels
     * through here. {@code propertiesPerModel}/{@code instancePoolSize} are the only fields not
     * reachable from the legacy positional constructors (those always derive them); the Builder
     * is the only caller that can set them explicitly.
     */
    public SimulatorParameters(int totalModels, int totalResources, int ioTimePerResource, int cpuTimePerResource,
            int lockIOTimePerResource, int lockCPUTimePerResource, int initialTransactions,
            int totalTransactionSteps, int maxTransactionModels, int maxTransactionResources,
            boolean useConstantTransactionSize, int constantTransactionSize,
            TransactionSizeProfile transactionSizeProfile, int smallTransactionSize, int largeTransactionSize,
            double largeTransactionProbability,
            boolean useRandomResourceSelection,
            double semanticOverlap, int maxCprsPerResource, double avgCprsPerResource, CprTopologyMode cprTopologyMode,
            ReferentialAction referentialAction,
            ConcurrencyModel concurrencyModel,
            DeadlockStrategy deadlockStrategy,
            int simulationDurationSeconds, int numberOfIoProcessors, int numberOfCpuProcessors,
            int numberOfReconcilerProcessors, int totalLocks, Long transactionSamplingSeed,
            Long semanticOverlapSeed, long stepTimeSlice,
            TransactionMode transactionMode, int numTerminals, long meanInterArrivalDelay,
            long rollbackFixed, long rollbackCPUTimePerResource,
            TransactionGenerationMode transactionGenerationMode,
            List<Integer> propertiesPerModel, int instancePoolSize,
            int admissionBatchSize) {
        this(totalModels, totalResources, ioTimePerResource, cpuTimePerResource,
                lockIOTimePerResource, lockCPUTimePerResource, initialTransactions,
                totalTransactionSteps, maxTransactionModels, maxTransactionResources,
                useConstantTransactionSize, constantTransactionSize,
                transactionSizeProfile, smallTransactionSize, largeTransactionSize,
                largeTransactionProbability,
                useRandomResourceSelection,
                semanticOverlap, maxCprsPerResource, avgCprsPerResource, cprTopologyMode,
                referentialAction,
                concurrencyModel,
                deadlockStrategy,
                simulationDurationSeconds, numberOfIoProcessors, numberOfCpuProcessors,
                numberOfReconcilerProcessors, totalLocks, transactionSamplingSeed,
                semanticOverlapSeed, stepTimeSlice,
                transactionMode, numTerminals, meanInterArrivalDelay,
                rollbackFixed, rollbackCPUTimePerResource,
                transactionGenerationMode,
                propertiesPerModel, instancePoolSize,
                admissionBatchSize, 0); // warmupSeconds: 0 (no discard) for every legacy caller
    }

    /**
     * F4: same as the canonical constructor above, plus warmupSeconds. Every legacy constructor
     * funnels through the (admissionBatchSize)-terminated overload above, which forwards
     * warmupSeconds=0; this one forwards oracleClosurePreclaim=false and default deadlock-
     * detection settings to the true terminal below.
     */
    public SimulatorParameters(int totalModels, int totalResources, int ioTimePerResource, int cpuTimePerResource,
            int lockIOTimePerResource, int lockCPUTimePerResource, int initialTransactions,
            int totalTransactionSteps, int maxTransactionModels, int maxTransactionResources,
            boolean useConstantTransactionSize, int constantTransactionSize,
            TransactionSizeProfile transactionSizeProfile, int smallTransactionSize, int largeTransactionSize,
            double largeTransactionProbability,
            boolean useRandomResourceSelection,
            double semanticOverlap, int maxCprsPerResource, double avgCprsPerResource, CprTopologyMode cprTopologyMode,
            ReferentialAction referentialAction,
            ConcurrencyModel concurrencyModel,
            DeadlockStrategy deadlockStrategy,
            int simulationDurationSeconds, int numberOfIoProcessors, int numberOfCpuProcessors,
            int numberOfReconcilerProcessors, int totalLocks, Long transactionSamplingSeed,
            Long semanticOverlapSeed, long stepTimeSlice,
            TransactionMode transactionMode, int numTerminals, long meanInterArrivalDelay,
            long rollbackFixed, long rollbackCPUTimePerResource,
            TransactionGenerationMode transactionGenerationMode,
            List<Integer> propertiesPerModel, int instancePoolSize,
            int admissionBatchSize, int warmupSeconds) {
        this(totalModels, totalResources, ioTimePerResource, cpuTimePerResource,
                lockIOTimePerResource, lockCPUTimePerResource, initialTransactions,
                totalTransactionSteps, maxTransactionModels, maxTransactionResources,
                useConstantTransactionSize, constantTransactionSize,
                transactionSizeProfile, smallTransactionSize, largeTransactionSize,
                largeTransactionProbability,
                useRandomResourceSelection,
                semanticOverlap, maxCprsPerResource, avgCprsPerResource, cprTopologyMode,
                referentialAction,
                concurrencyModel,
                deadlockStrategy,
                simulationDurationSeconds, numberOfIoProcessors, numberOfCpuProcessors,
                numberOfReconcilerProcessors, totalLocks, transactionSamplingSeed,
                semanticOverlapSeed, stepTimeSlice,
                transactionMode, numTerminals, meanInterArrivalDelay,
                rollbackFixed, rollbackCPUTimePerResource,
                transactionGenerationMode,
                propertiesPerModel, instancePoolSize,
                admissionBatchSize, warmupSeconds, false, // oracleClosurePreclaim: false for every legacy caller
                5000L, 0.2, 1000L, // default deadlock-detection settings for every legacy caller
                LockingProtocol.C2PL, // lockingProtocol: C2PL for every legacy caller
                1.0, // writeFraction: all-WRITE reproduces the pre-synthesis workload exactly
                OperationType.WRITE, // cuOperationType
                true, // writeOnlyCprSources: WRITE-filtered, as program mode already was
                0, // operationBatchSize: whole footprint, i.e. no interleaving
                true); // groupUnitAffinity: group members share one unit, as they always have
    }

    /**
     * Deadlock-detection-aware canonical constructor: same as the F4 constructor above, plus the
     * three deadlock-detection knobs (see DeadlockStrategy/AdaptiveWaitPolicy). Forwards
     * oracleClosurePreclaim=false to the true terminal below - every caller of this overload
     * (including the Primary constructor at the top of this file) predates the D-oracle arm.
     */
    public SimulatorParameters(int totalModels, int totalResources, int ioTimePerResource, int cpuTimePerResource,
            int lockIOTimePerResource, int lockCPUTimePerResource, int initialTransactions,
            int totalTransactionSteps, int maxTransactionModels, int maxTransactionResources,
            boolean useConstantTransactionSize, int constantTransactionSize,
            TransactionSizeProfile transactionSizeProfile, int smallTransactionSize, int largeTransactionSize,
            double largeTransactionProbability,
            boolean useRandomResourceSelection,
            double semanticOverlap, int maxCprsPerResource, double avgCprsPerResource, CprTopologyMode cprTopologyMode,
            ReferentialAction referentialAction,
            ConcurrencyModel concurrencyModel,
            DeadlockStrategy deadlockStrategy,
            int simulationDurationSeconds, int numberOfIoProcessors, int numberOfCpuProcessors,
            int numberOfReconcilerProcessors, int totalLocks, Long transactionSamplingSeed,
            Long semanticOverlapSeed, long stepTimeSlice,
            TransactionMode transactionMode, int numTerminals, long meanInterArrivalDelay,
            long rollbackFixed, long rollbackCPUTimePerResource,
            TransactionGenerationMode transactionGenerationMode,
            List<Integer> propertiesPerModel, int instancePoolSize,
            int admissionBatchSize, int warmupSeconds,
            long deadlockDetectionInterval, double deadlockDetectionBetaThreshold, long deadlockDetectionMinCooldown) {
        this(totalModels, totalResources, ioTimePerResource, cpuTimePerResource,
                lockIOTimePerResource, lockCPUTimePerResource, initialTransactions,
                totalTransactionSteps, maxTransactionModels, maxTransactionResources,
                useConstantTransactionSize, constantTransactionSize,
                transactionSizeProfile, smallTransactionSize, largeTransactionSize,
                largeTransactionProbability,
                useRandomResourceSelection,
                semanticOverlap, maxCprsPerResource, avgCprsPerResource, cprTopologyMode,
                referentialAction,
                concurrencyModel,
                deadlockStrategy,
                simulationDurationSeconds, numberOfIoProcessors, numberOfCpuProcessors,
                numberOfReconcilerProcessors, totalLocks, transactionSamplingSeed,
                semanticOverlapSeed, stepTimeSlice,
                transactionMode, numTerminals, meanInterArrivalDelay,
                rollbackFixed, rollbackCPUTimePerResource,
                transactionGenerationMode,
                propertiesPerModel, instancePoolSize,
                admissionBatchSize, warmupSeconds, false, // oracleClosurePreclaim: false for every legacy caller
                deadlockDetectionInterval, deadlockDetectionBetaThreshold, deadlockDetectionMinCooldown,
                LockingProtocol.C2PL, // lockingProtocol: C2PL for every legacy caller
                1.0, // writeFraction: all-WRITE reproduces the pre-synthesis workload exactly
                OperationType.WRITE, // cuOperationType
                true, // writeOnlyCprSources: WRITE-filtered, as program mode already was
                0, // operationBatchSize: whole footprint, i.e. no interleaving
                true); // groupUnitAffinity: group members share one unit, as they always have
    }

    /**
     * D-oracle arm: true terminal - every other constructor above funnels through here. Same as
     * the deadlock-detection-aware constructor above, plus oracleClosurePreclaim. Only
     * {@link Builder#build()} calls this overload directly.
     */
    public SimulatorParameters(int totalModels, int totalResources, int ioTimePerResource, int cpuTimePerResource,
            int lockIOTimePerResource, int lockCPUTimePerResource, int initialTransactions,
            int totalTransactionSteps, int maxTransactionModels, int maxTransactionResources,
            boolean useConstantTransactionSize, int constantTransactionSize,
            TransactionSizeProfile transactionSizeProfile, int smallTransactionSize, int largeTransactionSize,
            double largeTransactionProbability,
            boolean useRandomResourceSelection,
            double semanticOverlap, int maxCprsPerResource, double avgCprsPerResource, CprTopologyMode cprTopologyMode,
            ReferentialAction referentialAction,
            ConcurrencyModel concurrencyModel,
            DeadlockStrategy deadlockStrategy,
            int simulationDurationSeconds, int numberOfIoProcessors, int numberOfCpuProcessors,
            int numberOfReconcilerProcessors, int totalLocks, Long transactionSamplingSeed,
            Long semanticOverlapSeed, long stepTimeSlice,
            TransactionMode transactionMode, int numTerminals, long meanInterArrivalDelay,
            long rollbackFixed, long rollbackCPUTimePerResource,
            TransactionGenerationMode transactionGenerationMode,
            List<Integer> propertiesPerModel, int instancePoolSize,
            int admissionBatchSize, int warmupSeconds, boolean oracleClosurePreclaim,
            long deadlockDetectionInterval, double deadlockDetectionBetaThreshold, long deadlockDetectionMinCooldown,
            LockingProtocol lockingProtocol,
            double writeFraction,
            OperationType cuOperationType,
            boolean writeOnlyCprSources,
            int operationBatchSize,
            boolean groupUnitAffinity) {
        this.warmupSeconds = warmupSeconds;
        this.totalModels = totalModels;
        this.totalResources = totalResources;
        this.ioTimePerResource = ioTimePerResource;
        this.cpuTimePerResource = cpuTimePerResource;
        this.lockIOTimePerResource = lockIOTimePerResource;
        this.lockCPUTimePerResource = lockCPUTimePerResource;
        this.rollbackFixed = rollbackFixed;
        this.rollbackCPUTimePerResource = rollbackCPUTimePerResource;
        this.initialTransactions = initialTransactions;
        this.totalTransactionSteps = totalTransactionSteps;
        this.maxTransactionModels = maxTransactionModels;
        this.maxTransactionResources = maxTransactionResources;
        this.useConstantTransactionSize = useConstantTransactionSize;
        this.constantTransactionSize = constantTransactionSize;
        this.transactionSizeProfile = transactionSizeProfile != null ? transactionSizeProfile : TransactionSizeProfile.UNIFORM;
        this.smallTransactionSize = smallTransactionSize;
        this.largeTransactionSize = largeTransactionSize;
        this.largeTransactionProbability = largeTransactionProbability;
        this.useRandomResourceSelection = useRandomResourceSelection;
        this.semanticOverlap = semanticOverlap;
        this.maxCprsPerResource = maxCprsPerResource;
        this.avgCprsPerResource = avgCprsPerResource;
        this.cprTopologyMode = cprTopologyMode != null ? cprTopologyMode : CprTopologyMode.UNIFORM;
        this.referentialAction = referentialAction != null ? referentialAction
                : ReferentialAction.ON_CASCADE;
        this.concurrencyModel = concurrencyModel != null ? concurrencyModel
                : ConcurrencyModel.ACID;
        this.admissionBatchSize = admissionBatchSize;
        this.deadlockStrategy = deadlockStrategy != null ? deadlockStrategy
                : DeadlockStrategy.CAUTIOUS_WAITING;
        this.simulationDurationSeconds = simulationDurationSeconds;
        this.numberOfIoProcessors = numberOfIoProcessors;
        this.numberOfCpuProcessors = numberOfCpuProcessors;
        this.numberOfReconcilerProcessors = numberOfReconcilerProcessors;
        this.totalLocks = totalLocks;
        this.transactionSamplingSeed = transactionSamplingSeed;
        this.semanticOverlapSeed = semanticOverlapSeed;
        this.stepTimeSlice = stepTimeSlice;
        this.transactionMode = transactionMode != null ? transactionMode : TransactionMode.EQUILIBRIUM;
        this.numTerminals = numTerminals;
        this.meanInterArrivalDelay = meanInterArrivalDelay;
        this.transactionGenerationMode = transactionGenerationMode != null ? transactionGenerationMode
                : TransactionGenerationMode.RANDOM_OPERATIONS;
        this.propertiesPerModel = propertiesPerModel != null
                ? List.copyOf(propertiesPerModel)
                : defaultPropertiesPerModel(totalModels);
        this.instancePoolSize = instancePoolSize;
        this.oracleClosurePreclaim = oracleClosurePreclaim;
        this.transactionModelIndices = List.of();  // positional ctors: every model
        // Builder-assigned fields; positional ctors get the historical behaviour. Builder.build()
        // overwrites these immediately after construction - see each field's javadoc.
        this.accessDistribution = ResourceAccessDistribution.UNIFORM;
        this.zipfTheta = 0.0;
        this.workloadMix = WorkloadMix.PER_OPERATION;
        this.readOnlyTransactionFraction = 0.0;
        this.maxConcurrentRunning = null; // derive from processors
        this.cprInitialAttractiveness = null; // uniform target draw
        this.backoffScale = 1.0; // unchanged 500ms bases
        this.readyWaiterOrder = ReadyWaiterOrder.LIFO;
        this.cuQueuePolicy = CuQueuePolicy.PREPEND;
        this.cprAcyclicTotalOrder = false;
        this.deadlockDetectionInterval = deadlockDetectionInterval;
        this.deadlockDetectionBetaThreshold = deadlockDetectionBetaThreshold;
        this.deadlockDetectionMinCooldown = deadlockDetectionMinCooldown;
        this.lockingProtocol = lockingProtocol != null ? lockingProtocol
                : LockingProtocol.C2PL;
        this.writeFraction = writeFraction;
        this.cuOperationType = cuOperationType != null ? cuOperationType
                : OperationType.WRITE;
        this.writeOnlyCprSources = writeOnlyCprSources;
        this.operationBatchSize = Math.max(0, operationBatchSize);
        this.groupUnitAffinity = groupUnitAffinity;

        validateParameters();
    }

    /** Every model gets DEFAULT_PROPERTIES_PER_MODEL_COUNT properties unless overridden. */
    private static List<Integer> defaultPropertiesPerModel(int totalModels) {
        return Collections.nCopies(Math.max(totalModels, 0), DEFAULT_PROPERTIES_PER_MODEL_COUNT);
    }

    /**
     * Sized so instancePoolSize * totalModels * DEFAULT_PROPERTIES_PER_MODEL_COUNT stays close
     * to the legacy flat totalResources (exact when totalResources is evenly divisible).
     */
    private static int defaultInstancePoolSize(int totalResources, int totalModels) {
        int denominator = Math.max(1, totalModels) * DEFAULT_PROPERTIES_PER_MODEL_COUNT;
        return Math.max(1, totalResources / denominator);
    }

    /**
     * Load parameters from properties file in resources folder
     */
    public static SimulatorParameters fromPropertiesFile(String filename) throws IOException {
        return SimulatorParametersPropertyLoader.fromResourcesFile(filename);
    }

    /**
     * Load parameters from default properties file
     */
    public static SimulatorParameters fromDefaultConfig() throws IOException {
        return fromPropertiesFile(DEFAULT_CONFIG_FILE);
    }

    /**
     * Create parameters with sensible defaults for testing/development
     */
    public static SimulatorParameters defaultParameters() {
        return new Builder().build();
    }

    private void validateParameters() {
        if (totalModels <= 0)
            throw new IllegalArgumentException("totalModels must be positive");
        if (totalResources <= 0)
            throw new IllegalArgumentException("totalResources must be positive");
        if (totalResources < totalModels)
            throw new IllegalArgumentException(
                    "totalResources (" + totalResources + ") must be >= totalModels (" + totalModels
                            + ") - each model needs at least one resource");
        if (ioTimePerResource < 0)
            throw new IllegalArgumentException("ioTimePerResource cannot be negative");
        if (cpuTimePerResource < 0)
            throw new IllegalArgumentException("cpuTimePerResource cannot be negative");
        if (rollbackFixed < 0)
            throw new IllegalArgumentException("rollbackFixed cannot be negative");
        if (rollbackCPUTimePerResource < 0)
            throw new IllegalArgumentException("rollbackCPUTimePerResource cannot be negative");
        if (initialTransactions < 0)
            throw new IllegalArgumentException("initialTransactions cannot be negative");
        if (totalTransactionSteps <= 0)
            throw new IllegalArgumentException("totalTransactionSteps must be positive");
        if (simulationDurationSeconds <= 0)
            throw new IllegalArgumentException("simulationDurationSeconds must be positive");
        if (warmupSeconds < 0)
            throw new IllegalArgumentException("warmupSeconds cannot be negative");
        if (warmupSeconds >= simulationDurationSeconds)
            throw new IllegalArgumentException(
                    "warmupSeconds (" + warmupSeconds + ") must be < simulationDurationSeconds ("
                            + simulationDurationSeconds + ") - a run with no post-warmup measurement window is not useful");
        if (maxTransactionModels <= 0)
            throw new IllegalArgumentException("maxTransactionModels must be positive");
        if (maxTransactionResources <= 0)
            throw new IllegalArgumentException("maxTransactionResources must be positive");
        if (constantTransactionSize <= 0)
            throw new IllegalArgumentException("constantTransactionSize must be positive");
        if (smallTransactionSize <= 0)
            throw new IllegalArgumentException("smallTransactionSize must be positive");
        if (largeTransactionSize <= 0)
            throw new IllegalArgumentException("largeTransactionSize must be positive");
        if (smallTransactionSize > largeTransactionSize)
            throw new IllegalArgumentException("smallTransactionSize must be <= largeTransactionSize");
        if (largeTransactionProbability < 0.0 || largeTransactionProbability > 1.0)
            throw new IllegalArgumentException("largeTransactionProbability must be between 0 and 1");
        if (numberOfIoProcessors <= 0)
            throw new IllegalArgumentException("numberOfIoProcessors must be positive");
        if (numberOfCpuProcessors <= 0)
            throw new IllegalArgumentException("numberOfCpuProcessors must be positive");
        if (totalLocks <= 0)
            throw new IllegalArgumentException("totalLocks must be positive");
        if (stepTimeSlice <= 0)
            throw new IllegalArgumentException("stepTimeSlice must be positive");
        if (numTerminals <= 0)
            throw new IllegalArgumentException("numTerminals must be positive");
        if (meanInterArrivalDelay <= 0)
            throw new IllegalArgumentException("meanInterArrivalDelay must be positive");
        if (semanticOverlap < 0.0 || semanticOverlap > 1.0)
            throw new IllegalArgumentException("semanticOverlap must be between 0 and 1");
        if (maxCprsPerResource < 1)
            throw new IllegalArgumentException("maxCprsPerResource must be at least 1");
        if (avgCprsPerResource < 1.0 || avgCprsPerResource > maxCprsPerResource)
            throw new IllegalArgumentException("avgCprsPerResource must be between 1 and maxCprsPerResource");
        if (propertiesPerModel == null || propertiesPerModel.isEmpty())
            throw new IllegalArgumentException("propertiesPerModel must not be empty");
        for (int propertyCount : propertiesPerModel) {
            if (propertyCount < 1)
                throw new IllegalArgumentException("every propertiesPerModel entry must be >= 1, got " + propertyCount);
        }
        if (instancePoolSize < 1)
            throw new IllegalArgumentException("instancePoolSize must be >= 1");
        if (admissionBatchSize < 1)
            throw new IllegalArgumentException("admissionBatchSize must be >= 1");
    }

    @Override
    public String toString() {
        return String.format(
                "SimulatorParameters{models=%d, resources=%d, ioTime=%d, cpuTime=%d, " +
                        "initialTxns=%d, steps=%d, duration=%ds, processors=io:%d/cpu:%d, locks=%d}",
                totalModels, totalResources, ioTimePerResource, cpuTimePerResource,
                initialTransactions, totalTransactionSteps, simulationDurationSeconds,
                numberOfIoProcessors, numberOfCpuProcessors, totalLocks);
    }

    /**
     * Builder for SimulatorParameters with sensible defaults.
     * Only override the parameters you need to change.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int totalModels = 3;
        private int totalResources = 5000;
        private int ioTimePerResource = CANONICAL_IO_TIME_PER_RESOURCE;
        private int cpuTimePerResource = CANONICAL_CPU_TIME_PER_RESOURCE;
        private int lockIOTimePerResource = CANONICAL_LOCK_IO_TIME_PER_RESOURCE;
        private int lockCPUTimePerResource = CANONICAL_LOCK_CPU_TIME_PER_RESOURCE;
        private long rollbackFixed = DEFAULT_ROLLBACK_FIXED;
        private long rollbackCPUTimePerResource = DEFAULT_ROLLBACK_CPU_TIME_PER_RESOURCE;
        private int initialTransactions = 200;
        private int totalTransactionSteps = 10000;
        private int maxTransactionModels = 5;
        private int maxTransactionResources = 50;
        private boolean useConstantTransactionSize = false;
        private int constantTransactionSize = 50;
        private TransactionSizeProfile transactionSizeProfile = TransactionSizeProfile.UNIFORM;
        private int smallTransactionSize = 10;
        private int largeTransactionSize = 100;
        private double largeTransactionProbability = 0.5;
        private boolean useRandomResourceSelection = false;
        private double semanticOverlap = 0.05;
        private int maxCprsPerResource = 1;
        private double avgCprsPerResource = 1.0;
        private CprTopologyMode cprTopologyMode = CprTopologyMode.UNIFORM;
        private ReferentialAction referentialAction = ReferentialAction.ON_CASCADE;
        private ConcurrencyModel concurrencyModel = ConcurrencyModel.ACID;
        private TransactionGenerationMode transactionGenerationMode = TransactionGenerationMode.RANDOM_OPERATIONS;
        private long deadlockDetectionInterval = 5_000_000;
        private double deadlockDetectionBetaThreshold = 0.2;
        private long deadlockDetectionMinCooldown = 1_000_000;
        private DeadlockStrategy deadlockStrategy = DeadlockStrategy.CAUTIOUS_WAITING;
        private int admissionBatchSize = 1;
        private int warmupSeconds = 0;
        private int simulationDurationSeconds = 10000;
        private int numberOfIoProcessors = 15;
        private int numberOfCpuProcessors = 15;
        private int numberOfReconcilerProcessors = 1;
        private int totalLocks = 200;
        private Long transactionSamplingSeed = null;
        private Long semanticOverlapSeed = null;
        private long stepTimeSlice = 1_000_000L;
        private TransactionMode transactionMode = TransactionMode.EQUILIBRIUM;
        private int numTerminals = 200;
        private long meanInterArrivalDelay = 1_000_000L;
        private List<Integer> propertiesPerModel = null;
        private Integer instancePoolSize = null;
        private boolean oracleClosurePreclaim = false; // D-oracle arm; default off for every existing caller
        private OracleClosurePlacement oracleClosurePlacement = OracleClosurePlacement.AT_END;
        private Integer lockPoolSize = null;
        private List<Integer> transactionModelIndices = List.of();
        private ResourceAccessDistribution accessDistribution = ResourceAccessDistribution.UNIFORM;
        private double zipfTheta = 0.0;
        private WorkloadMix workloadMix = WorkloadMix.PER_OPERATION;
        private double readOnlyTransactionFraction = 0.0;
        private Integer maxConcurrentRunning = null;
        private Double cprInitialAttractiveness = null;
        private double backoffScale = 1.0;
        private ReadyWaiterOrder readyWaiterOrder = ReadyWaiterOrder.LIFO;
        private CuQueuePolicy cuQueuePolicy = CuQueuePolicy.PREPEND;
        private boolean cprAcyclicTotalOrder = false;
        private LockingProtocol lockingProtocol =
                LockingProtocol.C2PL; // default unchanged for every existing caller
        // All three default to "reproduce pre-synthesis behaviour exactly" - see the field javadocs.
        private double writeFraction = 1.0;
        private OperationType cuOperationType = OperationType.WRITE;
        private boolean writeOnlyCprSources = true;
        private int operationBatchSize = 0;
        // Group members share one processor unit - see the field javadoc. True is the engine's
        // historical behaviour; the workload campaign sets it false for neutrality between arms.
        private boolean groupUnitAffinity = true;

        public Builder totalModels(int val) { this.totalModels = val; return this; }
        public Builder totalResources(int val) { this.totalResources = val; return this; }
        public Builder ioTimePerResource(int val) { this.ioTimePerResource = val; return this; }
        public Builder cpuTimePerResource(int val) { this.cpuTimePerResource = val; return this; }
        public Builder lockIOTimePerResource(int val) { this.lockIOTimePerResource = val; return this; }
        public Builder lockCPUTimePerResource(int val) { this.lockCPUTimePerResource = val; return this; }
        public Builder rollbackFixed(long val) { this.rollbackFixed = val; return this; }
        public Builder rollbackCPUTimePerResource(long val) { this.rollbackCPUTimePerResource = val; return this; }
        public Builder initialTransactions(int val) { this.initialTransactions = val; return this; }
        public Builder totalTransactionSteps(int val) { this.totalTransactionSteps = val; return this; }
        public Builder maxTransactionModels(int val) { this.maxTransactionModels = val; return this; }
        public Builder maxTransactionResources(int val) { this.maxTransactionResources = val; return this; }
        public Builder useConstantTransactionSize(boolean val) { this.useConstantTransactionSize = val; return this; }
        public Builder constantTransactionSize(int val) { this.constantTransactionSize = val; return this; }
        public Builder transactionSizeProfile(TransactionSizeProfile val) { this.transactionSizeProfile = val; return this; }
        public Builder smallTransactionSize(int val) { this.smallTransactionSize = val; return this; }
        public Builder largeTransactionSize(int val) { this.largeTransactionSize = val; return this; }
        public Builder largeTransactionProbability(double val) { this.largeTransactionProbability = val; return this; }
        public Builder useRandomResourceSelection(boolean val) { this.useRandomResourceSelection = val; return this; }
        public Builder semanticOverlap(double val) { this.semanticOverlap = val; return this; }
        public Builder maxCprsPerResource(int val) { this.maxCprsPerResource = val; return this; }
        public Builder avgCprsPerResource(double val) { this.avgCprsPerResource = val; return this; }
        public Builder cprTopologyMode(CprTopologyMode val) { this.cprTopologyMode = val; return this; }
        public Builder referentialAction(ReferentialAction val) { this.referentialAction = val; return this; }
        public Builder concurrencyModel(ConcurrencyModel val) { this.concurrencyModel = val; return this; }
        public Builder admissionBatchSize(int val) { this.admissionBatchSize = val; return this; }
        public Builder simulationDurationSeconds(int val) { this.simulationDurationSeconds = val; return this; }
        public Builder warmupSeconds(int val) { this.warmupSeconds = val; return this; }
        public Builder numberOfIoProcessors(int val) { this.numberOfIoProcessors = val; return this; }
        public Builder numberOfCpuProcessors(int val) { this.numberOfCpuProcessors = val; return this; }
        public Builder numberOfReconcilerProcessors(int val) { this.numberOfReconcilerProcessors = val; return this; }
        public Builder totalLocks(int val) { this.totalLocks = val; return this; }
        public Builder transactionSamplingSeed(Long val) { this.transactionSamplingSeed = val; return this; }
        public Builder semanticOverlapSeed(Long val) { this.semanticOverlapSeed = val; return this; }
        public Builder stepTimeSlice(long val) { this.stepTimeSlice = val; return this; }
        public Builder transactionMode(TransactionMode val) { this.transactionMode = val; return this; }
        public Builder numTerminals(int val) { this.numTerminals = val; return this; }
        public Builder meanInterArrivalDelay(long val) { this.meanInterArrivalDelay = val; return this; }
        public Builder transactionGenerationMode(TransactionGenerationMode val) { this.transactionGenerationMode = val; return this; }
        public Builder deadlockDetectionInterval(long val) { this.deadlockDetectionInterval = val; return this; }
        public Builder deadlockDetectionBetaThreshold(double val) { this.deadlockDetectionBetaThreshold = val; return this; }
        public Builder deadlockDetectionMinCooldown(long val) { this.deadlockDetectionMinCooldown = val; return this; }
        public Builder deadlockStrategy(DeadlockStrategy val) { this.deadlockStrategy = val; return this; }
        public Builder propertiesPerModel(List<Integer> val) { this.propertiesPerModel = val; return this; }
        public Builder instancePoolSize(int val) { this.instancePoolSize = val; return this; }
        public Builder lockingProtocol(LockingProtocol val) { this.lockingProtocol = val; return this; }
        public Builder writeFraction(double val) { this.writeFraction = val; return this; }
        public Builder cuOperationType(OperationType val) { this.cuOperationType = val; return this; }
        public Builder writeOnlyCprSources(boolean val) { this.writeOnlyCprSources = val; return this; }
        public Builder operationBatchSize(int val) { this.operationBatchSize = val; return this; }
        public Builder groupUnitAffinity(boolean val) { this.groupUnitAffinity = val; return this; }
        public Builder accessDistribution(ResourceAccessDistribution val) {
            this.accessDistribution = val == null ? ResourceAccessDistribution.UNIFORM : val;
            return this;
        }
        public Builder zipfTheta(double val) { this.zipfTheta = val; return this; }
        public Builder workloadMix(WorkloadMix val) {
            this.workloadMix = val == null ? WorkloadMix.PER_OPERATION : val;
            return this;
        }
        public Builder readOnlyTransactionFraction(double val) { this.readOnlyTransactionFraction = val; return this; }
        /** {@code null} = derive from the processor count (historical behaviour). */
        public Builder maxConcurrentRunning(Integer val) { this.maxConcurrentRunning = val; return this; }
        /** {@code null} = uniform target draw (historical). See the field javadoc. */
        public Builder cprInitialAttractiveness(Double val) { this.cprInitialAttractiveness = val; return this; }
        public Builder backoffScale(double val) { this.backoffScale = val; return this; }
        public Builder readyWaiterOrder(ReadyWaiterOrder val) {
            if (val == null) {
                throw new IllegalArgumentException("readyWaiterOrder must not be null");
            }
            this.readyWaiterOrder = val; return this;
        }
        public Builder cuQueuePolicy(CuQueuePolicy val) {
            if (val == null) {
                throw new IllegalArgumentException("cuQueuePolicy must not be null");
            }
            this.cuQueuePolicy = val; return this;
        }
        public Builder cprAcyclicTotalOrder(boolean val) { this.cprAcyclicTotalOrder = val; return this; }

        /** Restrict ORIGINAL transactions to these model indices; empty = all models. */
        public Builder transactionModelIndices(List<Integer> indices) {
            this.transactionModelIndices = indices == null ? List.of() : indices;
            return this;
        }

        public Builder lockPoolSize(Integer val) {
            this.lockPoolSize = val;
            return this;
        }

        public Builder oracleClosurePlacement(OracleClosurePlacement val) {
            this.oracleClosurePlacement = val != null ? val : OracleClosurePlacement.AT_END;
            return this;
        }

        public Builder oracleClosurePreclaim(boolean val) {
            this.oracleClosurePreclaim = val;
            return this;
        }

        public SimulatorParameters build() {
            int resolvedInstancePoolSize = instancePoolSize != null
                    ? instancePoolSize
                    : SimulatorParameters.defaultInstancePoolSize(totalResources, totalModels);
            SimulatorParameters p = new SimulatorParameters(
                    totalModels, totalResources,
                    ioTimePerResource, cpuTimePerResource,
                    lockIOTimePerResource, lockCPUTimePerResource,
                    initialTransactions, totalTransactionSteps,
                    maxTransactionModels, maxTransactionResources,
                    useConstantTransactionSize, constantTransactionSize,
                    transactionSizeProfile, smallTransactionSize, largeTransactionSize, largeTransactionProbability,
                    useRandomResourceSelection,
                    semanticOverlap, maxCprsPerResource, avgCprsPerResource, cprTopologyMode,
                    referentialAction,
                    concurrencyModel,
                    deadlockStrategy,
                    simulationDurationSeconds,
                    numberOfIoProcessors, numberOfCpuProcessors,
                    numberOfReconcilerProcessors,
                    totalLocks,
                    transactionSamplingSeed,
                    semanticOverlapSeed, stepTimeSlice,
                    transactionMode,
                    numTerminals,
                    meanInterArrivalDelay,
                    rollbackFixed,
                    rollbackCPUTimePerResource,
                    transactionGenerationMode,
                    propertiesPerModel,
                    resolvedInstancePoolSize,
                    admissionBatchSize,
                    warmupSeconds,
                    oracleClosurePreclaim,
                    deadlockDetectionInterval,
                    deadlockDetectionBetaThreshold,
                    deadlockDetectionMinCooldown,
                    lockingProtocol,
                    writeFraction, cuOperationType, writeOnlyCprSources, operationBatchSize,
                    groupUnitAffinity);
            p.transactionModelIndices = List.copyOf(transactionModelIndices);
            p.accessDistribution = accessDistribution;
            p.zipfTheta = zipfTheta;
            p.workloadMix = workloadMix;
            p.oracleClosurePlacement = oracleClosurePlacement;
            p.lockPoolSize = lockPoolSize;
            p.readOnlyTransactionFraction = readOnlyTransactionFraction;
            p.maxConcurrentRunning = maxConcurrentRunning;
            p.cprInitialAttractiveness = cprInitialAttractiveness;
            p.backoffScale = backoffScale;
            p.readyWaiterOrder = readyWaiterOrder;
            p.cuQueuePolicy = cuQueuePolicy;
            p.cprAcyclicTotalOrder = cprAcyclicTotalOrder;
            validateBuilderOnlyFields(p);
            return p;
        }

        /**
         * Validation for the fields {@link #build()} assigns after construction.
         *
         * <p>Separate from {@code validateParameters()} because that runs inside the terminal
         * constructor, i.e. BEFORE these fields are written - checking them there would read the
         * positional-constructor defaults and pass unconditionally.
         */
        private static void validateBuilderOnlyFields(SimulatorParameters p) {
            if (p.zipfTheta < 0.0) {
                throw new IllegalArgumentException("zipfTheta must be >= 0 (got " + p.zipfTheta + ")");
            }
            if (p.accessDistribution == ResourceAccessDistribution.ZIPF
                    && p.zipfTheta > 0.0 && !p.useRandomResourceSelection) {
                throw new IllegalArgumentException(
                        "accessDistribution=ZIPF with zipfTheta=" + p.zipfTheta + " requires "
                                + "useRandomResourceSelection=true: the sequential path takes a CONTIGUOUS "
                                + "block of resource ordinals, which is a second clustering mechanism that "
                                + "would interact with skew over instance ids. Set "
                                + "useRandomResourceSelection(true), or leave zipfTheta at 0.");
            }
            if (p.readOnlyTransactionFraction < 0.0 || p.readOnlyTransactionFraction > 1.0) {
                throw new IllegalArgumentException(
                        "readOnlyTransactionFraction must be in [0,1] (got " + p.readOnlyTransactionFraction + ")");
            }
            // a stated precondition is only as good as whatever ENFORCES it.
            // Under TWO_CLASS the class sampler assigns EVERY operation type, so writeFraction is
            // dead. Rejecting the combination stops it looking like a knob that still does
            // something - the failure mode that rule exists for.
            if (p.workloadMix == WorkloadMix.TWO_CLASS && p.writeFraction != 1.0) {
                throw new IllegalArgumentException(
                        "workloadMix=TWO_CLASS makes writeFraction inert (the class sampler assigns every "
                                + "operation type), but writeFraction=" + p.writeFraction + " was set. "
                                + "Use readOnlyTransactionFraction to control the read mix, and leave "
                                + "writeFraction at its 1.0 default.");
            }
            if (!(p.backoffScale > 0.0) || Double.isNaN(p.backoffScale)) {
                throw new IllegalArgumentException(
                        "backoffScale must be > 0 (got " + p.backoffScale
                        + "): zero would make every retry immediate, which is a different"
                        + " policy rather than a scaled one.");
            }
            if (p.cprInitialAttractiveness != null && !(p.cprInitialAttractiveness > 0.0)) {
                throw new IllegalArgumentException(
                        "cprInitialAttractiveness must be > 0 (got " + p.cprInitialAttractiveness
                                + "): in-degree starts at 0, so alpha = 0 makes every candidate "
                                + "weight 0 and no target can be drawn. That is what the additive "
                                + "constant (initial attractiveness) exists to prevent.");
            }
            if (p.maxConcurrentRunning != null) {
                if (p.maxConcurrentRunning < 1) {
                    throw new IllegalArgumentException(
                            "maxConcurrentRunning must be >= 1 (got " + p.maxConcurrentRunning + ")");
                }
                // F9: a running cap above the live population is unreachable, so it is almost
                // certainly a mistake rather than an intent to uncap.
                if (p.maxConcurrentRunning > p.initialTransactions) {
                    throw new IllegalArgumentException(
                            "maxConcurrentRunning=" + p.maxConcurrentRunning + " exceeds initialTransactions="
                                    + p.initialTransactions + ", so the cap can never bind. Set it equal to "
                                    + "initialTransactions to make the live population the only concurrency "
                                    + "control.");
                }
            }
            if (p.workloadMix != WorkloadMix.TWO_CLASS && p.readOnlyTransactionFraction != 0.0) {
                throw new IllegalArgumentException(
                        "readOnlyTransactionFraction=" + p.readOnlyTransactionFraction + " is inert under "
                                + "workloadMix=" + p.workloadMix + ". Set workloadMix(TWO_CLASS) to use it.");
            }
        }
    }
}
