package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Transaction;

/**
 * Orchestrates bookkeeper-related operations.
 * Decides whether bookkeepers should be created based on referential action policy.
 */
public class BookkeeperOrchestrator {
    private final SimulatorConfig config;

    // Single constructor with only required dependencies
    public BookkeeperOrchestrator(SimulatorConfig config) {
        this.config = config;
    }
    
    /**
     * Determine whether bookkeepers should be created for this transaction.
     * Enforces ON_RESTRICT policy by preventing bookkeeper creation.
     * 
     * @param parent The parent transaction that would trigger bookkeepers
     * @param bookkeeperCount Number of bookkeepers that would be created
     * @return true if bookkeepers should be created (ON_CASCADE), false otherwise (ON_RESTRICT)
     */
    public boolean shouldCreateBookkeepers(Transaction parent, int bookkeeperCount) {
        // No bookkeepers - nothing to do
        if (bookkeeperCount == 0) {
            return true;
        }

        // ON CASCADE or no config - proceed normally
        if (config == null || config.referentialAction == ReferentialAction.ON_CASCADE) {
            return true;
        }

        // ON RESTRICT - mark transaction group for user action, don't create bookkeepers
        if (parent.getGroup() != null) {
            parent.getGroup().markForUserAction();
        }
        return false;
    }
}
