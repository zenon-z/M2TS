package de.tum.tssimulator.monitoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionAggregateMetrics;
import de.tum.tssimulator.core.TransactionGroup;

public class SimulationMetrics {
    private int totalExecuted = 0;
    private int totalBookkeepers = 0;
    private int totalAborted = 0;
    private int totalAwaitingUserAction = 0;
    private long totalLockTime = 0;
    private long completedIoTime = 0;
    private long completedCpuTime = 0;
    private long executedOperationsTotal = 0;
    private long acquisitionSpanMsTotal = 0;
    private long acquisitionSpanCount = 0;
    private long latestVirtualTime = 0;

    private final int[] dirtySetSizeSamples;
    private int sampleWriteIndex = 0;
    private final int totalResources;
    private final int dirtySetDenominatorResources;

    private int groupsStartedTotal = 0;
    private int groupsCompletedTotal = 0;
    private int groupsAbortedTotal = 0;

    // VLDB reviewer response, group-concurrency follow-up: per-group
    // [admissionTime, completionTime] intervals, one per group that COMPLETES (not aborted -
    // ACIDCompletionHandler.recordGroupImpact() is the only call site, on the success path only,
    // so this is a known blind spot on high-abort configs; see recordGroupLifetime()'s javadoc).
    // Exists to answer, with a DIRECT measurement rather than an inference from Little's Law
    // (L=throughput*W is an identity and can't distinguish "groups overlap and both throughput
    // and lifetime scale with load" from "something serializes group admission" - see the
    // discussion that motivated this), whether groups genuinely run concurrently and whether
    // mean group lifetime scales with MPL.
    private final List<long[]> groupLifetimeIntervals = new ArrayList<>();

    /** Kaplan-Meier lifetime, right-censored: one observation per admitted
     * group, whether it completed within the run (eventObserved=true, lifetime is exact) or
     * was still open when the run ended (eventObserved=false, lifetime is a LOWER bound - the
     * group's true lifetime is >= this). Distinct from groupLifetimeIntervals above (completed-
     * only, no censoring, no retryCount) - that field stays as-is for computeGroupConcurrencyStats(),
     * which doesn't need this. maxRetryCount is the group's own TransactionGroup.
     * getMaxRetryCountObserved() at the time of observation - the starvation/livelock cross-tab
     * signal (see TransactionGroupAbortCoordinator.restartTransaction()). */
    // FIVE-way lineage time decomposition: pendingQueue + backoff + admissionWait + running +
    // waitingRoom sum to exactly lifetime by construction (Transaction.LineagePhase accounting -
    // see its javadoc) - a closed accounting with no inference step, not independently-derived
    // estimates.
    //
    // waitingRoom was accumulated by Transaction and NOT exported here, so this record was FOUR
    // buckets against a five-bucket decomposition and the sum fell short of lifetime by exactly
    // the parent's wait for its own cascade. Measured at theta=0.80 f=0.80 before the fix:
    // pend 13 + back 570,994 + adm 161,875 + run 46,718 = 779,600 against a lifetime of 942,712,
    // leaving 163,113 - 17.3% - unattributed. On the control the shortfall is structurally zero
    // (one member per group, so no waiting room exists), which is what identifies the missing
    // term rather than merely showing that one is missing.
    //
    // This mattered because it is the CPR mechanism's own segment: a stacked per-transaction
    // breakdown built on the four buckets would have silently dropped the largest CPR-specific
    // cost, or - worse - recovered it as a residual, which is the shape that cannot fail.
    public record GroupLifetimeObservation(long lifetime, boolean eventObserved, int maxRetryCount,
            int maxBookkeeperDepth, long pendingQueue, long backoff, long admissionWait,
            long running, long waitingRoom, long runningWasted, long admissionTime) {
    }

    private final List<GroupLifetimeObservation> groupLifetimeObservations = new ArrayList<>();

    private int bookkeepersCreatedTotal = 0;
    private int attemptedBookkeepersTotal = 0;

    // Denominator for collapsed_fraction, counted here rather than taken from attempted above:
    // attempted is incremented before BOTH filters and so also counts VISITED-SET drops - cascade
    // pruning, which PostgreSQL has no counterpart for. Deriving the fraction from it would put a
    // simulator-only mechanism in the denominator.
    //
    //   collapsed_fraction = collapseSkippedTotal / collapseOfferedTotal
    //
    // Both are incremented inside BASEBookkeeperCreator.filterBookkeepers, over the SAME population:
    // CUs offered to the dirty-set collapse. PostgreSQL counterpart: outbox insert ATTEMPTS, with
    // the numerator being those the unique partial index rejects. F8 uses the same pair.
    private int collapseSkippedTotal = 0;
    private int collapseOfferedTotal = 0;

    // V3 per-run assertion: at V3's design point (fan-out 1, LINE, 2 models) a parent reaches each
    // target once, so the visited set must drop NOTHING. Counted so that is checkable per run rather
    // than assumed - if it ever fires, the schema generator is not producing the planned topology.
    private int visitedSetDroppedTotal = 0;
    private int bookkeepersCompletedTotal = 0;
    private int bookkeepersAbortedTotal = 0;

    private int partialGroupsTotal = 0;
    private int partialBookkeepersCreatedTotal = 0;
    private final int[] partialBookkeeperDepthCounts = new int[10];

    private int cautionWaitAborts = 0;
    private int deadlockAborts = 0;
    private int otherAborts = 0;

    private int totalReconciliationBookkeepers = 0;
    private long totalDirtySetSize = 0;
    private int dirtySetSamples = 0;
    private int baseBookkeepersMissingDirtyKey = 0;

    private long[] recoveryLatencySamples;
    private int recoveryLatencyWriteIndex = 0;
    // F5 (VLDB reviewer response): lock_wait_p50/p95/p99 - time spent queued for a lock before
    // acquiring it, distinct from the lock-HOLD tracking above (lockHoldStart/recordLockHold).
    private long[] lockWaitMsSamples;
    private int lockWaitWriteIndex = 0;
    // F5: restarts_total - group-level, incremented alongside groupsAbortedTotal since every
    // abort today unconditionally triggers a restart (TransactionGroupAbortCoordinator), but
    // kept as its own counter so that relationship isn't assumed to always hold 1:1.
    private int restartsTotal = 0;
    // Distinct from restartsTotal above: this counts
    // TransactionLockCoordinator.handleLockDenial()'s shouldReleaseOnSoftDeny()==true branch
    // firing - a soft-denied transaction released partial locks and will re-run acquireAllLocks()
    // from scratch on wake - NOT a full group abort/restart. Under C2PL this is the dominant cost
    // channel, and restartsTotal does not see it.
    private int softDenyReleaseCyclesTotal = 0;
    private long[] parentTransactionLatencySamples;
    private int parentTransactionLatencyWriteIndex = 0;
    private long[] cprSynchronizationDelaySamples;
    private int cprSynchronizationDelayWriteIndex = 0;
    private long[] rollbackLatencySamples;
    private int rollbackLatencyWriteIndex = 0;
    private double[] cascadeAmplificationSamples;
    private int cascadeAmplificationWriteIndex = 0;
    private long cascadeParentResourceReferencesTotal = 0;
    private long cascadeCprResourceReferencesTotal = 0;
    private int cascadeResourceReferenceSamples = 0;
    private long startedGroupParentResourceReferencesTotal = 0;
    private int startedGroupParentResourceReferenceSamples = 0;
    private long completedGroupParentResourceReferencesTotal = 0;
    private long completedGroupCprResourceReferencesTotal = 0;
    private int completedGroupResourceReferenceSamples = 0;
    private long completedParentResourceReferencesTotal = 0;
    private long completedBookkeeperResourceReferencesTotal = 0;

    private final int[] bookkeeperDepthCounts = new int[10];
    private int maxDepthObserved = 0;
    private RuntimeHealthSnapshot latestHealthSnapshot;
    private int peakPendingQueueDepth = 0;
    private int peakBlockedQueueDepth = 0;
    private int peakReadyQueueDepth = 0;
    private int peakLockQueueDepth = 0;
    private int peakIoQueueDepth = 0;
    private int peakCpuQueueDepth = 0;
    private int peakRollbackQueueDepth = 0;
    private int peakReconciliationQueueDepth = 0;
    private int peakAwaitingUserActionQueueDepth = 0;
    private int peakWaitingForGroupQueueDepth = 0;
    private final int[] pendingQueueDepthSamples;
    private final int[] blockedQueueDepthSamples;
    private final int[] lockQueueDepthSamples;
    private final int[] waitingForGroupQueueDepthSamples;
    private final int[] rollbackQueueDepthSamples;
    private int queueDepthSampleWriteIndex = 0;
    private int peakPendingStateCount = 0;
    private int peakRunningStateCount = 0;
    private int peakBlockedStateCount = 0;
    private int peakReadyStateCount = 0;
    private int peakAwaitingUserActionStateCount = 0;
    private int peakCompletedStateCount = 0;
    private int peakAbortedStateCount = 0;

    // TGS static-locking validation: "where are the other 255?" diagnostic. mean_running (see
    // runningStateTimeWeighted above) accounts for only one FSM state; these are its siblings,
    // same time-weighted-via-recordHealthSnapshot() pattern, so the full population (MPL, closed
    // loop) can be attributed across every TransactionState instead of leaving everything outside
    // RUNNING unaccounted for. PENDING and READY are the two candidates worth checking first:
    // PENDING means "never got as far as an admission attempt in this snapshot" (queued behind
    // the per-step dispatch budget); READY means "has locks already, waiting on the SEPARATE CPU
    // execution-slot gate" (canAdmitReadyTransaction/CapacityWaitManager's ready queue) - a
    // different bottleneck than lock admission entirely.
    private double pendingStateTimeWeighted = 0.0;
    private double blockedStateTimeWeighted = 0.0;
    private double readyStateTimeWeighted = 0.0;
    private double awaitingUserActionStateTimeWeighted = 0.0;

    // RuntimeHealthSnapshot.admissionWaiterCount is the
    // AdmissionWaitManager registry size - the ACID preclaim admission-wait population that
    // blockedStateTimeWeighted (above) cannot see (registerWaiter() fires no FSM transition -
    // see RuntimeHealthSnapshot.admissionWaiterCount's javadoc). Same time-weighted-mean +
    // peak pattern as runningStateTimeWeighted/peakRunningStateCount. This is the population
    // Thomasian-style thrashing thresholds (~30%) are actually meant to be a fraction of.
    private double admissionWaiterCountTimeWeighted = 0.0;
    private int peakAdmissionWaiterCount = 0;

    // TGS Postgres-anchor comparison: sampled H (RuntimeHealthSnapshot.locksHeldCount, i.e.
    // SS2PLLockManager.getLocksHeld()) - same time-weighted-mean + peak pattern, this is the
    // direct read of the quantity both systems' balance equations are about (see
    // locksHeldCount's field javadoc for why this isn't derived from meanRunningStateCountSampled
    // *k instead).
    private double locksHeldCountTimeWeighted = 0.0;
    private int peakLocksHeldCount = 0;

    private int watchdogFailureCount = 0;
    private long lastWatchdogVirtualTime = -1;

    // Time-weighted-average accumulators (Sigma count*dt over the run), fed exclusively by
    // recordHealthSnapshot() below - the only place a step's post-step virtualTime is
    // available alongside the RuntimeHealthSnapshot's queue/state counts.
    private long lastSnapshotVirtualTime = -1;
    private double blockedTimeWeighted = 0.0;
    /** Integral of each LineagePhase's live population over the POST-WARM-UP window. Divided by
     * completions it is per-transaction time in that phase; summed it is MPL x window, which is
     * the gate. See Transaction.PHASE_POPULATION for why this replaced per-lineage accounting. */
    private final double[] phaseOccupancy =
            new double[Transaction.LineagePhase.values().length];
    private long phaseOccupancyWindowTicks = 0;

    // CUs IN FLIGHT, time-weighted over the post-warm-up window.
    //
    // LineagePhase population tracks PARENTS only - markPhase is guarded
    // !(txn instanceof BookkeeperTransaction) at every call site - so the live CU count appears in
    // no existing metric. On the extended cores axis it is the quantity that says whether more
    // cores buy more CASCADE parallelism or just more parents: a cell whose throughput rises while
    // CUs in flight is flat is not scaling its cascades.
    //
    // Integrated in the same warm-up-gated block as phaseOccupancy and against the same dt, so it
    // shares that estimator's window exactly rather than defining a second one.
    private double cusInFlightTimeWeighted = 0.0;
    private int cusInFlightMax = 0;

    // THE LIVE COUNT, MAINTAINED - not derived from bookkeepersCreatedTotal.
    //
    // Computing it as created - completed - aborted reads ZERO at every instant:
    // incrementBookkeepersCreated is tallied at GROUP COMMIT ("Push cascade structure metrics to
    // global snapshot at commit time (avoids abort inflation)", ACIDCompletionHandler:262) while
    // completed ticks per CU BEFORE that commit, so the difference is never positive and a
    // Math.max(0, ..) clamp turns it into a clean zero. That counter's NAME says creation; its
    // call site says commit.
    //
    // Decremented inside incrementBookkeepersCompleted/Aborted rather than at their five call
    // sites, so the exits cannot drift from the tallies they pair with - they are the same
    // statement. Incremented from BookkeeperCreationCoordinator.registerBookkeepers, the single
    // point at which a CU enters a group for both ACID and BASE.
    private int cusLive = 0;
    // MUST READ ZERO. A negative live count means the increment and the decrements are
    // unbalanced, which a clamp would hide. Emitted rather than asserted in-engine, so a run still
    // finishes and the evidence travels with the row.
    private long cusLiveNegativeSamples = 0;
    // Per-snapshot extremes of the TOTAL population. Under equilibrium both equal MPL; a leak
    // shows up as max drifting above it at the step where it happens, which the integrated
    // total alone would smear out.
    private int phasePopulationMin = Integer.MAX_VALUE;
    private int phasePopulationMax = 0;
    private double waitingForGroupTimeWeighted = 0.0;
    private double inSystemTimeWeighted = 0.0;

