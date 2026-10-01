package de.tum.tssimulator.experiments;

import de.tum.tssimulator.monitoring.DebugConfig;
import de.tum.tssimulator.monitoring.DispatchStats;
import de.tum.tssimulator.monitoring.SimulationLogger;
import java.util.Locale;

/**
 * Container for experiment results that can be easily exported to CSV.
 */
public class ExperimentResult {
    // Independent variables (what we varied)
    public final String experimentName;
    public final int totalModels;
    public final int totalResources;
    public final int numberOfIoProcessors;
    public final int numberOfCpuProcessors;
    public final int maxConcurrentLocks;
    public final int initialTransactions;
    public final int simulationDurationSeconds;
    
    // Dependent variables (what we measured)
    public final long totalSimulatedIOTime;
    public final long totalSimulatedCPUTime;
    public final long totalSimulatedLockTime;
    public final long completedTransactions;
    public final long wallClockTime;
    public final int finalPendingCount;
    public final int finalBlockedCount;
    public final int totalAbortedGroups;
    public final int totalBookkeepers;
    public final int abortBackoffQueueSize;
    public final int waitingForGroupCount;
    public final int awaitingUserActionCount;
    public final int lockQueueSize;
    public final int ioQueueSize;
    public final int cpuQueueSize;
    public final int reconciliationQueueSize;
    
    // Debug fields (null if tracking disabled)
    public final DispatchStats dispatchStats;
    
    // Bookkeeper depth distribution
    public final int maxDepthObserved;
    public final int depth1Count;
    public final int depth2Count;
    public final int depth3Count;
    public final int depth4Count;
    public final int depth5Plus;

    public final int groupsStartedTotal;
    public final int groupsCompletedTotal;
    public final int bookkeepersCreatedTotal;
    public final int bookkeepersCompletedTotal;
    public final int bookkeepersAbortedTotal;
    public final int bookkeepersTerminatedTotal;
    public final int peakPendingQueueDepth;
    public final int peakBlockedQueueDepth;
    public final int peakReadyQueueDepth;
    public final int peakLockQueueDepth;
    public final int peakIoQueueDepth;
    public final int peakCpuQueueDepth;
    public final int peakRollbackQueueDepth;
    public final int peakWaitingForGroupQueueDepth;
    public final int queueDepthSampleCount;
    public final double p95PendingQueueDepth;
    public final double p95BlockedQueueDepth;
    public final double p95LockQueueDepth;
    public final double p95WaitingForGroupQueueDepth;
    public final double p95RollbackQueueDepth;
    public final int peakPendingStateCount;
    public final int peakRunningStateCount;
    public final int peakBlockedStateCount;
    public final int peakReadyStateCount;
    public final int peakCompletedStateCount;
    public final int parentLatencySampleCount;
    public final double parentLatencyMean;
    public final double parentLatencyP50;
    public final double parentLatencyP95;
    public final double parentLatencyP99;
    public final double parentLatencyMax;
    public final int cprSyncDelaySampleCount;
    public final double cprSyncDelayMean;
    public final double cprSyncDelayP50;
    public final double cprSyncDelayP95;
    public final double cprSyncDelayP99;
    public final double cprSyncDelayMax;
    public final int rollbackLatencySampleCount;
    public final double rollbackLatencyMean;
    public final double rollbackLatencyP50;
    public final double rollbackLatencyP95;
    public final double rollbackLatencyP99;
    public final double rollbackLatencyMax;
    public final int cascadeAmplificationSampleCount;
    public final double cascadeAmplificationMean;
    public final double cascadeAmplificationP50;
    public final double cascadeAmplificationP95;
    public final double cascadeAmplificationP99;
    public final double cascadeAmplificationMax;
    public final int cascadeResourceReferenceSampleCount;
    public final double meanCascadeParentResourceReferences;
    public final double meanCascadeCprResourceReferences;
    public final int startedGroupParentResourceReferenceSampleCount;
    public final double meanStartedGroupParentResourceReferences;
    public final int completedGroupResourceReferenceSampleCount;
    public final long completedGroupParentResourceReferencesTotal;
    public final long completedGroupCprResourceReferencesTotal;
    public final long completedGroupTotalOperations;
    public final long completedParentResourceReferencesTotal;
    public final long completedBookkeeperResourceReferencesTotal;
    public final long completedTotalOperations;
    
