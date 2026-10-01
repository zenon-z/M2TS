package de.tum.tssimulator.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.core.program.TransactionProgram;
import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import de.tum.tssimulator.locking.policy.LockingProtocol;

/**
 * Immutable configuration object for the simulator.
 * Use the Builder pattern for construction to improve readability.
 */
public class SimulatorConfig {
    public final int totalModels;
    // Derived from modelPool (sum of every model's resourceCount()), not passed through from
    // SimulatorParameters.totalResources - see ModelFactory/Model. Any direct reader (e.g.
    // LockGranularityMapper) is guaranteed to agree with the real resource count this way.
    public final int totalResources;
    public final List<Integer> propertiesPerModel;
    public final int instancePoolSize;
    public final long ioTimePerResource;
    public final long cpuTimePerResource;
    public final int initialTransactionCount;
    public final int totalTransactionSteps;
    public final int simulationDurationSeconds;
    public final long simulationDuration;
    // F4 (VLDB reviewer response): seconds/ms of simulated time to discard as warm-up before
    // steady-state metrics accumulate. 0 (default) = no discard, identical to pre-F4 behavior.
    public final int warmupSeconds;
    public final long warmup;
    public final List<Model> modelPool;
    /** Indices into {@link #modelPool} that ORIGINAL transactions may draw from; empty = all.
     *  CPR targets are unaffected -- they are cross-model by definition and pick their own
     *  target model. This restricts the workload, not the propagation. */
    public final List<Integer> transactionModelIndices;

    /** The models ORIGINAL transactions may draw from. Empty filter = the whole pool, so this
     *  is a no-op for every configuration that does not set it. */
    public List<Model> transactionModelPool() {
        if (transactionModelIndices.isEmpty()) {
            return modelPool;
        }
        List<Model> pool = new ArrayList<>(transactionModelIndices.size());
        for (int i : transactionModelIndices) {
            if (i < 0 || i >= modelPool.size()) {
                throw new IllegalArgumentException("transactionModelIndices contains " + i
                        + " but modelPool has " + modelPool.size() + " models");
            }
            pool.add(modelPool.get(i));
        }
        return pool;
    }
    public final int maxTransactionModels;
    public final int maxTransactionResources;
    public final boolean useConstantTransactionSize;  // If true, all transactions have exactly constantTransactionSize resources
    public final int constantTransactionSize;  // Fixed transaction size when useConstantTransactionSize is true
    public final TransactionSizeProfile transactionSizeProfile;
    public final int smallTransactionSize;
    public final int largeTransactionSize;
    public final double largeTransactionProbability;
    public final boolean useRandomResourceSelection;  // If true, resources selected randomly; if false, sequential (default)
    /** Footprint draw shape; skew lands on instanceId. See {@link ResourceAccessDistribution}. */
    public final ResourceAccessDistribution accessDistribution;
    /** Zipf exponent over instance ids; 0.0 short-circuits to the UNIFORM path bit-identically. */
    public final double zipfTheta;
    /** How operation types are assigned to sampled resources. See {@link WorkloadMix}. */
    public final WorkloadMix workloadMix;
    /** P(transaction is read-only) under {@link WorkloadMix#TWO_CLASS}; ignored otherwise. */
    public final double readOnlyTransactionFraction;
    /** Admission capacity; null = derive from numCpuProcessors. See SimulatorParameters. */
    public final Integer maxConcurrentRunning;
    /** CPR in-degree preferential attachment; null = uniform. See SimulatorParameters. */
    public final Double cprInitialAttractiveness;
    /** Multiplier on both backoff base delays. See SimulatorParameters.backoffScale. */
    public final double backoffScale;
    /** READY-waiter wake order. See SimulatorParameters.readyWaiterOrder. */
    public final ReadyWaiterOrder readyWaiterOrder;
    /** CU placement in PENDING. See SimulatorParameters.cuQueuePolicy. */
    public final CuQueuePolicy cuQueuePolicy;
    /** Property-level total order making the CPR schema a DAG by construction. */
    public final boolean cprAcyclicTotalOrder;
    public final long lockIOTime;
    public final long lockCPUTimePerResource;
    public final long rollbackFixed;
    public final long rollbackCPUTimePerResource;
    public final int numIoProcessors;
    public final int numCpuProcessors;
    public final int numReconcilerProcessors; // For BASE: number of dedicated reconciliation processors
    public final int totalLocks;
    public final double semanticOverlap;  // 0.0 to 1.0: percentage of resources with cross-model constraints
    public final int maxCprsPerResource; // Maximum outgoing CPRs per resource with at least one CPR
    public final double avgCprsPerResource; // Expected outgoing CPRs under the global fan-out model
    public final CprTopologyMode cprTopologyMode; // Target selection mode for CPR graphs
    public final ReferentialAction referentialAction;  // ON_CASCADE or ON_RESTRICT
    public final ConcurrencyModel concurrencyModel;  // ACID or BASE
    public final int admissionBatchSize; // k: waiters drained per wakeNext() call
    public final Long transactionSamplingSeed;  // null = non-deterministic, non-null = deterministic
    public final Long semanticOverlapSeed;  // null = non-deterministic bookkeeper creation, non-null = deterministic
    public final DeadlockStrategy deadlockStrategy; // Deadlock handling strategy
    public final long stepTimeSlice; // Time slice per step when using FIXED_TIME mode
    public final TransactionMode transactionMode;
    public final int numTerminals;
    public final long meanInterArrivalDelay;
    public final TransactionGenerationMode transactionGenerationMode;
    public final List<TransactionProgram> programPool;
    // D-oracle arm: see SimulatorParameters.oracleClosurePreclaim javadoc.
    public final boolean oracleClosurePreclaim;
    /** Where the inlined arm places the closure. See {@link OracleClosurePlacement}. */
    public final OracleClosurePlacement oracleClosurePlacement;
    /** LOCK pool size; null = min(numIoProcessors, numCpuProcessors). See SimulatorParameters. */
    public final Integer lockPoolSize;
    public final long deadlockDetectionInterval;
    public final double deadlockDetectionBetaThreshold;
    public final long deadlockDetectionMinCooldown;
    // See SimulatorParameters.lockingProtocol javadoc.
    public final LockingProtocol lockingProtocol;

