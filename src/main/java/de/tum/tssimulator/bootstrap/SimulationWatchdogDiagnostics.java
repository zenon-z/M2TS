package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;

public record SimulationWatchdogDiagnostics(
        long virtualTime,
        int maxStepsWithoutProgress,
        int pendingCount,
        int runningCount,
        int blockedCount,
        int readyCount,
        int abortedCount,
        int completedCount,
        int pendingQueueSize,
        int blockedQueueSize,
        int readyQueueSize,
        int lockQueueSize,
        int ioQueueSize,
        int cpuQueueSize,
        int waitingForGroupQueueSize,
        int abortedBackoffQueueSize,
        String classification,
        String analysis) {
    public static SimulationWatchdogDiagnostics capture(SimulatorContext ctx, long virtualTime,
            int currentCompleted, int currentAborted, int maxStepsWithoutProgress) {
        var queues = ctx.scheduling().queues();
        int pending = TransactionStateCounter.getCount(TransactionState.PENDING);
        int running = TransactionStateCounter.getCount(TransactionState.RUNNING);
        int blocked = TransactionStateCounter.getCount(TransactionState.BLOCKED);
        int ready = TransactionStateCounter.getCount(TransactionState.READY);

        int abortedBackoffQueueSize = 0;
        if (queues instanceof CentralQueueManager centralQueueManager) {
            abortedBackoffQueueSize = centralQueueManager.abortedBackoffSize();
        }

        String classification = classify(running, blocked);
        return new SimulationWatchdogDiagnostics(
                virtualTime,
                maxStepsWithoutProgress,
                pending,
                running,
                blocked,
                ready,
                currentAborted,
                currentCompleted,
                queues.size(QueueType.PENDING),
                queues.size(QueueType.BLOCKED_TXN),
                queues.size(QueueType.READY_TXN),
                queues.size(QueueType.LOCK),
                queues.size(QueueType.IO),
                queues.size(QueueType.CPU),
                queues.size(QueueType.WAITING_FOR_GROUP),
                abortedBackoffQueueSize,
                classification,
                analysisFor(classification, blocked, running));
    }

    public String exceptionMessage() {
        return "Hang detected after " + maxStepsWithoutProgress + " steps without progress at virtual time "
                + virtualTime;
    }

    public String structuredLogLine() {
        return String.format(
                "{\"event\":\"WATCHDOG_FAILURE\",\"vt\":%d,\"max_steps_without_progress\":%d,"
                        + "\"state_counts\":{\"PENDING\":%d,\"RUNNING\":%d,\"BLOCKED\":%d,\"READY\":%d,\"ABORTED\":%d,\"COMPLETED\":%d},"
                        + "\"queue_sizes\":{\"PENDING\":%d,\"BLOCKED_TXN\":%d,\"READY_TXN\":%d,\"LOCK\":%d,\"IO\":%d,\"CPU\":%d,\"WAITING_FOR_GROUP\":%d,\"ABORTED_BACKOFF\":%d},"
                        + "\"classification\":\"%s\",\"analysis\":\"%s\"}",
                virtualTime,
                maxStepsWithoutProgress,
                pendingCount,
                runningCount,
                blockedCount,
                readyCount,
                abortedCount,
                completedCount,
                pendingQueueSize,
                blockedQueueSize,
                readyQueueSize,
                lockQueueSize,
                ioQueueSize,
                cpuQueueSize,
                waitingForGroupQueueSize,
                abortedBackoffQueueSize,
                escape(classification),
                escape(analysis));
    }

    private static String classify(int runningCount, int blockedCount) {
        if (blockedCount > 0 && runningCount == 0) {
            return "CLASSIC_DEADLOCK";
        }
        if (runningCount > 0 && blockedCount > 0) {
            return "LIVELOCK_OR_STARVATION";
        }
        return "UNCLASSIFIED";
    }

    private static String analysisFor(String classification, int blockedCount, int runningCount) {
        return switch (classification) {
            case "CLASSIC_DEADLOCK" ->
                blockedCount + " blocked transactions with no running transaction holding progress";
            case "LIVELOCK_OR_STARVATION" ->
                runningCount + " running and " + blockedCount + " blocked transactions with no terminal progress";
            default -> "No clear deadlock pattern detected";
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