    // Dirty set statistics (BASE only, all values in % of totalResources)
    // Measures post-reconciliation inconsistency (sampled after dispatcher.dispatch())
    public final int dirtySetSampleCount;       // Number of samples (should = sim duration)
    public final double dirtyPostReconMeanPct;  // Mean inconsistency level
    public final double dirtyPostReconP50Pct;   // Median (50th percentile)
    public final double dirtyPostReconP95Pct;   // 95th percentile
    public final double dirtyPostReconP99Pct;   // 99th percentile (SLO metric)
    public final double dirtyPostReconMaxPct;   // Maximum observed
    
    // Recovery latency statistics (BASE only, measures time from dirty→clean in ms)
    // Event-based measurement: when clearDirty() is called, compute latency from markDirty()
    public final int recoveryLatencySampleCount;   // Number of recovery events
    public final double recoveryLatencyMean;     // Mean recovery time
    public final double recoveryLatencyP50;      // Median recovery time
    public final double recoveryLatencyP95;      // 95th percentile
    public final double recoveryLatencyP99;      // 99th percentile
    public final double recoveryLatencyMax;      // Maximum recovery time
    public final int dirtyAtSimEndCount;           // Resources still dirty when simulation ends
    
    // Derived metrics
    public final double avgIOTime;
    public final double avgCPUTime;
    public final double avgLockTime;
    public final double throughput;
    
    public ExperimentResult(String experimentName, int totalModels, int totalResources,
                          int numberOfIoProcessors, int numberOfCpuProcessors, int maxConcurrentLocks,
                          int initialTransactions, int simulationDurationSeconds,
                          long totalSimulatedIOTime, long totalSimulatedCPUTime,
                          long totalSimulatedLockTime, long completedTransactions, long wallClockTime,
                          int finalPendingCount, int finalBlockedCount, int totalAbortedGroups, int totalBookkeepers,
                          int abortBackoffQueueSize,
                          int waitingForGroupCount, int awaitingUserActionCount,
                          int lockQueueSize, int ioQueueSize, int cpuQueueSize, int reconciliationQueueSize,
                          DispatchStats dispatchStats,
                          int maxDepthObserved, int depth1, int depth2, int depth3, int depth4, int depth5Plus,
                          SimulationLogger.DirtySetStats dirtySetStats,
                          SimulationLogger.RecoveryLatencyStats recoveryLatencyStats,
                          int dirtyAtSimEnd) {
        this(experimentName, totalModels, totalResources, numberOfIoProcessors, numberOfCpuProcessors,
                maxConcurrentLocks, initialTransactions, simulationDurationSeconds,
                totalSimulatedIOTime, totalSimulatedCPUTime, totalSimulatedLockTime,
                completedTransactions, wallClockTime, finalPendingCount, finalBlockedCount,
                totalAbortedGroups, totalBookkeepers, abortBackoffQueueSize, waitingForGroupCount,
                awaitingUserActionCount, lockQueueSize, ioQueueSize, cpuQueueSize, reconciliationQueueSize,
                dispatchStats, maxDepthObserved, depth1, depth2, depth3, depth4, depth5Plus,
                0, 0, totalBookkeepers, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                null, null, null, null,
                0, 0.0, 0.0, 0, 0.0,
                0, 0L, 0L, 0L, 0L,
                dirtySetStats, recoveryLatencyStats, dirtyAtSimEnd);
    }