    // Operation synthesis. See the same-named javadocs on SimulatorParameters.
    public final double writeFraction;
    public final OperationType cuOperationType;
    public final boolean writeOnlyCprSources;
    /** See SimulatorParameters.operationBatchSize. */
    public final int operationBatchSize;
    /** See SimulatorParameters.groupUnitAffinity - a modelling claim about what a cascade is. */
    public final boolean groupUnitAffinity;

    // Keep existing constructor for backward compatibility
    public SimulatorConfig(int totalModels, int totalResources,
            long ioTimePerResource, long cpuTimePerResource,
            int initialTransactionCount, int totalTransactionSteps, int simulationDurationSeconds,
            List<Model> modelPool,
            int maxTransactionModels, int maxTransactionResources,
            long lockIOTime, long lockCPUTimePerResource,
            int numIoProcessors, int numCpuProcessors, int totalLocks) {
        this.totalModels = totalModels;
        this.totalResources = modelPool.stream().mapToInt(Model::resourceCount).sum();
        this.propertiesPerModel = modelPool.stream().map(Model::propertyCount).toList();
        this.instancePoolSize = modelPool.isEmpty() ? 0 : modelPool.get(0).instancePoolSize();
        this.ioTimePerResource = ioTimePerResource;
        this.cpuTimePerResource = cpuTimePerResource;
        this.initialTransactionCount = initialTransactionCount;
        this.totalTransactionSteps = totalTransactionSteps;
        this.simulationDurationSeconds = simulationDurationSeconds;
        this.simulationDuration = simulationDurationSeconds * 1_000_000L;
        this.warmupSeconds = 0;
        this.warmup = 0L;
        this.modelPool = modelPool;
        this.maxTransactionModels = maxTransactionModels;
        this.maxTransactionResources = maxTransactionResources;
        this.lockIOTime = lockIOTime;
        this.lockCPUTimePerResource = lockCPUTimePerResource;
        this.rollbackFixed = 50_000;
        this.rollbackCPUTimePerResource = 10_000;
        this.numIoProcessors = numIoProcessors;
        this.numCpuProcessors = numCpuProcessors;
        this.numReconcilerProcessors = 1; // Default to 1 reconciler processor
        this.totalLocks = totalLocks;
        this.useConstantTransactionSize = false;  // Default to variable size
        this.constantTransactionSize = 50;  // Default constant size
        this.transactionSizeProfile = TransactionSizeProfile.UNIFORM;
        this.smallTransactionSize = 10;
        this.largeTransactionSize = 100;
        this.largeTransactionProbability = 0.5;
        this.useRandomResourceSelection = false;  // Default to sequential selection
        this.accessDistribution = ResourceAccessDistribution.UNIFORM;
        this.zipfTheta = 0.0;
        this.workloadMix = WorkloadMix.PER_OPERATION;
        this.readOnlyTransactionFraction = 0.0;
        this.maxConcurrentRunning = null;
        this.cprInitialAttractiveness = null;
        this.backoffScale = 1.0;
        this.readyWaiterOrder = ReadyWaiterOrder.LIFO;
        this.cuQueuePolicy = CuQueuePolicy.PREPEND;
        this.cprAcyclicTotalOrder = false;
        // Default to 50% overlap for backward compatibility - except with <2 models, where
        // semanticOverlap>0 is unsatisfiable (no cross-model link is possible) and this legacy
        // constructor has no parameter to override it, unlike Builder.validate()'s explicit check.
        this.semanticOverlap = modelPool.size() >= 2 ? 0.5 : 0.0;
        this.maxCprsPerResource = 1;
        this.avgCprsPerResource = 1.0;
        this.cprTopologyMode = CprTopologyMode.UNIFORM;
        this.referentialAction = ReferentialAction.ON_CASCADE;  // Default for backward compatibility
        this.concurrencyModel = ConcurrencyModel.ACID;  // Default to ACID for backward compatibility
        this.admissionBatchSize = 1;  // Default preserves single-wake behavior
        this.transactionSamplingSeed = null;  // Default to non-deterministic sampling
        this.semanticOverlapSeed = null;  // Default to non-deterministic bookkeeper creation
        this.deadlockStrategy = DeadlockStrategy.CAUTIOUS_WAITING; // Default deadlock strategy
        this.stepTimeSlice = 1_000_000; // Default 1 second slice
        this.transactionMode = TransactionMode.EQUILIBRIUM;
        this.numTerminals = 200;
        this.meanInterArrivalDelay = 1_000_000;
        this.transactionGenerationMode = TransactionGenerationMode.RANDOM_OPERATIONS;
        this.programPool = List.of();
        this.oracleClosurePreclaim = false;
        this.oracleClosurePlacement = OracleClosurePlacement.AT_END;
        this.lockPoolSize = null;
        this.transactionModelIndices = List.of();   // empty = draw from every model
        this.deadlockDetectionInterval = 5_000_000;
        this.deadlockDetectionBetaThreshold = 0.2;
        this.deadlockDetectionMinCooldown = 1_000_000;
        this.lockingProtocol = LockingProtocol.C2PL;
        this.writeFraction = 1.0;
        this.cuOperationType = OperationType.WRITE;
        this.writeOnlyCprSources = true;
        this.operationBatchSize = 0;
        this.groupUnitAffinity = true;
    }

