package de.tum.tssimulator.experiments.support;

import java.util.Collections;
import java.util.Locale;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.CuQueuePolicy;
import de.tum.tssimulator.config.ReadyWaiterOrder;
import de.tum.tssimulator.config.ResourceAccessDistribution;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.WorkloadMix;
import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import de.tum.tssimulator.locking.policy.LockingProtocol;

/**
 * Base configuration for the workload-axis figures (contention, read-only mix, scalability,
 * transaction length). Every value not swept by an experiment is held here, once.
 *
 * <p><b>Two different things are called k.</b> The workload spec's k is TRANSACTION LENGTH
 * ({@code constantTransactionSize}). The repo's k, including the {@code k} column in
 * {@link RawRunRow} and {@link ConfigId}, is {@code admissionBatchSize}. The transaction-length
 * axis goes in the sidecar as {@code txn_len}, never into that column.
 */
public final class WorkloadAxisDefaults {

    /**
     * Model geometry. 200 properties makes every swept {@code f} exactly representable, since
     * {@code CprSchemaBuilder} quantises {@code f} to {@code 1/totalProperties}; 10 models clears
     * the {@code (n-1)/n} emission ceiling that LINE and HOTSPOT impose at the high {@code f}.
     *
     * <p>Geometry trades off against {@link #INSTANCE_POOL_SIZE} and cannot be chosen
     * independently of it: {@code D = totalModels * propertiesPerModel * instancePoolSize}, and a
     * contention level fixes D.
     */
    public static final int TOTAL_MODELS = Integer.getInteger("ts.wl.totalModels", 10);
    public static final int PROPERTIES_PER_MODEL = Integer.getInteger("ts.wl.propertiesPerModel", 20);
    public static final int TOTAL_PROPERTIES = TOTAL_MODELS * PROPERTIES_PER_MODEL;

    /**
     * Contention knob: the number of instances per property, and therefore D.
     *
     * <p>Contention is set here rather than through lock granularity or the core count: lock
     * aliasing is false sharing rather than data contention, and cores set service capacity.
     */
    public static final int INSTANCE_POOL_SIZE = Integer.getInteger("ts.wl.instancePool", 2561);

    /**
     * Live group population, and the only concurrency control ({@link #baseBuilder} sets
     * {@code maxConcurrentRunning = MPL}).
     *
     * <p>Deliberately NOT tied to the core count: tying them makes a cores sweep vary offered
     * concurrency and service capacity at once. 192 is above the throughput peak on purpose,
     * because it is where the no-CPR control stays capacity-saturated across the whole contention
     * axis - which is what makes capacity share a ratio to a constant.
     */
    public static final int MPL = Integer.getInteger("ts.wl.mpl", 192);

    /**
     * Service capacity: the size of the IO and of the CPU processor pool.
     *
     * <p>Since {@link #MPL} was decoupled from it, the live footprint is {@code MPL * k} rather
     * than {@code cores * k}, so this sets capacity and not contention. It is the top of the
     * cores sweep's range.
     *
     * <p>One structural consequence belongs in that figure's caption: contention RISES with
     * cores, and at 1 core there is no concurrency and therefore no lock conflict by
     * construction.
     */
    public static final int CORES = Integer.getInteger("ts.wl.cores", 24);
    /** The spec's k: TRANSACTION LENGTH. Not admissionBatchSize. */
    public static final int TRANSACTION_LENGTH = 10;

    public static final double BASE_SEMANTIC_OVERLAP = 0.25;
    public static final double BASE_MU = 2.0;
    public static final int BASE_M = 5;

    /**
     * UNIFORM, the complete cross-model digraph.
     *
     * <p>The concentration axis sweeps {@link #BASE_ALPHA}, which operates on target selection
     * WITHIN whatever candidate set the model graph allows - so letting alpha do the
     * concentrating requires the model graph to impose none of its own. UNIFORM also lifts the
     * topology-dependent ceiling on {@code semanticOverlap} and removes a confound: under named
     * topologies the lines differed in whether closures terminate structurally.
     */
    public static final CprTopologyMode BASE_TOPOLOGY = CprTopologyMode.UNIFORM;

    /**
     * OFF: the CPR graph may contain cycles, and cascade termination is the runtime visited set in
     * {@code CprSchema.computeAllClosures}.
     *
     * <p>The visited set is the mechanism the paper claims - a precondition resolved within the
     * transaction - so a structural DAG would make the simulator terminate for a reason the model
     * does not state, and a total order on properties would also forbid mutual maintenance
     * between two models, which a real multi-model schema has.
     *
     * <p>The flag remains as an off-by-default option, and
     * {@code CprPreferentialAttachmentTest} checks that it produces a DAG <em>when enabled</em>.
     * Consequence for reporting: longest-path depth over the schema is not well defined on a
     * possibly-cyclic graph, so observed cascade depth is reported from the runtime instead.
     */
    public static final boolean BASE_ACYCLIC_TOTAL_ORDER = false;

