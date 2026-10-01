package de.tum.tssimulator.experiments;

import java.util.List;

import de.tum.tssimulator.bootstrap.DeadlockStateDumper;
import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.bootstrap.Simulator;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.execution.StepMode;
import de.tum.tssimulator.experiments.support.TerminatedBy;
import de.tum.tssimulator.locking.impl.BudgetedLockManagerDecorator;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.monitoring.DebugConfig;
import de.tum.tssimulator.monitoring.DispatchStats;
import de.tum.tssimulator.monitoring.SimulationLogger;

/**
 * Runs a single simulation and collects metrics.
 * Extracted from ExperimentRunner to follow Single Responsibility Principle.
 */
public class SimulationRunner {

    /**
     * A single run's ExperimentResult alongside the full ExperimentRuntimeSnapshot that
     * produced it (including the 5 new transaction-state accumulators) - exposes what run()
     * already builds internally and normally discards, without changing ExperimentResult's
     * existing 100+-parameter constructor or any of its current callers.
     *
     * F3 (VLDB reviewer response): terminatedBy records why this seed's run stopped
     * (TIME_LIMIT = normal stop, WATCHDOG = hung and force-killed). Callers built before F3
     * always see TIME_LIMIT here (runDetailed() never returns a WATCHDOG outcome - it still
     * throws, unchanged); only runDetailedAllowPartial() can produce WATCHDOG.
     */
    public record SimulationRunOutcome(ExperimentResult result, ExperimentRuntimeSnapshot snapshot,
                                        TerminatedBy terminatedBy) {
    }

    /**
     * Run a single simulation and collect metrics.
     */
    public ExperimentResult run(String experimentName, SimulatorParameters params) {
        return runDetailed(experimentName, params).result();
    }

    /**
     * Same as {@link #run(String, SimulatorParameters)} but also returns the
     * ExperimentRuntimeSnapshot the run produced, for callers (E1-E4 experiments) that need
     * the 5 new transaction-state accumulators alongside the existing ExperimentResult fields.
     *
     * Unchanged since before F3: a watchdog-triggered hang still propagates
     * SimulationWatchdogException out of this method rather than returning a WATCHDOG outcome.
     * Use {@link #runDetailedAllowPartial(String, SimulatorParameters)} for the new
     * never-drop-a-seed behavior the E-A..E-E raw-row experiments rely on.
     */
    public SimulationRunOutcome runDetailed(String experimentName, SimulatorParameters params) {
        SimulatorContext ctx = buildContext(params);
        Simulator simulator = new Simulator(ctx, StepMode.FIXED_TIME);

        long startTime = System.currentTimeMillis();
        simulator.run();
        long endTime = System.currentTimeMillis();

        return captureOutcome(experimentName, params, ctx, endTime - startTime, true, TerminatedBy.TIME_LIMIT);
    }

    /**
     * F3 (VLDB reviewer response): same as {@link #runDetailed(String, SimulatorParameters)},
     * but a watchdog hang is captured as a censored outcome (terminatedBy=WATCHDOG) instead of
     * throwing SimulationWatchdogException out of this method. The SimulatorContext is still
     * valid at the point the watchdog fires - Simulator.run()'s finally block already ran, and
     * watchdogFailure() already recorded the failure into SimulationMetrics before throwing -
     * so a best-effort ExperimentRuntimeSnapshot can still be captured. collectMetrics()'s
     * BASE-mode dirty-set sample-count assertion (which assumes a run always reaches full
     * duration) is relaxed to a logged warning for this path (strict=false), since a
     * watchdog-truncated run legitimately has fewer samples than simulationDurationSeconds.
     */
    public SimulationRunOutcome runDetailedAllowPartial(String experimentName, SimulatorParameters params) {
        SimulatorContext ctx = buildContext(params);
        Simulator simulator = new Simulator(ctx, StepMode.FIXED_TIME);

        long startTime = System.currentTimeMillis();
        try {
            simulator.run();
            long endTime = System.currentTimeMillis();
            return captureOutcome(experimentName, params, ctx, endTime - startTime, true, TerminatedBy.TIME_LIMIT);
        } catch (SimulationWatchdogException e) {
            long endTime = System.currentTimeMillis();
            return captureOutcome(experimentName, params, ctx, endTime - startTime, false, TerminatedBy.WATCHDOG);
        }
    }