    // Private constructor for builder
    private SimulatorConfig(Builder builder) {
        this.totalModels = builder.totalModels;
        // Derived from the actual constructed model pool, not passed through from
        // builder.totalResources (kept only for source compatibility with existing callers -
        // see Builder.totalResources()).
        this.totalResources = builder.modelPool.stream().mapToInt(Model::resourceCount).sum();
        this.propertiesPerModel = builder.modelPool.stream().map(Model::propertyCount).toList();
        this.instancePoolSize = builder.modelPool.isEmpty() ? 0 : builder.modelPool.get(0).instancePoolSize();
        this.ioTimePerResource = builder.ioTimePerResource;
        this.cpuTimePerResource = builder.cpuTimePerResource;
        this.initialTransactionCount = builder.initialTransactionCount;
        this.totalTransactionSteps = builder.totalTransactionSteps;
        this.simulationDurationSeconds = builder.simulationDurationSeconds;
        this.simulationDuration = builder.simulationDurationSeconds * 1_000_000L;
        this.warmupSeconds = builder.warmupSeconds;
        this.warmup = builder.warmupSeconds * 1_000_000L;
        this.modelPool = builder.modelPool;
        this.maxTransactionModels = builder.maxTransactionModels;
        this.maxTransactionResources = builder.maxTransactionResources;
        this.useConstantTransactionSize = builder.useConstantTransactionSize;
        this.constantTransactionSize = builder.constantTransactionSize;
        this.transactionSizeProfile = builder.transactionSizeProfile;
        this.smallTransactionSize = builder.smallTransactionSize;
        this.largeTransactionSize = builder.largeTransactionSize;
        this.largeTransactionProbability = builder.largeTransactionProbability;
        this.useRandomResourceSelection = builder.useRandomResourceSelection;
        this.accessDistribution = builder.accessDistribution;
        this.zipfTheta = builder.zipfTheta;
        this.workloadMix = builder.workloadMix;
        this.readOnlyTransactionFraction = builder.readOnlyTransactionFraction;
        this.maxConcurrentRunning = builder.maxConcurrentRunning;
        this.cprInitialAttractiveness = builder.cprInitialAttractiveness;
        this.backoffScale = builder.backoffScale;
        this.readyWaiterOrder = builder.readyWaiterOrder;
        this.cuQueuePolicy = builder.cuQueuePolicy;
        this.cprAcyclicTotalOrder = builder.cprAcyclicTotalOrder;
        this.lockIOTime = builder.lockIOTime;
        this.lockCPUTimePerResource = builder.lockCPUTimePerResource;
        this.rollbackFixed = builder.rollbackFixed;
        this.rollbackCPUTimePerResource = builder.rollbackCPUTimePerResource;
        this.numIoProcessors = builder.numIoProcessors;
        this.numCpuProcessors = builder.numCpuProcessors;
        this.numReconcilerProcessors = builder.numReconcilerProcessors;
        this.totalLocks = builder.totalLocks;
        this.semanticOverlap = builder.semanticOverlap;
        this.maxCprsPerResource = builder.maxCprsPerResource;
        this.avgCprsPerResource = builder.avgCprsPerResource;
        this.cprTopologyMode = builder.cprTopologyMode;
        this.referentialAction = builder.referentialAction;
        this.concurrencyModel = builder.concurrencyModel;
        this.admissionBatchSize = builder.admissionBatchSize;
        this.deadlockStrategy = builder.deadlockStrategy;
        this.transactionSamplingSeed = builder.transactionSamplingSeed;
        this.semanticOverlapSeed = builder.semanticOverlapSeed;
        this.stepTimeSlice = builder.stepTimeSlice;
        this.transactionMode = builder.transactionMode;
        this.numTerminals = builder.numTerminals;
        this.meanInterArrivalDelay = builder.meanInterArrivalDelay;
        this.transactionGenerationMode = builder.transactionGenerationMode;
        this.programPool = builder.programPool;
        this.oracleClosurePreclaim = builder.oracleClosurePreclaim;
        this.oracleClosurePlacement = builder.oracleClosurePlacement;
        this.lockPoolSize = builder.lockPoolSize;
        this.transactionModelIndices = List.copyOf(builder.transactionModelIndices);
        this.deadlockDetectionInterval = builder.deadlockDetectionInterval;
        this.deadlockDetectionBetaThreshold = builder.deadlockDetectionBetaThreshold;
        this.deadlockDetectionMinCooldown = builder.deadlockDetectionMinCooldown;
        this.lockingProtocol = builder.lockingProtocol;
        this.writeFraction = builder.writeFraction;
        this.cuOperationType = builder.cuOperationType;
        this.writeOnlyCprSources = builder.writeOnlyCprSources;
        this.operationBatchSize = builder.operationBatchSize;
        this.groupUnitAffinity = builder.groupUnitAffinity;
    }