    public ExperimentResult(String experimentName, int totalModels, int totalResources,
                          int numberOfIoProcessors, int numberOfCpuProcessors, int maxConcurrentLocks,
                          int initialTransactions, int simulationDurationSeconds,
                          long totalSimulatedIOTime, long totalSimulatedCPUTime,
                          long totalSimulatedLockTime, long completedTransactions, long wallClockTime,
                          int finalPendingCount, int finalBlockedCount, int totalAbortedGroups, int totalBookkeepers,
                          int abortBackoffSize,
                          int waitingForGroupCount, int awaitingUserActionCount,
                          int lockQueueSize, int ioQueueSize, int cpuQueueSize, int reconciliationQueueSize,
                          DispatchStats dispatchStats,
                          int maxDepthObserved, int depth1, int depth2, int depth3, int depth4, int depth5Plus,
                          int groupsStartedTotal, int groupsCompletedTotal,
                          int bookkeepersCreatedTotal, int bookkeepersCompletedTotal,
                          int bookkeepersAbortedTotal, int bookkeepersTerminatedTotal,
                          int peakPendingQueueDepth, int peakBlockedQueueDepth, int peakReadyQueueDepth,
                          int peakLockQueueDepth, int peakIoQueueDepth, int peakCpuQueueDepth,
                          int peakRollbackQueueDepth, int peakWaitingForGroupQueueDepth,
                          int queueDepthSampleCount, double p95PendingQueueDepth,
                          double p95BlockedQueueDepth, double p95LockQueueDepth,
                          double p95WaitingForGroupQueueDepth, double p95RollbackQueueDepth,
                          int peakPendingStateCount, int peakRunningStateCount, int peakBlockedStateCount,
                          int peakReadyStateCount, int peakCompletedStateCount,
                          SimulationLogger.DistributionStats parentLatencyStats,
                          SimulationLogger.DistributionStats cprSyncDelayStats,
                          SimulationLogger.DistributionStats rollbackLatencyStats,
                          SimulationLogger.DistributionStats cascadeAmplificationStats,
                          int cascadeResourceReferenceSampleCount,
                          double meanCascadeParentResourceReferences,
                          double meanCascadeCprResourceReferences,
                          int startedGroupParentResourceReferenceSampleCount,
                          double meanStartedGroupParentResourceReferences,
                          int completedGroupResourceReferenceSampleCount,
                          long completedGroupParentResourceReferencesTotal,
                          long completedGroupCprResourceReferencesTotal,
                          long completedParentResourceReferencesTotal,
                          long completedBookkeeperResourceReferencesTotal,
                          SimulationLogger.DirtySetStats dirtySetStats,
                          SimulationLogger.RecoveryLatencyStats recoveryLatencyStats,
                          int dirtyAtSimEnd) {
        this.experimentName = experimentName;
        this.totalModels = totalModels;
        this.totalResources = totalResources;
        this.numberOfIoProcessors = numberOfIoProcessors;
        this.numberOfCpuProcessors = numberOfCpuProcessors;
        this.maxConcurrentLocks = maxConcurrentLocks;
        this.initialTransactions = initialTransactions;
        this.simulationDurationSeconds = simulationDurationSeconds;
        this.totalSimulatedIOTime = totalSimulatedIOTime;
        this.totalSimulatedCPUTime = totalSimulatedCPUTime;
        this.totalSimulatedLockTime = totalSimulatedLockTime;
        this.completedTransactions = completedTransactions;
        this.wallClockTime = wallClockTime;
        this.finalPendingCount = finalPendingCount;
        this.finalBlockedCount = finalBlockedCount;
        this.totalAbortedGroups = totalAbortedGroups;
        this.totalBookkeepers = totalBookkeepers;
        this.abortBackoffQueueSize = abortBackoffSize;
        this.waitingForGroupCount = waitingForGroupCount;
        this.awaitingUserActionCount = awaitingUserActionCount;
        this.lockQueueSize = lockQueueSize;
        this.ioQueueSize = ioQueueSize;
        this.cpuQueueSize = cpuQueueSize;
        this.reconciliationQueueSize = reconciliationQueueSize;
        this.dispatchStats = dispatchStats;
        this.maxDepthObserved = maxDepthObserved;
        this.depth1Count = depth1;
        this.depth2Count = depth2;
        this.depth3Count = depth3;
        this.depth4Count = depth4;
        this.depth5Plus = depth5Plus;
        this.groupsStartedTotal = groupsStartedTotal;
        this.groupsCompletedTotal = groupsCompletedTotal;
        this.bookkeepersCreatedTotal = bookkeepersCreatedTotal;
        this.bookkeepersCompletedTotal = bookkeepersCompletedTotal;
        this.bookkeepersAbortedTotal = bookkeepersAbortedTotal;
        this.bookkeepersTerminatedTotal = bookkeepersTerminatedTotal;
        this.peakPendingQueueDepth = peakPendingQueueDepth;
        this.peakBlockedQueueDepth = peakBlockedQueueDepth;
        this.peakReadyQueueDepth = peakReadyQueueDepth;
        this.peakLockQueueDepth = peakLockQueueDepth;
        this.peakIoQueueDepth = peakIoQueueDepth;
        this.peakCpuQueueDepth = peakCpuQueueDepth;
        this.peakRollbackQueueDepth = peakRollbackQueueDepth;
        this.peakWaitingForGroupQueueDepth = peakWaitingForGroupQueueDepth;
        this.queueDepthSampleCount = queueDepthSampleCount;
        this.p95PendingQueueDepth = p95PendingQueueDepth;
        this.p95BlockedQueueDepth = p95BlockedQueueDepth;
        this.p95LockQueueDepth = p95LockQueueDepth;
        this.p95WaitingForGroupQueueDepth = p95WaitingForGroupQueueDepth;
        this.p95RollbackQueueDepth = p95RollbackQueueDepth;
        this.peakPendingStateCount = peakPendingStateCount;
        this.peakRunningStateCount = peakRunningStateCount;
        this.peakBlockedStateCount = peakBlockedStateCount;
        this.peakReadyStateCount = peakReadyStateCount;
        this.peakCompletedStateCount = peakCompletedStateCount;

        this.parentLatencySampleCount = parentLatencyStats == null ? 0 : parentLatencyStats.sampleCount;
        this.parentLatencyMean = parentLatencyStats == null ? 0.0 : parentLatencyStats.mean;
        this.parentLatencyP50 = parentLatencyStats == null ? 0.0 : parentLatencyStats.p50;
        this.parentLatencyP95 = parentLatencyStats == null ? 0.0 : parentLatencyStats.p95;
        this.parentLatencyP99 = parentLatencyStats == null ? 0.0 : parentLatencyStats.p99;
        this.parentLatencyMax = parentLatencyStats == null ? 0.0 : parentLatencyStats.max;
        this.cprSyncDelaySampleCount = cprSyncDelayStats == null ? 0 : cprSyncDelayStats.sampleCount;
        this.cprSyncDelayMean = cprSyncDelayStats == null ? 0.0 : cprSyncDelayStats.mean;
        this.cprSyncDelayP50 = cprSyncDelayStats == null ? 0.0 : cprSyncDelayStats.p50;
        this.cprSyncDelayP95 = cprSyncDelayStats == null ? 0.0 : cprSyncDelayStats.p95;
        this.cprSyncDelayP99 = cprSyncDelayStats == null ? 0.0 : cprSyncDelayStats.p99;
        this.cprSyncDelayMax = cprSyncDelayStats == null ? 0.0 : cprSyncDelayStats.max;
        this.rollbackLatencySampleCount = rollbackLatencyStats == null ? 0 : rollbackLatencyStats.sampleCount;
        this.rollbackLatencyMean = rollbackLatencyStats == null ? 0.0 : rollbackLatencyStats.mean;
        this.rollbackLatencyP50 = rollbackLatencyStats == null ? 0.0 : rollbackLatencyStats.p50;
        this.rollbackLatencyP95 = rollbackLatencyStats == null ? 0.0 : rollbackLatencyStats.p95;
        this.rollbackLatencyP99 = rollbackLatencyStats == null ? 0.0 : rollbackLatencyStats.p99;
        this.rollbackLatencyMax = rollbackLatencyStats == null ? 0.0 : rollbackLatencyStats.max;
        this.cascadeAmplificationSampleCount = cascadeAmplificationStats == null ? 0 : cascadeAmplificationStats.sampleCount;
        this.cascadeAmplificationMean = cascadeAmplificationStats == null ? 0.0 : cascadeAmplificationStats.mean;
        this.cascadeAmplificationP50 = cascadeAmplificationStats == null ? 0.0 : cascadeAmplificationStats.p50;
        this.cascadeAmplificationP95 = cascadeAmplificationStats == null ? 0.0 : cascadeAmplificationStats.p95;
        this.cascadeAmplificationP99 = cascadeAmplificationStats == null ? 0.0 : cascadeAmplificationStats.p99;
        this.cascadeAmplificationMax = cascadeAmplificationStats == null ? 0.0 : cascadeAmplificationStats.max;
        this.cascadeResourceReferenceSampleCount = cascadeResourceReferenceSampleCount;
        this.meanCascadeParentResourceReferences = meanCascadeParentResourceReferences;
        this.meanCascadeCprResourceReferences = meanCascadeCprResourceReferences;
        this.startedGroupParentResourceReferenceSampleCount = startedGroupParentResourceReferenceSampleCount;
        this.meanStartedGroupParentResourceReferences = meanStartedGroupParentResourceReferences;
        this.completedGroupResourceReferenceSampleCount = completedGroupResourceReferenceSampleCount;
        this.completedGroupParentResourceReferencesTotal = completedGroupParentResourceReferencesTotal;
        this.completedGroupCprResourceReferencesTotal = completedGroupCprResourceReferencesTotal;
        this.completedGroupTotalOperations =
                completedGroupParentResourceReferencesTotal + completedGroupCprResourceReferencesTotal;
        this.completedParentResourceReferencesTotal = completedParentResourceReferencesTotal;
        this.completedBookkeeperResourceReferencesTotal = completedBookkeeperResourceReferencesTotal;
        this.completedTotalOperations =
                completedParentResourceReferencesTotal + completedBookkeeperResourceReferencesTotal;
        
        // Assign dirty set statistics
        if (dirtySetStats != null) {
            this.dirtySetSampleCount = dirtySetStats.sampleCount;
            this.dirtyPostReconMeanPct = dirtySetStats.meanPct;
            this.dirtyPostReconP50Pct = dirtySetStats.p50Pct;
            this.dirtyPostReconP95Pct = dirtySetStats.p95Pct;
            this.dirtyPostReconP99Pct = dirtySetStats.p99Pct;
            this.dirtyPostReconMaxPct = dirtySetStats.maxPct;
        } else {
            // ACID mode or error - no dirty set tracking
            this.dirtySetSampleCount = 0;
            this.dirtyPostReconMeanPct = 0.0;
            this.dirtyPostReconP50Pct = 0.0;
            this.dirtyPostReconP95Pct = 0.0;
            this.dirtyPostReconP99Pct = 0.0;
            this.dirtyPostReconMaxPct = 0.0;
        }
        
        // Assign recovery latency statistics
        if (recoveryLatencyStats != null) {
            this.recoveryLatencySampleCount = recoveryLatencyStats.sampleCount;
            this.recoveryLatencyMean = recoveryLatencyStats.mean;
            this.recoveryLatencyP50 = recoveryLatencyStats.p50;
            this.recoveryLatencyP95 = recoveryLatencyStats.p95;
            this.recoveryLatencyP99 = recoveryLatencyStats.p99;
            this.recoveryLatencyMax = recoveryLatencyStats.max;
        } else {
            // ACID mode or no recovery events
            this.recoveryLatencySampleCount = 0;
            this.recoveryLatencyMean = 0.0;
            this.recoveryLatencyP50 = 0.0;
            this.recoveryLatencyP95 = 0.0;
            this.recoveryLatencyP99 = 0.0;
            this.recoveryLatencyMax = 0.0;
        }
        
        this.dirtyAtSimEndCount = dirtyAtSimEnd;
        
        // Calculate derived metrics
        if (completedTransactions > 0) {
            this.avgIOTime = (double) totalSimulatedIOTime / completedTransactions;
            this.avgCPUTime = (double) totalSimulatedCPUTime / completedTransactions;
            this.avgLockTime = (double) totalSimulatedLockTime / completedTransactions;
            
            // Calculate throughput based on simulation duration (elapsed time in the simulation)
            // NOT the sum of transaction execution times (which assumes sequential execution)
            // The simulation runs for exactly simulationDurationSeconds with parallel execution
            this.throughput = (completedTransactions * 1.0) / simulationDurationSeconds;
        } else {
            this.avgIOTime = 0;
            this.avgCPUTime = 0;
            this.avgLockTime = 0;
            this.throughput = 0;
        }
    }
    