    /**
     * completion-count stopping rule: the pre-existing StepMode.
     * FIXED_TRANSACTION / FixedTransactionStepStrategy, driven by
     * params.totalTransactionSteps, in place of a new mechanism - self-scales across configs
     * whose true mean group lifetime differs, which a fixed simulationDurationSeconds cannot.
     * No wall-clock cap (see the overload below for that) - only the completion target and the
     * SimulationWatchdog (engine-agnostic, wired into Simulator.run()'s shared loop regardless
     * of StepStrategy) bound how long this can run.
     */
    public SimulationRunOutcome runFixedTransactionAllowPartial(String experimentName, SimulatorParameters params) {
        // BUGFIX (caught by CrossModeAgreementTest immediately after being introduced, same
        // turn): 0, not Long.MAX_VALUE - the overload below's "<=0 means no cap" guard exists
        // precisely so callers don't have to pass a sentinel through a
        // System.currentTimeMillis() + budget addition, which overflows Long.MAX_VALUE into a
        // huge NEGATIVE deadline (always "in the past"), terminating every run after a single
        // step. Exactly the kind of bug the cross-mode test exists to catch - it did.
        return runFixedTransactionAllowPartial(experimentName, params, 0, 0);
    }

    /**
     * Same as {@link #runFixedTransactionAllowPartial(String, SimulatorParameters)}, plus two
     * independent caps:
     *
     * <p>{@code simulatedTimeCap} bounds virtual (simulated) time - a run that hasn't reached
     * its completion target by then stops cleanly, tagged TIME_LIMIT, every still-open group
     * captured as a right-censored Kaplan-Meier observation. This is the KM-SAFE cap: passing the
     * same value to every seed/config censors each one at the identical point in its own
     * timeline, independent of how it got there - satisfying Kaplan-Meier's non-informative-
     * censoring assumption.
     *
     * <p>{@code wallClockBudgetMs} bounds real elapsed time - a PURE runaway guard, not meant to
     * be hit under normal operation (set it generous). Correction: an earlier
     * version of this method used wall-clock time as the primary cap, tagging a capped run
     * TIME_LIMIT - but a wall-clock cap censors at a point that correlates with how slow/starving
     * that specific run was (slow runs censor earlier in their own timeline than fast ones),
     * which is informative censoring and biases Kaplan-Meier toward the effect under study. A run
     * that hits this guard is tagged WALL_CLOCK_RUNAWAY instead, specifically so it's excluded
     * from KM analysis (report it separately) rather than silently contaminating the estimator.
     *
     * <p>Either budget &lt;= 0 means that particular cap is disabled.
     */
    public SimulationRunOutcome runFixedTransactionAllowPartial(String experimentName, SimulatorParameters params,
            long simulatedTimeCap, long wallClockBudgetMs) {
        SimulatorContext ctx = buildContext(params);
        if (simulatedTimeCap > 0) {
            ctx.simulatedTimeCap = simulatedTimeCap;
        }
        if (wallClockBudgetMs > 0) {
            ctx.wallClockDeadlineMs = System.currentTimeMillis() + wallClockBudgetMs;
        }
        Simulator simulator = new Simulator(ctx, StepMode.FIXED_TRANSACTION);

        long startTime = System.currentTimeMillis();
        try {
            simulator.run();
            long endTime = System.currentTimeMillis();
            // The step strategy doesn't report WHICH of the three stop conditions fired - all
            // three just set finished=true - so infer it here from what's known after the fact.
            boolean reachedTarget = ctx.logger().metrics().getGroupsCompletedTotal() >= params.totalTransactionSteps;
            TerminatedBy terminatedBy;
            if (reachedTarget) {
                terminatedBy = TerminatedBy.COMPLETION_TARGET;
            } else if (simulatedTimeCap > 0 && ctx.virtualTime >= simulatedTimeCap) {
                terminatedBy = TerminatedBy.TIME_LIMIT;
            } else {
                // Fell short of both the target and the simulated-time cap - only the wall-clock
                // runaway guard could have ended it.
                terminatedBy = TerminatedBy.WALL_CLOCK_RUNAWAY;
            }
            return captureOutcome(experimentName, params, ctx, endTime - startTime, true, terminatedBy);
        } catch (SimulationWatchdogException e) {
            long endTime = System.currentTimeMillis();
            return captureOutcome(experimentName, params, ctx, endTime - startTime, false, TerminatedBy.WATCHDOG);
        }
    }

    private SimulatorContext buildContext(SimulatorParameters params) {
        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        return SimulatorContext.create(config);
    }

