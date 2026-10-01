package de.tum.tssimulator.execution;

import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.TransactionGroupRegistry;

abstract class AbstractGroupCompletionPolicy implements GroupCompletionPolicy {
    protected final TransactionProcessor processor;
    protected final TransactionLifecyclePort lifecycle;
    protected final TransactionGroupRegistry registry;

    protected AbstractGroupCompletionPolicy(
            TransactionProcessor processor,
            TransactionLifecyclePort lifecycle,
            TransactionGroupRegistry registry) {
        this.processor = processor;
        this.lifecycle = lifecycle;
        this.registry = registry;
    }

    protected void assertAtCompletePhase(Transaction txn, String kind) {
        if (txn.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                    kind + " " + txn.getId()
                            + " not at COMPLETE phase. Current phase: "
                            + txn.getPhase());
        }
    }

    protected boolean completeOnRestrictTransaction(Transaction parent) {
        processor.release(parent);
        parent.transitionTo(TransactionEvent.AWAITING_USER_ACTION);
        lifecycle.moveToAwaitingUserAction(parent);
        return true;
    }

    protected boolean completeSingleTransaction(Transaction txn, TransactionGroup group) {
        if (txn.getState() == TransactionState.AWAITING_USER_ACTION) {
            return false;
        }

        assertAtCompletePhase(txn, "Transaction");
        processor.release(txn);
        lifecycle.complete(txn);

        if (group != null) {
            SimulationLogger logger = lifecycle.getLogger();
            if (logger != null) {
                logger.metrics().incrementGroupsCompleted();
            }
            registry.clear(group);
        }

        return true;
    }
}