    /**
     * Get CSV header row
     */
    public static String getCSVHeader() {
        String base = "experiment,models,resources,io_procs,cpu_procs,lock_budget,init_txns,sim_duration_s," +
               "total_io_ms,total_cpu_ms,total_lock_ms,completed,wall_clock_ms," +
               "pending,blocked,aborted_groups,bookkeepers," +
               "abort_backoff,waiting_for_group,awaiting_user_action," +
               "lock_queue,io_queue,cpu_queue,reconciliation_queue," +
               "avg_io_ms,avg_cpu_ms,avg_lock_ms,throughput_tps," +
               "max_depth,depth1,depth2,depth3,depth4,depth5plus," +
               "dirty_sample_count,dirty_post_recon_mean_pct,dirty_post_recon_p50_pct," +
               "dirty_post_recon_p95_pct,dirty_post_recon_p99_pct,dirty_post_recon_max_pct," +
               "recovery_latency_sample_count,recovery_latency_mean_us,recovery_latency_p50_us," +
               "recovery_latency_p95_us,recovery_latency_p99_us,recovery_latency_max_us,dirty_at_sim_end_count";
        
        if (DebugConfig.ENABLE_STALL_TRACKING) {
            base += ",dispatches_total,dispatches_with_work,dispatches_no_work," +
                    "no_work_backoff,no_work_waiting,no_work_admission_failed,no_work_capacity," +
                    "txn_admitted,txn_blocked,txn_backed_off," +
                    "runnable_queue_avg,runnable_queue_min,runnable_queue_max";
        }
        
        return base;
    }
    
