package de.tum.tssimulator.config;

/**
 * Enumeration of supported concurrency control models.
 */
public enum ConcurrencyModel {
    /**
     * ACID: Atomicity, Consistency, Isolation, Durability
     * Uses Strict Strong 2-Phase Locking (SS2PL)
     */
    ACID,
    
    /**
     * BASE: Basically Available, Soft state, Eventual consistency
     * Uses optimistic concurrency with Last-Write-Wins reconciliation
     */
    BASE
}
