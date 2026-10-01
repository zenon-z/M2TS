package de.tum.tssimulator.locking.coordinator;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * Conservative 2PL: atomic all-or-nothing. The direct requester releases everything it holds on
 * a soft-deny block (matching the original, unconditional behavior this class replaces for
 * C2PL).
 *
 * shouldReleaseForceBlockedMember() returns false: force-blocked group members - as opposed to
 * the direct requester - keep their locks under C2PL. Having them release like the requester does
 * would be more internally consistent, but it breaks three tests (ACIDBookkeeperLockTimingTest,
 * ACIDBookkeeperCreationTimingTest, BookkeeperLockOwnershipTest) with an admission-path phase
 * consistency problem: a released-and-rewoken member re-entering admission resets phase state
 * other code assumes is monotonic. Resolving that for C2PL is separate work from the S2PL
 * behaviour this class exists to provide - see LockingProtocol's javadoc.
 *
 * Note the contrast with S2PLTransactionLockCoordinator, which ALSO returns false here: same
 * return value, opposite reason. There it's the actually-correct hold-and-wait semantics. Here
 * it's "untested alternative reverted, original preserved" - not evidence the two protocols
 * secretly agree on force-blocked-member handling.
 */
public class C2PLTransactionLockCoordinator extends TransactionLockCoordinator {

    public C2PLTransactionLockCoordinator(LockManager lockManager,
            QueueManager queueManager,
            SimulationLogger logger,
            LockGranularityMapper granularityMapper,
            TransactionScheduler scheduler) {
        super(lockManager, queueManager, logger, granularityMapper, scheduler);
    }

    @Override
    protected boolean shouldReleaseOnSoftDeny(Transaction txn) {
        return true;
    }

    @Override
    protected boolean shouldReleaseForceBlockedMember() {
        return false;
    }
}
