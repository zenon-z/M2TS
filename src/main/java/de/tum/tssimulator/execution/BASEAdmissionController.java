package de.tum.tssimulator.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.Scheduler;

/**
 * BASE admission controller implementing optimistic admission.
 * 
 * BASE Mode Characteristics:
 * - No lock checking: Transactions admitted immediately without locks
 * - Optimistic execution: Conflicts detected during execution phase
 * - Simpler admission: Always admit if capacity available
 * - Reconciliation: Uses bookkeepers for eventual consistency
 * 
 * Admission Flow:
 * 1. Check capacity only (no locks)
 * 2. Call BASEStrategy.process() to initialize transaction
 * 3. BASEStrategy handles bookkeeper creation and queueing
 */
public class BASEAdmissionController extends AbstractAdmissionController {
    private static final Logger LOGGER = LoggerFactory.getLogger(BASEAdmissionController.class);

    public BASEAdmissionController(Scheduler scheduler,
                                   TransactionProcessor processor,
                                   IOModel ioModel,
                                   SimulationLogger logger,
                                   PhaseDispatcher downstreamPhases,
                                   int maxConcurrent,
                                   QueueManager queueManager) {
        super(scheduler, processor, ioModel, logger, downstreamPhases, maxConcurrent, queueManager);
    }

    @Override
    protected long processPendingTransaction(Transaction txn) {
        int currentRunning = TransactionStateCounter.getCount(TransactionState.RUNNING);
        
        // BOOKKEEPERS: Skip ALL admission checks - they're part of parent's atomic unit
        if (txn instanceof BookkeeperTransaction) {
            BookkeeperTransaction bk = (BookkeeperTransaction) txn;
            if (bk.getRoot().getState() == TransactionState.RUNNING) {
                // Bookkeeper inherits parent's state - no capacity check needed
                boolean success = processor.process(txn);
                if (dispatchStats != null) {
                    dispatchStats.transactionsAdmitted++;
                }
                return 0;  // No lock time for bookkeepers
            } else {
                LOGGER.warn("Bookkeeper {} root is not RUNNING; admission is stuck", txn.getId());
            }
        }
        
        // Check capacity before admission
        if (!canAdmitPendingTransaction(txn, currentRunning)) {
            capacityWaitManager.registerPendingWaiter(txn.getGroup());
            if (dispatchStats != null) dispatchStats.recordNoWorkReason("CAPACITY_LIMIT");
            return -1;  // Signal to stop processing
        }
        
        // BASE: Always admit immediately - no locks needed
        boolean success = processor.process(txn);
        if (dispatchStats != null) {
            dispatchStats.transactionsAdmitted++;
            dispatchStats.parentsAdmitted++;
        }
        
        // BASE always returns true, no lock time
        return 0;
    }
}
