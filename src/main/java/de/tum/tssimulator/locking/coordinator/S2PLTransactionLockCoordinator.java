package de.tum.tssimulator.locking.coordinator;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * Strict/Strong 2PL: claim-as-needed, hold-and-wait. Nothing is ever released on a soft deny -
 * neither the direct requester nor a force-blocked group member - they block while continuing to
 * hold everything already acquired. See LockingProtocol's javadoc for why the previous single
 * shared implementation did NOT actually give this - a txn.getPhase()==LOCK guard released the
 * direct requester's partial holdings regardless of protocol, and since acquisition happens
 * entirely within LOCK phase for a transaction with no bookkeeper cascade (e.g. the S2PL-oracle
 * arm), hold-and-wait never applied to it at all.
 *
 * shouldReleaseForceBlockedMember() returning false here happens to match
 * C2PLTransactionLockCoordinator's value, but not its reason: C2PL kept false only because the
 * more-consistent alternative broke tests (see that class's comment). Here false is the actual
 * hold-and-wait contract - a force-blocked member is exactly as entitled to keep what it holds as
 * the direct requester is under shouldReleaseOnSoftDeny() above. Do not "fix" this to true.
 */
public class S2PLTransactionLockCoordinator extends TransactionLockCoordinator {

    /** Operations whose locks are acquired before execution starts. 0 = the whole footprint. */
    private final int operationBatchSize;

    public S2PLTransactionLockCoordinator(LockManager lockManager,
            QueueManager queueManager,
            SimulationLogger logger,
            LockGranularityMapper granularityMapper,
            TransactionScheduler scheduler) {
        this(lockManager, queueManager, logger, granularityMapper, scheduler, 0);
    }

    public S2PLTransactionLockCoordinator(LockManager lockManager,
            QueueManager queueManager,
            SimulationLogger logger,
            LockGranularityMapper granularityMapper,
            TransactionScheduler scheduler,
            int operationBatchSize) {
        super(lockManager, queueManager, logger, granularityMapper, scheduler);
        this.operationBatchSize = operationBatchSize;
    }

    /**
     * Acquires the next {@code operationBatchSize} operations' locks, or the whole footprint when
     * that is 0.
     *
     * <p><b>{@code b = 0} DELEGATES, it does not simulate.</b> The early return calls
     * {@link #acquireAllLocks} literally, rather than running one whole-footprint batch through the
     * cursor code. Without that, rung 0 of the validation ladder would exercise the new path and its
     * bit-identity check would be testing the new code against itself instead of against the old
     * behaviour. With it, rung-0 identity is structural - and because the cursor is never engaged,
     * {@code hasMoreOperations()} stays false and the completion hook never fires either.
     */
    @Override
    public boolean acquireBatchLocks(Transaction txn) {
        if (operationBatchSize <= 0) {
            return acquireAllLocks(txn);
        }
        return acquireOperations(txn, txn.takeNextBatch(operationBatchSize));
    }

    @Override
    protected boolean shouldReleaseOnSoftDeny(Transaction txn) {
        return false;
    }

    @Override
    protected boolean shouldReleaseForceBlockedMember() {
        return false;
    }
}