    private SimulationRunOutcome captureOutcome(String experimentName, SimulatorParameters params,
            SimulatorContext ctx, long wallClock, boolean strict, TerminatedBy terminatedBy) {
        // Collect metrics, including partial closures from timed-out or active groups
        for (TransactionGroup group : ctx.registry().getActiveGroups()) {
            ctx.logger().metrics().recordPartialGroupImpact(group);
            // BUGFIX (Kaplan-Meier right-censoring - caught by the user pointing out 0% censored
            // was impossible given meanLiveGroupsSampled~32 at MPL=32): TransactionGroupRegistry
            // never clears an ABANDONED group's entry (only registry.clear(), called on the
            // COMPLETION path, removes entries), so getActiveGroups() also contains every group
            // that was ever superseded by a restart, not just genuinely still-open ones. The
            // PREVIOUS filter (parent.getState() != ABORTED) tried to exclude those but couldn't:
            // a transaction sitting in the exponential-backoff queue between restarts is ALSO in
            // TransactionState.ABORTED at that instant, indistinguishable from a permanently-
            // abandoned lineage via getState() alone - so the filter silently excluded the
            // MOST-stuck subpopulation (mid-backoff groups), the exact thing right-censoring
            // exists to capture, producing 0% censored across every run. Fixed by checking
            // TransactionGroup.isSuperseded() (set explicitly, once, exactly when a lineage is
            // abandoned - see TransactionGroupAbortCoordinator.restartTransaction()) instead of
            // inferring staleness from a transient state field.
            // Uses firstAdmissionTime (set once at the lineage's TRUE first admission, carried
            // forward - never reset - across restarts; see TransactionScheduler and Transaction's
            // javadoc on the field) rather than submissionTime (reset to "now" on every restart,
            // so it would measure only the final attempt's span - caught via an impossible
            // negative-residual result in the retry/backoff decomposition) or lockHoldStart
            // (only set on a SUCCESSFUL C2PL preclaim, so it's -1/unset for a group stuck in
            // admission-denial backoff), and getLockRetryCount() directly off the transaction
            // rather than a per-group accumulator, since TransactionGroup is not stable across
            // restarts (see ACIDCompletionHandler's recordGroupImpact() for the full correction).
            Transaction parent = group.getParent();
            if (parent != null && !group.isSuperseded()
                    && parent.getState() != TransactionState.COMPLETED) {
                // Five-way lineage time decomposition: close out whatever phase this
                // still-open (censored) lineage is currently in, at run end, before reading
                // its accumulators.
                parent.finalizeLineageTimeAccounting(ctx.virtualTime);
                ctx.logger().metrics().recordCensoredGroupLifetime(
                        parent.getFirstAdmissionTime(), ctx.virtualTime, parent.getLockRetryCount(),
                        group.getMaxBookkeeperDepth(), parent.getCumulativePendingQueue(),
                        parent.getCumulativeBackoff(), parent.getCumulativeAdmissionWait(),
                        parent.getCumulativeRunning(), parent.getCumulativeWaitingRoom(),
                        parent.getCumulativeRunningWasted());
            }
        }
        DeadlockStateDumper.dumpIfEnabled(ctx, terminatedBy.name());
        ExperimentRuntimeSnapshot snapshot = ctx.experimentSnapshot();
        ExperimentResult result = collectMetrics(experimentName, params, ctx, snapshot, wallClock, strict);
        return new SimulationRunOutcome(result, snapshot, terminatedBy);
    }

    /**
     * Collect metrics from completed simulation.
     *
     * @param strict when false (only from runDetailedAllowPartial()'s watchdog-catch path), the
     *               BASE-mode dirty-set-sample-count and ACID-mode no-dirty-set-samples
     *               assertions are downgraded from IllegalStateException to a logged warning,
     *               since a watchdog-truncated run legitimately has fewer samples than a
     *               completed one.
     */
    /** strict: throw IllegalStateException (today's behavior). !strict: log a warning and
     * continue (F3's watchdog-partial-capture path, where the assertion's premise - a run that
     * reached full duration - legitimately does not hold). */
    private void failOrWarn(boolean strict, String message) {
        if (strict) {
            throw new IllegalStateException(message);
        }
        System.out.println("[runDetailedAllowPartial WARNING, non-strict] " + message);
    }

