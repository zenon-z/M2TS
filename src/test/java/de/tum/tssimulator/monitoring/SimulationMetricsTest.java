package de.tum.tssimulator.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Time-weighted-average accumulation (Sigma count*dt) in SimulationMetrics.recordHealthSnapshot(),
 * including the divide-by-zero guard for degenerate/watchdog-terminated runs.
 */
class SimulationMetricsTest {

    // Every count here is a *QueueDepth (not *StateCount) - recordHealthSnapshot() deliberately
    // builds both the "blocked" numerator and the "in-system" denominator from queue depths, not
    // TransactionStateCounter-based *StateCount fields: *StateCount excludes bookkeepers
    // (Transaction.countsTowardLimit()/BookkeeperTransaction's override), while
    // waitingForGroupQueueDepth is a raw queue size that includes them, so mixing the two would
    // let frac_waiting_room exceed 1.0 whenever bookkeepers outnumbered parents in the waiting
    // room. Keeping numerator and denominator on the queue-depth population avoids that.
    private static RuntimeHealthSnapshot snapshot(long virtualTime, int blockedQueueDepth,
            int waitingForGroupQueueDepth, int pendingQueueDepth, int cpuQueueDepth, int readyQueueDepth,
            int awaitingUserActionQueueDepth) {
        return new RuntimeHealthSnapshot(
                virtualTime,
                pendingQueueDepth, blockedQueueDepth, readyQueueDepth,
                0, // lockQueueDepth (unused here)
                0, // ioQueueDepth (unused here)
                cpuQueueDepth,
                0, // rollbackQueueDepth (unused here)
                0, // reconciliationQueueDepth (unused here)
                awaitingUserActionQueueDepth,
                waitingForGroupQueueDepth,
                0, 0, 0, 0, 0, // pendingStateCount..awaitingUserActionStateCount (unused here)
                0, 0, // completedStateCount, abortedStateCount (never in-system)
                0, // admissionWaiterCount (unused here)
                0); // locksHeldCount (unused here)
    }

    @Test
    void fractionTimeBlockedMatchesHandComputedWeightedAverageWithNonUniformDtAndAStalledStep() {
        SimulationMetrics metrics = new SimulationMetrics(100, 10);

        // t=0: seeds lastSnapshotVirtualTime, contributes no dt.
        metrics.recordHealthSnapshot(snapshot(0, 0, 0, 2, 3, 0, 0));
        // t=100 (dt=100): blocked=2, waitingForGroup=1 (a distinct queue from cpu/lock/io, so it
        // adds on top rather than double-counting), inSystem=pending(1)+cpu(3)+blocked(2)+waitingForGroup(1)=7.
        metrics.recordHealthSnapshot(snapshot(100, 2, 1, 1, 3, 0, 0));
        // t=100 again (dt=0, a stalled step): must not contribute, regardless of its counts.
        metrics.recordHealthSnapshot(snapshot(100, 5, 5, 5, 5, 5, 5));
        // t=250 (dt=150): blocked=1, waitingForGroup=0, inSystem=cpu(4)+blocked(1)=5.
        metrics.recordHealthSnapshot(snapshot(250, 1, 0, 0, 4, 0, 0));

        double expectedInSystemWeighted = 7.0 * 100 + 5.0 * 150; // = 1450
        double expectedBlockedWeighted = 2.0 * 100 + 1.0 * 150;  // = 350
        double expectedWaitingRoomWeighted = 1.0 * 100 + 0.0 * 150; // = 100

        assertEquals(expectedInSystemWeighted, metrics.getInSystemTimeWeighted(), 1e-9);
        assertEquals(expectedBlockedWeighted / expectedInSystemWeighted, metrics.getFractionTimeBlocked(), 1e-9);
        assertEquals(expectedWaitingRoomWeighted / expectedInSystemWeighted, metrics.getFractionTimeWaitingRoom(),
                1e-9);
    }

    @Test
    void fractionsAreBoundedInZeroOne() {
        SimulationMetrics metrics = new SimulationMetrics(100, 10);
        metrics.recordHealthSnapshot(snapshot(0, 0, 0, 2, 3, 0, 0));
        metrics.recordHealthSnapshot(snapshot(100, 2, 1, 1, 3, 0, 0));
        metrics.recordHealthSnapshot(snapshot(250, 1, 0, 0, 4, 0, 0));

        assertTrue(metrics.getFractionTimeBlocked() >= 0.0 && metrics.getFractionTimeBlocked() <= 1.0);
        assertTrue(metrics.getFractionTimeWaitingRoom() >= 0.0 && metrics.getFractionTimeWaitingRoom() <= 1.0);
    }

    @Test
    void fractionsAreZeroNotNaNWhenAtMostOneSnapshotWasRecorded() {
        SimulationMetrics zeroSnapshots = new SimulationMetrics(100, 10);
        assertEquals(0.0, zeroSnapshots.getFractionTimeBlocked());
        assertEquals(0.0, zeroSnapshots.getFractionTimeWaitingRoom());
        assertFalse(Double.isNaN(zeroSnapshots.getFractionTimeBlocked()));

        SimulationMetrics oneSnapshot = new SimulationMetrics(100, 10);
        oneSnapshot.recordHealthSnapshot(snapshot(0, 3, 2, 1, 1, 0, 0));
        assertEquals(0.0, oneSnapshot.getFractionTimeBlocked(),
                "a single snapshot has no dt to integrate over yet - must not divide by zero");
        assertEquals(0.0, oneSnapshot.getFractionTimeWaitingRoom());
    }

    @Test
    void lockSecondsWaitingRoomFractionIsZeroWithNoLockHoldsRecorded() {
        SimulationMetrics metrics = new SimulationMetrics(100, 10);
        assertEquals(0.0, metrics.getLockSecondsWaitingRoomFraction());

        metrics.recordLockHold(3, 1000, false); // held, but never passed through the waiting room
        assertEquals(0.0, metrics.getLockSecondsWaitingRoomFraction());
    }

    @Test
    void lockSecondsWaitingRoomFractionWeightsByCountTimesHoldDuration() {
        SimulationMetrics metrics = new SimulationMetrics(100, 10);
        metrics.recordLockHold(2, 100, true);  // 200 ms-locks in the waiting room
        metrics.recordLockHold(3, 100, false); // 300 ms-locks not in the waiting room

        assertEquals(200.0 / 500.0, metrics.getLockSecondsWaitingRoomFraction(), 1e-9);
    }
}
