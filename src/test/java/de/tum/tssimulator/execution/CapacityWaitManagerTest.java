package de.tum.tssimulator.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;

/**
 * CapacityWaitManager.wakeNext() batch-drain behavior (k = admissionBatchSize).
 */
class CapacityWaitManagerTest {

    /**
     * Records every group wakeNext() hands off, instead of exercising the full FSM/queue
     * machinery those methods normally drive - admitReadyTransactionGroup/
     * enqueuePendingTransactionGroup are overridden so the test stays at the
     * CapacityWaitManager/dispatcher contract, not the whole admission pipeline.
     */
    private static class RecordingAdmissionController extends AbstractAdmissionController {
        final List<TransactionGroup> readyWakes = new ArrayList<>();
        final List<TransactionGroup> pendingWakes = new ArrayList<>();
        Consumer<TransactionGroup> onReadyWake = group -> { };
        Consumer<TransactionGroup> onPendingWake = group -> { };

        RecordingAdmissionController() {
            super(null, null, null, null, null, 0, null);
        }

        @Override
        public void admitReadyTransactionGroup(TransactionGroup group) {
            readyWakes.add(group);
            onReadyWake.accept(group);
        }

        @Override
        public void enqueuePendingTransactionGroup(TransactionGroup group) {
            pendingWakes.add(group);
            onPendingWake.accept(group);
        }

        @Override
        protected long processPendingTransaction(Transaction txn) {
            return 0;
        }
    }

    private static TransactionGroup newGroup() {
        TransactionGroup group = new TransactionGroup();
        Transaction dummyTxn = new Transaction(
                "dummy", 0L, Collections.emptyList(), group, -1, Collections.emptyList(), null);
        group.setParent(dummyTxn);
        return group;
    }

    @Test
    void wakeNextDrainsExactlyMinKAndWaiters() {
        RecordingAdmissionController controller = new RecordingAdmissionController();
        CapacityWaitManager manager = controller.getCapacityWaitManager();
        manager.setAdmissionBatchSize(3);

        List<TransactionGroup> groups = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TransactionGroup group = newGroup();
            groups.add(group);
            manager.registerReadyWaiter(group);
        }
        assertEquals(5, manager.size());

        manager.wakeNext();
        assertEquals(3, controller.readyWakes.size(), "first wakeNext() should drain min(k, waiters) = 3");
        assertEquals(2, manager.size());

        manager.wakeNext();
        assertEquals(5, controller.readyWakes.size(), "second wakeNext() should drain the remaining 2");
        assertEquals(0, manager.size());
    }

    @Test
    void readyWaitersAreDrainedBeforePendingWaiters() {
        RecordingAdmissionController controller = new RecordingAdmissionController();
        CapacityWaitManager manager = controller.getCapacityWaitManager();
        manager.setAdmissionBatchSize(3);

        manager.registerReadyWaiter(newGroup());
        manager.registerReadyWaiter(newGroup());
        manager.registerPendingWaiter(newGroup());
        manager.registerPendingWaiter(newGroup());
        assertEquals(4, manager.size());

        manager.wakeNext();

        assertEquals(2, controller.readyWakes.size(), "both ready waiters must be drained first");
        assertEquals(1, controller.pendingWakes.size(), "remaining batch budget spills into pending");
        assertEquals(1, manager.size(), "one pending waiter should remain unwoken");
    }

    @Test
    void registeringTheSameGroupTwiceDoesNotDoubleQueueIt() {
        RecordingAdmissionController controller = new RecordingAdmissionController();
        CapacityWaitManager manager = controller.getCapacityWaitManager();
        TransactionGroup group = newGroup();

        manager.registerReadyWaiter(group);
        manager.registerReadyWaiter(group);
        assertEquals(1, manager.size(), "re-registering the same group instance must not duplicate the entry");

        manager.setAdmissionBatchSize(5);
        manager.wakeNext();
        assertEquals(1, controller.readyWakes.size(), "group should be woken exactly once, not once per registration");
    }

    @Test
    void wakeNextIsSafeAcrossRepeatedCallsWithPartiallyDrainedQueues() {
        RecordingAdmissionController controller = new RecordingAdmissionController();
        CapacityWaitManager manager = controller.getCapacityWaitManager();
        manager.setAdmissionBatchSize(2);

        for (int i = 0; i < 3; i++) {
            manager.registerReadyWaiter(newGroup());
        }

        assertDoesNotThrow(manager::wakeNext); // drains 2, 1 left
        assertEquals(1, manager.size());
        assertDoesNotThrow(manager::wakeNext); // drains the last 1
        assertEquals(0, manager.size());
        assertDoesNotThrow(manager::wakeNext); // no-op on empty queues
        assertEquals(0, manager.size());
        assertEquals(3, controller.readyWakes.size());
    }

    @Test
    void admissionBatchSizeBelowOneIsRejected() {
        RecordingAdmissionController controller = new RecordingAdmissionController();
        CapacityWaitManager manager = controller.getCapacityWaitManager();
        assertThrows(IllegalArgumentException.class, () -> manager.setAdmissionBatchSize(0));
    }

    /**
     * The scenario k is meant to test: a group woken by wakeNext() that immediately fails its
     * next capacity check again (e.g. maxConcurrent already exhausted by other admissions in
     * the same step) must flow back through the normal admission path and re-register as a
     * waiter - not be lost, not be double-registered.
     */
    @Test
    void reFailedGroupFlowsBackAndReregistersWithoutLossOrDuplication() {
        RecordingAdmissionController controller = new RecordingAdmissionController();
        CapacityWaitManager manager = controller.getCapacityWaitManager();
        manager.setAdmissionBatchSize(1);

        TransactionGroup group = newGroup();
        manager.registerReadyWaiter(group);

        // Simulate the group immediately re-failing admission and being requeued as a pending
        // waiter, exactly once - this mirrors what the real admission controller does when the
        // capacity check fails again right after being woken.
        controller.onReadyWake = woken -> manager.registerPendingWaiter(woken);

        manager.wakeNext();
        assertEquals(1, controller.readyWakes.size(), "group must be woken once, not lost");
        assertTrue(controller.readyWakes.contains(group));
        assertEquals(1, manager.size(), "re-failed group must reappear as a waiter, not vanish");

        // Stop re-registering, so this second wake actually lets the group through.
        controller.onPendingWake = woken -> { };
        manager.wakeNext();
        assertEquals(1, controller.pendingWakes.size(), "requeued group must eventually be woken again");
        assertTrue(controller.pendingWakes.contains(group));
        assertEquals(0, manager.size(), "group must not remain double-registered after finally being admitted");
    }
}