    /**
     * Initial attractiveness (Dorogovtsev/Mendes/Samukhin; Price for the in-degree case). Target
     * draw is proportional to {@code (inDegree + alpha)}: small alpha concentrates in-degree into
     * hotspots, large alpha converges to the uniform draw.
     */
    public static final double BASE_ALPHA = 1.0;

    /**
     * Group-unit affinity in the base config. FALSE, because it is not neutral between arms: a
     * no-CPR group has one member, so pinning has nothing to serialise (control measured immune at
     * 1.000x), while every CPR arm pays it (1.173x at 24 cores). The cores experiment carries
     * {@code true} as an explicit arm. See {@link #baseBuilder}.
     */
    public static final boolean BASE_AFFINITY = false;

    /**
     * The concentration axis: concentrated, base, effectively uniform.
     *
     * <p>Chosen against the pigeonhole gate at the seeds the campaign actually uses - seeds are
     * cell-specific, so one alpha draws a different graph at every x-point of every axis. Note for
     * captions that alpha=100 and alpha=1000 are indistinguishable, so the axis spans
     * "concentrated to no concentration" rather than three points on a live continuum.
     */
    public static final double[] PANEL_D_ALPHA = {0.05, 1.0, 100.0};
    public static final double BASE_THETA = 0.5;
    public static final double BASE_READ_ONLY_FRACTION = 0.5;

    /**
     * Step grain. The service-quanta floor is {@code service/stepTimeSlice >= 100} and the binding
     * transaction is the CU, not the parent: a CU touches one resource, so its service is
     * ~{@code io+cpu} ticks regardless of k. That makes this choice invariant in k, so the
     * transaction-length axis needs no per-k quantum.
     */
    public static final long STEP_TIME_SLICE = 10L;
    public static final int SIMULATION_SECONDS = Integer.getInteger("ts.wl.simulationSeconds", 60);
    public static final int WARMUP_SECONDS = Integer.getInteger("ts.wl.warmupSeconds", 10);

    public static final int IO_TIME_PER_RESOURCE = 1000;
    public static final int CPU_TIME_PER_RESOURCE = 1000;

    /**
     * Retry pacing, as a multiple of {@code BackoffQueue}'s 500 ms base.
     *
     * <p><b>1.0 is not a recommendation; it is the value the committed rows were produced
     * under.</b> Nothing downstream re-derives a number from this constant, so changing it
     * desyncs every figure and the reference cell silently rather than failing.
     */
    public static final double BACKOFF_SCALE =
            Double.parseDouble(System.getProperty("ts.wl.backoffScale", "1.0"));

    /**
     * CU placement in PENDING: PREPEND (historical default), PREPEND_IN_ORDER, or APPEND.
     *
     * <p>Measured to have no effect; retained so the claim can be re-checked. The system property
     * only SELECTS the value - the value itself travels as a params field, so the arms differ in
     * {@code config_hash} and in every output row rather than only in a filename.
     */
    public static final CuQueuePolicy CU_QUEUE_POLICY =
            CuQueuePolicy.valueOf(
                    System.getProperty("ts.wl.cuQueue", "PREPEND").toUpperCase(Locale.ROOT));

    /** READY-waiter wake order: LIFO (historical default) or FIFO. Travels as a params field. */
    public static final ReadyWaiterOrder READY_WAITER_ORDER =
            ReadyWaiterOrder.valueOf(
                    System.getProperty("ts.wl.readyOrder", "LIFO").toUpperCase(Locale.ROOT));

    /**
     * Analytic capacity: {@code min over pools (units / per-pool service per transaction)}.
     *
     * <p>IO and CPU are SEPARATE pools that pipeline across transactions - one transaction
     * alternates between them, but different transactions occupy them simultaneously - so capacity
     * is set by whichever pool saturates first, not by treating the two as one serial resource. At
     * 24 cores with {@code k = 10} and 1000 ticks per resource that is 2400 tps, which the no-CPR
     * control reproduces exactly.
     *
     * <p><b>Read derived quantities against the CONTROL at the same cell, not against this
     * figure.</b> The analytic value checks that the control is behaving; the control is the
     * denominator. The control itself runs at ~4x its own service purely from closed-loop queueing
     * at MPL=192, so measuring a latency multiple against 1x would charge ordinary queueing to
     * CPR cost.
     */
    public static double referenceCapacityTps(int cores, int txnLen) {
        double ioServicePerTxnS = txnLen * IO_TIME_PER_RESOURCE / 1_000_000.0;
        double cpuServicePerTxnS = txnLen * CPU_TIME_PER_RESOURCE / 1_000_000.0;
        return Math.min(cores / ioServicePerTxnS, cores / cpuServicePerTxnS);
    }

