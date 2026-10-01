package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;

interface GroupCompletionPolicy {
    boolean completeBookkeeper(BookkeeperTransaction bookkeeper);

    boolean completeTransaction(Transaction parent, BookkeeperOrchestrator orchestrator);
}