    private ExperimentResult collectMetrics(String experimentName,
                                           SimulatorParameters params,
                                           SimulatorContext ctx,
                                           ExperimentRuntimeSnapshot snapshot,
                                           long wallClock,
                                           boolean strict) {
        var execution = ctx.execution();
        var health = snapshot.healthSnapshot();

        long totalIO = Transaction.getTotalSimulatedIO();
        long totalCPU = Transaction.getTotalSimulatedCPU();
        long totalLock = snapshot.totalLockTime();
        // Use state counter which excludes bookkeepers from COMPLETED count
        long completed = TransactionStateCounter.getCount(TransactionState.COMPLETED);
        int pending = health.pendingQueueDepth();
        int blocked = health.blockedQueueDepth();
        int abortedGroups = snapshot.totalAbortedGroups();
        int bookkeepers = snapshot.totalBookkeepers();
        
        // Debug: Check lockWaiters state if we have blocked transactions
        if (blocked > 0 && DebugConfig.isEnabled()) {
            System.out.println("\n[BLOCKED STATE] " + blocked + " transactions in BLOCKED_TXN queue");
            
            // Get lock manager through the explicitly wired execution bundle.
            if (execution != null) {
                var processor = execution.processor();
                var lockCoordinator = processor.getLockCoordinator();
                var lockManager = lockCoordinator.getLockManager();
                
                if (lockManager instanceof BudgetedLockManagerDecorator) {
                    var decorator = (BudgetedLockManagerDecorator) lockManager;
                    var delegate = decorator.getDelegate();
                    if (delegate instanceof SS2PLLockManager) {
                        var ss2pl = (SS2PLLockManager) delegate;
                        int lockWaitersMapSize = ss2pl.getLockWaitersMapSize();
                        int totalWaiters = ss2pl.getTotalWaitersCount();
                        var uniqueWaiters = ss2pl.getAllUniqueWaiters();
                        
                        System.out.println("[BLOCKED STATE] lockWaiters map has " + lockWaitersMapSize + " locks with waiters");
                        System.out.println("[BLOCKED STATE] Total waiter entries: " + totalWaiters + " (can have duplicates if waiting for multiple locks)");
                        System.out.println("[BLOCKED STATE] Unique waiting transactions: " + uniqueWaiters.size());
                        System.out.println("[BLOCKED STATE] Transactions in BLOCKED_TXN but NOT in lockWaiters: " + (blocked - uniqueWaiters.size()));
                        
                        if (blocked > uniqueWaiters.size()) {
                            System.out.println("[BLOCKED STATE] *** WARNING: " + (blocked - uniqueWaiters.size()) + 
                                             " transactions are BLOCKED but not registered as waiters! ***");
                        }
                    }
                }
            }
        }
        
        int abortBackoffSize = snapshot.abortBackoffQueueSize();
        int waitingForGroup = health.waitingForGroupQueueDepth();
        int awaitingUserAction = health.awaitingUserActionQueueDepth();
        int lockQueue = health.lockQueueDepth();
        int ioQueue = health.ioQueueDepth();
        int cpuQueue = health.cpuQueueDepth();
        int reconciliationQueue = health.reconciliationQueueDepth();

        DispatchStats dispatchStats = snapshot.dispatchStats();
        if (dispatchStats != null && DebugConfig.PRINT_DISPATCH_SUMMARY) {
            System.out.println("\n=== DISPATCH STATS SUMMARY ===");
            System.out.println(dispatchStats.getSummary());
            System.out.println("==============================\n");
        }
        
        // Collect bookkeeper depth distribution
        int maxDepth = snapshot.maxDepthObserved();
        int depth1 = snapshot.depthCountAt(1);
        int depth2 = snapshot.depthCountAt(2);
        int depth3 = snapshot.depthCountAt(3);
        int depth4 = snapshot.depthCountAt(4);
        int depth5Plus = snapshot.depth5PlusCount();
        
        // NEW: Calculate dirty set statistics
        SimulationLogger.DirtySetStats dirtySetStats = snapshot.dirtySetStats();
        
        if (params.concurrencyModel == ConcurrencyModel.BASE) {
            // BASE mode - must collect stats
            if (ctx.dirtyState() == null) {
                failOrWarn(strict, "BASE mode but dirtySetManager is null");
            } else if (dirtySetStats == null) {
                failOrWarn(strict, "BASE mode but no dirty set stats collected");
            } else {
                // CRITICAL CHECK: Verify sample count (invalidates quantiles if wrong). Relaxed
                // to a warning when !strict (F3: a watchdog-truncated run legitimately has fewer
                // samples than simulationDurationSeconds - this is expected on the censored path,
                // not a bug).
                int expectedSamples = SimulatorConfig
                        .dirtySampleCount(params.simulationDurationSeconds);
                if (dirtySetStats.sampleCount != expectedSamples) {
                    failOrWarn(strict, String.format(
                        "Sample count mismatch! Expected %d, got %d. Check sampling in FixedTimeStepStrategy.",
                        expectedSamples, dirtySetStats.sampleCount));
                }
                // Print for verification
                System.out.println("Dirty Set Statistics: " + dirtySetStats);
            }
        } else {
            // ACID mode - must NOT collect stats
            // Verify no stats were accidentally collected
            int collectedSamples = ctx.logger().getDirtySetSampleCount();
            if (collectedSamples > 0) {
                failOrWarn(strict, "ACID mode but " + collectedSamples + " dirty set samples were collected");
            }
        }
        
        // Calculate recovery latency statistics (BASE mode only)
        SimulationLogger.RecoveryLatencyStats recoveryLatencyStats =
                snapshot.recoveryLatencyStats();
        int dirtyAtSimEnd = snapshot.dirtyAtSimulationEndCount();
        if (recoveryLatencyStats != null) {
            System.out.println("Recovery Latency Statistics: " + recoveryLatencyStats);
            System.out.println("Resources still dirty at simulation end: " + dirtyAtSimEnd);
        }
        
        return new ExperimentResult(
            experimentName,
            params.totalModels,
            params.totalResources,
            ctx.config().numIoProcessors,
            ctx.config().numCpuProcessors,
            params.totalLocks,
            params.initialTransactions,
            params.simulationDurationSeconds,
            totalIO,
            totalCPU,
            totalLock,
            completed,
            wallClock,
            pending,
            blocked,
            abortedGroups,
            bookkeepers,
            abortBackoffSize,
            waitingForGroup,
            awaitingUserAction,
            lockQueue,
            ioQueue,
            cpuQueue,
            reconciliationQueue,
            dispatchStats,
            maxDepth,
            depth1,
            depth2,
            depth3,
            depth4,
            depth5Plus,
            snapshot.groupsStartedTotal(),
            snapshot.groupsCompletedTotal(),
            snapshot.bookkeepersCreatedTotal(),
            snapshot.bookkeepersCompletedTotal(),
            snapshot.bookkeepersAbortedTotal(),
            snapshot.bookkeepersTerminatedTotal(),
            snapshot.peakPendingQueueDepth(),
            snapshot.peakBlockedQueueDepth(),
            snapshot.peakReadyQueueDepth(),
            snapshot.peakLockQueueDepth(),
            snapshot.peakIoQueueDepth(),
            snapshot.peakCpuQueueDepth(),
            snapshot.peakRollbackQueueDepth(),
            snapshot.peakWaitingForGroupQueueDepth(),
            snapshot.queueDepthSampleCount(),
            snapshot.p95PendingQueueDepth(),
            snapshot.p95BlockedQueueDepth(),
            snapshot.p95LockQueueDepth(),
            snapshot.p95WaitingForGroupQueueDepth(),
            snapshot.p95RollbackQueueDepth(),
            snapshot.peakPendingStateCount(),
            snapshot.peakRunningStateCount(),
            snapshot.peakBlockedStateCount(),
            snapshot.peakReadyStateCount(),
            snapshot.peakCompletedStateCount(),
            snapshot.parentTransactionLatencyStats(),
            snapshot.cprSynchronizationDelayStats(),
            snapshot.rollbackLatencyStats(),
            snapshot.cascadeAmplificationStats(),
            snapshot.cascadeResourceReferenceSamples(),
            snapshot.meanCascadeParentResourceReferences(),
            snapshot.meanCascadeCprResourceReferences(),
            snapshot.startedGroupParentResourceReferenceSamples(),
            snapshot.meanStartedGroupParentResourceReferences(),
            snapshot.completedGroupResourceReferenceSamples(),
            snapshot.completedGroupParentResourceReferencesTotal(),
            snapshot.completedGroupCprResourceReferencesTotal(),
            snapshot.completedParentResourceReferencesTotal(),
            snapshot.completedBookkeeperResourceReferencesTotal(),
            dirtySetStats,  // NEW: Pass stats object (null for ACID)
            recoveryLatencyStats,  // NEW: Recovery latency stats (null for ACID)
            dirtyAtSimEnd  // NEW: Resources still dirty at end
        );
    }
}
