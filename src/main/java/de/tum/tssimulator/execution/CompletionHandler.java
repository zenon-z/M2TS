package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;

/**
 * Handles transaction completion with mode-specific semantics.
 * 
 * Implementations provide ACID vs BASE completion strategies:
 * - ACID: Parent waits for all bookkeepers (atomic group completion)
 * - BASE: Parent completes immediately (eventual consistency)
 */
public interface CompletionHandler {
    /**
     * Complete a parent transaction.
     * 
     * @param parent Parent transaction to complete
     * @param orchestrator Bookkeeper orchestrator for ON_RESTRICT handling
     * @return true if transaction completed, false if waiting
     */
    boolean completeParent(Transaction parent, BookkeeperOrchestrator orchestrator);
    
    /**
     * Complete a bookkeeper transaction.
     * 
     * @param bookkeeper Bookkeeper transaction to complete
     * @return true if bookkeeper completed, false if waiting
     */
    boolean completeBookkeeper(BookkeeperTransaction bookkeeper);
}
