package de.tum.tssimulator.state;

import java.util.List;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Resource;

/**
 * Canonical utility for deriving DirtyKey from BookkeeperTransactions.
 * 
 * Purpose: Prevent key derivation inconsistencies across components
 * (BASEBookkeeperCreator, BASECompletionHandler, etc.)
 * 
 * INVARIANT: Bookkeepers target exactly one resource.
 * If this assumption changes, dirty set tracking must be redesigned.
 */
public class DirtyKeyUtil {
    
    /**
     * CANONICAL IMPLEMENTATION: All dirty set operations must use this.
     * 
     * @param bk The bookkeeper transaction
     * @return DirtyKey for the target resource, or null if invalid
     * @throws IllegalStateException if bookkeeper targets multiple resources
     */
    public static DirtyKey fromBookkeeper(BookkeeperTransaction bk) {
        if (bk == null) {
            return null;
        }
        
        Model targetModel = bk.getTargetModel();
        List<Resource> resources = bk.getResources();
        
        if (targetModel == null || resources == null || resources.isEmpty()) {
            return null;
        }
        
        // INVARIANT CHECK: Enforce single-resource assumption
        if (resources.size() != 1) {
            throw new IllegalStateException(
                "Bookkeeper " + bk.getId() + " has " + resources.size() + 
                " resources (expected exactly 1). If multi-resource bookkeepers " +
                "are needed, dirty set tracking must be redesigned.");
        }
        
        return new DirtyKey(targetModel.getName(), resources.get(0));
    }
}