    // DIRECT group-population measurement, because meanConcurrentGroups = throughput*lifetime
    // is derived rather than measured and excludes aborted-then-restarted groups entirely:
    // time-weighted sample of currentLiveGroups = groupsStartedTotal
    // - groupsCompletedTotal - groupsAbortedTotal, taken at the SAME health-snapshot cadence as
    // blockedTimeWeighted etc. This is exactly the quantity TransactionPoolManager.
    // currentOpenGroups() computes internally (that method is private, so this is an
    // independent, external recomputation of the same formula, not a call-through) - sampling it
    // over time answers both "what is the true live-group population" (mean/peak, comparable
    // directly against MPL) and "is the closed-loop pool replenishing to target" (compare the
    // mean against mpl) with one counter, per the discussion that motivated this.
    private double liveGroupsTimeWeighted = 0.0;
    private int peakLiveGroupsSampled = 0;

    // TGS static-locking validation: peakRunningStateCount (below, pre-existing) answers "is
    // there a cap"; this time-weighted mean answers "where does occupancy actually sit" relative
    // to the D/k packing bound - same liveGroupsTimeWeighted/dt pattern, just for
    // TransactionState.RUNNING specifically instead of "every live group regardless of state".
    // Deliberately NOT derived from attempt-time n_running (see AdmissionAttemptProvenance's
    // javadoc and the TGS plan's Stage 0 findings) - attempts fire preferentially right after a
    // departure, so an attempt-time sample of running-count is systematically biased low; this
    // one is sampled independently via recordHealthSnapshot(), same mechanism
    // liveGroupsTimeWeighted uses.
    private double runningStateTimeWeighted = 0.0;

    // wasted_cpu_io_seconds: IO/CPU time already sunk into transactions that end up aborted,
    // read off their TransactionMetrics right before they transition to ABORTED.
    private double wastedIoTime = 0.0;
    private double wastedCpuTime = 0.0;

    // lock_seconds_waiting_room: lock-hold duration (lockCountHeld * holdDuration), split into
    // "held while parked in the waiting room" vs. "held overall".
    private double totalLockHoldMsWeighted = 0.0;
    private double waitingRoomLockHoldMsWeighted = 0.0;
    private double totalLocksAcquiredCount = 0.0;
    /** Number of recordLockHold() invocations. Distinct from totalLocksAcquiredCount (their SUM):
     * separating the two says whether a discrepancy is "wrong count per record" or "wrong number of
     * records", which a sum alone cannot. */
    private long lockHoldRecordingCount = 0L;

    /**
     * How far through its operations each aborting transaction had got, as a fraction in [0,1],
     * bucketed into tenths. A DISTRIBUTION rather than a mean because that is the point: without
     * interleaving a denial discards nothing (all locks are taken before any work), so this would
     * be a spike at 0. Interleaving makes mid-cursor aborts possible, and a spike at either end
     * means the cursor is not actually interleaving - which would let the hold-time criterion pass
     * for the wrong reason.
     */
    // DISCARDED SERVICE AT ABORT. How much service an attempt had actually completed when it
    // aborted, split parent vs CU, plus how many of its operations had been reached.
    //
    // Exists because restarts/completion answers a DIFFERENT question (how OFTEN) and wasted_ticks
    // is computed as restarts x service, i.e. it ASSUMES every abort discards one full service -
    // the exact quantity under test when comparing late-placed against interleaved CPR work. Two
    // arms can abort equally often and lose different amounts each time.
    //
    // Recorded from TransactionGroupAbortCoordinator.recordWastedWork(), which is the only point
    // where an about-to-abort member's progress is still readable, and which already covers the
    // parent AND every bookkeeper in the group.
    private long abortedParentAttempts = 0L;
    private long abortedCuAttempts = 0L;
    private double discardedParentService = 0.0;
    private double discardedCuService = 0.0;
    private long discardedParentOpsReached = 0L;
    private long discardedCuOpsReached = 0L;

    private final long[] cursorProgressAtAbortBuckets = new long[10];
    private long cursorProgressAtAbortSamples = 0L;

    // Opt-in raw (virtualTime, cumulative completions) trace, one point per
    // recordHealthSnapshot() call: the data a warmupSeconds setting has to be chosen from. Off by
    // default so the ~40,000-sample-per-run memory and CSV cost is only paid where it is needed.
    // Enabled via the ts.metrics.throughputTrace system property
    // (checked once at construction, not per-call, so a caller can't toggle it mid-run).
    //
    // cumulative completions alone can't distinguish "no steady state" from
    // "too few completion EVENTS for a completions-based estimator to say anything" - at
    // R=1.2/MPL=32 a 4000s run had ~11 completions total, and a warm-up estimator built only on
    // that series cannot converge regardless of the true underlying dynamics. blockedQueueDepth
    // and inSystemCount are high-rate signals (one sample per health snapshot, not per
    // completion) that can actually distinguish a plateaued-but-sparse regime from genuine
    // non-stationarity (queue occupancy trending upward with no stationary distribution) or
    // metastable oscillation (slow thrash/recover cycling) - added alongside the completions
    // trace under the same enable flag.
    private final boolean throughputTraceEnabled =
            Boolean.getBoolean("ts.metrics.throughputTrace");
    private final List<Long> throughputTraceVirtualTime =
            throughputTraceEnabled ? new ArrayList<>() : null;
    private final List<Integer> throughputTraceCumulativeCompletions =
            throughputTraceEnabled ? new ArrayList<>() : null;
    private final List<Integer> throughputTraceBlockedQueueDepth =
            throughputTraceEnabled ? new ArrayList<>() : null;
    private final List<Integer> throughputTraceInSystemCount =
            throughputTraceEnabled ? new ArrayList<>() : null;
    // per-restart lockRetryCount at the moment a group is restarted after an
    // abort - the raw material for a starvation/livelock-vs-collective-thrashing histogram.
    // There is no stable per-logical-transaction identity across restarts (TransactionFactory.
    // restartTransaction() issues a fresh id each time), so this cannot be grouped into "this
    // lineage was restarted N times" directly - but retryCount itself IS carried forward and
    // incremented across restarts (see TransactionFactory.restartTransaction()), so a
    // distribution concentrated at high values means a subset of lineages are surviving many
    // restarts (consistent with starvation/livelock); a distribution concentrated near 0 means
    // most restarts are first-or-second attempts (collective thrashing, no persistent victims).
    private final List<Integer> restartRetryCountSamples =
            throughputTraceEnabled ? new ArrayList<>() : null;

    public void recordRestartRetryCount(int retryCount) {
        if (throughputTraceEnabled) {
            restartRetryCountSamples.add(retryCount);
        }
    }

    public int[] getRestartRetryCountSamples() {
        if (!throughputTraceEnabled) {
            return new int[0];
        }
        return restartRetryCountSamples.stream().mapToInt(Integer::intValue).toArray();
    }

    public boolean isThroughputTraceEnabled() {
        return throughputTraceEnabled;
    }

    // TGS static-locking validation (Tay/Goodman/Suri parity plan): opt-in per-admission-attempt
    // trace, separate flag from throughputTraceEnabled above since the two are enabled
    // independently by different experiments. Two outputs at very different granularity, both
    // populated from ACIDAdmissionController.tryAdmitWithLockCheck() via recordAdmissionAttempt():
    //   - First-attempt rows (attemptIndex == 0 only): P1/P2 are computed on these - repeated
    //     same-resource-set attempts against the same blocker are correlated, not independent
    //     draws, and would bias measured P(blocked) upward if mixed in. Volume is bounded by
    //     admitted-transaction count per run.
    //   - All-attempts histogram, keyed by (heldLockUnits, provenance, outcome): needed only for
    //     the first-vs-all diagnostic and for empirically determining which re-attempt channel
    //     (WOKEN vs RACE_RETRY - see AdmissionAttemptProvenance's javadoc) dominates at a given
    //     grid cell, without materializing one row per attempt (attempt volume at high MPL under
    //     heavy blocking can be orders of magnitude larger than admitted-transaction count).
    private final boolean admissionAttemptTraceEnabled =
            Boolean.getBoolean("ts.metrics.admissionAttemptTrace");
    private final List<Long> admissionAttemptVirtualTime =
            admissionAttemptTraceEnabled ? new ArrayList<>() : null;
    private final List<Integer> admissionAttemptK =
            admissionAttemptTraceEnabled ? new ArrayList<>() : null;
    private final List<Integer> admissionAttemptNRunning =
            admissionAttemptTraceEnabled ? new ArrayList<>() : null;
    private final List<Integer> admissionAttemptHeldLockUnits =
            admissionAttemptTraceEnabled ? new ArrayList<>() : null;
    private final List<String> admissionAttemptOutcome =
            admissionAttemptTraceEnabled ? new ArrayList<>() : null;
    private final Map<AdmissionAttemptHistogramKey, Integer> admissionAttemptHistogram =
            admissionAttemptTraceEnabled ? new LinkedHashMap<>() : null;

    /** Histogram key for the all-attempts diagnostic - see admissionAttemptHistogram's javadoc
     * above. A record (not a String concatenation) so equals()/hashCode() are exact and cheap. */
    public record AdmissionAttemptHistogramKey(int heldLockUnits, String provenance, String outcome) {
    }

    // TGS static-locking validation: per-STEP admission counters for the "instrument before
    // reading" diagnostic - localizes the PENDING-pile-up throttle found via the FSM histogram
    // (RUNNING/PENDING/BLOCKED/READY breakdown) to a specific mechanism by showing, per step,
    // how many attempts actually got issued vs how many transactions are sitting in PENDING.
    // Unconditional (not gated behind admissionAttemptTraceEnabled) since they're two int
    // increments - cheap enough to always track, and useful independent of the full trace.
    // get*AndReset are the read side, called once per step by FixedTimeStepStrategy when
    // -Dts.debug.perStepLog=true.
    private int stepAdmissionAttemptsCount = 0;
    private int stepAdmissionsGrantedCount = 0;

    // whole-run (never reset) counterparts to the two step
    // counters above, giving a genuine per-attempt blocked rate
    // (totalBlockedAtCheckCount / totalAdmissionAttemptsCount) without needing the opt-in
    // admissionAttemptTraceEnabled trace - AdmissionOutcome.BLOCKED_AT_CHECK is already computed
    // at every admission attempt regardless of trace state, this just exposes an unconditional
    // cumulative tally of it. Unlike admissionAttemptHistogram (keyed by heldLockUnits too, and
    // gated behind the trace flag), this is a single pair of ints, cheap enough to always track.
    private long totalAdmissionAttemptsCount = 0;
    private long totalBlockedAtCheckCount = 0;

    public int getAndResetStepAdmissionAttemptsCount() {
        int value = stepAdmissionAttemptsCount;
        stepAdmissionAttemptsCount = 0;
        return value;
    }

    public int getAndResetStepAdmissionsGrantedCount() {
        int value = stepAdmissionsGrantedCount;
        stepAdmissionsGrantedCount = 0;
        return value;
    }

    /** Whole-run cumulative admission-attempt count, never reset - see totalAdmissionAttemptsCount's
     * field javadoc. */
    public long getTotalAdmissionAttemptsCount() {
        return totalAdmissionAttemptsCount;
    }

    /** Whole-run cumulative AdmissionOutcome.BLOCKED_AT_CHECK count, never reset. Denominator for
     * this ratio is getTotalAdmissionAttemptsCount() (every attempt, per A4's outcome-definition
     * rule - LOST_RACE excluded from the numerator but included in the denominator since the
     * check still ran; see AdmissionOutcome's javadoc). */
    public long getTotalBlockedAtCheckCount() {
        return totalBlockedAtCheckCount;
    }

    /**
     * Record one admission-attempt observation. provenance/outcome are the execution-package
     * AdmissionAttemptProvenance/AdmissionOutcome enums' name()s (passed as String so this class
     * doesn't need a new dependency on the execution package - see the call site's javadoc).
     * The per-step counters above are updated unconditionally; everything past that early return
     * is gated behind -Dts.metrics.admissionAttemptTrace=true as before.
     */
    public void recordAdmissionAttempt(long virtualTime, int k, int nRunning, int heldLockUnits,
            int attemptIndex, String provenance, String outcome) {
        stepAdmissionAttemptsCount++;
        totalAdmissionAttemptsCount++;
        if ("ADMITTED".equals(outcome)) {
            stepAdmissionsGrantedCount++;
        } else if ("BLOCKED_AT_CHECK".equals(outcome)) {
            totalBlockedAtCheckCount++;
        }
        if (!admissionAttemptTraceEnabled) {
            return;
        }
        if (attemptIndex == 0) {
            admissionAttemptVirtualTime.add(virtualTime);
            admissionAttemptK.add(k);
            admissionAttemptNRunning.add(nRunning);
            admissionAttemptHeldLockUnits.add(heldLockUnits);
            admissionAttemptOutcome.add(outcome);
        }
        AdmissionAttemptHistogramKey key = new AdmissionAttemptHistogramKey(heldLockUnits, provenance, outcome);
        admissionAttemptHistogram.merge(key, 1, Integer::sum);
    }

    // TGS static-locking validation: per-admission elapsed virtual time from a transaction's
    // FIRST attempt to its eventual successful admission. Tests the "blocking costs no virtual
    // time" hypothesis for the D-invariance at low k directly - if the admission loop drains
    // PENDING within one step (no per-step iteration cap since it was removed - see
    // AbstractAdmissionController.processTransactionLoop's javadoc), a transaction that blocks
    // and is re-admitted before the step ends contributes ~0 delay, which is indistinguishable
    // from never having blocked at all in every OTHER metric this experiment collects. Median
    // delay by (D,k) is the direct test: exactly zero at low k, positive at high k, would
    // confirm it. Same volume profile as the first-attempt list (bounded by admitted-transaction
    // count), same opt-in flag.
    private final List<Long> admissionDelay = admissionAttemptTraceEnabled ? new ArrayList<>() : null;

    /** Records one admission's first-attempt-to-admitted elapsed virtual time. No-op when the
     * trace isn't enabled. */
    public void recordAdmissionDelay(long delay) {
        if (!admissionAttemptTraceEnabled) {
            return;
        }
        admissionDelay.add(delay);
    }

    public long[] getAdmissionDelay() {
        if (!admissionAttemptTraceEnabled) {
            return new long[0];
        }
        return admissionDelay.stream().mapToLong(Long::longValue).toArray();
    }

    public boolean isAdmissionAttemptTraceEnabled() {
        return admissionAttemptTraceEnabled;
    }

