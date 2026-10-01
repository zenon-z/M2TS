package de.tum.tssimulator.bookkeeping;

import java.util.List;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.program.OperationType;

/**
 * BASE bookkeeper that executes asynchronously on reconciliation processors.
 * Uses BACKGROUND_WORK state for its lifecycle.
 */
class BaseBookkeeperTransaction extends BookkeeperTransaction {

    public BaseBookkeeperTransaction(Transaction parent, List<Resource> resources,
                                     BookkeepingReason reason, Model targetModel,
                                     int index) {
        super(parent, resources, reason, targetModel, index);
        // Constructor leaves in UNINITIALIZED - factory calls initializeState().
    }

    public BaseBookkeeperTransaction(Transaction parent, List<Resource> resources,
                                     BookkeepingReason reason, Model targetModel,
                                     int index, OperationType cuOperationType) {
        super(parent, resources, reason, targetModel, index, cuOperationType);
        // Constructor leaves in UNINITIALIZED - factory calls initializeState().
    }

    @Override
    protected TransactionEvent creationEvent() {
        return TransactionEvent.BACKGROUND_CREATED;
    }

    @Override
    public boolean usesBackgroundWorkLifecycle() {
        return true;
    }
}
