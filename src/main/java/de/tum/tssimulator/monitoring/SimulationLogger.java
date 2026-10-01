package de.tum.tssimulator.monitoring;

import java.nio.file.Path;

import de.tum.tssimulator.monitoring.DebugConfig;

public class SimulationLogger {
    private final SimulationEventSink sink;
    private final SimulationMetrics metrics;
    private final SimulationSummaryWriter summaryWriter;
    private final LifecycleAccountingReporter lifecycleReporter;

    public SimulationLogger(String filename, int totalResources, int expectedSamples) {
        this(new PrintWriterSimulationEventSink(Path.of(filename)), totalResources, expectedSamples);
    }

    public SimulationLogger(SimulationEventSink sink, int totalResources, int expectedSamples) {
        this(sink, new SimulationMetrics(totalResources, expectedSamples),
                new SimulationSummaryWriter(), new LifecycleAccountingReporter());
    }

    public SimulationLogger(String filename, int totalResources, int dirtySetDenominatorResources, int expectedSamples) {
        this(new PrintWriterSimulationEventSink(Path.of(filename)), totalResources, dirtySetDenominatorResources,
                expectedSamples);
    }

    public SimulationLogger(SimulationEventSink sink, int totalResources, int dirtySetDenominatorResources,
            int expectedSamples) {
        this(sink, new SimulationMetrics(totalResources, dirtySetDenominatorResources, expectedSamples),
                new SimulationSummaryWriter(), new LifecycleAccountingReporter());
    }

    SimulationLogger(SimulationEventSink sink,
            SimulationMetrics metrics,
            SimulationSummaryWriter summaryWriter,
            LifecycleAccountingReporter lifecycleReporter) {
        this.sink = sink;
        this.metrics = metrics;
        this.summaryWriter = summaryWriter;
        this.lifecycleReporter = lifecycleReporter;
    }

    public SimulationLogger(String filename) {
        this(filename, 0, 10000);
    }

    public SimulationMetrics metrics() {
        return metrics;
    }

    public void log(String message) {
        sink.writeLine(message);
    }

