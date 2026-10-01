package test.java.queue;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;

/**
 * Pins {@code groupUnitAffinity} at the one site that reads it.
 *
 * <p>Affinity is a MODELLING CLAIM: a bookkeeper is a continuation of its parent's work on
 * resources the parent already touched, so a group is one unit of execution and its members
 * serialise on one processor. The flag exists because that claim is worth ~41-70% of dynamic-arm
 * throughput and was previously unrecorded in any run output - not because it is a tuning knob.
 *
 * <p>The two routing tests are deliberately opposite assertions on the SAME setup, so neither can
 * pass with the gate removed: delete the {@code groupUnitAffinity} condition in
 * {@code CentralQueueManager.enqueue} and the "off" test fails; hardcode the branch away and the
 * "on" test fails.
 */
public class GroupUnitAffinityTest {

    private static final int UNIT_COUNT = 4;
    private static final int PINNED_UNIT = 2;

    private static IOModel ioModel(boolean groupUnitAffinity) {
        // SimulatorConfig.Builder rather than TestConfigFactory: the legacy 15-arg constructor
        // hardcodes the canonical defaults, so it can only ever produce groupUnitAffinity=true and
        // the "off" arm of this test would be untestable through it.
        return new IOModel(new SimulatorConfig.Builder()
                .modelPool(new ModelFactory(1).createModels(List.of(1)))
                .totalModels(1)
                .totalLocks(1)
                .semanticOverlap(0.0)
                .groupUnitAffinity(groupUnitAffinity)
                .build());
    }

    /** A group already pinned to {@link #PINNED_UNIT}, plus one member of it. */
    private static Transaction pinnedGroupMember(String id) {
        TransactionGroup group = new TransactionGroup();
        group.pinUnitIfAbsent(QueueType.IO, PINNED_UNIT);
        Transaction txn = new Transaction(id, List.of(), group);
        txn.initializeState();
        return txn;
    }

    @Test
    public void affinityOnRoutesAGroupMemberToItsPinnedUnitsOwnQueue() {
        CentralQueueManager q = new CentralQueueManager(ioModel(true));
        q.initPooledQueues(QueueType.IO, UNIT_COUNT);

        q.enqueue(pinnedGroupMember("A"), QueueType.IO);

        Assertions.assertNull(q.dequeue(QueueType.IO),
                "with affinity on the shared pool must be empty - the member bypassed it");
        Transaction own = q.dequeueOwn(QueueType.IO, PINNED_UNIT, 0L, Long.MAX_VALUE);
        Assertions.assertNotNull(own, "member should be on its pinned unit's own queue");
        Assertions.assertEquals("A", own.getId());
    }

    @Test
    public void affinityOffRoutesTheSameMemberToTheSharedPool() {
        CentralQueueManager q = new CentralQueueManager(ioModel(false));
        q.initPooledQueues(QueueType.IO, UNIT_COUNT);

        // Same group, same pin already set - only the flag differs. The pin is still WRITTEN when
        // affinity is off (the gate is on the read), so this also pins that an unread pin has no
        // effect rather than silently half-applying.
        q.enqueue(pinnedGroupMember("A"), QueueType.IO);

        Assertions.assertNull(q.dequeueOwn(QueueType.IO, PINNED_UNIT, 0L, Long.MAX_VALUE),
                "with affinity off the pinned unit's own queue must stay empty");
        Transaction shared = q.dequeue(QueueType.IO);
        Assertions.assertNotNull(shared, "member should be ordinary shared-pool work");
        Assertions.assertEquals("A", shared.getId());
    }

    @Test
    public void anUnpinnedGroupIsSharedPoolWorkUnderBothSettings() {
        for (boolean affinity : new boolean[] { true, false }) {
            CentralQueueManager q = new CentralQueueManager(ioModel(affinity));
            q.initPooledQueues(QueueType.IO, UNIT_COUNT);

            Transaction txn = new Transaction("A", List.of(), new TransactionGroup());
            txn.initializeState();
            q.enqueue(txn, QueueType.IO);

            Assertions.assertNotNull(q.dequeue(QueueType.IO),
                    "an unpinned group has no affinity to honour, affinity=" + affinity);
        }
    }
}