    /**
     * Create a new SimulatorConfig from SimulatorParameters and model pool.
     * Equivalent to {@link #fromParameters(SimulatorParameters, List, List)} with an empty program pool.
     */
    public static SimulatorConfig fromParameters(SimulatorParameters params, List<Model> modelPool) {
        return fromParameters(params, modelPool, List.of());
    }

    /**
     * Create a new SimulatorConfig from SimulatorParameters, model pool, and transaction program pool.
     */
    public static SimulatorConfig fromParameters(SimulatorParameters params, List<Model> modelPool,
            List<TransactionProgram> programPool) {
        int effectiveIoProcessors = params.numberOfIoProcessors;
        int effectiveCpuProcessors = params.numberOfCpuProcessors;
        int effectiveReconcilerProcessors = params.numberOfReconcilerProcessors;
        if (params.concurrencyModel == ConcurrencyModel.BASE
            && params.numberOfReconcilerProcessors > 0) {
            // Regular processors get priority - if not enough processors, disable reconcilers
            if (params.numberOfReconcilerProcessors >= params.numberOfCpuProcessors) {
                // THROW, do not silently zero. Asking for zero reconcilers is legitimate; asking for
                // some and receiving none is not - it ran half of V3's planned N ladder {16, 24}
                // with NO DRAIN AT ALL and no warning, and the resulting rows looked like ordinary
                // measurements (7bd47f9).
                throw new IllegalArgumentException(String.format(
                        "numberOfReconcilerProcessors=%d must be less than numberOfCpuProcessors=%d: "
                        + "BASE carves the reconcilers out of the CPU pool, so this request would "
                        + "leave no foreground capacity and the drain would be silently disabled. "
                        + "Raise the pool (withForegroundFixed sets it to foreground + reconcilers) "
                        + "or lower the reconciler count.",
                        params.numberOfReconcilerProcessors, params.numberOfCpuProcessors));
            } else {
                // Enough processors - allocate some to reconcilers
                effectiveCpuProcessors = params.numberOfCpuProcessors - params.numberOfReconcilerProcessors;
                effectiveIoProcessors = Math.min(params.numberOfIoProcessors, effectiveCpuProcessors);
                effectiveReconcilerProcessors = params.numberOfReconcilerProcessors;
            }
        }

        return new Builder()
                .totalModels(params.totalModels)
                .totalResources(params.totalResources)
                .ioTimePerResource(params.ioTimePerResource)
                .cpuTimePerResource(params.cpuTimePerResource)
                .initialTransactionCount(params.initialTransactions)
                .totalTransactionSteps(params.totalTransactionSteps)
                .simulationDurationSeconds(params.simulationDurationSeconds)
                .warmupSeconds(params.warmupSeconds)
                .modelPool(modelPool)
                .maxTransactionModels(params.maxTransactionModels)
                .maxTransactionResources(params.maxTransactionResources)
                .useConstantTransactionSize(params.useConstantTransactionSize)
                .constantTransactionSize(params.constantTransactionSize)
                .transactionSizeProfile(params.transactionSizeProfile)
                .smallTransactionSize(params.smallTransactionSize)
                .largeTransactionSize(params.largeTransactionSize)
                .largeTransactionProbability(params.largeTransactionProbability)
                .useRandomResourceSelection(params.useRandomResourceSelection)
                .accessDistribution(params.accessDistribution)
                .zipfTheta(params.zipfTheta)
                .workloadMix(params.workloadMix)
                .readOnlyTransactionFraction(params.readOnlyTransactionFraction)
                .maxConcurrentRunning(params.maxConcurrentRunning)
                .cprInitialAttractiveness(params.cprInitialAttractiveness)
                .backoffScale(params.backoffScale)
                .readyWaiterOrder(params.readyWaiterOrder)
                .cuQueuePolicy(params.cuQueuePolicy)
                .cprAcyclicTotalOrder(params.cprAcyclicTotalOrder)
                .lockIOTime(params.lockIOTimePerResource)
                .lockCPUTimePerResource(params.lockCPUTimePerResource)
                .rollbackFixed(params.rollbackFixed)
                .rollbackCPUTimePerResource(params.rollbackCPUTimePerResource)
                .numIoProcessors(effectiveIoProcessors)
                .numCpuProcessors(effectiveCpuProcessors)
                .numReconcilerProcessors(effectiveReconcilerProcessors)
                .totalLocks(params.totalLocks)
                .semanticOverlap(params.semanticOverlap)
                .maxCprsPerResource(params.maxCprsPerResource)
                .avgCprsPerResource(params.avgCprsPerResource)
                .cprTopologyMode(params.cprTopologyMode)
                .referentialAction(params.referentialAction)
                .concurrencyModel(params.concurrencyModel)
                .admissionBatchSize(params.admissionBatchSize)
                .deadlockStrategy(params.deadlockStrategy)
                .transactionSamplingSeed(params.transactionSamplingSeed)
                .semanticOverlapSeed(params.semanticOverlapSeed)
                .stepTimeSlice(params.stepTimeSlice)
                .transactionMode(params.transactionMode)
                .numTerminals(params.numTerminals)
                .meanInterArrivalDelay(params.meanInterArrivalDelay)
                .transactionGenerationMode(params.transactionGenerationMode)
                .programPool(programPool)
                .oracleClosurePreclaim(params.oracleClosurePreclaim)
                .oracleClosurePlacement(params.oracleClosurePlacement)
                .lockPoolSize(params.lockPoolSize)
                .transactionModelIndices(params.transactionModelIndices)
                .deadlockDetectionInterval(params.deadlockDetectionInterval)
                .lockingProtocol(params.lockingProtocol)
                .writeFraction(params.writeFraction)
                .cuOperationType(params.cuOperationType)
                .writeOnlyCprSources(params.writeOnlyCprSources)
                .operationBatchSize(params.operationBatchSize)
                .groupUnitAffinity(params.groupUnitAffinity)
                .build();
    }