    /** Parallel to getAdmissionAttemptK()/getAdmissionAttemptNRunning()/etc. First-attempt
     * (attemptIndex == 0) observations only. Empty array if the trace wasn't enabled. */
    public long[] getAdmissionAttemptVirtualTime() {
        if (!admissionAttemptTraceEnabled) {
            return new long[0];
        }
        return admissionAttemptVirtualTime.stream().mapToLong(Long::longValue).toArray();
    }

    public int[] getAdmissionAttemptK() {
        if (!admissionAttemptTraceEnabled) {
            return new int[0];
        }
        return admissionAttemptK.stream().mapToInt(Integer::intValue).toArray();
    }

    public int[] getAdmissionAttemptNRunning() {
        if (!admissionAttemptTraceEnabled) {
            return new int[0];
        }
        return admissionAttemptNRunning.stream().mapToInt(Integer::intValue).toArray();
    }

    public int[] getAdmissionAttemptHeldLockUnits() {
        if (!admissionAttemptTraceEnabled) {
            return new int[0];
        }
        return admissionAttemptHeldLockUnits.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Parallel to the other getAdmissionAttempt*() arrays - AdmissionOutcome.name() per
     * first attempt. */
    public String[] getAdmissionAttemptOutcome() {
        if (!admissionAttemptTraceEnabled) {
            return new String[0];
        }
        return admissionAttemptOutcome.toArray(new String[0]);
    }

    /** Flattened (heldLockUnits, provenance, outcome, count) view of the all-attempts histogram,
     * three parallel arrays plus counts - kept as arrays rather than exposing the Map type across
     * the ExperimentRuntimeSnapshot boundary, matching how groupLifetimeObservations is flattened
     * there. Empty arrays if the trace wasn't enabled. */
    public int[] getAdmissionAttemptHistogramHeldLockUnits() {
        if (!admissionAttemptTraceEnabled) {
            return new int[0];
        }
        return admissionAttemptHistogram.keySet().stream().mapToInt(AdmissionAttemptHistogramKey::heldLockUnits).toArray();
    }

    public String[] getAdmissionAttemptHistogramProvenance() {
        if (!admissionAttemptTraceEnabled) {
            return new String[0];
        }
        return admissionAttemptHistogram.keySet().stream().map(AdmissionAttemptHistogramKey::provenance).toArray(String[]::new);
    }

    public String[] getAdmissionAttemptHistogramOutcome() {
        if (!admissionAttemptTraceEnabled) {
            return new String[0];
        }
        return admissionAttemptHistogram.keySet().stream().map(AdmissionAttemptHistogramKey::outcome).toArray(String[]::new);
    }

    public int[] getAdmissionAttemptHistogramCount() {
        if (!admissionAttemptTraceEnabled) {
            return new int[0];
        }
        return admissionAttemptHistogram.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /** Parallel to getThroughputTraceCumulativeCompletions(). Empty array if the trace wasn't
     * enabled (-Dts.metrics.throughputTrace=true) for this run. */
    public long[] getThroughputTraceVirtualTime() {
        if (!throughputTraceEnabled) {
            return new long[0];
        }
        return throughputTraceVirtualTime.stream().mapToLong(Long::longValue).toArray();
    }

    /** Parallel to getThroughputTraceVirtualTime() - blockedQueueDepth at each sampled
     * instant, the same population getFractionTimeBlocked() integrates over time. */
    public int[] getThroughputTraceBlockedQueueDepth() {
        if (!throughputTraceEnabled) {
            return new int[0];
        }
        return throughputTraceBlockedQueueDepth.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Parallel to getThroughputTraceVirtualTime() - the same inSystemCount
     * getFractionTimeBlocked()/getFractionTimeWaitingRoom() use as their shared denominator
     * (every in-flight parent+bookkeeper across every execution-phase queue). */
    public int[] getThroughputTraceInSystemCount() {
        if (!throughputTraceEnabled) {
            return new int[0];
        }
        return throughputTraceInSystemCount.stream().mapToInt(Integer::intValue).toArray();
    }

    public int[] getThroughputTraceCumulativeCompletions() {
        if (!throughputTraceEnabled) {
            return new int[0];
        }
        return throughputTraceCumulativeCompletions.stream().mapToInt(Integer::intValue).toArray();
    }

    // F4 (VLDB reviewer response): warm-up/steady-state windowing. markWarmupBoundary() snapshots
    // every accumulator this class exposes as a rate/fraction so callers can compute
    // (end - boundary) deltas instead of integrating from t=0. Deliberately NOT retrofitted onto
    // the percentile/quantile sample arrays (lock-wait, cascade-amplification, recovery/rollback
    // latency, dirty-set-size) - none of those carry a per-sample virtual-time tag today, and
    // adding one to ~7 arrays is out of scope for this pass. Known limitation, not a bug.
    private WindowMarker warmupBoundary = null;

    /**
     * Snapshot of the outer counters at the warm-up boundary. Fields carry an AtMark suffix so they
     * are distinguishable from the live outer fields they copy: identical names here would be
     * CORRECT (this is a snapshot record, every reference resolves to its own field) but
     * indistinguishable by inspection from a genuine shadowing bug, forcing the next reader to
     * redo that analysis.
     */
    private static final class WindowMarker {
        final long virtualTimeAtMark;
        final int groupsCompletedTotalAtMark;
        final double blockedTimeWeightedAtMark;
        final double waitingForGroupTimeWeightedAtMark;
        final double inSystemTimeWeightedAtMark;
        final double wastedIoTimeAtMark;
        final double wastedCpuTimeAtMark;
        final long abortedParentAttemptsAtMark;
        final long abortedCuAttemptsAtMark;
        final double discardedParentServiceAtMark;
        final double discardedCuServiceAtMark;
        final long discardedParentOpsReachedAtMark;
        final long discardedCuOpsReachedAtMark;

        WindowMarker(long virtualTime, int groupsCompletedTotal, double blockedTimeWeighted,
                double waitingForGroupTimeWeighted, double inSystemTimeWeighted,
                double wastedIoTime, double wastedCpuTime,
                long abortedParentAttempts, long abortedCuAttempts,
                double discardedParentService, double discardedCuService,
                long discardedParentOpsReached, long discardedCuOpsReached) {
            this.abortedParentAttemptsAtMark = abortedParentAttempts;
            this.abortedCuAttemptsAtMark = abortedCuAttempts;
            this.discardedParentServiceAtMark = discardedParentService;
            this.discardedCuServiceAtMark = discardedCuService;
            this.discardedParentOpsReachedAtMark = discardedParentOpsReached;
            this.discardedCuOpsReachedAtMark = discardedCuOpsReached;
            this.virtualTimeAtMark = virtualTime;
            this.groupsCompletedTotalAtMark = groupsCompletedTotal;
            this.blockedTimeWeightedAtMark = blockedTimeWeighted;
            this.waitingForGroupTimeWeightedAtMark = waitingForGroupTimeWeighted;
            this.inSystemTimeWeightedAtMark = inSystemTimeWeighted;
            this.wastedIoTimeAtMark = wastedIoTime;
            this.wastedCpuTimeAtMark = wastedCpuTime;
        }
    }

    /**
     * F4: capture the warm-up boundary. Idempotent (first call wins) so Simulator.run() doesn't
     * need its own "already marked" guard - it can call this unconditionally every step once
     * virtual time has passed the configured warmup.
     */
    public void markWarmupBoundary() {
        if (warmupBoundary != null) {
            return;
        }
        warmupBoundary = new WindowMarker(latestVirtualTime, groupsCompletedTotal,
                blockedTimeWeighted, waitingForGroupTimeWeighted, inSystemTimeWeighted,
                wastedIoTime, wastedCpuTime,
                abortedParentAttempts, abortedCuAttempts,
                discardedParentService, discardedCuService,
                discardedParentOpsReached, discardedCuOpsReached);
    }

    public boolean hasWarmupBoundary() {
        return warmupBoundary != null;
    }

    /**
     * Ticks per simulated second. Virtual time is measured in TICKS, and a tick is a MICROSECOND -
     * {@code SimulatorConfig.TICKS_PER_SECOND = 1_000_000} and
     * {@code simulationDuration = simulationDurationSeconds * 1_000_000L}.
     *
     * <p><b>A factor of 1000 here is invisible to every internal consistency check.</b> Dividing
     * by 1000 instead makes the three accessors below return MILLISECONDS while named, documented
     * and consumed as seconds, and the visible symptom is only a throughput column 1000x too
     * small: a run whose transactions demand 20ms of service on 8 processors reports 0.23 "tps"
     * where the true rate is 230/s. Nothing catches it, because it is CONSISTENT -
     * {@code getWindowedThroughput} divides completions by the same window, so count = rate x
     * window still reconciles and every cross-run comparison is in the same unit. Only an
     * absolute check against service demand can see it, which is what
     * {@code WorkloadAxisUnitsTest} does, on the identity this constant governs.
     *
     * <p>{@code ExperimentRuntimeSnapshot.wastedCpuIoFraction()} is NOT part of that: its
     * {@code measurementWindowS() * 1_000_000.0} denominator is written for true seconds already,
     * so correcting it too would over-correct by 1000x.
     */
    private static final double TICKS_PER_SECOND = 1_000_000.0;

    /** Seconds of simulated time actually discarded - 0 if no warm-up boundary was ever marked
     * (e.g. warmupSeconds=0, or a watchdog killed the run before the boundary was reached). */
    public double getWarmupDiscardedS() {
        return warmupBoundary == null ? 0.0 : warmupBoundary.virtualTimeAtMark / TICKS_PER_SECOND;
    }

    /** Seconds of simulated time between the warm-up boundary and the last observed health
     * snapshot - the denominator for every windowed rate/fraction below. Falls back to the full
     * run (0..latestVirtualTime) when no boundary was marked, so callers get a sane value
     * (not 0/NaN) even with warmupSeconds=0. */
    public double getMeasurementWindowS() {
        long start = warmupBoundary == null ? 0L : warmupBoundary.virtualTimeAtMark;
        return Math.max(0L, latestVirtualTime - start) / TICKS_PER_SECOND;
    }

    public int getCompletionsInWindow() {
        int start = warmupBoundary == null ? 0 : warmupBoundary.groupsCompletedTotalAtMark;
        return Math.max(0, groupsCompletedTotal - start);
    }

    /** Windowed throughput (completed groups per second of the post-warmup measurement window),
     * the F4-aware counterpart to getThroughputTransactionsPerSecond(). */
    public double getWindowedThroughput() {
        double windowS = getMeasurementWindowS();
        return windowS <= 0.0 ? 0.0 : getCompletionsInWindow() / windowS;
    }

    public double getWindowedFractionTimeBlocked() {
        double denStart = warmupBoundary == null ? 0.0 : warmupBoundary.inSystemTimeWeightedAtMark;
        double numStart = warmupBoundary == null ? 0.0 : warmupBoundary.blockedTimeWeightedAtMark;
        double den = inSystemTimeWeighted - denStart;
        return den <= 0.0 ? 0.0 : (blockedTimeWeighted - numStart) / den;
    }

    public double getWindowedFractionTimeWaitingRoom() {
        double denStart = warmupBoundary == null ? 0.0 : warmupBoundary.inSystemTimeWeightedAtMark;
        double numStart = warmupBoundary == null ? 0.0 : warmupBoundary.waitingForGroupTimeWeightedAtMark;
        double den = inSystemTimeWeighted - denStart;
        return den <= 0.0 ? 0.0 : (waitingForGroupTimeWeighted - numStart) / den;
    }

    /** Windowed wasted-CPU/IO fraction, using the same (wastedIo+wastedCpu)/(processorCapacity)
     * definition as getWastedIoTime()/getWastedCpuTime() callers already use - windowed here
     * only in the numerator (wasted time sunk during the window), since the caller supplies its
     * own capacity-based denominator (see ExperimentRuntimeSnapshot.wastedCpuIoFraction()). */
    /** Time-weighted mean CUs in flight over the post-warm-up window, or 0 before the boundary. */
    public double getMeanCusInFlight() {
        return phaseOccupancyWindowTicks <= 0 ? 0.0
                : cusInFlightTimeWeighted / phaseOccupancyWindowTicks;
    }

    public int getPeakCusInFlight() {
        return cusInFlightMax;
    }

    /** CUs entering a group - see cusLive. Called once per registration batch. */
    public void incrementCusRegistered(int count) {
        if (count > 0) {
            cusLive += count;
        }
    }

    /** Zero unless the live-CU increment and decrements are unbalanced. */
    public long getCusInFlightNegativeSamples() {
        return cusLiveNegativeSamples;
    }

    public double getWindowedWastedIoTime() {
        double start = warmupBoundary == null ? 0.0 : warmupBoundary.wastedIoTimeAtMark;
        return Math.max(0.0, wastedIoTime - start);
    }

    public double getWindowedWastedCpuTime() {
        double start = warmupBoundary == null ? 0.0 : warmupBoundary.wastedCpuTimeAtMark;
        return Math.max(0.0, wastedCpuTime - start);
    }

    /**
     * One aborting attempt's discarded service, from the single call site where it is still
     * readable. {@code opsReached} is the operation cursor - how far into its own operation list
     * the attempt had got - which is the shape of the loss, not just its size.
     */
    public void recordDiscardedServiceAtAbort(boolean isBookkeeper, double ioTime, double cpuTime,
            int opsReached) {
        double service = Math.max(0.0, ioTime) + Math.max(0.0, cpuTime);
        if (isBookkeeper) {
            abortedCuAttempts++;
            discardedCuService += service;
            discardedCuOpsReached += Math.max(0, opsReached);
        } else {
            abortedParentAttempts++;
            discardedParentService += service;
            discardedParentOpsReached += Math.max(0, opsReached);
        }
    }

    // UNWINDOWED totals, for the coverage assertion only: abortedParentAttempts must equal
    // groupsAbortedTotal exactly, which is what proves the hook is on the path the population
    // actually takes. A zero sum has many explanations; a zero count has one.
    public long getAbortedParentAttemptsTotal() { return abortedParentAttempts; }
    public long getAbortedCuAttemptsTotal() { return abortedCuAttempts; }

    public long getWindowedAbortedParentAttempts() {
        long start = warmupBoundary == null ? 0L : warmupBoundary.abortedParentAttemptsAtMark;
        return Math.max(0L, abortedParentAttempts - start);
    }

    public long getWindowedAbortedCuAttempts() {
        long start = warmupBoundary == null ? 0L : warmupBoundary.abortedCuAttemptsAtMark;
        return Math.max(0L, abortedCuAttempts - start);
    }

    public double getWindowedDiscardedParentService() {
        double start = warmupBoundary == null ? 0.0 : warmupBoundary.discardedParentServiceAtMark;
        return Math.max(0.0, discardedParentService - start);
    }

    public double getWindowedDiscardedCuService() {
        double start = warmupBoundary == null ? 0.0 : warmupBoundary.discardedCuServiceAtMark;
        return Math.max(0.0, discardedCuService - start);
    }

    public long getWindowedDiscardedParentOpsReached() {
        long start = warmupBoundary == null ? 0L : warmupBoundary.discardedParentOpsReachedAtMark;
        return Math.max(0L, discardedParentOpsReached - start);
    }

    public long getWindowedDiscardedCuOpsReached() {
        long start = warmupBoundary == null ? 0L : warmupBoundary.discardedCuOpsReachedAtMark;
        return Math.max(0L, discardedCuOpsReached - start);
    }

    // E4: time series of reconciliation queue depth, matching the existing five sample arrays.
    private final int[] reconciliationQueueDepthSamples;

    public SimulationMetrics(int totalResources, int expectedSamples) {
        this(totalResources, totalResources, expectedSamples);
    }

    public SimulationMetrics(int totalResources, int dirtySetDenominatorResources, int expectedSamples) {
        this.dirtySetSizeSamples = new int[expectedSamples];
        this.totalResources = totalResources;
        this.dirtySetDenominatorResources = Math.max(1, dirtySetDenominatorResources);
        this.recoveryLatencySamples = new long[Math.max(1, expectedSamples * 10)];
        this.lockWaitMsSamples = new long[Math.max(1, expectedSamples * 10)];
        this.parentTransactionLatencySamples = new long[Math.max(1, expectedSamples * 10)];
        this.cprSynchronizationDelaySamples = new long[Math.max(1, expectedSamples * 10)];
        this.rollbackLatencySamples = new long[Math.max(1, expectedSamples * 10)];
        this.cascadeAmplificationSamples = new double[Math.max(1, expectedSamples * 10)];
        this.pendingQueueDepthSamples = new int[expectedSamples];
        this.blockedQueueDepthSamples = new int[expectedSamples];
        this.lockQueueDepthSamples = new int[expectedSamples];
        this.waitingForGroupQueueDepthSamples = new int[expectedSamples];
        this.rollbackQueueDepthSamples = new int[expectedSamples];
        this.reconciliationQueueDepthSamples = new int[expectedSamples];
    }

    public void incrementExecuted() {
        totalExecuted++;
    }

    public void incrementBookkeepers() {
        totalBookkeepers++;
    }

    public void incrementAborted() {
        totalAborted++;
    }

    public void incrementAwaitingUserAction() {
        totalAwaitingUserAction++;
    }

    // ---- W1-W4 workload observability (report ACHIEVED, not configured) ----
    //
    // Theta and readOnlyTransactionFraction are KNOBS. Drawing k DISTINCT resources truncates the
    // head of a Zipf - the most popular ids are drawn early then excluded for the rest of that
    // footprint - so the realised access distribution is materially flatter than theta suggests,
    // and the distortion grows with k and with theta, i.e. it is worst exactly where the
    // experiment is most interesting. Without these, a null result cannot be told apart from
    // "the skew never reached the sampler".
    //
    // Recorded over ADMITTED parents, matching F4's specification, and kept for the WHOLE window
    // rather than sampled: a top-1% share estimated from periodic samples is noisiest precisely
    // in the tail that defines it. A few thousand longs is nothing next to the run.
    private long[] instanceAccessCounts = new long[0];
    private long instanceAccessTotal = 0L;
    private long distinctInstancesTouchedTotal = 0L;
    private long admittedParentsForAccess = 0L;
    private long writeOperationsAdmitted = 0L;
    private long readOnlyParentsAdmitted = 0L;

    /**
     * One admitted parent: its footprint's instance ids, its write count, and whether it is
     * read-only. Called once per group start, so it counts each parent exactly once - not per
     * retry and not per operation execution, either of which would bias the distribution toward
     * transactions that struggle.
     */
    public void recordAdmittedWorkload(List<Resource> resources,
            long writeOps, boolean readOnly) {
        admittedParentsForAccess++;
        writeOperationsAdmitted += writeOps;
        if (readOnly) {
            readOnlyParentsAdmitted++;
        }
        Set<Integer> distinct = new HashSet<>();
        for (Resource r : resources) {
            int id = r.instanceId();
            if (id >= instanceAccessCounts.length) {
                instanceAccessCounts = Arrays.copyOf(instanceAccessCounts, id + 1);
            }
            instanceAccessCounts[id]++;
            instanceAccessTotal++;
            distinct.add(id);
        }
        distinctInstancesTouchedTotal += distinct.size();
    }

    /**
     * Share of all admitted accesses landing on the hottest {@code fraction} of instances that were
     * touched at all. <b>Use this, not nominal theta, as the independent variable in any
     * regression or on any x-axis</b> - theta is the setting that produced it.
     */
    public double getRealizedTopAccessShare(double fraction) {
        if (instanceAccessTotal <= 0) {
            return 0.0;
        }
        long[] sorted = Arrays.stream(instanceAccessCounts).filter(c -> c > 0).sorted()
                .toArray();
        if (sorted.length == 0) {
            return 0.0;
        }
        int top = Math.max(1, (int) Math.round(sorted.length * fraction));
        long hot = 0L;
        for (int i = sorted.length - 1; i >= sorted.length - top; i--) {
            hot += sorted[i];
        }
        return hot / (double) instanceAccessTotal;
    }

    /** F4's saturation check: read against k. If the hot set is smaller than the footprint, every
     *  transaction conflicts with every other and the measurement saturates. */
    public double getMeanDistinctInstancesTouched() {
        return admittedParentsForAccess == 0 ? 0.0
                : distinctInstancesTouchedTotal / (double) admittedParentsForAccess;
    }

    /** Realised {@code ceil(n/2)} under TWO_CLASS, against the configured transaction length. */
    public double getWritesPerTxnMean() {
        return admittedParentsForAccess == 0 ? 0.0
                : writeOperationsAdmitted / (double) admittedParentsForAccess;
    }

    /** Realised counterpart to the nominal readOnlyTransactionFraction. */
    public double getReadOnlyTxnFractionRealized() {
        return admittedParentsForAccess == 0 ? 0.0
                : readOnlyParentsAdmitted / (double) admittedParentsForAccess;
    }

    /** Non-zero once any parent has been admitted, which is what tells a real zero from an
     * unwired counter. */
    public long getAdmittedParentsForAccess() {
        return admittedParentsForAccess;
    }

    /** The raw histogram, for a Gini or any other shape statistic computed in analysis. */
    public long[] getInstanceAccessCounts() {
        return Arrays.copyOf(instanceAccessCounts, instanceAccessCounts.length);
    }

    // ---- conflict probability per access: the contention CALIBRATION target ----
    //
    // frac_blocked was the original target and cannot resolve the band: it is a time-weighted
    // occupancy fraction over a small blocked population, so at the W1-W4 operating point its
    // seed sd is 0.07-0.19 against a band only 0.20 wide - four rounds of bisection could not
    // land it, and the failure was the criterion rather than the search.
    //
    // This is a ratio of two LARGE counts (tens of thousands of lock requests per run), so its
    // sampling error is orders of magnitude smaller, and it is a monotone function of MPL*k/D -
    // which is precisely what the instance pool sets. It is also first-order: a conflict is
    // contention, whereas blocked-time occupancy is a downstream symptom of one.
    //
    // Counted at SS2PLLockManager.requestLock, at the !canGrant branch, so it is POLICY-INDEPENDENT
    // - CautiousWaitingPolicy's evaluationsTotal counts the same event but only while that policy
    // is installed.
    private long lockRequestsTotal = 0L;
    private long lockConflictsTotal = 0L;

    /** One lock request. {@code conflicted} = the lock was held incompatibly (!canGrant). */
    public void recordLockRequest(boolean conflicted) {
        lockRequestsTotal++;
        if (conflicted) {
            lockConflictsTotal++;
        }
    }

    /** Conflicts over requests. The contention calibration target - see the field comment. */
    public double getLockConflictRate() {
        return lockRequestsTotal == 0 ? 0.0 : lockConflictsTotal / (double) lockRequestsTotal;
    }

    /** nonzero proves the counter is wired, separating a real zero from an absent one. */
    public long getLockRequestsTotal() {
        return lockRequestsTotal;
    }

    public long getLockConflictsTotal() {
        return lockConflictsTotal;
    }

    public void incrementGroupsStarted() {
        groupsStartedTotal++;
    }

    public void recordStartedGroupParentResources(int parentResources) {
        startedGroupParentResourceReferencesTotal += Math.max(0, parentResources);
        startedGroupParentResourceReferenceSamples++;
    }

    /**
     * Parents that have COMMITTED, as distinct from GROUPS that have completed.
     *
     * <p>Under ACID the two coincide: a parent moves to the waiting room and completes only when its
     * whole group does ({@code ACIDCompletionHandler.completeParent} -> {@code moveToWaitingRoom}).
     * Under BASE they do NOT: the parent commits immediately and its bookkeepers drain afterwards.
     *
     * <p><b>The pool manager needs this one, not groupsCompleted.</b> Its job is to hold the
     * PRODUCER population at the configured MPL, and a committed parent has left that population
     * even though its drains are still pending. Keying it on group completion would let pending
     * bookkeepers occupy the MPL, so the producer would be throttled by the drain - the opposite of
     * BASE, and it would make lambda_offered depend on N by construction, which would read as V3's
     * criterion 2 failing when it is the pool manager.
     *
     * <p>{@code groupsCompleted} stays at the last bookkeeper, because that is what F2/F3's
     * {@code dirty_post_recon_*} and recovery-latency figures need.
     */
    private int parentsCommittedTotal = 0;

    public int getParentsCommittedTotal() {
        return parentsCommittedTotal;
    }

    /** A parent committed but its group is still draining - BASE only. */
    public void incrementParentCommitted() {
        parentsCommittedTotal++;
    }

    /** A group completed without a parent commit at the same moment - BASE's last-bookkeeper site. */
    public void incrementGroupsCompletedWithoutParent() {
        groupsCompletedTotal++;
    }

    /** Group completion that IS also the parent's commit - every ACID site, and BASE's
     *  zero-bookkeeper case. */
    public void incrementGroupsCompleted() {
        parentsCommittedTotal++;
        groupsCompletedTotal++;
    }

    public void recordCompletedGroupResourceReferences(int parentResources, int cprResources) {
        completedGroupParentResourceReferencesTotal += Math.max(0, parentResources);
        completedGroupCprResourceReferencesTotal += Math.max(0, cprResources);
        completedGroupResourceReferenceSamples++;
    }

    public void recordCompletedParentResourceReferences(int parentResources) {
        completedParentResourceReferencesTotal += Math.max(0, parentResources);
    }

    public void recordCompletedBookkeeperResourceReferences(int bookkeeperResources) {
        completedBookkeeperResourceReferencesTotal += Math.max(0, bookkeeperResources);
    }

    public void incrementGroupsAborted() {
        groupsAbortedTotal++;
    }

    public void incrementBookkeepersCreated(int count) {
        bookkeepersCreatedTotal += count;
    }
    
    public void incrementAttemptedBookkeepers(int count) {
        attemptedBookkeepersTotal += count;
    }

    /** One CU offered to the dirty-set collapse (denominator). See the field javadoc. */
    public void incrementCollapseOffered(int count) {
        collapseOfferedTotal += count;
    }

    /** One CU skipped because its target was already dirty (numerator). */
    public void incrementCollapseSkipped(int count) {
        collapseSkippedTotal += count;
    }

    /** One CU dropped by the group's visited set - cascade pruning, NOT collapse. */
    public void incrementVisitedSetDropped(int count) {
        visitedSetDroppedTotal += count;
    }

    public void recordPartialGroupImpact(TransactionGroup group) {
        if (group.getBookkeepers().isEmpty()) {
            return;
        }
        partialGroupsTotal++;
        partialBookkeepersCreatedTotal += group.getBookkeepers().size();
        int[] depths = group.getBookkeepersAtDepth();
        for (int i = 0; i < depths.length && i < partialBookkeeperDepthCounts.length; i++) {
            partialBookkeeperDepthCounts[i] += depths[i];
        }
    }

    public void incrementBookkeepersCompleted() {
        cusLive--;
        bookkeepersCompletedTotal++;
    }

    public void incrementBookkeepersAborted(int count) {
        cusLive -= Math.max(0, count);
        bookkeepersAbortedTotal += count;
    }

    public void recordAbortReason(String reason) {
        if (reason != null) {
            if (reason.contains("cautious") || reason.contains("wait")) {
                cautionWaitAborts++;
            } else if (reason.contains("deadlock")) {
                deadlockAborts++;
            } else {
                otherAborts++;
            }
        } else {
            otherAborts++;
        }
    }

    public void addLockTime(long duration) {
        totalLockTime += duration;
    }

    /**
     * Service demand (IO and CPU) carried by transactions that reached COMPLETE, accumulated at the
     * same call site as {@link #addLockTime}.
     *
     * <p>Exists for the service-demand invariance check: splitting a transaction into batches must
     * not change how much work it does, so mean IO+CPU per completion must be independent of
     * {@code operationBatchSize}. Nothing but a reporting harness reads these - they are pure
     * accumulators with no control flow attached, so they cannot move a simulation.
     *
     * <p>Counts only COMPLETED transactions. Totals over a whole run are NOT comparable across
     * batch sizes, because interleaving changes abort behaviour and therefore how many
     * transactions complete inside the time limit; the invariant is per completion.
     */
    public void addCompletedIoTime(long duration) {
        completedIoTime += duration;
    }

    public void addCompletedCpuTime(long duration) {
        completedCpuTime += duration;
    }

    /**
     * Operations actually EXECUTED by transactions that reached COMPLETE.
     *
     * <p>Closes a hole in the resource-reference gate. {@code recordCompletedParentResourceReferences}
     * counts {@code getResources().size()} - what a transaction declares it will touch - which a
     * skipped batch leaves untouched. When a lock denial silently dropped a batch, refs-per-completion
     * still read exactly k while a third of completions had not executed part of their footprint.
     * Declared footprint and executed work are separate claims, so they get separate counters.
     */
    public void recordExecutedOperations(int operationCount) {
        executedOperationsTotal += Math.max(0, operationCount);
    }

    public long getExecutedOperationsTotal() {
        return executedOperationsTotal;
    }

    /**
     * Acquisition-to-release span of a completing transaction: the interval its FIRST batch of locks
     * was held for, which is the "T" the hold-time derivation is written against.
     *
     * <p>The derivation says k batches acquired at {@code t_i ~ (i-1)T/k} and all released at T give
     * a mean hold of about {@code T/2}. T there is measured from the first acquisition, not from
     * submission. Submission-to-completion latency additionally contains admission waiting, blocked
     * time and restarts - everything that happened BEFORE any lock was held - and that part grows
     * sharply with load while the hold does not. Normalising the mean hold against it makes the
     * ratio fall with N for a reason unrelated to lock holding, which is a denominator artifact
     * rather than a result.
     */
    public void recordAcquisitionSpan(long span) {
        acquisitionSpanMsTotal += Math.max(0L, span);
        acquisitionSpanCount++;
    }

    public long getAcquisitionSpanMsTotal() {
        return acquisitionSpanMsTotal;
    }

    public long getAcquisitionSpanCount() {
        return acquisitionSpanCount;
    }

    public double getMeanAcquisitionSpan() {
        return acquisitionSpanCount == 0 ? 0.0 : (double) acquisitionSpanMsTotal / acquisitionSpanCount;
    }

    public long getCompletedIoTime() {
        return completedIoTime;
    }

    public long getCompletedCpuTime() {
        return completedCpuTime;
    }

    public void updateVirtualTime(long virtualTime) {
        latestVirtualTime = Math.max(virtualTime, 0);
    }

    public long getTotalLockTime() {
        return totalLockTime;
    }

    public long getLatestVirtualTime() {
        return latestVirtualTime;
    }

    public int getTotalAborted() {
        return totalAborted;
    }

    public int getTotalBookkeepers() {
        return totalBookkeepers;
    }

    public int getTotalAwaitingUserAction() {
        return totalAwaitingUserAction;
    }

    public int getTotalExecuted() {
        return totalExecuted;
    }

    public int getGroupsStartedTotal() {
        return groupsStartedTotal;
    }

    public int getGroupsCompletedTotal() {
        return groupsCompletedTotal;
    }

    public int getGroupsAbortedTotal() {
        return groupsAbortedTotal;
    }

    public int getPartialGroupsTotal() {
        return partialGroupsTotal;
    }

    public int getPartialBookkeepersCreatedTotal() {
        return partialBookkeepersCreatedTotal;
    }

    public int partialDepthCountAt(int depth) {
        if (depth >= 0 && depth < partialBookkeeperDepthCounts.length) {
            return partialBookkeeperDepthCounts[depth];
        }
        return 0;
    }

    public int getBookkeepersCreatedTotal() {
        return bookkeepersCreatedTotal;
    }
    
    public int getAttemptedBookkeepersTotal() {
        return attemptedBookkeepersTotal;
    }

    public int getCollapseOfferedTotal() {
        return collapseOfferedTotal;
    }

    public int getCollapseSkippedTotal() {
        return collapseSkippedTotal;
    }

    public int getVisitedSetDroppedTotal() {
        return visitedSetDroppedTotal;
    }

    public int getBookkeepersCompletedTotal() {
        return bookkeepersCompletedTotal;
    }

    public int getBookkeepersAbortedTotal() {
        return bookkeepersAbortedTotal;
    }

    public int getBookkeepersTerminatedTotal() {
        return bookkeepersCompletedTotal + bookkeepersAbortedTotal;
    }

    public int getCautiousWaitAborts() {
        return cautionWaitAborts;
    }

    public int getDeadlockAborts() {
        return deadlockAborts;
    }

    public int getOtherAborts() {
        return otherAborts;
    }

    @Deprecated
    public void recordDirtySetSize(int size) {
        totalDirtySetSize += size;
        dirtySetSamples++;
    }

    /**
     * Backlog shape over the post-warmup window - a DIAGNOSTIC, not a verdict.
     *
     * <p><b>Do not decide stability from this.</b> Stability is not something to estimate from the
     * engine's own output: it is computable from the configuration.
     * A CU arrives at rate {@code lambda}, costs a fixed {@code S = ioTimePerResource +
     * cpuTimePerResource} (BASE zeroes the lock phase, and CUs are single-resource), and {@code N}
     * reconcilers serve them, so
     *
     * <pre>rho = lambda * S / N,  STABLE iff rho &lt; 1</pre>
     *
     * with no slope, no batches, no threshold and nothing to calibrate. That is also exactly what
     * F2 predicts, which makes the simulator side of criterion 3 an A1-A4-style identity check -
     * does the engine reproduce its own queueing arithmetic - rather than a measurement.
     *
     * <p>Two successive estimator designs were built here before that was noticed. The second was
     * discarded for a reason worth keeping: its saturation threshold would have been calibrated from
     * the very cells it then classified, with a free choice of which load's N=1 fraction to sit
     * below - a threshold generated from the data it thresholds.
     *
     * <p><b>What survives, and why.</b> The backlog series is still worth reporting: V3's criterion 3
     * asks for the backlog DISTRIBUTION (mean, p95, growth shape), because a matched stability point
     * with mismatched distributions would be a coincidence rather than agreement. These fields are
     * that distribution's shape. They are columns, not gates.
     *
     * <p>Series cadence: one sample per {@code stepTimeSlice} of SIMULATED time - 1 s at the
     * canonical 1,000,000-tick slice. PostgreSQL's sampler matches that, not wall clock.
     */
    public record BacklogShape(double slope, double standardError, int batchCount,
            double meanBacklogFraction, boolean evaluable) {

        static BacklogShape notEvaluable() {
            return new BacklogShape(Double.NaN, Double.NaN, 0, Double.NaN, false);
        }
    }

    /** Batches the post-warmup window is split into for the reported slope. */
    public static final int STABILITY_BATCHES = 16;

    /** Minimum samples per batch; below this the shape is not evaluable. */
    public static final int MIN_SAMPLES_PER_BATCH = 5;

    /**
     * Backlog shape over the post-warmup window. Batch means rather than raw OLS, because a queue
     * series is autocorrelated and a raw-sample SE understates badly - the slope is reported, so its
     * error bar should not be misleading even though nothing gates on it.
     *
     * @param warmupSamples samples to discard; pass 0 to discard the first 25% as burn-in.
     */
    public BacklogShape calculateBacklogShape(int warmupSamples) {
        int total = sampleWriteIndex;
        int from = warmupSamples > 0 ? warmupSamples : (int) (total * 0.25);
        int n = total - from;
        if (n < STABILITY_BATCHES * MIN_SAMPLES_PER_BATCH) {
            return BacklogShape.notEvaluable();
        }

        double windowMean = 0.0;
        for (int i = from; i < total; i++) {
            windowMean += dirtySetSizeSamples[i];
        }
        windowMean /= n;

        int batchSize = n / STABILITY_BATCHES;
        double[] batchMeans = new double[STABILITY_BATCHES];
        for (int b = 0; b < STABILITY_BATCHES; b++) {
            double sum = 0.0;
            for (int i = 0; i < batchSize; i++) {
                sum += dirtySetSizeSamples[from + b * batchSize + i];
            }
            batchMeans[b] = sum / batchSize;
        }

        double meanX = (STABILITY_BATCHES - 1) / 2.0;
        double meanY = 0.0;
        for (double v : batchMeans) {
            meanY += v;
        }
        meanY /= STABILITY_BATCHES;

        double sxx = 0.0;
        double sxy = 0.0;
        for (int b = 0; b < STABILITY_BATCHES; b++) {
            double dx = b - meanX;
            sxx += dx * dx;
            sxy += dx * (batchMeans[b] - meanY);
        }
        double slope = sxy / sxx;

        double sse = 0.0;
        for (int b = 0; b < STABILITY_BATCHES; b++) {
            double fitted = meanY + slope * (b - meanX);
            sse += Math.pow(batchMeans[b] - fitted, 2);
        }
        double se = Math.sqrt((sse / (STABILITY_BATCHES - 2)) / sxx);

        return new BacklogShape(slope, se, STABILITY_BATCHES,
                windowMean / dirtySetDenominatorResources, true);
    }

    /**
     * Dirty-set size summed OVER OFFERS - the occupancy an offer actually sees.
     *
     * <p><b>Why a second occupancy metric.</b> {@code sampleDirtySetSize} samples on a TIME grid and
     * so estimates the TIME average, which Little's law also gives: at V3's N=24/W=16 cell both say
     * 3.48% of K. But collapse there is <b>47%</b>, and collapse is decided by what is dirty at the
     * instant an offer is made - not by a time average. Offers arrive at parent commits, which are
     * the same events that create the dirtiness, so the two averages need not agree and here differ
     * by an order of magnitude.
     *
     * <p>Measuring it directly turns "offers see a fuller set than the clock does" from a story into
     * a number: if {@code meanOfferDirtySetSize() / K} lands at the collapsed fraction, the account
     * is confirmed; if it lands at 3.48%, the account is wrong and the gap is elsewhere.
     *
     * <p>Counted at the SAME site and on the SAME condition as {@code collapseOfferedTotal}, so the
     * two cannot drift apart in what they consider an offer.
     */
    private long offerDirtySetSizeSum = 0L;
    private long offerDirtySetSizeCount = 0L;

    public void recordOfferDirtySetSize(int dirtySetSize) {
        offerDirtySetSizeSum += dirtySetSize;
        offerDirtySetSizeCount++;
    }

    /**
     * The same occupancy with the offering parent's OWN burst removed.
     *
     * <p><b>The plain metric over-counts, and by a measured amount.</b> A parent offers k*f CUs at one
     * commit and each successive offer sees its siblings' keys already marked - but by the W=1
     * measurement those sibling keys are all DISTINCT, so they can never cause a collapse. At W=1
     * this reads <b>4.87 keys against collapse of exactly 0.0000</b>, which falsifies
     * "collapse = offer occupancy / K" as stated.
     *
     * <p>Exclusion is exact, not estimated: virtual time does not advance inside the filter loop, so
     * every key this parent has marked in this burst is still dirty, and their count is exactly the
     * number of bookkeepers admitted so far.
     */
    private long offerDirtySetSizeExclOwnSum = 0L;

    public void recordOfferDirtySetSizeExclOwn(int dirtySetSizeExcludingOwnBurst) {
        offerDirtySetSizeExclOwnSum += dirtySetSizeExcludingOwnBurst;
    }

    /** Mean dirty-set size over offers, excluding the offer's own burst; NaN if nothing offered. */
    public double meanOfferDirtySetSizeExclOwn() {
        return offerDirtySetSizeCount == 0 ? Double.NaN
                : (double) offerDirtySetSizeExclOwnSum / offerDirtySetSizeCount;
    }

    /**
     * Size of the reachable dirty-key space, K.
     *
     * <p>Needed by V3's pigeonhole gate: when concurrent bursts offer {@code W * f * k} keys into a
     * space of K, collisions are FORCED by counting rather than measured. This is V2's (x2) rule in
     * another form - V2 excluded cells where {@code mu^D > P} and lost 10 of 24 combinations to it.
     */
    public int getDirtyKeySpaceSize() {
        return dirtySetDenominatorResources;
    }

    /** Mean dirty-set size over offers, or NaN if nothing was offered. */
    public double meanOfferDirtySetSize() {
        return offerDirtySetSizeCount == 0 ? Double.NaN
                : (double) offerDirtySetSizeSum / offerDirtySetSizeCount;
    }

    public void sampleDirtySetSize(int dirtySetSize) {
        if (dirtySetSize < 0) {
            throw new IllegalArgumentException(String.format(
                    "Dirty set size cannot be negative: %d (check DirtySetManager)", dirtySetSize));
        }

        if (sampleWriteIndex < dirtySetSizeSamples.length) {
            dirtySetSizeSamples[sampleWriteIndex++] = dirtySetSize;
        } else {
            throw new IllegalStateException("DirtySetSizeSamples array full at index " + sampleWriteIndex
                    + " (expected " + dirtySetSizeSamples.length + " samples)");
        }
    }

    public void incrementReconciliationBookkeepers() {
        totalReconciliationBookkeepers++;
    }

    public int getTotalReconciliationBookkeepers() {
        return totalReconciliationBookkeepers;
    }

    public double getAverageDirtySetSize() {
        return dirtySetSamples > 0 ? (double) totalDirtySetSize / dirtySetSamples : 0;
    }

    public int getDirtySetSamples() {
        return dirtySetSamples;
    }

    public int getBaseBookkeepersMissingDirtyKey() {
        return baseBookkeepersMissingDirtyKey;
    }

    public void incrementMissingDirtyKey() {
        baseBookkeepersMissingDirtyKey++;
    }

    public void recordBookkeeperDepth(int depth) {
        if (depth >= 0 && depth < bookkeeperDepthCounts.length) {
            bookkeeperDepthCounts[depth]++;
        }
        if (depth > maxDepthObserved) {
            maxDepthObserved = depth;
        }
    }

    public int[] getBookkeeperDepthCounts() {
        return bookkeeperDepthCounts;
    }

    public int getMaxDepthObserved() {
        return maxDepthObserved;
    }

    public int getBookkeepersAtDepth(int depth) {
        if (depth >= 0 && depth < bookkeeperDepthCounts.length) {
            return bookkeeperDepthCounts[depth];
        }
        return 0;
    }

    public int getDirtySetSampleCount() {
        return sampleWriteIndex;
    }

    public void recordHealthSnapshot(RuntimeHealthSnapshot snapshot) {
        latestHealthSnapshot = snapshot;
        updateVirtualTime(snapshot.virtualTime());

        // Computed unconditionally (not just when throughputTraceEnabled) because the
        // dt-guarded accumulation block below also needs it - see its own inSystemCount local
        // for the full population-consistency rationale.
        int currentInSystemCount = snapshot.pendingQueueDepth() + snapshot.lockQueueDepth()
                + snapshot.ioQueueDepth() + snapshot.cpuQueueDepth() + snapshot.blockedQueueDepth()
                + snapshot.readyQueueDepth() + snapshot.awaitingUserActionQueueDepth()
                + snapshot.waitingForGroupQueueDepth();

        if (throughputTraceEnabled) {
            throughputTraceVirtualTime.add(snapshot.virtualTime());
            throughputTraceCumulativeCompletions.add(groupsCompletedTotal);
            throughputTraceBlockedQueueDepth.add(snapshot.blockedQueueDepth());
            throughputTraceInSystemCount.add(currentInSystemCount);
        }

        // Time-weighted accumulation: dt is the elapsed virtual time since the previous
        // snapshot: the count carried through that interval is the count reported by *this*
        // snapshot (post-step state), matching Sigma count*dt integration. The first call has
        // no prior snapshot to measure dt against, so it contributes dt=0 and only seeds
        // lastSnapshotVirtualTime.
        if (lastSnapshotVirtualTime >= 0) {
            long dt = snapshot.virtualTime() - lastSnapshotVirtualTime;
            if (dt > 0) {
                // Population consistency: the *StateCount fields (pendingStateCount,
                // runningStateCount, blockedStateCount, ...) come from TransactionStateCounter,
                // which - per Transaction.countsTowardLimit()/BookkeeperTransaction's override -
                // deliberately excludes bookkeepers. waitingForGroupQueueDepth is a raw queue
                // size (QueueType.WAITING_FOR_GROUP) that includes both parents AND bookkeepers,
                // since every group member individually passes through the waiting room. Mixing
                // a parent-only denominator with a parent+bookkeeper numerator let
                // frac_waiting_room exceed 1.0 whenever bookkeepers outnumbered parents in the
                // waiting room (caught by the E2 smoke test) - so both the "blocked" and
                // "in-system" populations here are rebuilt from the *QueueDepth fields instead,
                // which count every Transaction object (parent or bookkeeper) the same way
                // waitingForGroupQueueDepth does, keeping numerator and denominator drawn from
                // the same population.
                //
                // Consequence for what the resulting fraction MEANS: because that population is
                // "every MMT *member*" (parent + bookkeepers), not "every MMT", getFractionTimeBlocked()
                // is the time-weighted fraction of MMT MEMBERS blocked, not the fraction of MMTs
                // blocked - report/plot it as member-time, not transaction-time. (Arguably the
                // more meaningful quantity for contention anyway, since a blocked bookkeeper
                // still holds up its group.)
                // Integrate the phase populations, but ONLY past the warm-up boundary: that is
                // the entire warm-up treatment this estimator needs, against the three separate
                // rules the per-lineage version required.
                if (warmupBoundary != null) {
                    int[] pop = Transaction.phasePopulation();
                    for (int p = 0; p < pop.length && p < phaseOccupancy.length; p++) {
                        phaseOccupancy[p] += (double) pop[p] * dt;
                    }
                    phaseOccupancyWindowTicks += dt;
                    int live = TransactionAggregateMetrics
                            .phasePopulationTotal();
                    phasePopulationMin = Math.min(phasePopulationMin, live);
                    phasePopulationMax = Math.max(phasePopulationMax, live);
                    // created - completed - aborted, recomputed from THIS tick's counters: the same
                    // idiom currentLiveGroups uses below, and for the same reason - there is no
                    // separate live counter to drift out of step with the totals.
                    if (cusLive < 0) {
                        cusLiveNegativeSamples++;
                    }
                    cusInFlightTimeWeighted += (double) Math.max(0, cusLive) * dt;
                    cusInFlightMax = Math.max(cusInFlightMax, cusLive);
                }
                blockedTimeWeighted += (double) snapshot.blockedQueueDepth() * dt;
                waitingForGroupTimeWeighted += (double) snapshot.waitingForGroupQueueDepth() * dt;
                inSystemTimeWeighted += (double) currentInSystemCount * dt;
                // currentLiveGroups computed fresh from THIS tick's counter values, same
                // "count reported by this snapshot carries through the interval" convention as
                // the three lines above - groupsAbortedTotal is the genuinely group-level
                // counter (distinct from getTotalAborted()'s member-level one), matching what
                // TransactionPoolManager.currentOpenGroups() uses internally.
                int currentLiveGroups = Math.max(0, groupsStartedTotal - groupsCompletedTotal - groupsAbortedTotal);
                liveGroupsTimeWeighted += (double) currentLiveGroups * dt;
                runningStateTimeWeighted += (double) snapshot.runningStateCount() * dt;
                pendingStateTimeWeighted += (double) snapshot.pendingStateCount() * dt;
                blockedStateTimeWeighted += (double) snapshot.blockedStateCount() * dt;
                readyStateTimeWeighted += (double) snapshot.readyStateCount() * dt;
                awaitingUserActionStateTimeWeighted += (double) snapshot.awaitingUserActionStateCount() * dt;
                admissionWaiterCountTimeWeighted += (double) snapshot.admissionWaiterCount() * dt;
                locksHeldCountTimeWeighted += (double) snapshot.locksHeldCount() * dt;
            }
        }
        {
            int currentLiveGroups = Math.max(0, groupsStartedTotal - groupsCompletedTotal - groupsAbortedTotal);
            peakLiveGroupsSampled = Math.max(peakLiveGroupsSampled, currentLiveGroups);
        }
        lastSnapshotVirtualTime = snapshot.virtualTime();

        if (queueDepthSampleWriteIndex < reconciliationQueueDepthSamples.length) {
            reconciliationQueueDepthSamples[queueDepthSampleWriteIndex] = snapshot.reconciliationQueueDepth();
        }

        peakPendingQueueDepth = Math.max(peakPendingQueueDepth, snapshot.pendingQueueDepth());
        peakBlockedQueueDepth = Math.max(peakBlockedQueueDepth, snapshot.blockedQueueDepth());
        peakReadyQueueDepth = Math.max(peakReadyQueueDepth, snapshot.readyQueueDepth());
        peakLockQueueDepth = Math.max(peakLockQueueDepth, snapshot.lockQueueDepth());
        peakIoQueueDepth = Math.max(peakIoQueueDepth, snapshot.ioQueueDepth());
        peakCpuQueueDepth = Math.max(peakCpuQueueDepth, snapshot.cpuQueueDepth());
        peakRollbackQueueDepth = Math.max(peakRollbackQueueDepth, snapshot.rollbackQueueDepth());
        peakReconciliationQueueDepth = Math.max(peakReconciliationQueueDepth, snapshot.reconciliationQueueDepth());
        peakAwaitingUserActionQueueDepth = Math.max(peakAwaitingUserActionQueueDepth, snapshot.awaitingUserActionQueueDepth());
        peakWaitingForGroupQueueDepth = Math.max(peakWaitingForGroupQueueDepth, snapshot.waitingForGroupQueueDepth());

        if (queueDepthSampleWriteIndex < pendingQueueDepthSamples.length) {
            pendingQueueDepthSamples[queueDepthSampleWriteIndex] = snapshot.pendingQueueDepth();
            blockedQueueDepthSamples[queueDepthSampleWriteIndex] = snapshot.blockedQueueDepth();
            lockQueueDepthSamples[queueDepthSampleWriteIndex] = snapshot.lockQueueDepth();
            waitingForGroupQueueDepthSamples[queueDepthSampleWriteIndex] = snapshot.waitingForGroupQueueDepth();
            rollbackQueueDepthSamples[queueDepthSampleWriteIndex] = snapshot.rollbackQueueDepth();
            queueDepthSampleWriteIndex++;
        }

        peakPendingStateCount = Math.max(peakPendingStateCount, snapshot.pendingStateCount());
        peakRunningStateCount = Math.max(peakRunningStateCount, snapshot.runningStateCount());
        peakBlockedStateCount = Math.max(peakBlockedStateCount, snapshot.blockedStateCount());
        peakReadyStateCount = Math.max(peakReadyStateCount, snapshot.readyStateCount());
        peakAwaitingUserActionStateCount = Math.max(peakAwaitingUserActionStateCount, snapshot.awaitingUserActionStateCount());
        peakCompletedStateCount = Math.max(peakCompletedStateCount, snapshot.completedStateCount());
        peakAbortedStateCount = Math.max(peakAbortedStateCount, snapshot.abortedStateCount());
        peakAdmissionWaiterCount = Math.max(peakAdmissionWaiterCount, snapshot.admissionWaiterCount());
        peakLocksHeldCount = Math.max(peakLocksHeldCount, snapshot.locksHeldCount());
    }


    public RuntimeHealthSnapshot getLatestHealthSnapshot() {
        return latestHealthSnapshot;
    }

    public double getThroughputTransactionsPerSecond() {
        if (latestVirtualTime <= 0) {
            return 0.0;
        }
        return totalExecuted / (latestVirtualTime / TICKS_PER_SECOND);
    }

    public void recordWatchdogFailure(long virtualTime) {
        watchdogFailureCount++;
        lastWatchdogVirtualTime = virtualTime;
        updateVirtualTime(virtualTime);
    }

    public int getWatchdogFailureCount() {
        return watchdogFailureCount;
    }

    public long getLastWatchdogVirtualTime() {
        return lastWatchdogVirtualTime;
    }

    public int getPeakPendingQueueDepth() { return peakPendingQueueDepth; }
    public int getPeakBlockedQueueDepth() { return peakBlockedQueueDepth; }
    public int getPeakReadyQueueDepth() { return peakReadyQueueDepth; }
    public int getPeakLockQueueDepth() { return peakLockQueueDepth; }
    public int getPeakIoQueueDepth() { return peakIoQueueDepth; }
    public int getPeakCpuQueueDepth() { return peakCpuQueueDepth; }
    public int getPeakRollbackQueueDepth() { return peakRollbackQueueDepth; }
    public int getPeakReconciliationQueueDepth() { return peakReconciliationQueueDepth; }
    public int getPeakAwaitingUserActionQueueDepth() { return peakAwaitingUserActionQueueDepth; }
    public int getPeakWaitingForGroupQueueDepth() { return peakWaitingForGroupQueueDepth; }
    public int getPeakPendingStateCount() { return peakPendingStateCount; }
    public int getPeakRunningStateCount() { return peakRunningStateCount; }
    public int getPeakBlockedStateCount() { return peakBlockedStateCount; }
    public int getPeakReadyStateCount() { return peakReadyStateCount; }
    public int getPeakAwaitingUserActionStateCount() { return peakAwaitingUserActionStateCount; }
    public int getPeakCompletedStateCount() { return peakCompletedStateCount; }
    public int getPeakAbortedStateCount() { return peakAbortedStateCount; }
    public int getQueueDepthSampleCount() { return queueDepthSampleWriteIndex; }
    public double getPendingQueueDepthP95() { return calculateIntQuantile(pendingQueueDepthSamples, queueDepthSampleWriteIndex, 0.95); }
    public double getBlockedQueueDepthP95() { return calculateIntQuantile(blockedQueueDepthSamples, queueDepthSampleWriteIndex, 0.95); }
    public double getLockQueueDepthP95() { return calculateIntQuantile(lockQueueDepthSamples, queueDepthSampleWriteIndex, 0.95); }
    public double getWaitingForGroupQueueDepthP95() { return calculateIntQuantile(waitingForGroupQueueDepthSamples, queueDepthSampleWriteIndex, 0.95); }
    public double getRollbackQueueDepthP95() { return calculateIntQuantile(rollbackQueueDepthSamples, queueDepthSampleWriteIndex, 0.95); }
    public double getReconciliationQueueDepthP95() { return calculateIntQuantile(reconciliationQueueDepthSamples, queueDepthSampleWriteIndex, 0.95); }

    /** Read-only view of the reconciliation-queue-depth time series (sample i's virtual time
     * lines up with the i-th write of every other per-step sample array recorded in the same
     * recordHealthSnapshot() call, e.g. blockedQueueDepthSamples). */
    public int[] getReconciliationQueueDepthSamples() {
        return Arrays.copyOf(reconciliationQueueDepthSamples, queueDepthSampleWriteIndex);
    }

    /**
     * Time-weighted fraction of MMT MEMBER-time (Sigma memberCount*dt, parents AND bookkeepers -
     * see the population-consistency note in recordHealthSnapshot()) spent BLOCKED. This is
     * member-time, not transaction-time: a group with several blocked bookkeepers contributes
     * more than a group with one blocked parent. Guarded: returns 0.0 (never NaN) when zero or
     * one health snapshot was recorded, e.g. a degenerate or watchdog-terminated run - a naive
     * in-[0,1] assertion must not silently pass on a divide-by-zero NaN.
     */
    public double getFractionTimeBlocked() {
        return inSystemTimeWeighted <= 0.0 ? 0.0 : blockedTimeWeighted / inSystemTimeWeighted;
    }

    /**
     * Time-weighted fraction of MMT MEMBER-time (parents AND bookkeepers, same population as
     * getFractionTimeBlocked()) spent parked in the waiting room (QueueType.WAITING_FOR_GROUP).
     * Same zero-snapshot guard as getFractionTimeBlocked().
     *
     * Caveat: a member parked in the waiting room is still TransactionState.RUNNING (the group
     * stays one atomic unit under C2PL), so this and getFractionTimeBlocked() are not a clean
     * partition of member-time - don't stack them to sum to 1.
     */
    public double getFractionTimeWaitingRoom() {
        return inSystemTimeWeighted <= 0.0 ? 0.0 : waitingForGroupTimeWeighted / inSystemTimeWeighted;
    }

    public double getInSystemTimeWeighted() { return inSystemTimeWeighted; }

    /** wasted_cpu_io_seconds: IO/CPU time already sunk into a transaction before it was
     * ultimately aborted, recorded right before the member's transitionTo(ABORTED) call. */
    public void recordWastedIoTime(double ioTime) {
        wastedIoTime += Math.max(0.0, ioTime);
    }

    public void recordWastedCpuTime(double cpuTime) {
        wastedCpuTime += Math.max(0.0, cpuTime);
    }

    public double getWastedIoTime() { return wastedIoTime; }
    public double getWastedCpuTime() { return wastedCpuTime; }

    /** lock_seconds_waiting_room: accumulate lockCountHeld * holdDuration at lock release,
     * split by whether the member ever passed through the waiting room.
     *
     * also accumulates the plain (unweighted) lock count, needed to measure
     * ACTUAL instance-level lock footprint per group/attempt instead of the static
     * k_eff_static proxy - this only became complete once the F1 bookkeeper-instrumentation
     * gap was fixed (ACIDAdmissionController.processBookkeeperWithBlockingSemantics()), since
     * before that fix bookkeepers on the "needs additional locks" path never called this method
     * at all and were invisible to both this and the pre-existing weighted sum. */
    public void recordLockHold(double lockCountHeld, double holdDuration, boolean wasInWaitingRoom) {
        double clampedCount = Math.max(0.0, lockCountHeld);
        double weighted = clampedCount * Math.max(0.0, holdDuration);
        totalLockHoldMsWeighted += weighted;
        totalLocksAcquiredCount += clampedCount;
        lockHoldRecordingCount++;
        if (wasInWaitingRoom) {
            waitingRoomLockHoldMsWeighted += weighted;
        }
    }

    public double getTotalLockHoldMsWeighted() { return totalLockHoldMsWeighted; }
    public double getWaitingRoomLockHoldMsWeighted() { return waitingRoomLockHoldMsWeighted; }
    public double getTotalLocksAcquiredCount() { return totalLocksAcquiredCount; }

    public long getLockHoldRecordingCount() { return lockHoldRecordingCount; }

    public void recordCursorProgressAtAbort(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        int bucket = Math.min(9, (int) (clamped * 10));
        cursorProgressAtAbortBuckets[bucket]++;
        cursorProgressAtAbortSamples++;
    }

    /** Ten buckets of width 0.1; index 0 is [0.0,0.1), index 9 is [0.9,1.0]. */
    public long[] getCursorProgressAtAbortBuckets() { return cursorProgressAtAbortBuckets.clone(); }

    public long getCursorProgressAtAbortSamples() { return cursorProgressAtAbortSamples; }

    /** True when aborts land in more than one bucket - the non-degeneracy the ladder checks. */
    public boolean isCursorProgressAtAbortNonDegenerate() {
        int occupied = 0;
        for (long count : cursorProgressAtAbortBuckets) {
            if (count > 0) {
                occupied++;
            }
        }
        return occupied > 1;
    }

    /** Weighted mean lock-hold duration per lock: sum(count_i * duration_i) / sum(count_i).
     * Directly comparable across configs at matched footprint - the "atomic vs pipelined"
     * hypothesis (a 100-lock flat transaction holds all 100 for its full duration; a cascade
     * acquires the same 100 piecewise, each held for a shorter span) predicts this differs by
     * 5-10x between a flat-transaction-dominated config and a cascade-dominated one at equal
     * total lock count. */
    public double getMeanLockHoldDurationPerLock() {
        return totalLocksAcquiredCount <= 0.0 ? 0.0 : totalLockHoldMsWeighted / totalLocksAcquiredCount;
    }

    /** Records one completed group's [admissionTime, completionTime] interval. Silently
     * skips untracked (admissionTime &lt; 0, i.e. the parent never had lock-hold tracking
     * started - shouldn't happen on the completion path but guarded rather than corrupting the
     * sample set) or invalid (completionTime &lt; admissionTime) intervals rather than
     * throwing, since this is diagnostic instrumentation, not a correctness-critical accumulator. */
    public void recordGroupLifetime(long admissionTime, long completionTime) {
        if (admissionTime < 0 || completionTime < admissionTime) {
            return;
        }
        groupLifetimeIntervals.add(new long[] {admissionTime, completionTime});
    }

    /** Kaplan-Meier event observation: group completed at completionTime, exact lifetime
     * known. See recordCensoredGroupLifetime() for the run-end-censored counterpart. */
    public void recordGroupLifetimeObservation(long admissionTime, long completionTime, int maxRetryCount,
            int maxBookkeeperDepth, long pendingQueue, long backoff, long admissionWait, long running,
            long waitingRoom, long runningWasted) {
        if (admissionTime < 0 || completionTime < admissionTime) {
            return;
        }
        groupLifetimeObservations.add(
                new GroupLifetimeObservation(completionTime - admissionTime, true, maxRetryCount,
                        maxBookkeeperDepth, pendingQueue, backoff, admissionWait, running, waitingRoom,
                        runningWasted, admissionTime));
    }

    /** Kaplan-Meier right-censored observation: group was admitted but still open (not
     * completed, not aborted) when the run ended - its true lifetime is >= runEndTime -
     * admissionTime, not exactly known. Called once per still-open group from
     * SimulationRunner.captureOutcome() at run end, not from the main step loop. */
    public void recordCensoredGroupLifetime(long admissionTime, long runEndTime, int maxRetryCount,
            int maxBookkeeperDepth, long pendingQueue, long backoff, long admissionWait, long running,
            long waitingRoom, long runningWasted) {
        if (admissionTime < 0 || runEndTime < admissionTime) {
            return;
        }
        groupLifetimeObservations.add(
                new GroupLifetimeObservation(runEndTime - admissionTime, false, maxRetryCount,
                        maxBookkeeperDepth, pendingQueue, backoff, admissionWait, running, waitingRoom,
                        runningWasted, admissionTime));
    }

    /** Per-phase population-time over the measurement window, indexed by LineagePhase.ordinal(). */
    public double[] getPhaseOccupancy() {
        return phaseOccupancy.clone();
    }

    /** Virtual ticks actually integrated over. The gate is sum(phaseOccupancy) == MPL x this. */
    public long getPhaseOccupancyWindowTicks() {
        return phaseOccupancyWindowTicks;
    }

    public int getPhasePopulationMin() {
        return phasePopulationMin == Integer.MAX_VALUE ? 0 : phasePopulationMin;
    }

    public int getPhasePopulationMax() {
        return phasePopulationMax;
    }

    public List<GroupLifetimeObservation> getGroupLifetimeObservations() {
        return List.copyOf(groupLifetimeObservations);
    }

    /** Defensive copy of every recorded [admissionTime, completionTime] pair - completed
     * groups only, see the field's javadoc for the abort-path blind spot. */
    public long[][] getGroupLifetimeIntervals() {
        return groupLifetimeIntervals.stream().map(long[]::clone).toArray(long[][]::new);
    }

    public double getMeanGroupLifetime() {
        if (groupLifetimeIntervals.isEmpty()) {
            return 0.0;
        }
        long sum = 0;
        for (long[] interval : groupLifetimeIntervals) {
            sum += interval[1] - interval[0];
        }
        return sum / (double) groupLifetimeIntervals.size();
    }

    /** Classic sweep-line interval-overlap computation: +1 at each group's admission, -1 at its
     * completion, sorted by time (ties broken end-before-start so a group ending exactly when
     * another starts isn't double-counted as concurrent). Returns [meanConcurrentGroups,
     * peakConcurrentGroups] - mean is the time-weighted average of the running count, exactly
     * analogous to how frac_blocked etc. integrate population*dt elsewhere in this class, except
     * here every input is a real timestamped event instead of a periodic sample. This is a
     * DIRECT measurement of group concurrency, independent of throughput - see the field javadoc
     * for why that independence is the whole point. */
    public double[] computeGroupConcurrencyStats() {
        int n = groupLifetimeIntervals.size();
        if (n == 0) {
            return new double[] {0.0, 0.0};
        }
        // event: [time, delta, kind] where kind orders ends (0) before starts (1) at equal time
        long[][] events = new long[2 * n][3];
        for (int i = 0; i < n; i++) {
            long[] interval = groupLifetimeIntervals.get(i);
            events[2 * i] = new long[] {interval[0], 1, 1};
            events[2 * i + 1] = new long[] {interval[1], -1, 0};
        }
        Arrays.sort(events, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[2], b[2]));

        int current = 0;
        int peak = 0;
        long lastTime = events[0][0];
        double timeWeightedSum = 0.0;
        for (long[] event : events) {
            timeWeightedSum += current * (double) (event[0] - lastTime);
            current += (int) event[1];
            peak = Math.max(peak, current);
            lastTime = event[0];
        }
        long totalSpan = events[events.length - 1][0] - events[0][0];
        double meanConcurrent = totalSpan <= 0 ? current : timeWeightedSum / totalSpan;
        return new double[] {meanConcurrent, peak};
    }

    /** Time-weighted mean of currentLiveGroups (groupsStartedTotal - groupsCompletedTotal -
     * groupsAbortedTotal), sampled every recordHealthSnapshot() call - a TRUE population count,
     * independent of throughput and inclusive of groups that are open because they're mid-abort/
     * mid-restart, not just ones that will eventually complete. This is the metric that replaces
     * computeGroupConcurrencyStats() for "is there a concurrency ceiling" questions -
     * computeGroupConcurrencyStats() is throughput*lifetime by construction (both scoped to
     * completed groups only) and cannot independently confirm or refute a ceiling. */
    public double getMeanLiveGroupsSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0 : liveGroupsTimeWeighted / lastSnapshotVirtualTime;
    }

    public int getPeakLiveGroupsSampled() {
        return peakLiveGroupsSampled;
    }

    /** Time-weighted mean of TransactionState.RUNNING population, sampled independently via
     * recordHealthSnapshot() - see runningStateTimeWeighted's field javadoc for why this
     * (not attempt-time n_running) is the unbiased signal for "where does achieved concurrency
     * actually sit." Pairs with the pre-existing getPeakRunningStateCount(). */
    public double getMeanRunningStateCountSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0 : runningStateTimeWeighted / lastSnapshotVirtualTime;
    }

