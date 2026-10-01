package de.tum.tssimulator.model;

import java.util.HashSet;
import java.util.Set;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;

public class IOModel {
    // Cost is scoped to the WORK UNIT being executed, which is the whole transaction unless the
    // operation cursor is engaged, in which case it is the batch in flight. IO/CPU are per
    // operation; lock acquisition stays per distinct resource. Under RANDOM_OPERATIONS with the
    // cursor disengaged all three reduce to resources.size() exactly, as before.
    private final SimulatorConfig config;

    public IOModel(SimulatorConfig config) {
        this.config = config;
    }

    /**
     * The config this model was built from. Exposed so CentralQueueManager can read
     * {@code groupUnitAffinity} at the single dispatch site that honours the group pin - the
     * dispatch layer has no SimulatorConfig of its own, and threading one through the processor
     * pools to reach one boolean would be a larger change than the flag warrants.
     */
    public SimulatorConfig getConfig() {
        return config;
    }

    public long getIOTime(Transaction txn) {
        // Bookkeepers scale 1:1 with every other transaction - no special-cased speedup, by
        // experiment design.
        return txn.getCurrentBatchOperationCount() * config.ioTimePerResource;
    }

    public long getCPUTime(Transaction txn) {
        return txn.getCurrentBatchOperationCount() * config.cpuTimePerResource;
    }

    public long getLockTime(Transaction txn) {
        return config.lockIOTime + txn.getResources().size() * config.lockCPUTimePerResource;
    }

    public long getRollbackTime(Transaction txn) {
        Set<Resource> touchedResources = new HashSet<>(txn.getResources());
        if (txn.getGroup() != null) {
            for (Transaction member : txn.getGroup().getAllMembers()) {
                touchedResources.addAll(member.getResources());
            }
        }
        return config.rollbackFixed + touchedResources.size() * config.rollbackCPUTimePerResource;
    }

    public long getTotalTime(Transaction txn) {
        return getIOTime(txn) + getCPUTime(txn) + getLockTime(txn);
    }
    
    /**
     * Calculate lock acquisition time for a transaction.
     * 
     * The time scales with:
     * 1. Number of resources the transaction touches
     * 2. Lock granularity (resources per lock)
     * 3. Number of actual lock objects that need to be acquired
     * 
     * Formula:
     * - lockTime = lockIOTime * numActualLocks + lockCPUTime * numResources
     * 
     * Where numActualLocks depends on granularity:
     * - Fine-grained (10000 locks for 5000 resources): ~1 lock per resource
     * - Coarse-grained (100 locks for 5000 resources): ~50 resources per lock
     * 
     * Note: Lock acquisition is NOT parallelized across processors. The phrase
     * "processors share the work for locking mechanism" in the paper means they
     * share a common lock manager, not that lock time is divided by processor count.
     * Lock acquisition remains a serial bottleneck, which matches the paper's results.
     */
    public long getLockAcquisitionTime(Transaction txn) {
        int numResources = txn.getCurrentBatchDistinctResourceCount();
        
        // Calculate granularity: resources per lock
        double resourcesPerLock = (double) config.totalResources / config.totalLocks;
        
        // Estimate number of unique locks needed for this transaction
        int numActualLocks;
        if (resourcesPerLock >= 1) {
            // Coarse-grained: multiple resources share one lock
            // Example: 5000 resources, 100 locks → 50 resources per lock
            // A transaction with 500 resources needs ~500/50 = 10 locks
            numActualLocks = Math.max(1, (int) Math.ceil(numResources / resourcesPerLock));
        } else {
            // Fine-grained: more locks than resources, so each resource typically gets its own lock
            // Example: 5000 resources, 100000 locks → 0.05 resources per lock
            // A transaction with 500 resources needs at most 500 locks (one per resource)
            // Since there are plenty of locks available, we use numResources directly
            numActualLocks = numResources;
        }
        
        // Lock IO time: cost per lock object acquired
        long lockIOTime = config.lockIOTime * numActualLocks;
        
        // Lock CPU time: cost per resource being locked
        long lockCPUTime = config.lockCPUTimePerResource * numResources;
        
        // Lock acquisition is a serial bottleneck - NOT divided by processor count
        // This matches the paper's experimental results
        return lockIOTime + lockCPUTime;
    }

}