    // Getter methods for new fields
    public ConcurrencyModel getConcurrencyModel() {
        return concurrencyModel;
    }

    /** One simulated second, in ticks. {@code SimulatorParameters:19}: one tick = 1 microsecond. */
    public static final long TICKS_PER_SECOND = 1_000_000L;

    /**
     * Steps a run will take, and therefore the number of dirty-set samples it will produce.
     *
     * <p>ONE derivation, shared. Both the sample array's size ({@code SimulatorContext}) and the
     * sample-count assertion ({@code SimulationRunner}) used {@code simulationDurationSeconds}
     * directly, which is only the step count when {@code stepTimeSlice} is exactly one simulated
     * second. At the finer quantum M8 requires - V0 ran at 10 ticks - the array overflowed on the
     * 21st sample and the run died with "DirtySetSizeSamples array full at index 20".
     *
     * <p>That was the third place the 1 s quantum was baked in, after the cost basis and the slice
     * itself. Shared here so the two consumers cannot drift apart again.
     */
    /** Dirty-set samples per simulated second. 1 = the historical cadence. */
    public static long dirtySampleHz() {
        return Math.max(1L, Long.getLong("ts.sim.dirtySampleHz", 1L));
    }

    /** Ticks between dirty-set samples, at the configured rate. */
    public static long dirtySamplePeriodTicks() {
        return Math.max(1L, TICKS_PER_SECOND / dirtySampleHz());
    }

    /**
     * Dirty-set samples a run will produce.
     *
     * <p>ONE derivation, shared by the sampler ({@code FixedTimeStepStrategy}), the array that holds
     * the samples ({@code SimulatorContext}) and the count assertion ({@code SimulationRunner}) -
     * the same rule {@link #expectedStepCount} exists for.
     *
     * <p><b>A rate and a period are not the same number, and deriving each independently is how
     * they disagree.</b> Sizing the array as {@code duration * hz} while the sampler steps by
     * {@code TICKS_PER_SECOND / hz} differs whenever the division is inexact: at {@code hz = 3333}
     * the period is 300 ticks, giving 66,667 samples against an array of 66,662, and the run dies
     * mid-flight on "DirtySetSizeSamples array full".
     */
    public static int dirtySampleCount(int simulationDurationSeconds) {
        long ticks = (long) simulationDurationSeconds * TICKS_PER_SECOND;
        long period = dirtySamplePeriodTicks();
        // (ticks - 1) / period + 1, NOT ticks / period. The sampler fires once per DISTINCT value of
        // virtualTime / period as virtualTime runs 0 .. ticks-1, so index 0 counts. The two agree
        // only when the period divides the window: at period 300 over 20,000,000 ticks the floor
        // form says 66,666 and the sampler produced 66,667, and the run died on the count assertion.
        return (int) ((ticks - 1) / period + 1);
    }

    public static int expectedStepCount(int simulationDurationSeconds, long stepTimeSlice) {
        if (stepTimeSlice <= 0) {
            throw new IllegalArgumentException("stepTimeSlice must be positive: " + stepTimeSlice);
        }
        long steps = (long) simulationDurationSeconds * TICKS_PER_SECOND / stepTimeSlice;
        if (steps > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(String.format(
                    "%d s at a %d-tick slice is %d steps - more than an int can hold. Cut the "
                    + "duration or coarsen the slice.", simulationDurationSeconds, stepTimeSlice, steps));
        }
        return (int) Math.max(1, steps);
    }

