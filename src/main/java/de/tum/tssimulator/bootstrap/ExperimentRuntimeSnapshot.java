package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.monitoring.DispatchStats;
import de.tum.tssimulator.monitoring.RuntimeHealthSnapshot;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMetrics;

/**
 * Read-only experiment-facing snapshot of runtime state and exported metrics.
 */
public record ExperimentRuntimeSnapshot(
        RuntimeHealthSnapshot healthSnapshot,
        long totalLockTime,
        // MISLEADINGLY NAMED, and kept that way because existing experiments read it - see
        // abortsPerCompletion() below. Despite the name this is wired from
        // SimulationMetrics.getTotalAborted(), a MEMBER-level counter (parent AND every
        // bookkeeper individually via TransactionLifecycleManager.abort()), NOT one increment per
        // aborted GROUP; it measures ~4x the genuinely group-level restartsTotal(). For a
        // group-level abort count, use restartsTotal().
        int totalAbortedGroups,
        int totalBookkeepers,
        int abortBackoffQueueSize,
        DispatchStats dispatchStats,
        int maxDepthObserved,
        int[] bookkeeperDepthCounts,
        int groupsStartedTotal,
        // Parents COMMITTED - distinct from groupsCompleted under BASE. The pool manager keys
        // on this so pending drains do not occupy the MPL. Coincides under ACID.
        int parentsCommittedTotal,
        int groupsCompletedTotal,
        int bookkeepersCreatedTotal,
        int attemptedBookkeepersTotal,
        // V3 collapsed_fraction = collapseSkippedTotal / collapseOfferedTotal. NOT derivable from
        // attemptedBookkeepersTotal above, which is counted before the visited-set filter and so
        // includes cascade pruning - a mechanism PostgreSQL has no counterpart for. F8 shares it.
        int collapseSkippedTotal,
        int collapseOfferedTotal,
        // Cascade pruning, counted separately. V3 asserts this is 0 at its design point.
        int visitedSetDroppedTotal,
        // Mean dirty-set size AS OFFERS SEE IT, distinct from the time-sampled mean. Collapse is
        // decided at offer instants, and offers are parent commits - the same events that create
        // the dirtiness - so this is NOT the time average and at V3's cell differs by ~13x.
        double meanOfferDirtySetSize,
        // The same, with the offering parent's own burst removed - siblings are distinct keys and
        // cannot collide, so the plain figure over-counts (4.87 at W=1 against 0.0000 collapse).
        double meanOfferDirtySetSizeExclOwn,
        // K, the reachable dirty-key space. V3 gates W*f*k against it: V2's (x2) pigeonhole rule.
        int dirtyKeySpaceSize,
        // V3: backlog DISTRIBUTION shape - a reported diagnostic, NOT the stability verdict.
        // Stability is rho = lambda*S/N, computed from the configuration. See BacklogShape.
        SimulationMetrics.BacklogShape backlogShape,
        int partialGroupsTotal,
        int partialBookkeepersCreatedTotal,
        int[] partialBookkeeperDepthCounts,
        int bookkeepersCompletedTotal,
        int bookkeepersAbortedTotal,
        int bookkeepersTerminatedTotal,
        int peakPendingQueueDepth,
        int peakBlockedQueueDepth,
        int peakReadyQueueDepth,
        int peakLockQueueDepth,
        int peakIoQueueDepth,
        int peakCpuQueueDepth,
        int peakRollbackQueueDepth,
        int peakWaitingForGroupQueueDepth,
        int queueDepthSampleCount,
        double p95PendingQueueDepth,
        double p95BlockedQueueDepth,
        double p95LockQueueDepth,
        double p95WaitingForGroupQueueDepth,
        double p95RollbackQueueDepth,
        int peakPendingStateCount,
        int peakRunningStateCount,
        int peakBlockedStateCount,
        int peakReadyStateCount,
        int peakCompletedStateCount,
        SimulationLogger.DistributionStats parentTransactionLatencyStats,
        SimulationLogger.DistributionStats cprSynchronizationDelayStats,
        SimulationLogger.DistributionStats rollbackLatencyStats,
        SimulationLogger.DistributionStats cascadeAmplificationStats,
        int cascadeResourceReferenceSamples,
        double meanCascadeParentResourceReferences,
        double meanCascadeCprResourceReferences,
        int startedGroupParentResourceReferenceSamples,
        double meanStartedGroupParentResourceReferences,
        // ---- W1-W4 realised workload ----
        /** Share of admitted accesses on the hottest 1% of touched instances. The independent
         *  variable for any theta regression - nominal theta is only the setting that produced it,
         *  because drawing k DISTINCT resources truncates the head of the Zipf. */
        double realizedTop1pctAccessShare,
        /** Same at 10%, so the shape is visible rather than a single point. */
        double realizedTop10pctAccessShare,
        /** F4's saturation check: read against k. */
        double meanDistinctInstancesTouched,
        /** Realised ceil(n/2) under TWO_CLASS. */
        double writesPerTxnMean,
        /** Realised counterpart to nominal readOnlyTransactionFraction. */
        double readOnlyTxnFractionRealized,
        /** nonzero proves the counter is WIRED, distinguishing a real zero from an absent one. */
        long admittedParentsForAccess,
        /** Conflicts over lock requests - the contention CALIBRATION target. A ratio of two
         *  large counts, so it resolves at n=5 where frac_blocked (a time-weighted occupancy
         *  fraction over a small population, sd 0.07-0.19 against a 0.20-wide band) does not. */
        double lockConflictRate,
        /** nonzero proves the conflict counter is wired. */
        long lockRequestsTotal,
        int completedGroupResourceReferenceSamples,
        long completedGroupParentResourceReferencesTotal,
        long completedGroupCprResourceReferencesTotal,
        long completedParentResourceReferencesTotal,
        long completedBookkeeperResourceReferencesTotal,
        SimulationLogger.DirtySetStats dirtySetStats,
        SimulationLogger.RecoveryLatencyStats recoveryLatencyStats,
        int dirtyAtSimulationEndCount,
        // Raw accumulators for the 5 new transaction-state metrics (E1-E4 experiment design).
        // Kept raw rather than pre-computed fractions so downstream experiments/plots stay
        // flexible about how they combine them - see SimulationMetrics for the guarded
        // fraction getters these mirror. fractionTimeBlocked/fractionTimeWaitingRoom are
        // MEMBER-time fractions (parents AND bookkeepers), not transaction-time - see
        // SimulationMetrics.getFractionTimeBlocked()'s javadoc.
        double fractionTimeBlocked,
        double fractionTimeWaitingRoom,
        double inSystemTimeWeighted,
        double wastedIoTime,
        double wastedCpuTime,
        double totalLockHoldMsWeighted,
        double waitingRoomLockHoldMsWeighted,
        // plain (unweighted) sum of lockCountHeld across every recordLockHold()
        // call - now meaningful for bookkeepers too since the F1 fix in
        // ACIDAdmissionController.processBookkeeperWithBlockingSemantics(). Use
        // meanLockHoldDurationPerLock() below rather than dividing totalLockHoldMsWeighted by
        // this directly, though that's exactly what it does.
        double totalLocksAcquiredCount,
        // group-concurrency follow-up: DIRECT measurements from real per-group
        // [admissionTime, completionTime] timestamps (SimulationMetrics.
        // computeGroupConcurrencyStats()/getMeanGroupLifetime()) - NOT inferred from
        // throughput via Little's Law, which is an identity and can't distinguish "groups
        // overlap and both throughput and lifetime scale with load" from "something serializes
        // group admission". Completed groups only - see SimulationMetrics'
        // groupLifetimeIntervals field javadoc for the abort-path blind spot.
        double meanConcurrentGroups,
        double peakConcurrentGroups,
        double meanGroupLifetime,
        // meanConcurrentGroups/peakConcurrentGroups above are
        // throughput*lifetime by construction (both scoped to completed groups only) - circular
        // for "is there a ceiling" questions, and blind to groups that abort/restart. These two
        // are the TRUE, independently-sampled population count (SimulationMetrics.
        // getMeanLiveGroupsSampled()/getPeakLiveGroupsSampled()) - use these, not the pair
        // above, for concurrency-ceiling questions.
        double meanLiveGroupsSampled,
        int peakLiveGroupsSampled,
        // Kaplan-Meier lifetime, right-censored: one entry per admitted group -
        // groupLifetimeObservation is the observed span (exact if
        // groupLifetimeObservationEvent[i]==1/completed, a lower bound if ==0/still-open-at-
        // run-end), groupLifetimeObservationMaxRetryCount is that specific transaction's
        // getLockRetryCount() at observation time (NOT a per-group accumulator - TransactionGroup
        // is not stable across restarts, see SimulationMetrics.GroupLifetimeObservation's javadoc
        // and ACIDCompletionHandler.recordGroupImpact() for the correction history).
        long[] groupLifetimeObservation,
        int[] groupLifetimeObservationEvent,
        int[] groupLifetimeObservationMaxRetryCount,
        // Exact per-group deepest bookkeeper depth at observation time (TransactionGroup.
        // getMaxBookkeeperDepth(), not the lossy depth>=8-bucketed bookkeeperDepthCounts
        // histogram) - parallel array to the three above, one entry per group observation.
        int[] groupLifetimeObservationMaxDepth,
        // Four-way lineage time decomposition: pendingQueue + backoff + admissionWait +
        // running sum to groupLifetimeObservation[i] exactly - a closed accounting (see
        // Transaction.LineagePhase's javadoc), not four independent estimates. Parallel arrays,
        // one entry per group observation, same indexing as the fields above.
        long[] groupLifetimeObservationPendingQueue,
        long[] groupLifetimeObservationBackoff,
        long[] groupLifetimeObservationAdmissionWait,
        long[] groupLifetimeObservationRunning,
        // The fifth bucket: a cascading parent's wait for its own bookkeepers. Without it the
        // other four fall short of lifetime by 17.3% at theta=0.80/f=0.80 and by exactly zero on
        // the control - see SimulationMetrics.GroupLifetimeObservation.
        long[] groupLifetimeObservationWaitingRoom,
        // The committing attempt's own execution is running - runningWasted. Split of RUNNING,
        // not a sixth interval: see Transaction.cumulativeRunningWasted.
        long[] groupLifetimeObservationRunningWasted,
        // ABSOLUTE virtual time of the lineage's first admission. The observation otherwise keeps
        // only completionTime - admissionTime, so there is nothing to filter a warm-up-era group
        // on: groupLifetimeObservations is NOT windowed (SimulationMetrics.markWarmupBoundary
        // deliberately skips the per-sample arrays, none of which carried a virtual-time tag).
        // A transaction born during warm-up carries warm-up queueing into its lineage, so a
        // breakdown that pools it is reporting a transient as steady state.
        long[] groupLifetimeObservationAdmissionTime,
        // TIME-WEIGHTED phase occupancy over the post-warm-up window, indexed by
        // LineagePhase.ordinal(). This - not the per-lineage arrays above - is what the
        // breakdown figure plots: see Transaction.PHASE_POPULATION.
        double[] phaseOccupancy,
        long phaseOccupancyWindowTicks,
        int phasePopulationMin,
        int phasePopulationMax,
        double reconciliationQueueDepthP95,
        int[] reconciliationQueueDepthSamples,
        // F4 (VLDB reviewer response): warm-up/steady-state windowing. See
        // SimulationMetrics.markWarmupBoundary()'s javadoc for what is and isn't windowed.
        double warmupDiscardedS,
        double measurementWindowS,
        int completionsInWindow,
        double windowedThroughput,
        double windowedFractionTimeBlocked,
        double windowedFractionTimeWaitingRoom,
        double windowedWastedIoTime,
        double windowedWastedCpuTime,
        // F5 (VLDB reviewer response): lock-WAIT time distribution (distinct from the
        // lock-HOLD tracking above) and the restart counter.
        SimulationLogger.DistributionStats lockWaitStats,
        int restartsTotal,
        // Operation-level SS2PL instrumentation. All four were already being recorded and none was
        // reachable from an experiment, which is how a metric can read zero for an entire arm
        // without anyone noticing.
        /** C2PL soft-deny release-and-retry cycles: release everything, block, re-acquire from
         * scratch. Distinct from restartsTotal, which counts full group aborts only. */
        long softDenyReleaseCyclesTotal,
        /** SHARED -> EXCLUSIVE mode changes granted. */
        long upgradesGranted,
        /** Upgrades denied because co-readers made them impossible - contention that only
         * per-operation modes can produce. */
        long upgradesDenied,
        /** How far through its operations each aborting transaction got, in tenths. A spike in one
         * bucket means no transaction ever aborted mid-cursor. */
        long[] cursorProgressAtAbortBuckets,
        /** Operations that actually cleared IO and CPU on transactions that completed. Separate from
         * the resource-reference counters, which record what a transaction DECLARED it would touch -
         * a dropped batch leaves those intact and only shows up here. */
        long executedOperationsTotal,
        /** Service demand charged to completions. With executedOperationsTotal these give demand per
         * completion, the quantity that must stay flat across batch sizes for criterion 3's ratio to
         * be interpretable: inflated demand lengthens spans, which is the ratio's denominator. */
        long completedIoTime,
        long completedCpuTime,
        /** How many recordLockHold() calls produced totalLockHoldMsWeighted. Asserted separately from
         * the durations because "k batches emitted k records" and "the durations are right" fail for
         * different reasons and an aggregate cannot tell them apart. */
        long lockHoldRecordingCount,
        /** Mean acquisition-to-release span - criterion 3's correct denominator. See
         * SimulationMetrics.recordAcquisitionSpan for why submission-to-completion latency is not. */
        double meanAcquisitionSpan,
        long acquisitionSpanCount,
        // E-A follow-up: opt-in raw throughput trace - see
        // SimulationMetrics.throughputTraceEnabled's javadoc. Empty arrays unless the run set
        // -Dts.metrics.throughputTrace=true.
        long[] throughputTraceVirtualTime,
        int[] throughputTraceCumulativeCompletions,
        // high-rate signals (blocked queue depth, in-system count) alongside the
        // completions trace - a completions series alone can't distinguish "sparse but
        // stationary" from "genuinely non-stationary," see SimulationMetrics' javadoc on these.
        int[] throughputTraceBlockedQueueDepth,
        int[] throughputTraceInSystemCount,
        // per-restart lockRetryCount samples - see SimulationMetrics'
        // recordRestartRetryCount() javadoc.
        int[] restartRetryCountSamples,
        // TGS static-locking validation: opt-in per-admission-attempt trace - see
        // SimulationMetrics.recordAdmissionAttempt()'s javadoc. Empty arrays unless the run set
        // -Dts.metrics.admissionAttemptTrace=true. First four + outcome are first-attempt-only
        // (attemptIndex == 0) parallel arrays; the four histogram* arrays are the flattened
        // all-attempts (heldLockUnits, provenance, outcome) -> count histogram.
        long[] admissionAttemptVirtualTime,
        int[] admissionAttemptK,
        int[] admissionAttemptNRunning,
        int[] admissionAttemptHeldLockUnits,
        String[] admissionAttemptOutcome,
        int[] admissionAttemptHistogramHeldLockUnits,
        String[] admissionAttemptHistogramProvenance,
        String[] admissionAttemptHistogramOutcome,
        int[] admissionAttemptHistogramCount,
        // TGS static-locking validation: unbiased (independently, time-sampled - NOT attempt-time)
        // companion to peakRunningStateCount above - see SimulationMetrics.
        // getMeanRunningStateCountSampled()'s javadoc for why attempt-time n_running is the wrong
        // signal for "where does achieved concurrency sit."
        double meanRunningStateCountSampled,
        // First-attempt-to-admitted elapsed virtual time per admission - see SimulationMetrics.
        // recordAdmissionDelay()'s javadoc (the direct test of whether blocking costs any virtual
        // time in this engine). Empty unless -Dts.metrics.admissionAttemptTrace=true.
        long[] admissionDelay,
        // TGS static-locking validation: "where are the other 255?" - full FSM state histogram,
        // mean (time-weighted, always on) siblings of meanRunningStateCountSampled above, plus
        // the one peak*StateCount that was missing from this record (peakAwaitingUserActionStateCount -
        // every other peak*StateCount was already here). See SimulationMetrics.
        // pendingStateTimeWeighted's field javadoc for what PENDING vs READY distinguishes.
        double meanPendingStateCountSampled,
        double meanBlockedStateCountSampled,
        double meanReadyStateCountSampled,
        double meanAwaitingUserActionStateCountSampled,
        int peakAwaitingUserActionStateCount,
        // sampled AdmissionWaitManager waiter-count (mean, time-
        // weighted at recordHealthSnapshot cadence, + peak) - see SimulationMetrics.
        // getMeanAdmissionWaiterCountSampled()'s javadoc. This is the population thrashing
        // thresholds are actually a fraction of; blockedStateCountSampled/blockedQueueDepth are
        // blind to it.
        double meanAdmissionWaiterCountSampled,
        int peakAdmissionWaiterCount,
        // unconditional whole-run cumulative admission-outcome
        // counters - see SimulationMetrics.getTotalAdmissionAttemptsCount()/
        // getTotalBlockedAtCheckCount()'s javadoc. totalBlockedAtCheckCount / totalAdmissionAttemptsCount
        // is the genuine per-attempt blocked rate, available without -Dts.metrics.admissionAttemptTrace=true.
        long totalAdmissionAttemptsCount,
        long totalBlockedAtCheckCount,
        // TGS Postgres-anchor comparison: sampled H (locks-held count) - see
        // SimulationMetrics.getMeanLocksHeldCountSampled()'s javadoc. n = this / k is the direct,
        // measured (not derived) achieved-concurrency quantity comparable to the Postgres arm's
        // pg_locks poll.
        double meanLocksHeldCountSampled,
        int peakLocksHeldCount,
        /** BACKOFF intervals observed AT CLOSE, over ALL lineages - completed, aborted and
         * still-open alike. Count is separate from the total because "were all the intervals seen"
         * and "were they the right length" fail for different reasons. Do NOT divide the total by a
         * count of COMPLETED lineages: that is a censored population and mixing the two produced two
         * withdrawn conclusions. See TransactionAggregateMetrics.recordBackoffInterval for why this
         * cannot be recorded at completion or at abort. */
        long backoffIntervalCount,
        long backoffIntervalTotal,
        // DISCARDED SERVICE AT ABORT, windowed like completionsInWindow. See
        // SimulationMetrics.recordDiscardedServiceAtAbort() for why restarts/completion and
        // wasted_ticks cannot answer the question these measure.
        long windowedAbortedParentAttempts,
        long windowedAbortedCuAttempts,
        double windowedDiscardedParentService,
        double windowedDiscardedCuService,
        long windowedDiscardedParentOpsReached,
        long windowedDiscardedCuOpsReached,
        // UNWINDOWED, for the coverage assertion only - must equal groupsAbortedTotal.
        long abortedParentAttemptsTotal,
        long abortedCuAttemptsTotal,
        // CUs in flight, time-weighted over the post-warm-up window (see SimulationMetrics).
        double meanCusInFlight,
        int peakCusInFlight,
        long cusInFlightNegativeSamples) {

    public ExperimentRuntimeSnapshot {
        bookkeeperDepthCounts = bookkeeperDepthCounts == null ? new int[0] : bookkeeperDepthCounts.clone();
        partialBookkeeperDepthCounts = partialBookkeeperDepthCounts == null ? new int[0] : partialBookkeeperDepthCounts.clone();
        reconciliationQueueDepthSamples = reconciliationQueueDepthSamples == null
                ? new int[0] : reconciliationQueueDepthSamples.clone();
        throughputTraceVirtualTime = throughputTraceVirtualTime == null
                ? new long[0] : throughputTraceVirtualTime.clone();
        throughputTraceCumulativeCompletions = throughputTraceCumulativeCompletions == null
                ? new int[0] : throughputTraceCumulativeCompletions.clone();
        throughputTraceBlockedQueueDepth = throughputTraceBlockedQueueDepth == null
                ? new int[0] : throughputTraceBlockedQueueDepth.clone();
        throughputTraceInSystemCount = throughputTraceInSystemCount == null
                ? new int[0] : throughputTraceInSystemCount.clone();
        restartRetryCountSamples = restartRetryCountSamples == null
                ? new int[0] : restartRetryCountSamples.clone();
        groupLifetimeObservation = groupLifetimeObservation == null
                ? new long[0] : groupLifetimeObservation.clone();
        groupLifetimeObservationEvent = groupLifetimeObservationEvent == null
                ? new int[0] : groupLifetimeObservationEvent.clone();
        groupLifetimeObservationMaxRetryCount = groupLifetimeObservationMaxRetryCount == null
                ? new int[0] : groupLifetimeObservationMaxRetryCount.clone();
        groupLifetimeObservationMaxDepth = groupLifetimeObservationMaxDepth == null
                ? new int[0] : groupLifetimeObservationMaxDepth.clone();
        groupLifetimeObservationPendingQueue = groupLifetimeObservationPendingQueue == null
                ? new long[0] : groupLifetimeObservationPendingQueue.clone();
        groupLifetimeObservationBackoff = groupLifetimeObservationBackoff == null
                ? new long[0] : groupLifetimeObservationBackoff.clone();
        groupLifetimeObservationAdmissionWait = groupLifetimeObservationAdmissionWait == null
                ? new long[0] : groupLifetimeObservationAdmissionWait.clone();
        groupLifetimeObservationRunning = groupLifetimeObservationRunning == null
                ? new long[0] : groupLifetimeObservationRunning.clone();
        groupLifetimeObservationWaitingRoom = groupLifetimeObservationWaitingRoom == null
                ? new long[0] : groupLifetimeObservationWaitingRoom.clone();
        groupLifetimeObservationRunningWasted = groupLifetimeObservationRunningWasted == null
                ? new long[0] : groupLifetimeObservationRunningWasted.clone();
        groupLifetimeObservationAdmissionTime = groupLifetimeObservationAdmissionTime == null
                ? new long[0] : groupLifetimeObservationAdmissionTime.clone();
        phaseOccupancy = phaseOccupancy == null ? new double[0] : phaseOccupancy.clone();
        admissionAttemptVirtualTime = admissionAttemptVirtualTime == null
                ? new long[0] : admissionAttemptVirtualTime.clone();
        admissionAttemptK = admissionAttemptK == null ? new int[0] : admissionAttemptK.clone();
        admissionAttemptNRunning = admissionAttemptNRunning == null ? new int[0] : admissionAttemptNRunning.clone();
        admissionAttemptHeldLockUnits = admissionAttemptHeldLockUnits == null
                ? new int[0] : admissionAttemptHeldLockUnits.clone();
        admissionAttemptOutcome = admissionAttemptOutcome == null ? new String[0] : admissionAttemptOutcome.clone();
        admissionAttemptHistogramHeldLockUnits = admissionAttemptHistogramHeldLockUnits == null
                ? new int[0] : admissionAttemptHistogramHeldLockUnits.clone();
        admissionAttemptHistogramProvenance = admissionAttemptHistogramProvenance == null
                ? new String[0] : admissionAttemptHistogramProvenance.clone();
        admissionAttemptHistogramOutcome = admissionAttemptHistogramOutcome == null
                ? new String[0] : admissionAttemptHistogramOutcome.clone();
        admissionAttemptHistogramCount = admissionAttemptHistogramCount == null
                ? new int[0] : admissionAttemptHistogramCount.clone();
        admissionDelay = admissionDelay == null ? new long[0] : admissionDelay.clone();
    }

    @Override
    public int[] bookkeeperDepthCounts() {
        return bookkeeperDepthCounts.clone();
    }

    @Override
    public int[] reconciliationQueueDepthSamples() {
        return reconciliationQueueDepthSamples.clone();
    }

    @Override
    public long[] throughputTraceVirtualTime() {
        return throughputTraceVirtualTime.clone();
    }

    @Override
    public int[] throughputTraceCumulativeCompletions() {
        return throughputTraceCumulativeCompletions.clone();
    }

    @Override
    public int[] throughputTraceBlockedQueueDepth() {
        return throughputTraceBlockedQueueDepth.clone();
    }

    @Override
    public int[] throughputTraceInSystemCount() {
        return throughputTraceInSystemCount.clone();
    }

    @Override
    public int[] restartRetryCountSamples() {
        return restartRetryCountSamples.clone();
    }

    @Override
    public long[] groupLifetimeObservation() {
        return groupLifetimeObservation.clone();
    }

    @Override
    public int[] groupLifetimeObservationEvent() {
        return groupLifetimeObservationEvent.clone();
    }

    @Override
    public int[] groupLifetimeObservationMaxRetryCount() {
        return groupLifetimeObservationMaxRetryCount.clone();
    }

    @Override
    public int[] groupLifetimeObservationMaxDepth() {
        return groupLifetimeObservationMaxDepth.clone();
    }

    @Override
    public long[] groupLifetimeObservationPendingQueue() {
        return groupLifetimeObservationPendingQueue.clone();
    }

    @Override
    public long[] groupLifetimeObservationBackoff() {
        return groupLifetimeObservationBackoff.clone();
    }

    @Override
    public long[] groupLifetimeObservationAdmissionWait() {
        return groupLifetimeObservationAdmissionWait.clone();
    }

    @Override
    public long[] groupLifetimeObservationRunning() {
        return groupLifetimeObservationRunning.clone();
    }

    @Override
    public long[] groupLifetimeObservationWaitingRoom() {
        return groupLifetimeObservationWaitingRoom.clone();
    }

    @Override
    public long[] groupLifetimeObservationRunningWasted() {
        return groupLifetimeObservationRunningWasted.clone();
    }

    @Override
    public long[] groupLifetimeObservationAdmissionTime() {
        return groupLifetimeObservationAdmissionTime.clone();
    }

    @Override
    public double[] phaseOccupancy() {
        return phaseOccupancy.clone();
    }

    @Override
    public long[] admissionAttemptVirtualTime() {
        return admissionAttemptVirtualTime.clone();
    }

    @Override
    public int[] admissionAttemptK() {
        return admissionAttemptK.clone();
    }

    @Override
    public int[] admissionAttemptNRunning() {
        return admissionAttemptNRunning.clone();
    }

    @Override
    public int[] admissionAttemptHeldLockUnits() {
        return admissionAttemptHeldLockUnits.clone();
    }

    @Override
    public String[] admissionAttemptOutcome() {
        return admissionAttemptOutcome.clone();
    }

    @Override
    public int[] admissionAttemptHistogramHeldLockUnits() {
        return admissionAttemptHistogramHeldLockUnits.clone();
    }

    @Override
    public String[] admissionAttemptHistogramProvenance() {
        return admissionAttemptHistogramProvenance.clone();
    }

    @Override
    public String[] admissionAttemptHistogramOutcome() {
        return admissionAttemptHistogramOutcome.clone();
    }

    @Override
    public int[] admissionAttemptHistogramCount() {
        return admissionAttemptHistogramCount.clone();
    }

    @Override
    public long[] admissionDelay() {
        return admissionDelay.clone();
    }

    /** aborts_per_completion, computed from the MEMBER-level totalAbortedGroups() field - which
     * is {@code getTotalAborted()}, not {@code getGroupsAbortedTotal()}.
     *
     * <p>It therefore scales with average group fan-out (K) and not with abort frequency alone, so
     * it is NOT a clean contention signal and must not be compared across configs with different
     * f/mu without accounting for that. Kept as-is because existing experiments
     * (AcidMplSweepExperiment et al.) depend on this exact behaviour.
     *
     * <p>For a genuinely group-level aborts-per-completion - one increment per aborting GROUP,
     * which is what the name sounds like - compute restartsTotal() / groupsCompletedTotal() at the
     * call site, as RawRunRow's abortsPerCompletion column does (see
     * SeedSweepRunner.toRawRunRow()). */
    public double abortsPerCompletion() {
        return groupsCompletedTotal() == 0 ? 0.0 : totalAbortedGroups() / (double) groupsCompletedTotal();
    }

    /** wasted_cpu_io_seconds share of (numIoProcessors + numCpuProcessors) * durationSeconds -
     * the caller supplies the processor-time denominator since it's not part of this snapshot. */
    public double wastedCpuIoFraction(int numIoProcessors, int numCpuProcessors, int simulationDurationSeconds) {
        double denominator = (numIoProcessors + numCpuProcessors) * simulationDurationSeconds * 1_000_000.0;
        return denominator <= 0.0 ? 0.0 : (wastedIoTime + wastedCpuTime) / denominator;
    }

    /** F4: windowed counterpart to wastedCpuIoFraction(), using the post-warmup measurement
     * window (measurementWindowS) as the time base instead of the full simulationDurationSeconds. */
    public double windowedWastedCpuIoFraction(int numIoProcessors, int numCpuProcessors) {
        double denominator = (numIoProcessors + numCpuProcessors) * measurementWindowS() * 1_000_000.0;
        return denominator <= 0.0 ? 0.0 : (windowedWastedIoTime() + windowedWastedCpuTime()) / denominator;
    }

    /** lock_seconds_waiting_room = waitingRoomLockHoldMsWeighted / totalLockHoldMsWeighted,
     * guarded (0 for single-member groups / no bookkeepers, i.e. totalLockHoldMsWeighted == 0). */
    public double lockSecondsWaitingRoomFraction() {
        return totalLockHoldMsWeighted() <= 0.0 ? 0.0 : waitingRoomLockHoldMsWeighted() / totalLockHoldMsWeighted();
    }

    /** Mirror of SimulationMetrics.getMeanLockHoldDurationPerLock() - weighted mean hold
     * duration per distinct lock, comparable across configs at matched footprint. */
    public double meanLockHoldDurationPerLock() {
        return totalLocksAcquiredCount() <= 0.0 ? 0.0 : totalLockHoldMsWeighted() / totalLocksAcquiredCount();
    }

    public int depthCountAt(int depth) {
        return depth >= 0 && depth < bookkeeperDepthCounts.length ? bookkeeperDepthCounts[depth] : 0;
    }

    /** Return count of bookkeepers at given depth for partial groups. */
    public int partialDepthCountAt(int depth) {
        return depth >= 0 && depth < partialBookkeeperDepthCounts.length ? partialBookkeeperDepthCounts[depth] : 0;
    }

    public int depth5PlusCount() {
        int total = 0;
        for (int i = 5; i < bookkeeperDepthCounts.length; i++) {
            total += bookkeeperDepthCounts[i];
        }
        return total;
    }
}