    /**
     * Convert result to CSV row
     * Uses US locale to ensure period (.) as decimal separator for CSV compatibility
     */
    public String toCSV() {
        String base = String.format(Locale.US, 
            "\"%s\",%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d," +
            "%.2f,%.2f,%.2f,%.6f,%d,%d,%d,%d,%d,%d," +
            "%d,%.4f,%.4f,%.4f,%.4f,%.4f," +
            "%d,%.2f,%.2f,%.2f,%.2f,%.2f,%d",
            experimentName, totalModels, totalResources, numberOfIoProcessors, numberOfCpuProcessors,
            maxConcurrentLocks, initialTransactions, simulationDurationSeconds,
            totalSimulatedIOTime, totalSimulatedCPUTime,
            totalSimulatedLockTime, completedTransactions, wallClockTime, finalPendingCount,
            finalBlockedCount, totalAbortedGroups, totalBookkeepers,
            abortBackoffQueueSize, waitingForGroupCount, awaitingUserActionCount,
            lockQueueSize, ioQueueSize, cpuQueueSize, reconciliationQueueSize,
            avgIOTime, avgCPUTime, avgLockTime, throughput,
            maxDepthObserved, depth1Count, depth2Count, depth3Count, depth4Count, depth5Plus,
            dirtySetSampleCount, dirtyPostReconMeanPct, dirtyPostReconP50Pct,
            dirtyPostReconP95Pct, dirtyPostReconP99Pct, dirtyPostReconMaxPct,
            recoveryLatencySampleCount, recoveryLatencyMean, recoveryLatencyP50,
            recoveryLatencyP95, recoveryLatencyP99, recoveryLatencyMax, dirtyAtSimEndCount);
        
        if (DebugConfig.ENABLE_STALL_TRACKING && dispatchStats != null) {
            base += String.format(Locale.US,
                ",%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%d,%d",
                dispatchStats.totalDispatchCalls,
                dispatchStats.dispatchesWithWork,
                dispatchStats.dispatchesNoWork,
                dispatchStats.noWorkBackoffNotDue,
                dispatchStats.noWorkWaitingForGroup,
                dispatchStats.noWorkAdmissionFailed,
                dispatchStats.noWorkCapacityLimit,
                dispatchStats.transactionsAdmitted,
                dispatchStats.transactionsBlocked,
                dispatchStats.transactionsBackedOff,
                dispatchStats.getAvgRunnableQueueSize(),
                dispatchStats.getMinRunnableQueueSize(),
                dispatchStats.maxRunnableQueueSize);
        }
        
        return base;
    }
    
    @Override
    public String toString() {
        return String.format(
            "[%s] Models=%d, Completed=%d, Throughput=%.2f tps, AvgLock=%.2f ms",
            experimentName, totalModels, completedTransactions, throughput, avgLockTime
        );
    }
}
