package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.TransactionAggregateMetrics;
import de.tum.tssimulator.execution.AbstractAdmissionController;
import de.tum.tssimulator.execution.PhaseDispatcherGroup;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.monitoring.DispatchStats;
import de.tum.tssimulator.monitoring.RuntimeHealthSnapshot;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMetrics;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;

final class ExperimentRuntimeSnapshotFactory {

    private ExperimentRuntimeSnapshotFactory() {
    }

    static ExperimentRuntimeSnapshot capture(SimulatorContext context) {
        QueuePort queues = context.scheduling().queues();
        SimulationLogger logger = context.logger();
        RuntimeHealthSnapshot healthSnapshot = context.monitor().snapshot(context.virtualTime);

        int abortBackoffSize = queues instanceof CentralQueueManager centralQueueManager
                ? centralQueueManager.abortedBackoffSize()
                : 0;

        SimulationLogger.DirtySetStats dirtySetStats = logger.calculateDirtySetStats();
        SimulationLogger.RecoveryLatencyStats recoveryLatencyStats = null;
        int dirtyAtSimulationEndCount = 0;
        if (context.config().concurrencyModel == ConcurrencyModel.BASE && context.dirtyState() != null) {
            recoveryLatencyStats = logger.calculateRecoveryLatencyStats();
            dirtyAtSimulationEndCount = context.dirtyState().size();
        }

        // computed once here (sweep-line over every recorded group interval) -
        // the two callers below index into this local rather than each re-triggering the sweep.
        double[] groupConcurrencyStats = logger.metrics().computeGroupConcurrencyStats();

        // Kaplan-Meier lifetime: flatten the observation list into 3 parallel
        // arrays here, once, rather than exposing the record list type across the
        // ExperimentRuntimeSnapshot boundary (every other array field on that record is a
        // primitive array).
        var groupLifetimeObservations = logger.metrics().getGroupLifetimeObservations();
        long[] groupLifetimeObservation = new long[groupLifetimeObservations.size()];
        int[] groupLifetimeObservationEvent = new int[groupLifetimeObservations.size()];
        int[] groupLifetimeObservationMaxRetryCount = new int[groupLifetimeObservations.size()];
        int[] groupLifetimeObservationMaxDepth = new int[groupLifetimeObservations.size()];
        // FIVE-way lineage time decomposition - see SimulationMetrics.GroupLifetimeObservation's
        // javadoc: sums to groupLifetimeObservation[i] exactly, by construction. It was four here,
        // and four does NOT sum to lifetime - waitingRoom was accumulated and never exported.
        long[] groupLifetimeObservationPendingQueue = new long[groupLifetimeObservations.size()];
        long[] groupLifetimeObservationBackoff = new long[groupLifetimeObservations.size()];
        long[] groupLifetimeObservationAdmissionWait = new long[groupLifetimeObservations.size()];
        long[] groupLifetimeObservationRunning = new long[groupLifetimeObservations.size()];
        long[] groupLifetimeObservationWaitingRoom = new long[groupLifetimeObservations.size()];
        long[] groupLifetimeObservationRunningWasted = new long[groupLifetimeObservations.size()];
        long[] groupLifetimeObservationAdmissionTime = new long[groupLifetimeObservations.size()];
        for (int i = 0; i < groupLifetimeObservations.size(); i++) {
            var obs = groupLifetimeObservations.get(i);
            groupLifetimeObservation[i] = obs.lifetime();
            groupLifetimeObservationEvent[i] = obs.eventObserved() ? 1 : 0;
            groupLifetimeObservationMaxRetryCount[i] = obs.maxRetryCount();
            groupLifetimeObservationMaxDepth[i] = obs.maxBookkeeperDepth();
            groupLifetimeObservationPendingQueue[i] = obs.pendingQueue();
            groupLifetimeObservationBackoff[i] = obs.backoff();
            groupLifetimeObservationAdmissionWait[i] = obs.admissionWait();
            groupLifetimeObservationRunning[i] = obs.running();
            groupLifetimeObservationWaitingRoom[i] = obs.waitingRoom();
            groupLifetimeObservationRunningWasted[i] = obs.runningWasted();
            groupLifetimeObservationAdmissionTime[i] = obs.admissionTime();
        }

        return new ExperimentRuntimeSnapshot(
                healthSnapshot,
                logger.getTotalLockTime(),
                logger.getTotalAborted(),
                logger.getTotalBookkeepers(),
                abortBackoffSize,
                resolveDispatchStats(context.execution()),
                logger.getMaxDepthObserved(),
                logger.getBookkeeperDepthCounts(),
                logger.getGroupsStartedTotal(),
                logger.metrics().getParentsCommittedTotal(),
                logger.getGroupsCompletedTotal(),
                logger.getBookkeepersCreatedTotal(),
                logger.metrics().getAttemptedBookkeepersTotal(),
                logger.metrics().getCollapseSkippedTotal(),
                logger.metrics().getCollapseOfferedTotal(),
                logger.metrics().getVisitedSetDroppedTotal(),
                logger.metrics().meanOfferDirtySetSize(),
                logger.metrics().meanOfferDirtySetSizeExclOwn(),
                logger.metrics().getDirtyKeySpaceSize(),
                logger.metrics().calculateBacklogShape(0),
                logger.metrics().getPartialGroupsTotal(),
                logger.metrics().getPartialBookkeepersCreatedTotal(),
                extractPartialDepthCounts(logger.metrics()),
                logger.getBookkeepersCompletedTotal(),
                logger.getBookkeepersAbortedTotal(),
                logger.getBookkeepersTerminatedTotal(),
                logger.metrics().getPeakPendingQueueDepth(),
                logger.metrics().getPeakBlockedQueueDepth(),
                logger.metrics().getPeakReadyQueueDepth(),
                logger.metrics().getPeakLockQueueDepth(),
                logger.metrics().getPeakIoQueueDepth(),
                logger.metrics().getPeakCpuQueueDepth(),
                logger.metrics().getPeakRollbackQueueDepth(),
                logger.metrics().getPeakWaitingForGroupQueueDepth(),
                logger.metrics().getQueueDepthSampleCount(),
                logger.metrics().getPendingQueueDepthP95(),
                logger.metrics().getBlockedQueueDepthP95(),
                logger.metrics().getLockQueueDepthP95(),
                logger.metrics().getWaitingForGroupQueueDepthP95(),
                logger.metrics().getRollbackQueueDepthP95(),
                logger.metrics().getPeakPendingStateCount(),
                logger.metrics().getPeakRunningStateCount(),
                logger.metrics().getPeakBlockedStateCount(),
                logger.metrics().getPeakReadyStateCount(),
                logger.metrics().getPeakCompletedStateCount(),
                logger.calculateParentTransactionLatencyStats(),
                logger.calculateCprSynchronizationDelayStats(),
                logger.calculateRollbackLatencyStats(),
                logger.calculateCascadeAmplificationStats(),
                logger.getCascadeResourceReferenceSamples(),
                logger.getMeanCascadeParentResourceReferences(),
                logger.getMeanCascadeCprResourceReferences(),
                logger.getStartedGroupParentResourceReferenceSamples(),
                logger.getMeanStartedGroupParentResourceReferences(),
                logger.metrics().getRealizedTopAccessShare(0.01),
                logger.metrics().getRealizedTopAccessShare(0.10),
                logger.metrics().getMeanDistinctInstancesTouched(),
                logger.metrics().getWritesPerTxnMean(),
                logger.metrics().getReadOnlyTxnFractionRealized(),
                logger.metrics().getAdmittedParentsForAccess(),
                logger.metrics().getLockConflictRate(),
                logger.metrics().getLockRequestsTotal(),
                logger.getCompletedGroupResourceReferenceSamples(),
                logger.getCompletedGroupParentResourceReferencesTotal(),
                logger.getCompletedGroupCprResourceReferencesTotal(),
                logger.getCompletedParentResourceReferencesTotal(),
                logger.getCompletedBookkeeperResourceReferencesTotal(),
                dirtySetStats,
                recoveryLatencyStats,
                dirtyAtSimulationEndCount,
                logger.metrics().getFractionTimeBlocked(),
                logger.metrics().getFractionTimeWaitingRoom(),
                logger.metrics().getInSystemTimeWeighted(),
                logger.metrics().getWastedIoTime(),
                logger.metrics().getWastedCpuTime(),
                logger.metrics().getTotalLockHoldMsWeighted(),
                logger.metrics().getWaitingRoomLockHoldMsWeighted(),
                logger.metrics().getTotalLocksAcquiredCount(),
                groupConcurrencyStats[0],
                groupConcurrencyStats[1],
                logger.metrics().getMeanGroupLifetime(),
                logger.metrics().getMeanLiveGroupsSampled(),
                logger.metrics().getPeakLiveGroupsSampled(),
                groupLifetimeObservation,
                groupLifetimeObservationEvent,
                groupLifetimeObservationMaxRetryCount,
                groupLifetimeObservationMaxDepth,
                groupLifetimeObservationPendingQueue,
                groupLifetimeObservationBackoff,
                groupLifetimeObservationAdmissionWait,
                groupLifetimeObservationRunning,
                groupLifetimeObservationWaitingRoom,
                groupLifetimeObservationRunningWasted,
                groupLifetimeObservationAdmissionTime,
                logger.metrics().getPhaseOccupancy(),
                logger.metrics().getPhaseOccupancyWindowTicks(),
                logger.metrics().getPhasePopulationMin(),
                logger.metrics().getPhasePopulationMax(),
                logger.metrics().getReconciliationQueueDepthP95(),
                logger.metrics().getReconciliationQueueDepthSamples(),
                logger.metrics().getWarmupDiscardedS(),
                logger.metrics().getMeasurementWindowS(),
                logger.metrics().getCompletionsInWindow(),
                logger.metrics().getWindowedThroughput(),
                logger.metrics().getWindowedFractionTimeBlocked(),
                logger.metrics().getWindowedFractionTimeWaitingRoom(),
                logger.metrics().getWindowedWastedIoTime(),
                logger.metrics().getWindowedWastedCpuTime(),
                logger.calculateLockWaitStats(),
                logger.metrics().getRestartsTotal(),
                logger.metrics().getSoftDenyReleaseCyclesTotal(),
                upgradesGranted(context),
                upgradesDenied(context),
                logger.metrics().getCursorProgressAtAbortBuckets(),
                logger.metrics().getExecutedOperationsTotal(),
                logger.metrics().getCompletedIoTime(),
                logger.metrics().getCompletedCpuTime(),
                logger.metrics().getLockHoldRecordingCount(),
                logger.metrics().getMeanAcquisitionSpan(),
                logger.metrics().getAcquisitionSpanCount(),
                logger.metrics().getThroughputTraceVirtualTime(),
                logger.metrics().getThroughputTraceCumulativeCompletions(),
                logger.metrics().getThroughputTraceBlockedQueueDepth(),
                logger.metrics().getThroughputTraceInSystemCount(),
                logger.metrics().getRestartRetryCountSamples(),
                logger.metrics().getAdmissionAttemptVirtualTime(),
                logger.metrics().getAdmissionAttemptK(),
                logger.metrics().getAdmissionAttemptNRunning(),
                logger.metrics().getAdmissionAttemptHeldLockUnits(),
                logger.metrics().getAdmissionAttemptOutcome(),
                logger.metrics().getAdmissionAttemptHistogramHeldLockUnits(),
                logger.metrics().getAdmissionAttemptHistogramProvenance(),
                logger.metrics().getAdmissionAttemptHistogramOutcome(),
                logger.metrics().getAdmissionAttemptHistogramCount(),
                logger.metrics().getMeanRunningStateCountSampled(),
                logger.metrics().getAdmissionDelay(),
                logger.metrics().getMeanPendingStateCountSampled(),
                logger.metrics().getMeanBlockedStateCountSampled(),
                logger.metrics().getMeanReadyStateCountSampled(),
                logger.metrics().getMeanAwaitingUserActionStateCountSampled(),
                logger.metrics().getPeakAwaitingUserActionStateCount(),
                logger.metrics().getMeanAdmissionWaiterCountSampled(),
                logger.metrics().getPeakAdmissionWaiterCount(),
                logger.metrics().getTotalAdmissionAttemptsCount(),
                logger.metrics().getTotalBlockedAtCheckCount(),
                logger.metrics().getMeanLocksHeldCountSampled(),
                logger.metrics().getPeakLocksHeldCount(),
                // Harvested HERE, at snapshot capture, because TransactionAggregateMetrics is a
                // STATIC sink that SeedSweepRunner clears in a finally block after every run. Read
                // it from the caller afterwards and it is always zero - which is exactly what
                // happened on the first attempt, caught only because the expected count (~570) was
                // written down before the run rather than interpreted after it.
                TransactionAggregateMetrics.getBackoffIntervalCount(),
                TransactionAggregateMetrics.getBackoffIntervalTotal(),
                logger.metrics().getWindowedAbortedParentAttempts(),
                logger.metrics().getWindowedAbortedCuAttempts(),
                logger.metrics().getWindowedDiscardedParentService(),
                logger.metrics().getWindowedDiscardedCuService(),
                logger.metrics().getWindowedDiscardedParentOpsReached(),
                logger.metrics().getWindowedDiscardedCuOpsReached(),
                logger.metrics().getAbortedParentAttemptsTotal(),
                logger.metrics().getAbortedCuAttemptsTotal(),
                logger.metrics().getMeanCusInFlight(),
                logger.metrics().getPeakCusInFlight(),
                logger.metrics().getCusInFlightNegativeSamples());
    }