    /**
     * CPR offers per burst - the numerator of the pigeonhole gate.
     *
     * <p><b>Not {@code MPL * f * k}</b>, which charges every resource as a CPR source and
     * overstates the offer rate by about 4x at the base config. Only WRITE resources seed
     * bookkeepers ({@code writeOnlyCprSources}), and under {@code TWO_CLASS} a read-only
     * transaction carries no writes while an update transaction carries {@code ceil(k/2)} - so the
     * expected sources per transaction are {@code (1 - readOnlyFraction) * ceil(k/2) * f}.
     *
     * <p>The distinction binds at the top of the transaction-length axis: at {@code k = 50} the
     * uncorrected form puts the gate at 98-362% of its limit depending on alpha, i.e. apparently
     * failing, when the actual offer rate is a quarter of that.
     */
    public static double cprOffersPerBurst(int mpl, double f, int txnLen, double readOnlyFraction) {
        double writesPerUpdateTxn = Math.ceil(txnLen / 2.0);
        return mpl * (1.0 - readOnlyFraction) * writesPerUpdateTxn * f;
    }

    /** The service-quanta floor; see {@link #STEP_TIME_SLICE}. */
    public static final double M8_MIN_SERVICE_QUANTA = 100.0;

    private WorkloadAxisDefaults() {
    }

    /** Total resources implied by the model geometry: {@code TOTAL_PROPERTIES * instancePoolSize}. */
    public static int totalResources(int instancePoolSize) {
        return TOTAL_PROPERTIES * instancePoolSize;
    }

    public static SimulatorParameters.Builder baseBuilder() {
        return baseBuilder(INSTANCE_POOL_SIZE);
    }

    public static SimulatorParameters.Builder baseBuilder(int instancePoolSize) {
        int d = totalResources(instancePoolSize);
        return SimulatorParameters.builder()
                .totalModels(TOTAL_MODELS)
                .propertiesPerModel(Collections.nCopies(TOTAL_MODELS, PROPERTIES_PER_MODEL))
                .instancePoolSize(instancePoolSize)
                .totalResources(d)
                // One lock per resource. Asserted, not merely set - see assertLockAliasingOff.
                .totalLocks(d)
                .ioTimePerResource(IO_TIME_PER_RESOURCE)
                .cpuTimePerResource(CPU_TIME_PER_RESOURCE)
                .lockIOTimePerResource(1)
                .lockCPUTimePerResource(1)
                .numberOfIoProcessors(CORES)
                .numberOfCpuProcessors(CORES)
                .numberOfReconcilerProcessors(0)
                .initialTransactions(MPL)
                // The live population is the only concurrency control. An admission capacity
                // derived from min(io,cpu) would conflate service parallelism with admission
                // pressure; setting the cap equal to the population removes that without adding a
                // second free knob.
                .maxConcurrentRunning(MPL)
                .useConstantTransactionSize(true)
                .constantTransactionSize(TRANSACTION_LENGTH)
                .maxTransactionModels(5)
                // ZIPF requires random (scattered) selection; contiguous blocks are a second
                // clustering mechanism that would confound the contention axis.
                .useRandomResourceSelection(true)
                .accessDistribution(ResourceAccessDistribution.ZIPF)
                .zipfTheta(BASE_THETA)
                .workloadMix(WorkloadMix.TWO_CLASS)
                .readOnlyTransactionFraction(BASE_READ_ONLY_FRACTION)
                .semanticOverlap(BASE_SEMANTIC_OVERLAP)
                .avgCprsPerResource(BASE_MU)
                .maxCprsPerResource(BASE_M)
                .cprTopologyMode(BASE_TOPOLOGY)
                .cprInitialAttractiveness(BASE_ALPHA)
                // A params field rather than a -D switch, so it reaches config_hash, copyBuilder
                // and every output row; otherwise three backoff policies would carry identical
                // config_hash values.
                .backoffScale(BACKOFF_SCALE)
                .readyWaiterOrder(READY_WAITER_ORDER)
                .cuQueuePolicy(CU_QUEUE_POLICY)
                .cprAcyclicTotalOrder(BASE_ACYCLIC_TOTAL_ORDER)
                .referentialAction(ReferentialAction.ON_CASCADE)
                .concurrencyModel(ConcurrencyModel.ACID)
                .lockingProtocol(LockingProtocol.C2PL)
                .deadlockStrategy(DeadlockStrategy.CAUTIOUS_WAITING)
                .stepTimeSlice(STEP_TIME_SLICE)
                .simulationDurationSeconds(SIMULATION_SECONDS)
                .warmupSeconds(WARMUP_SECONDS)
                .admissionBatchSize(4)
                // Off for neutrality between arms, not for realism: pinning has nothing to
                // serialise in a one-member control group, so it would tax only the CPR arms and
                // inflate the headline gap by a scheduling artifact. The two values are a bracket
                // (static pinning against perfect migration), and the cores experiment carries
                // true as an explicit arm, where static pinning inverts scaling past 16 cores.
                .groupUnitAffinity(false);
    }