    /** Siblings of getMeanRunningStateCountSampled() - see pendingStateTimeWeighted's field
     * javadoc for what each answers in the "where are the other 255?" diagnostic. */
    public double getMeanPendingStateCountSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0 : pendingStateTimeWeighted / lastSnapshotVirtualTime;
    }

    public double getMeanBlockedStateCountSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0 : blockedStateTimeWeighted / lastSnapshotVirtualTime;
    }

    public double getMeanReadyStateCountSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0 : readyStateTimeWeighted / lastSnapshotVirtualTime;
    }

    public double getMeanAwaitingUserActionStateCountSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0
                : awaitingUserActionStateTimeWeighted / lastSnapshotVirtualTime;
    }

    /** Time-weighted mean of AdmissionWaitManager's own waiter registry size, sampled at
     * recordHealthSnapshot() cadence - see admissionWaiterCountTimeWeighted's field javadoc.
     * Pairs with getPeakAdmissionWaiterCount(). This is the population-level number thrashing
     * thresholds are defined against, not blockedStateCountSampled/blockedQueueDepth (both
     * blind to preclaim admission-waiters). */
    public double getMeanAdmissionWaiterCountSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0 : admissionWaiterCountTimeWeighted / lastSnapshotVirtualTime;
    }

    public int getPeakAdmissionWaiterCount() {
        return peakAdmissionWaiterCount;
    }

    /** Time-weighted mean of SS2PLLockManager.getLocksHeld() (H), sampled at recordHealthSnapshot()
     * cadence - see locksHeldCountTimeWeighted's field javadoc. Pairs with getPeakLocksHeldCount().
     * n = this / k gives the achieved-concurrency quantity directly comparable to the Postgres
     * anchor's `SELECT count(*) FROM pg_locks WHERE locktype='advisory'` poll. */
    public double getMeanLocksHeldCountSampled() {
        return lastSnapshotVirtualTime <= 0 ? 0.0 : locksHeldCountTimeWeighted / lastSnapshotVirtualTime;
    }

    public int getPeakLocksHeldCount() {
        return peakLocksHeldCount;
    }

    /**
     * lock_seconds_waiting_room = waitingRoomLockHoldMsWeighted / totalLockHoldMsWeighted,
     * guarded against divide-by-zero (e.g. single-member groups, no bookkeepers).
     *
     * <p><b>KNOWN DEFECT: this metric reads 1.0 by construction.</b> Not a structural bias
     * toward (N-1)/N - that explanation was checked against raw data and falsified. The two
     * weighted sums were dumped at full precision for 12 (R, mpl, seed) combinations spanning
     * MPL=1..200, and every one was BIT-IDENTICAL rather than merely close to 1.0. The cause:
     *
     * setLockHoldStart()/setLockCountHeld() - the two calls that seed this metric's numerator
     * and denominator - are called from exactly one place in the whole codebase:
     * ACIDAdmissionController.tryAdmitWithLockCheck(), which only ever runs for PARENT
     * transactions. Every BookkeeperTransaction is diverted before reaching that call, on both
     * branches of ACIDAdmissionController.processPendingTransaction(): the inherited-locks fast
     * path returns immediately (by design - "parent already paid it"), and the
     * "needs additional locks" branch calls processBookkeeperWithBlockingSemantics(), which
     * calls processor.process(bookkeeper) directly and never reaches tryAdmitWithLockCheck()
     * (this second omission looks like an oversight, not a documented design choice - a
     * bookkeeper on this branch does acquire real additional locks, but that acquisition is
     * never marked, so it is silently excluded here too).
     *
     * Net effect: the ONLY transaction ever counted in this metric, for any group, is the
     * parent - and the parent unconditionally calls moveToWaitingRoom() whenever the group has
     * bookkeepers (ACIDCompletionHandler.completeParent(), unguarded). So whenever
     * totalLockHoldMsWeighted > 0 (i.e. the group has >=1 bookkeeper), the numerator and
     * denominator are the SAME single measurement by construction, and the ratio is exactly
     * 1.0 - not a biased signal, a tautology with zero information content. This is why it
     * reads identically 1.000000 across every MPL, every R level, every seed sampled, including
     * MPL=1 - the "getting closer to 1 as groups get bigger" story doesn't apply, and never did.
     *
     * NOT FIXED in this pass: doing so means instrumenting bookkeepers' actual lock acquisition
     * (likely inside processBookkeeperWithBlockingSemantics() and/or wherever "additional locks"
     * bookkeepers really acquire their locks - not yet located) and is a change to live
     * production admission-control code, not a metrics-side fix. Deliberately deferred rather
     * than rushed before a 20,000-run campaign. Until fixed, this metric carries no information
     * and must not be read as a waiting-room contention signal - use frac_waiting_room
     * (member-time weighted, see getFractionTimeWaitingRoom()) instead, which is unaffected by
     * this defect (it does not use lockHoldStart at all) and IS one of the three primary
     * mechanism channels. This metric is a SECONDARY diagnostic, not one of the three primary
     * channels (frac_blocked / frac_waiting_room / wasted_cpu_io_frac), and is deliberately
     * excluded from the residual_frac decomposition check.
     */
    public double getLockSecondsWaitingRoomFraction() {
        return totalLockHoldMsWeighted <= 0.0 ? 0.0 : waitingRoomLockHoldMsWeighted / totalLockHoldMsWeighted;
    }

    private static double calculateQuantile(double[] sortedSamples, double quantile) {
        if (sortedSamples.length == 0) {
            return 0.0;
        }

        int n = sortedSamples.length;
        int idx = (int) Math.ceil(quantile * n) - 1;
        idx = Math.max(0, Math.min(n - 1, idx));
        return sortedSamples[idx];
    }

    private static double calculateIntQuantile(int[] samples, int count, double quantile) {
        if (count == 0) {
            return 0.0;
        }

        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = samples[i];
        }
        Arrays.sort(values);
        return calculateQuantile(values, quantile);
    }

    public DirtySetStats calculateDirtySetStats() {
        if (sampleWriteIndex == 0) {
            return null;
        }

        int n = sampleWriteIndex;
        double[] dirtyPct = new double[n];
        double sum = 0.0;
        double max = 0.0;

        for (int i = 0; i < n; i++) {
            double pct = (dirtySetDenominatorResources > 0)
                    ? (100.0 * dirtySetSizeSamples[i] / dirtySetDenominatorResources)
                    : 0.0;
            dirtyPct[i] = pct;
            sum += pct;
            max = Math.max(max, pct);

            if (pct < 0.0 || pct > 100.0) {
                throw new IllegalStateException(String.format(
                        "Invalid dirty percentage at sample %d: %.2f%% (dirtySize=%d, dirtySetDenominatorResources=%d)",
                        i, pct, dirtySetSizeSamples[i], dirtySetDenominatorResources));
            }
        }

        double mean = sum / n;
        Arrays.sort(dirtyPct);
        double p50 = calculateQuantile(dirtyPct, 0.50);
        double p95 = calculateQuantile(dirtyPct, 0.95);
        double p99 = calculateQuantile(dirtyPct, 0.99);

        if (max > 100.0) {
            throw new IllegalStateException(String.format(
                    "Dirty set max exceeds 100%%: %.4f (check dirtySetDenominatorResources)", max));
        }
        if (mean < 0 || mean > max) {
            throw new IllegalStateException(String.format(
                    "Dirty set mean out of bounds: mean=%.4f, max=%.4f", mean, max));
        }
        if (!(p50 <= p95 && p95 <= p99 && p99 <= max)) {
            throw new IllegalStateException(String.format(
                    "Dirty set stats violate quantile ordering: p50=%.4f, p95=%.4f, p99=%.4f, max=%.4f",
                    p50, p95, p99, max));
        }

        return new DirtySetStats(n, mean, p50, p95, p99, max);
    }

    /** F5: lock_wait_p50/p95/p99 sample - time between a member entering BLOCKED_TXN (queued for
     * a lock) and successfully acquiring it. See Transaction.lockWaitStart. */
    public void recordLockWait(long wait) {
        if (lockWaitWriteIndex < lockWaitMsSamples.length) {
            lockWaitMsSamples[lockWaitWriteIndex++] = wait;
        } else {
            long[] expanded = new long[lockWaitMsSamples.length * 2];
            System.arraycopy(lockWaitMsSamples, 0, expanded, 0, lockWaitMsSamples.length);
            lockWaitMsSamples = expanded;
            lockWaitMsSamples[lockWaitWriteIndex++] = wait;
        }
    }

    public DistributionStats calculateLockWaitStats() {
        return calculateLongDistribution(lockWaitMsSamples, lockWaitWriteIndex);
    }

    /** F5: restarts_total, incremented by TransactionGroupAbortCoordinator.restartTransaction(). */
    public void incrementRestartsTotal() {
        restartsTotal++;
    }

    public int getRestartsTotal() {
        return restartsTotal;
    }

    /**
     * Incremented by TransactionLockCoordinator.handleLockDenial() every time
     * shouldReleaseOnSoftDeny() fires (partial-lock release on a soft deny, followed by a
     * from-scratch re-acquisition attempt on wake) - see the field comment above.
     */
    public void incrementSoftDenyReleaseCyclesTotal() {
        softDenyReleaseCyclesTotal++;
    }

    public int getSoftDenyReleaseCyclesTotal() {
        return softDenyReleaseCyclesTotal;
    }

    public void recordRecoveryLatency(long latency) {
        if (recoveryLatencyWriteIndex < recoveryLatencySamples.length) {
            recoveryLatencySamples[recoveryLatencyWriteIndex++] = latency;
        } else {
            long[] expanded = new long[recoveryLatencySamples.length * 2];
            System.arraycopy(recoveryLatencySamples, 0, expanded, 0, recoveryLatencySamples.length);
            recoveryLatencySamples = expanded;
            recoveryLatencySamples[recoveryLatencyWriteIndex++] = latency;
        }
    }

    public RecoveryLatencyStats calculateRecoveryLatencyStats() {
        if (recoveryLatencyWriteIndex == 0) {
            return null;
        }

        int n = recoveryLatencyWriteIndex;
        double[] latencies = new double[n];
        double sum = 0.0;
        double max = 0.0;

        for (int i = 0; i < n; i++) {
            latencies[i] = recoveryLatencySamples[i];
            sum += latencies[i];
            max = Math.max(max, latencies[i]);
        }

        Arrays.sort(latencies);

        return new RecoveryLatencyStats(
                n,
                sum / n,
                calculateQuantile(latencies, 0.50),
                calculateQuantile(latencies, 0.95),
                calculateQuantile(latencies, 0.99),
                max);
    }

    public void recordParentTransactionLatency(long latency) {
        if (parentTransactionLatencyWriteIndex >= parentTransactionLatencySamples.length) {
            parentTransactionLatencySamples = Arrays.copyOf(parentTransactionLatencySamples,
                    parentTransactionLatencySamples.length * 2);
        }
        parentTransactionLatencySamples[parentTransactionLatencyWriteIndex++] = latency;
    }

    public void recordCprSynchronizationDelay(long delay) {
        if (cprSynchronizationDelayWriteIndex >= cprSynchronizationDelaySamples.length) {
            cprSynchronizationDelaySamples = Arrays.copyOf(cprSynchronizationDelaySamples,
                    cprSynchronizationDelaySamples.length * 2);
        }
        cprSynchronizationDelaySamples[cprSynchronizationDelayWriteIndex++] = delay;
    }

    public void recordRollback(long rollback) {
        if (rollbackLatencyWriteIndex >= rollbackLatencySamples.length) {
            rollbackLatencySamples = Arrays.copyOf(rollbackLatencySamples,
                    rollbackLatencySamples.length * 2);
        }
        rollbackLatencySamples[rollbackLatencyWriteIndex++] = rollback;
    }

    public void recordCascadeAmplification(double amplification) {
        if (cascadeAmplificationWriteIndex >= cascadeAmplificationSamples.length) {
            cascadeAmplificationSamples = Arrays.copyOf(cascadeAmplificationSamples,
                    cascadeAmplificationSamples.length * 2);
        }
        cascadeAmplificationSamples[cascadeAmplificationWriteIndex++] = amplification;
    }

    public void recordCascadeResourceReferences(int parentResources, int cprResources) {
        cascadeParentResourceReferencesTotal += parentResources;
        cascadeCprResourceReferencesTotal += cprResources;
        cascadeResourceReferenceSamples++;
    }

    public DistributionStats calculateParentTransactionLatencyStats() {
        return calculateLongDistribution(parentTransactionLatencySamples, parentTransactionLatencyWriteIndex);
    }

    public DistributionStats calculateCprSynchronizationDelayStats() {
        return calculateLongDistribution(cprSynchronizationDelaySamples, cprSynchronizationDelayWriteIndex);
    }

    public DistributionStats calculateRollbackLatencyStats() {
        return calculateLongDistribution(rollbackLatencySamples, rollbackLatencyWriteIndex);
    }

    public DistributionStats calculateCascadeAmplificationStats() {
        return calculateDoubleDistribution(cascadeAmplificationSamples, cascadeAmplificationWriteIndex);
    }

    public int getCascadeResourceReferenceSamples() {
        return cascadeResourceReferenceSamples;
    }

    public double getMeanCascadeParentResourceReferences() {
        return cascadeResourceReferenceSamples == 0 ? 0.0
                : cascadeParentResourceReferencesTotal / (double) cascadeResourceReferenceSamples;
    }

    public double getMeanCascadeCprResourceReferences() {
        return cascadeResourceReferenceSamples == 0 ? 0.0
                : cascadeCprResourceReferencesTotal / (double) cascadeResourceReferenceSamples;
    }

    public int getStartedGroupParentResourceReferenceSamples() {
        return startedGroupParentResourceReferenceSamples;
    }

    public double getMeanStartedGroupParentResourceReferences() {
        return startedGroupParentResourceReferenceSamples == 0 ? 0.0
                : startedGroupParentResourceReferencesTotal / (double) startedGroupParentResourceReferenceSamples;
    }

    public int getCompletedGroupResourceReferenceSamples() {
        return completedGroupResourceReferenceSamples;
    }

    public long getCompletedGroupParentResourceReferencesTotal() {
        return completedGroupParentResourceReferencesTotal;
    }

    public long getCompletedGroupCprResourceReferencesTotal() {
        return completedGroupCprResourceReferencesTotal;
    }

    public long getCompletedParentResourceReferencesTotal() {
        return completedParentResourceReferencesTotal;
    }

    public long getCompletedBookkeeperResourceReferencesTotal() {
        return completedBookkeeperResourceReferencesTotal;
    }

    private static DistributionStats calculateLongDistribution(long[] samples, int count) {
        if (count == 0) {
            return new DistributionStats(0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double[] values = new double[count];
        double sum = 0.0;
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            values[i] = samples[i];
            sum += values[i];
            max = Math.max(max, values[i]);
        }
        Arrays.sort(values);
        return new DistributionStats(count, sum / count, calculateQuantile(values, 0.50),
                calculateQuantile(values, 0.95), calculateQuantile(values, 0.99), max);
    }

    private static DistributionStats calculateDoubleDistribution(double[] samples, int count) {
        if (count == 0) {
            return new DistributionStats(0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double[] values = Arrays.copyOf(samples, count);
        double sum = 0.0;
        double max = 0.0;
        for (double value : values) {
            sum += value;
            max = Math.max(max, value);
        }
        Arrays.sort(values);
        return new DistributionStats(count, sum / count, calculateQuantile(values, 0.50),
                calculateQuantile(values, 0.95), calculateQuantile(values, 0.99), max);
    }

    public static class DirtySetStats {
        public final int sampleCount;
        public final double meanPct;
        public final double p50Pct;
        public final double p95Pct;
        public final double p99Pct;
        public final double maxPct;

        public DirtySetStats(int sampleCount, double meanPct, double p50Pct,
                double p95Pct, double p99Pct, double maxPct) {
            this.sampleCount = sampleCount;
            this.meanPct = meanPct;
            this.p50Pct = p50Pct;
            this.p95Pct = p95Pct;
            this.p99Pct = p99Pct;
            this.maxPct = maxPct;
        }

        @Override
        public String toString() {
            return String.format(
                    "DirtySetStats[n=%d, mean=%.2f%%, p50=%.2f%%, p95=%.2f%%, p99=%.2f%%, max=%.2f%%]",
                    sampleCount, meanPct, p50Pct, p95Pct, p99Pct, maxPct);
        }
    }

    /**
     * Recovery latency, in TICKS - one tick = 1 microsecond ({@code SimulatorParameters:19}).
     *
     * <p><b>These are not milliseconds.</b> {@code DirtySetManager.clearDirty} records
     * {@code virtualTime - dirtiedAtTick}, a raw difference of engine timestamps, and nothing
     * converts it anywhere on the way here. Printing this as {@code mean=%.2fms} costs a whole
     * false finding: an N=24 mean reads as 659 ms against a 300 us service, a 2,197x "clearing
     * delay" that is in fact 2.2x.
     *
     * <p>f7a520f named the fix rule and did not apply it - "the boundaries where simulation time
     * meets the real world: config input, CSV output" - while renaming 1130 stems. <b>A rename is
     * not a conversion.</b> The ten CSV headers and seven plot axes it left saying {@code _ms} are
     * now {@code _us}: a relabel, not a rescale, so no collected value moves.
     */
    public static class RecoveryLatencyStats {
        public final int sampleCount;
        public final double mean;
        public final double p50;
        public final double p95;
        public final double p99;
        public final double max;

        public RecoveryLatencyStats(int sampleCount, double mean, double p50,
                double p95, double p99, double max) {
            this.sampleCount = sampleCount;
            this.mean = mean;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.max = max;
        }

        @Override
        public String toString() {
            return String.format(
                    "RecoveryLatencyStats[n=%d, mean=%.2fus, p50=%.2fus, p95=%.2fus, p99=%.2fus, max=%.2fus]",
                    sampleCount, mean, p50, p95, p99, max);
        }
    }

    public static class DistributionStats {
        public final int sampleCount;
        public final double mean;
        public final double p50;
        public final double p95;
        public final double p99;
        public final double max;

        public DistributionStats(int sampleCount, double mean, double p50,
                double p95, double p99, double max) {
            this.sampleCount = sampleCount;
            this.mean = mean;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.max = max;
        }
    }

    // --- eviction re-charge, instrumentation only -------------------------------------------
    // assign() calls preparePhase unconditionally, which resets the phase remainder to its full
    // duration -- so a member evicted mid-phase by blockAll/abort redoes that phase. These
    // record how often and how much, so the effect can be SIZED before anyone decides whether
    // it is worth fixing. Nothing reads them to change behaviour.
    private long blockAllTotal = 0;

    public void incrementBlockAllTotal() {
        blockAllTotal++;
    }

    public long getBlockAllTotal() {
        return blockAllTotal;
    }

    private long evictionRechargeCount = 0;
    private long evictionRechargeTicks = 0;

    public void recordEvictionRecharge(long ticks) {
        evictionRechargeCount++;
        if (ticks > 0) {
            evictionRechargeTicks += ticks;
        }
    }

    public long getEvictionRechargeCount() {
        return evictionRechargeCount;
    }

    public long getEvictionRechargeTicks() {
        return evictionRechargeTicks;
    }
}