    public static class Builder {
        private int totalModels;
        private int totalResources;
        private long ioTimePerResource;
        private long cpuTimePerResource;
        private int initialTransactionCount;
        private int totalTransactionSteps;
        private int simulationDurationSeconds;
        private int warmupSeconds = 0; // F4: no discard by default
        private List<Model> modelPool;
        private int maxTransactionModels;
        private int maxTransactionResources;
        private boolean useConstantTransactionSize = false; // Default to variable size
        private int constantTransactionSize = 50; // Default constant size
        private TransactionSizeProfile transactionSizeProfile = TransactionSizeProfile.UNIFORM;
        private int smallTransactionSize = 10;
        private int largeTransactionSize = 100;
        private double largeTransactionProbability = 0.5;
        private boolean useRandomResourceSelection = false; // Default to sequential
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
        private long lockIOTime;
        private long lockCPUTimePerResource;
        private long rollbackFixed = 50_000;
        private long rollbackCPUTimePerResource = 10_000;
        private int numIoProcessors;
        private int numCpuProcessors;
        private int numReconcilerProcessors = 1; // Default to 1 reconciler processor
        private int totalLocks;
        private double semanticOverlap = 0.5; // Default to 50% overlap
        private int maxCprsPerResource = 1;
        private double avgCprsPerResource = 1.0;
        private CprTopologyMode cprTopologyMode = CprTopologyMode.UNIFORM;
        private ReferentialAction referentialAction = ReferentialAction.ON_CASCADE; // Default to ON_CASCADE
        private ConcurrencyModel concurrencyModel = ConcurrencyModel.ACID; // Default to ACID
        private int admissionBatchSize = 1; // Default preserves single-wake behavior
        private DeadlockStrategy deadlockStrategy = DeadlockStrategy.CAUTIOUS_WAITING; // Default deadlock strategy
        private Long transactionSamplingSeed = null; // Default to non-deterministic sampling
        private Long semanticOverlapSeed = null; // Default to non-deterministic bookkeeper creation
        private long stepTimeSlice = 1000; // Default 1 second slice for FIXED_TIME mode
        private TransactionMode transactionMode = TransactionMode.EQUILIBRIUM;
        private int numTerminals = 30;
        private long meanInterArrivalDelay = 1000;
        private TransactionGenerationMode transactionGenerationMode = TransactionGenerationMode.RANDOM_OPERATIONS;
        private List<TransactionProgram> programPool = List.of();
        private boolean oracleClosurePreclaim = false;
        private OracleClosurePlacement oracleClosurePlacement = OracleClosurePlacement.AT_END;
        private Integer lockPoolSize = null;
        private List<Integer> transactionModelIndices = List.of();
        private LockingProtocol lockingProtocol = LockingProtocol.C2PL;
        private double writeFraction = 1.0;
        private OperationType cuOperationType = OperationType.WRITE;
        private boolean writeOnlyCprSources = true;
        private int operationBatchSize = 0;
        private boolean groupUnitAffinity = true;
        private long deadlockDetectionInterval = 5000;
        private double deadlockDetectionBetaThreshold = 0.2;
        private long deadlockDetectionMinCooldown = 1000;

        public Builder totalModels(int totalModels) {
            this.totalModels = totalModels;
            return this;
        }

        public Builder totalResources(int totalResources) {
            this.totalResources = totalResources;
            return this;
        }

        public Builder ioTimePerResource(long ioTimePerResource) {
            this.ioTimePerResource = ioTimePerResource;
            return this;
        }

        public Builder cpuTimePerResource(long cpuTimePerResource) {
            this.cpuTimePerResource = cpuTimePerResource;
            return this;
        }

        public Builder initialTransactionCount(int initialTransactionCount) {
            this.initialTransactionCount = initialTransactionCount;
            return this;
        }

        public Builder totalTransactionSteps(int totalTransactionSteps) {
            this.totalTransactionSteps = totalTransactionSteps;
            return this;
        }

        public Builder simulationDurationSeconds(int simulationDurationSeconds) {
            this.simulationDurationSeconds = simulationDurationSeconds;
            return this;
        }

        public Builder warmupSeconds(int warmupSeconds) {
            this.warmupSeconds = warmupSeconds;
            return this;
        }

        public Builder modelPool(List<Model> modelPool) {
            this.modelPool = modelPool;
            return this;
        }

        public Builder maxTransactionModels(int maxTransactionModels) {
            this.maxTransactionModels = maxTransactionModels;
            return this;
        }

        public Builder maxTransactionResources(int maxTransactionResources) {
            this.maxTransactionResources = maxTransactionResources;
            return this;
        }

        public Builder useConstantTransactionSize(boolean useConstantTransactionSize) {
            this.useConstantTransactionSize = useConstantTransactionSize;
            return this;
        }

        public Builder constantTransactionSize(int constantTransactionSize) {
            this.constantTransactionSize = constantTransactionSize;
            return this;
        }

        public Builder transactionSizeProfile(TransactionSizeProfile transactionSizeProfile) {
            this.transactionSizeProfile = transactionSizeProfile;
            return this;
        }

        public Builder smallTransactionSize(int smallTransactionSize) {
            this.smallTransactionSize = smallTransactionSize;
            return this;
        }

        public Builder largeTransactionSize(int largeTransactionSize) {
            this.largeTransactionSize = largeTransactionSize;
            return this;
        }

        public Builder largeTransactionProbability(double largeTransactionProbability) {
            this.largeTransactionProbability = largeTransactionProbability;
            return this;
        }

        public Builder useRandomResourceSelection(boolean useRandomResourceSelection) {
            this.useRandomResourceSelection = useRandomResourceSelection;
            return this;
        }

        public Builder accessDistribution(ResourceAccessDistribution accessDistribution) {
            this.accessDistribution = accessDistribution == null
                    ? ResourceAccessDistribution.UNIFORM : accessDistribution;
            return this;
        }

        public Builder zipfTheta(double zipfTheta) {
            this.zipfTheta = zipfTheta;
            return this;
        }

        public Builder workloadMix(WorkloadMix workloadMix) {
            this.workloadMix = workloadMix == null ? WorkloadMix.PER_OPERATION : workloadMix;
            return this;
        }

        public Builder readOnlyTransactionFraction(double readOnlyTransactionFraction) {
            this.readOnlyTransactionFraction = readOnlyTransactionFraction;
            return this;
        }

        public Builder maxConcurrentRunning(Integer maxConcurrentRunning) {
            this.maxConcurrentRunning = maxConcurrentRunning;
            return this;
        }

        public Builder backoffScale(double v) {
            this.backoffScale = v;
            return this;
        }

        public Builder readyWaiterOrder(ReadyWaiterOrder v) {
            this.readyWaiterOrder = v == null
                    ? ReadyWaiterOrder.LIFO : v;
            return this;
        }

