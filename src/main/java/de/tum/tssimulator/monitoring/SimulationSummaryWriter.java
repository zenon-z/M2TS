package de.tum.tssimulator.monitoring;

import java.util.Locale;

class SimulationSummaryWriter {
    public void writeSummary(SimulationEventSink sink, SimulationMetrics metrics) {
        RuntimeHealthSnapshot health = metrics.getLatestHealthSnapshot();
        sink.writeLine("");
        sink.writeLine(String.format(Locale.ROOT, "{\"summary\":true,\"executed\":%d,\"bookkeepers\":%d,\"aborted\":%d," +
                        "\"reconciliation_bookkeepers\":%d,\"avg_dirty_set_size\":%.2f,\"dirty_set_samples\":%d," +
                        "\"groups_started\":%d,\"groups_completed\":%d,\"groups_aborted\":%d," +
                        "\"bookkeepers_created\":%d,\"bookkeepers_completed\":%d,\"bookkeepers_aborted\":%d," +
                        "\"bookkeepers_terminated\":%d,\"cautious_wait_aborts\":%d,\"deadlock_aborts\":%d,\"other_aborts\":%d," +
                        "\"max_depth_observed\":%d,\"throughput_tps\":%.4f,\"watchdog_failures\":%d,\"last_watchdog_virtual_time_ms\":%d," +
                        "\"queue_pending_latest\":%d,\"queue_blocked_latest\":%d,\"queue_ready_latest\":%d,\"queue_lock_latest\":%d," +
                        "\"queue_io_latest\":%d,\"queue_cpu_latest\":%d,\"queue_reconciliation_latest\":%d," +
                        "\"queue_awaiting_user_action_latest\":%d,\"queue_waiting_for_group_latest\":%d," +
                        "\"state_pending_latest\":%d,\"state_running_latest\":%d,\"state_blocked_latest\":%d,\"state_ready_latest\":%d," +
                        "\"state_awaiting_user_action_latest\":%d,\"state_completed_latest\":%d,\"state_aborted_latest\":%d," +
                        "\"queue_pending_peak\":%d,\"queue_blocked_peak\":%d,\"queue_ready_peak\":%d,\"queue_lock_peak\":%d," +
                        "\"queue_io_peak\":%d,\"queue_cpu_peak\":%d,\"queue_reconciliation_peak\":%d," +
                        "\"queue_awaiting_user_action_peak\":%d,\"queue_waiting_for_group_peak\":%d," +
                        "\"state_pending_peak\":%d,\"state_running_peak\":%d,\"state_blocked_peak\":%d,\"state_ready_peak\":%d," +
                        "\"state_awaiting_user_action_peak\":%d,\"state_completed_peak\":%d,\"state_aborted_peak\":%d}",
                metrics.getTotalExecuted(), metrics.getTotalBookkeepers(), metrics.getTotalAborted(),
                metrics.getTotalReconciliationBookkeepers(), metrics.getAverageDirtySetSize(), metrics.getDirtySetSamples(),
                metrics.getGroupsStartedTotal(), metrics.getGroupsCompletedTotal(), metrics.getGroupsAbortedTotal(),
                metrics.getBookkeepersCreatedTotal(), metrics.getBookkeepersCompletedTotal(), metrics.getBookkeepersAbortedTotal(),
                metrics.getBookkeepersTerminatedTotal(), metrics.getCautiousWaitAborts(), metrics.getDeadlockAborts(),
                metrics.getOtherAborts(), metrics.getMaxDepthObserved(), metrics.getThroughputTransactionsPerSecond(),
                metrics.getWatchdogFailureCount(), metrics.getLastWatchdogVirtualTime(),
                latestOrZero(health, HealthField.PENDING_QUEUE),
                latestOrZero(health, HealthField.BLOCKED_QUEUE),
                latestOrZero(health, HealthField.READY_QUEUE),
                latestOrZero(health, HealthField.LOCK_QUEUE),
                latestOrZero(health, HealthField.IO_QUEUE),
                latestOrZero(health, HealthField.CPU_QUEUE),
                latestOrZero(health, HealthField.RECONCILIATION_QUEUE),
                latestOrZero(health, HealthField.AWAITING_QUEUE),
                latestOrZero(health, HealthField.WAITING_FOR_GROUP_QUEUE),
                latestOrZero(health, HealthField.PENDING_STATE),
                latestOrZero(health, HealthField.RUNNING_STATE),
                latestOrZero(health, HealthField.BLOCKED_STATE),
                latestOrZero(health, HealthField.READY_STATE),
                latestOrZero(health, HealthField.AWAITING_STATE),
                latestOrZero(health, HealthField.COMPLETED_STATE),
                latestOrZero(health, HealthField.ABORTED_STATE),
                metrics.getPeakPendingQueueDepth(),
                metrics.getPeakBlockedQueueDepth(),
                metrics.getPeakReadyQueueDepth(),
                metrics.getPeakLockQueueDepth(),
                metrics.getPeakIoQueueDepth(),
                metrics.getPeakCpuQueueDepth(),
                metrics.getPeakReconciliationQueueDepth(),
                metrics.getPeakAwaitingUserActionQueueDepth(),
                metrics.getPeakWaitingForGroupQueueDepth(),
                metrics.getPeakPendingStateCount(),
                metrics.getPeakRunningStateCount(),
                metrics.getPeakBlockedStateCount(),
                metrics.getPeakReadyStateCount(),
                metrics.getPeakAwaitingUserActionStateCount(),
                metrics.getPeakCompletedStateCount(),
                metrics.getPeakAbortedStateCount()));

        if (metrics.getMaxDepthObserved() > 0) {
            sink.writeLine("");
            sink.writeLine("// Bookkeeper Depth Distribution:");
            for (int d = 1; d <= metrics.getMaxDepthObserved() && d < metrics.getBookkeeperDepthCounts().length; d++) {
                int count = metrics.getBookkeepersAtDepth(d);
                if (count > 0) {
                    sink.writeLine(String.format("//   Depth %d: %d bookkeepers (%.1f%%)",
                            d, count, 100.0 * count / metrics.getBookkeepersCreatedTotal()));
                }
            }
        }
    }

