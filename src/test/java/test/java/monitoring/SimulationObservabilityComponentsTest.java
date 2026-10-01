package test.java.monitoring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.monitoring.RuntimeHealthSnapshot;
import de.tum.tssimulator.monitoring.SimulationEventSink;
import de.tum.tssimulator.monitoring.SimulationLogger;

class SimulationObservabilityComponentsTest {

    @Test
    void summaryUsesMetricsApiAndWritesStructuredSummary() {
        CapturingSink sink = new CapturingSink();
        SimulationLogger logger = new SimulationLogger(sink, 100, 10);

        logger.metrics().incrementExecuted();
        logger.metrics().incrementBookkeepers();
        logger.metrics().incrementAborted();
        logger.metrics().incrementGroupsStarted();
        logger.metrics().incrementGroupsCompleted();
        logger.metrics().incrementBookkeepersCreated(4);
        logger.metrics().incrementBookkeepersCompleted();
        logger.metrics().recordAbortReason("deadlock");
        logger.metrics().recordBookkeeperDepth(2);
        logger.metrics().recordHealthSnapshot(new RuntimeHealthSnapshot(
                2000L, 3, 2, 1, 0, 4, 5, 0, 0, 1, 2,
                6, 7, 2, 1, 1, 9, 1, 0, 0));
        logger.metrics().recordWatchdogFailure(2000L);

        logger.summary();

        String output = String.join("\n", sink.lines);
        assertTrue(output.contains("\"summary\":true"));
        assertTrue(output.contains("\"executed\":1"));
        assertTrue(output.contains("\"bookkeepers\":1"));
        assertTrue(output.contains("\"deadlock_aborts\":1"));
        // 2000 TICKS of virtual time, and a tick is a microsecond, so the window is 0.002s and
        // one executed transaction is 500/s.
        //
        // NOT 0.5000, which is 1 / (2000/1000) and encodes a millisecond-as-second conversion.
        // An assertion written that way is not wrong about the code - it pins the defect, which is
        // how such a defect survives a green suite. See SimulationMetricsUnitsTest for the gate
        // that anchors the unit against configured seconds rather than against another metric.
        assertTrue(output.contains("\"throughput_tps\":500.0000"),
                "throughput_tps must be 500.0000 (1 txn / 2000 ticks = 1 / 0.002s); a value of "
                        + "0.5000 means virtual time is being divided by 1000 rather than 1_000_000");
        assertTrue(output.contains("\"watchdog_failures\":1"));
        assertTrue(output.contains("\"queue_pending_latest\":3"));
        assertTrue(output.contains("\"state_running_latest\":"));
        assertTrue(output.contains("\"queue_cpu_peak\":"));
        assertTrue(output.contains("\"state_completed_peak\":"));
        assertTrue(output.contains("// Bookkeeper Depth Distribution:"));
        assertTrue(output.contains("Depth 2: 1 bookkeepers"));
        assertTrue(sink.closed);
    }

    private static class CapturingSink implements SimulationEventSink {
        private final List<String> lines = new ArrayList<>();
        private boolean closed;

        @Override
        public void writeLine(String line) {
            lines.add(line);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
