package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.state.TransactionGroupRegistry;

class BaseGroupCompletionPolicy extends AbstractGroupCompletionPolicy {
    BaseGroupCompletionPolicy(
            TransactionProcessor processor,
            TransactionLifecyclePort lifecycle,
            TransactionGroupRegistry registry) {
        super(processor, lifecycle, registry);
    }

    @Override
    public boolean completeBookkeeper(BookkeeperTransaction bookkeeper) {
        assertAtCompletePhase(bookkeeper, "Bookkeeper transaction");

        processor.createAndQueueBookkeepers(bookkeeper);
        processor.release(bookkeeper);
        assertAtCompletePhase(bookkeeper, "Bookkeeper");
        lifecycle.complete(bookkeeper);
        lifecycle.getLogger().metrics().incrementBookkeepersCompleted();
        return true;
    }

    @Override
    public boolean completeTransaction(Transaction parent, BookkeeperOrchestrator orchestrator) {
        assertAtCompletePhase(parent, "Parent transaction");
        assert parent.getPhase() == ExecutionPhase.COMPLETE
                : "Assertion failed: Parent " + parent.getId() + " not at COMPLETE phase";

        TransactionGroup group = registry.get(parent);
        if (group != null && group.isMarkedForUserAction()) {
            return completeOnRestrictTransaction(parent);
        }

        boolean hasBookkeepers = (group != null && !group.getBookkeepers().isEmpty());
        if (!hasBookkeepers) {
            processor.createAndQueueBookkeepers(parent);
            group = registry.get(parent);

            if (group != null && group.isMarkedForUserAction()) {
                return completeOnRestrictTransaction(parent);
            }
        }

        if (group == null || group.getBookkeepers().isEmpty()) {
            return completeSingleTransaction(parent, group);
        }

        processor.release(parent);
        lifecycle.complete(parent);
        return true;
    }
}
