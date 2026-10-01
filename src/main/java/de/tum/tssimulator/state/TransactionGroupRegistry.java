package de.tum.tssimulator.state;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.execution.PhaseDispatcherGroup;
import de.tum.tssimulator.monitoring.SimulationLogger;

public class TransactionGroupRegistry {
    private final Map<Transaction, TransactionGroup> txnToGroup = new ConcurrentHashMap<>();
    private final Map<BookkeeperTransaction, TransactionGroup> bkToGroup = new ConcurrentHashMap<>();
    private final SimulationLogger logger;
    private PhaseDispatcherGroup dispatcher;

    public TransactionGroupRegistry() {
        this.logger = null;  // Backward compatibility
    }
    
    public TransactionGroupRegistry(SimulationLogger logger) {
        this.logger = logger;
    }
    
    /**
     * Inject the phase dispatcher for clearing transactions from processors.
     * Called during simulator initialization.
     */
    public void setDispatcher(PhaseDispatcherGroup dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void register(TransactionGroup group) {
        // Allow early registration; skip until parent is set
        if (group.getParent() != null) {
            txnToGroup.put(group.getParent(), group);
        }
    }

    public void registerParent(TransactionGroup group, Transaction parent) {
        group.setParent(parent);
        
        // Inject dispatcher for clearing transactions from processors
        if (dispatcher != null) {
            group.setDispatcher(dispatcher);
        }
        
        // IDEMPOTENT PER GROUP. groupsStartedTotal counts GROUPS, so a group already known here
        // must not be counted again - and under BASE it was, 1170 times against 100 real parents.
        //
        // BookkeeperCreationCoordinator.getOrCreateGroup() re-registers the ROOT PARENT whenever a
        // cascade CU's registry lookup misses, which under BASE is every CU: measured 1170 of 1270
        // registrations, all from a BookkeeperTransaction, against 0 under ACID. That inflated
        // currentOpenGroups() to 1170 against a target of 100, so TransactionPoolManager saw a
        // permanent surplus, never topped the population up, and the run drained and stalled.
        //
        // Fixed HERE rather than in currentOpenGroups: compensating at one consumer would leave the
        // counter wrong for every other reader of groupsStartedTotal.
        //
        // WHY the BASE lookup misses is a separate question - whether BASE bookkeepers belong in
        // the registry at all is a design decision ("each bookkeeper completes independently, no
        // groups"), not a counting one. This fixes the count; that decision is still open.
        boolean alreadyKnown = txnToGroup.containsValue(group);
        txnToGroup.put(parent, group);

        // Track group creation for lifecycle accounting
        if (logger != null && !alreadyKnown) {
            logger.metrics().incrementGroupsStarted();
            logger.metrics().recordStartedGroupParentResources(parent.getResources().size());
            // Realised-workload observability. Here rather than at generation because the
            // access counter is over ADMITTED operations, and here rather than at execution
            // because a per-operation hook would count a retrying transaction repeatedly and bias
            // the realised distribution toward transactions that struggle.
            long writes = parent.getOperations().stream()
                    .filter(op -> op.getType() == OperationType.WRITE)
                    .count();
            logger.metrics().recordAdmittedWorkload(parent.getResources(), writes,
                    parent.isReadOnly());
        }
    }

    public void registerBookkeepers(TransactionGroup group, List<BookkeeperTransaction> bks) {
        for (BookkeeperTransaction bk : bks) {
            bkToGroup.put(bk, group);
        }
    }

    public TransactionGroup get(Transaction txn) {
        return txnToGroup.get(txn);
    }

    public Collection<TransactionGroup> getActiveGroups() {
        return txnToGroup.values();
    }

    public TransactionGroup get(BookkeeperTransaction bk) {
        return bkToGroup.get(bk);
    }

    public void clear(TransactionGroup group) {
        if (group.getParent() != null) {
            txnToGroup.remove(group.getParent());
        }
        for (BookkeeperTransaction bk : group.getBookkeepers()) {
            bkToGroup.remove(bk);
        }
    }
}