        public Builder cuQueuePolicy(CuQueuePolicy v) {
            this.cuQueuePolicy = v == null ? CuQueuePolicy.PREPEND : v;
            return this;
        }

        public Builder cprInitialAttractiveness(Double v) {
            this.cprInitialAttractiveness = v;
            return this;
        }

        public Builder cprAcyclicTotalOrder(boolean v) {
            this.cprAcyclicTotalOrder = v;
            return this;
        }

        public Builder lockIOTime(long lockIOTime) {
            this.lockIOTime = lockIOTime;
            return this;
        }

        public Builder lockCPUTimePerResource(long lockCPUTimePerResource) {
            this.lockCPUTimePerResource = lockCPUTimePerResource;
            return this;
        }

        public Builder rollbackFixed(long rollbackFixed) {
            this.rollbackFixed = rollbackFixed;
            return this;
        }

        public Builder rollbackCPUTimePerResource(long rollbackCPUTimePerResource) {
            this.rollbackCPUTimePerResource = rollbackCPUTimePerResource;
            return this;
        }

        public Builder numIoProcessors(int numIoProcessors) {
            this.numIoProcessors = numIoProcessors;
            return this;
        }

        public Builder numCpuProcessors(int numCpuProcessors) {
            this.numCpuProcessors = numCpuProcessors;
            return this;
        }

        public Builder numReconcilerProcessors(int numReconcilerProcessors) {
            this.numReconcilerProcessors = numReconcilerProcessors;
            return this;
        }

        public Builder totalLocks(int totalLocks) {
            this.totalLocks = totalLocks;
            return this;
        }

        public Builder semanticOverlap(double semanticOverlap) {
            this.semanticOverlap = semanticOverlap;
            return this;
        }

        public Builder maxCprsPerResource(int maxCprsPerResource) {
            this.maxCprsPerResource = maxCprsPerResource;
            return this;
        }

        public Builder avgCprsPerResource(double avgCprsPerResource) {
            this.avgCprsPerResource = avgCprsPerResource;
            return this;
        }

        public Builder cprTopologyMode(CprTopologyMode cprTopologyMode) {
            this.cprTopologyMode = cprTopologyMode;
            return this;
        }

        public Builder referentialAction(ReferentialAction referentialAction) {
            this.referentialAction = referentialAction;
            return this;
        }

        public Builder concurrencyModel(ConcurrencyModel concurrencyModel) {
            this.concurrencyModel = concurrencyModel;
            return this;
        }

        public Builder admissionBatchSize(int admissionBatchSize) {
            this.admissionBatchSize = admissionBatchSize;
            return this;
        }

        public Builder deadlockStrategy(DeadlockStrategy deadlockStrategy) {
            this.deadlockStrategy = deadlockStrategy;
            return this;
        }

        public Builder transactionSamplingSeed(Long transactionSamplingSeed) {
            this.transactionSamplingSeed = transactionSamplingSeed;
            return this;
        }

        public Builder semanticOverlapSeed(Long semanticOverlapSeed) {
            this.semanticOverlapSeed = semanticOverlapSeed;
            return this;
        }

        public Builder stepTimeSlice(long stepTimeSlice) {
            this.stepTimeSlice = stepTimeSlice;
            return this;
        }

        public Builder transactionMode(TransactionMode transactionMode) {
            this.transactionMode = transactionMode;
            return this;
        }

        public Builder numTerminals(int numTerminals) {
            this.numTerminals = numTerminals;
            return this;
        }

        public Builder meanInterArrivalDelay(long meanInterArrivalDelay) {
            this.meanInterArrivalDelay = meanInterArrivalDelay;
            return this;
        }

        public Builder transactionGenerationMode(TransactionGenerationMode transactionGenerationMode) {
            this.transactionGenerationMode = transactionGenerationMode;
            return this;
        }

        public Builder programPool(List<TransactionProgram> programPool) {
            this.programPool = programPool;
            return this;
        }

        /** Restrict ORIGINAL transactions to these model indices; empty = every model. */
        public Builder transactionModelIndices(List<Integer> indices) {
            this.transactionModelIndices = indices == null ? List.of() : indices;
            return this;
        }

        public Builder oracleClosurePreclaim(boolean oracleClosurePreclaim) {
            this.oracleClosurePreclaim = oracleClosurePreclaim;
            return this;
        }

        public Builder lockPoolSize(Integer lockPoolSize) {
            this.lockPoolSize = lockPoolSize;
            return this;
        }

        public Builder oracleClosurePlacement(OracleClosurePlacement oracleClosurePlacement) {
            this.oracleClosurePlacement = oracleClosurePlacement != null ? oracleClosurePlacement
                    : OracleClosurePlacement.AT_END;
            return this;
        }

        public Builder lockingProtocol(LockingProtocol lockingProtocol) {
            this.lockingProtocol = lockingProtocol != null ? lockingProtocol
                    : LockingProtocol.C2PL;
            return this;
        }

        public Builder writeFraction(double writeFraction) {
            this.writeFraction = writeFraction;
            return this;
        }

        public Builder cuOperationType(OperationType cuOperationType) {
            this.cuOperationType = cuOperationType != null ? cuOperationType : OperationType.WRITE;
            return this;
        }

        public Builder writeOnlyCprSources(boolean writeOnlyCprSources) {
            this.writeOnlyCprSources = writeOnlyCprSources;
            return this;
        }