    private static DispatchStats resolveDispatchStats(ExecutionBundle executionBundle) {
        if (executionBundle.dispatcher() instanceof AbstractAdmissionController intake) {
            return intake.getDispatchStats();
        }
        if (executionBundle.dispatcher() instanceof PhaseDispatcherGroup group) {
            AbstractAdmissionController intake = group.getDispatcher(AbstractAdmissionController.class);
            return intake != null ? intake.getDispatchStats() : null;
        }
        return null;
    }

    private static int[] extractPartialDepthCounts(SimulationMetrics metrics) {
        int[] counts = new int[10];
        for (int i = 0; i < 10; i++) {
            counts[i] = metrics.partialDepthCountAt(i);
        }
        return counts;
    }

    /**
     * Upgrade counters live on the lock manager, which production always wraps in
     * {@code BudgetedLockManagerDecorator} - so a direct instanceof on the LockManager reference
     * silently and always misses. {@code SS2PLLockManager.unwrap} exists for exactly this.
     */
    private static long upgradesGranted(SimulatorContext context) {
        SS2PLLockManager lm = SS2PLLockManager.unwrap(context.locking().lockManager());
        return lm == null ? 0L : lm.getUpgradesGranted();
    }

    private static long upgradesDenied(SimulatorContext context) {
        SS2PLLockManager lm = SS2PLLockManager.unwrap(context.locking().lockManager());
        return lm == null ? 0L : lm.getUpgradesDenied();
    }
}