    public void logEvent(LogEvent event) {
        // SWITCH EXPRESSION over the enum with NO default: an unhandled LogEvent.Type is a
        // COMPILE ERROR, not a silently dropped line. Under a statement-switch, adding a type to
        // the enum loses it silently -- the event exists, is logged, and is never written.
        String line = switch (event.getType()) {
            case LOCK_GRANTED, LOCK_DENIED, LOCK_REQUESTED, TRANSACTION_COMPLETED, TRANSACTION_ABORTED ->
                String.format("{\"event\":\"%s\",\"transaction\":\"%s\"}",
                        event.getType(), safe(event.getTransactionId()));
            case PHASE_STARTED, PHASE_COMPLETED, PHASE_EVICTED, GROUP_FORCE_BLOCKED,
                 CLEAR_SUPPRESSED_ABORTING,
                 BOOKKEEPER_QUEUED, BOOKKEEPERS_CREATED, DIRTY_MARKED, DIRTY_SKIPPED,
                 DIRTY_CLEARED, CLEARED_FROM_PROCESSORS, WAITING_FOR_GROUP, LOCK_RELEASED ->
                String.format("{\"event\":\"%s\",\"transaction\":\"%s\",\"vt\":%d,\"info\":\"%s\"}",
                        event.getType(), safe(event.getTransactionId()), event.getVirtualTime(),
                        jsonSafe(event.getMessage()));
            case WARNING ->
                String.format("{\"event\":\"WARNING\",\"transaction\":\"%s\",\"vt\":%d}",
                        safe(event.getTransactionId()), event.getVirtualTime());
            case TICK ->
                String.format("{\"event\":\"TICK\",\"vt\":%d}", event.getVirtualTime());
        };
        sink.writeLine(line);
        // The message is already inside `info` for the types above that carry it, so emitting the
        // legacy comment line as well would duplicate it on every PHASE_COMPLETED -- on a log that
        // already reaches 682 MB for one run.
        if (!carriesInfo(event.getType())
                && event.getMessage() != null && !event.getMessage().isEmpty()) {
            sink.writeLine("// " + event.getMessage());
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** Types whose message is carried inside the JSON `info` field. */
    private static boolean carriesInfo(LogEvent.Type t) {
        return t == LogEvent.Type.PHASE_STARTED || t == LogEvent.Type.PHASE_COMPLETED
                || t == LogEvent.Type.PHASE_EVICTED || t == LogEvent.Type.GROUP_FORCE_BLOCKED
                || t == LogEvent.Type.CLEAR_SUPPRESSED_ABORTING
                || t == LogEvent.Type.BOOKKEEPER_QUEUED || t == LogEvent.Type.BOOKKEEPERS_CREATED
                || t == LogEvent.Type.DIRTY_MARKED || t == LogEvent.Type.DIRTY_SKIPPED
                || t == LogEvent.Type.DIRTY_CLEARED
                || t == LogEvent.Type.CLEARED_FROM_PROCESSORS
                || t == LogEvent.Type.WAITING_FOR_GROUP || t == LogEvent.Type.LOCK_RELEASED;
    }

    /**
     * JSON-escapes a value destined for a string field. Without this a message containing a quote
     * or backslash produces a line that json.loads cannot parse -- and every consumer of these
     * logs parses per line.
     */
    private String jsonSafe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    public void incrementExecuted() {
        metrics.incrementExecuted();
    }

    public void incrementBookkeepers() {
        metrics.incrementBookkeepers();
    }

    public void incrementAborted() {
        metrics.incrementAborted();
    }

    public void incrementAwaitingUserAction() {
        metrics.incrementAwaitingUserAction();
    }

    public void incrementGroupsStarted() {
        metrics.incrementGroupsStarted();
    }

    public void incrementGroupsCompleted() {
        metrics.incrementGroupsCompleted();
    }

    public void incrementGroupsAborted() {
        metrics.incrementGroupsAborted();
    }

    public void incrementBookkeepersCreated(int count) {
        metrics.incrementBookkeepersCreated(count);
    }

    public void incrementBookkeepersCompleted() {
        metrics.incrementBookkeepersCompleted();
    }

    public void incrementBookkeepersAborted(int count) {
        metrics.incrementBookkeepersAborted(count);
    }

    public void recordAbortReason(String reason) {
        metrics.recordAbortReason(reason);
    }

    public void addLockTime(long duration) {
        metrics.addLockTime(duration);
    }

    public long getTotalLockTime() {
        return metrics.getTotalLockTime();
    }

    public int getTotalAborted() {
        return metrics.getTotalAborted();
    }

    public int getTotalBookkeepers() {
        return metrics.getTotalBookkeepers();
    }

    public int getTotalAwaitingUserAction() {
        return metrics.getTotalAwaitingUserAction();
    }

    public int getGroupsStartedTotal() {
        return metrics.getGroupsStartedTotal();
    }

    public int getGroupsCompletedTotal() {
        return metrics.getGroupsCompletedTotal();
    }

    public int getGroupsAbortedTotal() {
        return metrics.getGroupsAbortedTotal();
    }

    public int getBookkeepersCreatedTotal() {
        return metrics.getBookkeepersCreatedTotal();
    }

    public int getBookkeepersCompletedTotal() {
        return metrics.getBookkeepersCompletedTotal();
    }

    public int getBookkeepersAbortedTotal() {
        return metrics.getBookkeepersAbortedTotal();
    }

    public int getBookkeepersTerminatedTotal() {
        return metrics.getBookkeepersTerminatedTotal();
    }

    public int getCautiousWaitAborts() {
        return metrics.getCautiousWaitAborts();
    }

    public int getDeadlockAborts() {
        return metrics.getDeadlockAborts();
    }

    public int getOtherAborts() {
        return metrics.getOtherAborts();
    }

    @Deprecated
    public void recordDirtySetSize(int size) {
        metrics.recordDirtySetSize(size);
    }

    public void sampleDirtySetSize(int dirtySetSize) {
        metrics.sampleDirtySetSize(dirtySetSize);
    }

    public void incrementReconciliationBookkeepers() {
        metrics.incrementReconciliationBookkeepers();
    }

    public int getTotalReconciliationBookkeepers() {
        return metrics.getTotalReconciliationBookkeepers();
    }

    public double getAverageDirtySetSize() {
        return metrics.getAverageDirtySetSize();
    }

    public int getDirtySetSamples() {
        return metrics.getDirtySetSamples();
    }

    public int getBaseBookkeepersMissingDirtyKey() {
        return metrics.getBaseBookkeepersMissingDirtyKey();
    }

    public void incrementMissingDirtyKey() {
        metrics.incrementMissingDirtyKey();
    }

    public void recordBookkeeperDepth(int depth) {
        metrics.recordBookkeeperDepth(depth);
    }

    public int[] getBookkeeperDepthCounts() {
        return metrics.getBookkeeperDepthCounts();
    }

    public int getMaxDepthObserved() {
        return metrics.getMaxDepthObserved();
    }

    public int getBookkeepersAtDepth(int depth) {
        return metrics.getBookkeepersAtDepth(depth);
    }

    public int getDirtySetSampleCount() {
        return metrics.getDirtySetSampleCount();
    }

    public DirtySetStats calculateDirtySetStats() {
        SimulationMetrics.DirtySetStats stats = metrics.calculateDirtySetStats();
        if (stats == null) {
            return null;
        }
        return new DirtySetStats(stats.sampleCount, stats.meanPct, stats.p50Pct, stats.p95Pct, stats.p99Pct, stats.maxPct);
    }

    public void recordRecoveryLatency(long latency) {
        metrics.recordRecoveryLatency(latency);
    }

    public RecoveryLatencyStats calculateRecoveryLatencyStats() {
        SimulationMetrics.RecoveryLatencyStats stats = metrics.calculateRecoveryLatencyStats();
        if (stats == null) {
            return null;
        }
        return new RecoveryLatencyStats(stats.sampleCount, stats.mean, stats.p50, stats.p95, stats.p99, stats.max);
    }

    public DistributionStats calculateParentTransactionLatencyStats() {
        return wrap(metrics.calculateParentTransactionLatencyStats());
    }

    public DistributionStats calculateCprSynchronizationDelayStats() {
        return wrap(metrics.calculateCprSynchronizationDelayStats());
    }

    public void recordRollback(long rollback) {
        metrics.recordRollback(rollback);
    }

    public DistributionStats calculateRollbackLatencyStats() {
        return wrap(metrics.calculateRollbackLatencyStats());
    }

    public DistributionStats calculateCascadeAmplificationStats() {
        return wrap(metrics.calculateCascadeAmplificationStats());
    }

    /** F5 (VLDB reviewer response): lock_wait_p50/p95/p99. */
    public DistributionStats calculateLockWaitStats() {
        return wrap(metrics.calculateLockWaitStats());
    }

    public int getCascadeResourceReferenceSamples() {
        return metrics.getCascadeResourceReferenceSamples();
    }

    public int getStartedGroupParentResourceReferenceSamples() {
        return metrics.getStartedGroupParentResourceReferenceSamples();
    }

    public double getMeanCascadeParentResourceReferences() {
        return metrics.getMeanCascadeParentResourceReferences();
    }

    public double getMeanCascadeCprResourceReferences() {
        return metrics.getMeanCascadeCprResourceReferences();
    }

    public double getMeanStartedGroupParentResourceReferences() {
        return metrics.getMeanStartedGroupParentResourceReferences();
    }

    public void recordCompletedGroupResourceReferences(int parentResources, int cprResources) {
        metrics.recordCompletedGroupResourceReferences(parentResources, cprResources);
    }

    public int getCompletedGroupResourceReferenceSamples() {
        return metrics.getCompletedGroupResourceReferenceSamples();
    }

    public long getCompletedGroupParentResourceReferencesTotal() {
        return metrics.getCompletedGroupParentResourceReferencesTotal();
    }

    public long getCompletedGroupCprResourceReferencesTotal() {
        return metrics.getCompletedGroupCprResourceReferencesTotal();
    }

    public void recordCompletedParentResourceReferences(int parentResources) {
        metrics.recordCompletedParentResourceReferences(parentResources);
    }

    public void recordCompletedBookkeeperResourceReferences(int bookkeeperResources) {
        metrics.recordCompletedBookkeeperResourceReferences(bookkeeperResources);
    }

    public long getCompletedParentResourceReferencesTotal() {
        return metrics.getCompletedParentResourceReferencesTotal();
    }

    public long getCompletedBookkeeperResourceReferencesTotal() {
        return metrics.getCompletedBookkeeperResourceReferencesTotal();
    }

    private DistributionStats wrap(SimulationMetrics.DistributionStats stats) {
        return new DistributionStats(stats.sampleCount, stats.mean, stats.p50, stats.p95, stats.p99, stats.max);
    }

    public void summary() {
        summaryWriter.writeSummary(sink, metrics);
        if (DebugConfig.printLifecycleAssertions()) {
            lifecycleReporter.report(metrics);
        }
        sink.close();
    }

    public static class DirtySetStats extends SimulationMetrics.DirtySetStats {
        public DirtySetStats(int sampleCount, double meanPct, double p50Pct,
                double p95Pct, double p99Pct, double maxPct) {
            super(sampleCount, meanPct, p50Pct, p95Pct, p99Pct, maxPct);
        }
    }

    public static class RecoveryLatencyStats extends SimulationMetrics.RecoveryLatencyStats {
        public RecoveryLatencyStats(int sampleCount, double mean, double p50,
                double p95, double p99, double max) {
            super(sampleCount, mean, p50, p95, p99, max);
        }
    }

    public static class DistributionStats extends SimulationMetrics.DistributionStats {
        public DistributionStats(int sampleCount, double mean, double p50,
                double p95, double p99, double max) {
            super(sampleCount, mean, p50, p95, p99, max);
        }
    }
}