        public Builder groupUnitAffinity(boolean groupUnitAffinity) {
            this.groupUnitAffinity = groupUnitAffinity;
            return this;
        }

        public Builder operationBatchSize(int operationBatchSize) {
            this.operationBatchSize = Math.max(0, operationBatchSize);
            return this;
        }

        public Builder deadlockDetectionInterval(long deadlockDetectionInterval) {
            this.deadlockDetectionInterval = deadlockDetectionInterval;
            return this;
        }

        public Builder deadlockDetectionBetaThreshold(double deadlockDetectionBetaThreshold) {
            this.deadlockDetectionBetaThreshold = deadlockDetectionBetaThreshold;
            return this;
        }

        public Builder deadlockDetectionMinCooldown(long deadlockDetectionMinCooldown) {
            this.deadlockDetectionMinCooldown = deadlockDetectionMinCooldown;
            return this;
        }

        public SimulatorConfig build() {
            validate();
            return new SimulatorConfig(this);
        }

        private void validate() {
            if (semanticOverlap < 0.0 || semanticOverlap > 1.0) {
                throw new IllegalArgumentException("semanticOverlap must be between 0 and 1");
            }
            if (maxCprsPerResource < 1) {
                throw new IllegalArgumentException("maxCprsPerResource must be at least 1");
            }
            if (avgCprsPerResource < 1.0 || avgCprsPerResource > maxCprsPerResource) {
                throw new IllegalArgumentException("avgCprsPerResource must be between 1 and maxCprsPerResource");
            }
            if (semanticOverlap > 0.0 && modelPool != null && modelPool.size() < 2) {
                throw new IllegalArgumentException(
                        "semanticOverlap > 0 requires at least 2 models; with 1 model no cross-model link is possible");
            }
            if (smallTransactionSize <= 0 || largeTransactionSize <= 0) {
                throw new IllegalArgumentException("Transaction sizes must be positive");
            }
            if (smallTransactionSize > largeTransactionSize) {
                throw new IllegalArgumentException("smallTransactionSize must be <= largeTransactionSize");
            }
            if (largeTransactionProbability < 0.0 || largeTransactionProbability > 1.0) {
                throw new IllegalArgumentException("largeTransactionProbability must be between 0 and 1");
            }
            if (modelPool == null || modelPool.isEmpty()) {
                throw new IllegalArgumentException("modelPool must not be empty");
            }
            if (totalModels != modelPool.size()) {
                throw new IllegalArgumentException(
                        "totalModels (" + totalModels + ") is inconsistent with the constructed model pool ("
                                + modelPool.size() + " model(s)); totalModels must equal propertiesPerModel.size()");
            }
            if (transactionGenerationMode == TransactionGenerationMode.TRANSACTION_PROGRAMS) {
                if (programPool == null || programPool.isEmpty()) {
                    throw new IllegalArgumentException(
                            "transactionGenerationMode is TRANSACTION_PROGRAMS but programPool is empty; "
                                    + "build the config via the 3-arg SimulatorConfig.fromParameters(params, modelPool, programPool) overload");
                }
                for (TransactionProgram program : programPool) {
                    // The distinct-instance draw (TransactionFactory) needs, for every declared
                    // property, at least as many instance ids as the number of the program's
                    // variables sharing that property.
                    Map<Property, Integer> countsByProperty = new HashMap<>();
                    for (String variable : program.variables()) {
                        Property property = program.variableProperties().get(variable);
                        countsByProperty.merge(property, 1, Integer::sum);
                    }
                    int instancePoolSize = program.model().instancePoolSize();
                    for (Map.Entry<Property, Integer> entry : countsByProperty.entrySet()) {
                        if (instancePoolSize < entry.getValue()) {
                            throw new IllegalArgumentException(
                                    "Program '" + program.id() + "' needs " + entry.getValue()
                                            + " distinct instances on property '" + entry.getKey()
                                            + "' but instancePoolSize=" + instancePoolSize
                                            + " on model '" + program.model() + "'; the distinct-instance draw cannot succeed");
                        }
                    }
                }
            }
            if (transactionGenerationMode == TransactionGenerationMode.RANDOM_OPERATIONS) {
                // numModels can degenerate to 1 (maxTransactionModels draw), so any one model
                // must be able to supply the worst-case single-transaction resource draw on its
                // own. Schema-driven sizing provisions each model for what its program mode
                // needs, so there is no longer a blanket over-provision to fall back on.
                int worstCaseResourceDraw;
                if (useConstantTransactionSize || transactionSizeProfile == TransactionSizeProfile.CONSTANT) {
                    worstCaseResourceDraw = constantTransactionSize;
                } else if (transactionSizeProfile == TransactionSizeProfile.BIMODAL) {
                    worstCaseResourceDraw = Math.max(smallTransactionSize, largeTransactionSize);
                } else {
                    worstCaseResourceDraw = maxTransactionResources;
                }
                for (Model model : modelPool) {
                    if (model.resourceCount() < worstCaseResourceDraw) {
                        throw new IllegalArgumentException(
                                "Model '" + model.getName() + "' has " + model.resourceCount()
                                        + " resource(s), below the worst-case single-model draw of "
                                        + worstCaseResourceDraw + " a RANDOM_OPERATIONS-mode transaction can require "
                                        + "(numModels can degenerate to 1); increase instancePoolSize or that model's "
                                        + "property count");
                    }
                }
            }
        }
    }
}