    /**
     * Lock aliasing must be provably off, not merely intended to be.
     *
     * <p>Checks the REALISED granularity rather than trusting that {@code totalLocks} was set:
     * {@code LockGranularityMapper} derives its resource count from the actual model pool, not
     * from the {@code totalResources} field, so a caller that overrides the geometry after setting
     * {@code totalLocks} silently gets aliasing back.
     */
    public static void assertLockAliasingOff(SimulatorParameters params) {
        int d = TOTAL_PROPERTIES == 0 ? 0
                : params.totalModels * params.propertiesPerModel.get(0) * params.instancePoolSize;
        int granularity = (int) Math.ceil(d / (double) params.totalLocks);
        if (granularity != 1) {
            throw new IllegalStateException(String.format(
                    "lock aliasing is ON: D=%d over totalLocks=%d is %d resources per lock. "
                            + "Aliasing is false sharing, not data contention, and under ZIPF it "
                            + "concentrates on the same low instance ids theta does - which would "
                            + "read as a mechanism. Set totalLocks = D (%d).",
                    d, params.totalLocks, granularity, d));
        }
    }

    /**
     * MPL must be a RECORDED parameter, never the engine's cores-derived fallback.
     *
     * <p>{@code DefaultExecutionBundle} resolves admission capacity as
     * {@code maxConcurrentRunning != null ? maxConcurrentRunning : numCpuProcessors}, so a cell
     * that leaves {@code maxConcurrentRunning} unset silently runs at MPL = cores - and nothing in
     * the output row says so, because the derived value is not the recorded one. This makes that
     * fallback unreachable from this campaign by construction.
     */
    public static void assertMplIsRecorded(SimulatorParameters params) {
        if (params.maxConcurrentRunning == null) {
            throw new IllegalStateException(
                    "maxConcurrentRunning is unset, so admission capacity would fall back to "
                            + "numCpuProcessors (" + params.numberOfCpuProcessors + ") - MPL "
                            + "would silently track cores. Set it explicitly; baseBuilder() "
                            + "already does.");
        }
    }

    /**
     * Both seeds must be SET, because null does not mean "use a default" - it means
     * <b>non-deterministic</b>.
     *
     * <p>{@code semanticOverlapSeed} and {@code transactionSamplingSeed} are boxed, and
     * {@code CprSchemaBuilder} reads the first as
     * {@code (cprSeed != null) ? new Random(cprSeed) : new Random(System.nanoTime())}. A call site
     * that forgets one therefore does not fail and does not fall back to a fixed value: it draws a
     * different graph every invocation, silently, and every schema-derived column then describes a
     * graph no run used.
     *
     * <p>{@code baseBuilder()} deliberately leaves the seeds unset - they arrive per run from
     * {@code SeedSweepRunner.saltedSeeds} - which is why this assertion belongs at the point of
     * USE rather than at construction.
     *
     * @throws IllegalStateException if either seed is null
     */
    public static void assertSeedsAreRecorded(SimulatorParameters params) {
        if (params.semanticOverlapSeed == null || params.transactionSamplingSeed == null) {
            throw new IllegalStateException(String.format(
                    "Seeds must be set before a schema or a run is derived from these params "
                            + "(semanticOverlapSeed=%s, transactionSamplingSeed=%s). A null seed "
                            + "is NOT a default - CprSchemaBuilder falls back to "
                            + "new Random(System.nanoTime()), so the graph differs on every "
                            + "invocation and describes no run. Seed via "
                            + "SeedSweepRunner.saltedSeeds/copyBuilder first.",
                    params.semanticOverlapSeed, params.transactionSamplingSeed));
        }
    }

    /** A CU's service in step quanta; the floor is {@link #M8_MIN_SERVICE_QUANTA}. */
    public static double cuServiceToQuantum(SimulatorParameters params) {
        long cuServiceTicks = (long) params.ioTimePerResource + params.cpuTimePerResource
                + params.lockIOTimePerResource + params.lockCPUTimePerResource;
        return params.stepTimeSlice <= 0 ? 0.0 : cuServiceTicks / (double) params.stepTimeSlice;
    }
}
