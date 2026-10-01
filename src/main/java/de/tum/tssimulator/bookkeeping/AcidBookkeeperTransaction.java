package de.tum.tssimulator.bookkeeping;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.program.OperationType;
import java.util.List;

/**
 * ACID bookkeeper that executes synchronously on parent processor.
 * Uses normal PENDING → RUNNING state flow.
 */
class AcidBookkeeperTransaction extends BookkeeperTransaction {
    
    public AcidBookkeeperTransaction(Transaction parent, List<Resource> resources, 
                                     BookkeepingReason reason, Model targetModel, 
                                     int index) {
        super(parent, resources, reason, targetModel, index);
        // Constructor leaves in UNINITIALIZED - factory calls initializeState()
    }

    public AcidBookkeeperTransaction(Transaction parent, List<Resource> resources, 
                                     BookkeepingReason reason, Model targetModel, 
                                     int index, OperationType cuOperationType) {
        super(parent, resources, reason, targetModel, index, cuOperationType);
        // Constructor leaves in UNINITIALIZED - factory calls initializeState()
    }

    @Override
    protected TransactionEvent creationEvent() {
        // ACID bookkeepers use normal CREATED event → PENDING state
        return TransactionEvent.CREATED;
    }
}