    private int latestOrZero(RuntimeHealthSnapshot health, HealthField field) {
        if (health == null) {
            return 0;
        }
        return switch (field) {
            case PENDING_QUEUE -> health.pendingQueueDepth();
            case BLOCKED_QUEUE -> health.blockedQueueDepth();
            case READY_QUEUE -> health.readyQueueDepth();
            case LOCK_QUEUE -> health.lockQueueDepth();
            case IO_QUEUE -> health.ioQueueDepth();
            case CPU_QUEUE -> health.cpuQueueDepth();
            case RECONCILIATION_QUEUE -> health.reconciliationQueueDepth();
            case AWAITING_QUEUE -> health.awaitingUserActionQueueDepth();
            case WAITING_FOR_GROUP_QUEUE -> health.waitingForGroupQueueDepth();
            case PENDING_STATE -> health.pendingStateCount();
            case RUNNING_STATE -> health.runningStateCount();
            case BLOCKED_STATE -> health.blockedStateCount();
            case READY_STATE -> health.readyStateCount();
            case AWAITING_STATE -> health.awaitingUserActionStateCount();
            case COMPLETED_STATE -> health.completedStateCount();
            case ABORTED_STATE -> health.abortedStateCount();
        };
    }

    private enum HealthField {
        PENDING_QUEUE,
        BLOCKED_QUEUE,
        READY_QUEUE,
        LOCK_QUEUE,
        IO_QUEUE,
        CPU_QUEUE,
        RECONCILIATION_QUEUE,
        AWAITING_QUEUE,
        WAITING_FOR_GROUP_QUEUE,
        PENDING_STATE,
        RUNNING_STATE,
        BLOCKED_STATE,
        READY_STATE,
        AWAITING_STATE,
        COMPLETED_STATE,
        ABORTED_STATE
    }
}
