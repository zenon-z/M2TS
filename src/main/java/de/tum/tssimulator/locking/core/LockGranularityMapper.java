package de.tum.tssimulator.locking.core;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Resource;

/**
 * Maps resources to lock IDs based on totalLocks.
 *
 * The granularity (resources per lock) is computed as: ceil(totalResources / totalLocks)
 *
 * Examples:
 * - totalLocks = totalResources: Fine-grained (each resource gets its own lock)
 * - totalLocks = 1000: Medium-grained (totalResources/1000 resources per lock)
 * - totalLocks = 1: Coarse-grained (all resources share 1 global lock)
 *
 * <p>Resources no longer carry a dense global sequential number (a {@code Resource} is an
 * {@code (instanceId, property)} coordinate - {@code instanceId} repeats across every model and
 * property). {@link #globalOrdinal(Resource)} reconstructs the same contiguous-per-model-block
 * ordinal by composing {@link Model#localOrdinal(Resource)} with a per-model offset computed
 * once at construction, rather than re-deriving the ordering a second time.
 */
public class LockGranularityMapper {
    private final int totalLocks;
    private final int totalResources;
    private final String strategy;
    private final Map<Model, Integer> offsets; // one entry per model (~few), not per resource

    /**
     * @param totalLocks Total number of locks in the system (determines granularity)
     * @param modelPool The full model pool, in the order resources are considered contiguous
     * @param strategy "modulo" (consecutive grouping) or "hash" (distributed grouping)
     */
    public LockGranularityMapper(int totalLocks, List<Model> modelPool, String strategy) {
        if (totalLocks < 1) {
            throw new IllegalArgumentException("totalLocks must be >= 1");
        }
        if (modelPool == null || modelPool.isEmpty()) {
            throw new IllegalArgumentException("modelPool must not be empty");
        }
        this.totalLocks = totalLocks;
        this.strategy = strategy != null ? strategy : "modulo";

        Map<Model, Integer> computedOffsets = new HashMap<>();
        int runningTotal = 0;
        for (Model model : modelPool) {
            computedOffsets.put(model, runningTotal);
            runningTotal += model.resourceCount();
        }
        this.offsets = computedOffsets;
        this.totalResources = runningTotal;
        if (this.totalResources < 1) {
            throw new IllegalArgumentException("modelPool must own at least one resource");
        }
    }

    /**
     * Convenience constructor using modulo strategy
     */
    public LockGranularityMapper(int totalLocks, List<Model> modelPool) {
        this(totalLocks, modelPool, "modulo");
    }

    /**
     * A resource's position in the whole pool's contiguous-per-model-block ordering:
     * this model's offset (accumulated resourceCount() of every earlier model) plus its own
     * instance-major position within its model ({@link Model#localOrdinal(Resource)}). There is
     * exactly one implementation of "where does this resource sit" - Model's own - so this and
     * getOwnedResources()'s published order can never silently drift apart.
     */
    int globalOrdinal(Resource resource) {
        Integer offset = offsets.get(resource.model());
        if (offset == null) {
            throw new IllegalArgumentException(
                    "Resource " + resource + " belongs to a model outside this mapper's model pool");
        }
        return offset + resource.model().localOrdinal(resource);
    }

    /**
     * Maps a resource to a lock ID based on totalLocks.
     *
     * ALWAYS maps to the range [lock0, lock{totalLocks-1}] to enforce the lock budget.
     *
     * Examples with totalLocks=10, totalResources=100:
     * - Granularity = 10 resources/lock
     * - ordinal 0-9 -> lock0
     * - ordinal 10-19 -> lock1
     * - ordinal 20-29 -> lock2
     *
     * @param resource The resource
     * @return The lock identifier (e.g., "lock12")
     */
    public String getLockId(Resource resource) {
        if (strategy.equals("hash")) {
            // Hash-based: distribute resources across lock buckets
            int hash = Math.abs(resource.hashCode());
            int lockBucket = hash % totalLocks;
            return "lock" + lockBucket;
        } else {
            // Modulo-based: group consecutive resources
            int resourceNum = globalOrdinal(resource);

            if (totalLocks >= totalResources) {
                // Fine-grained: direct mapping (but still use lock0, lock1, ... format)
                return "lock" + Math.min(resourceNum, totalLocks - 1);
            } else {
                // Coarse-grained: multiple resources per lock
                int lockBucket = (resourceNum * totalLocks) / totalResources;
                return "lock" + Math.min(lockBucket, totalLocks - 1);
            }
        }
    }

    /**
     * Returns the total number of locks in the system.
     */
    public int getTotalLocks() {
        return totalLocks;
    }

    /**
     * Returns the computed granularity (resources per lock).
     */
    public double getGranularity() {
        return (double) totalResources / totalLocks;
    }

    public String getStrategy() {
        return strategy;
    }

    public int getTotalResources() {
        return totalResources;
    }

    /**
     * Collect unique lock IDs required for a list of resources.
     *
     * @param resources List of resources
     * @return Set of unique lock IDs required
     */
    public Set<String> collectLockIds(List<Resource> resources) {
        Set<String> lockIds = new LinkedHashSet<>();
        if (resources != null) {
            for (Resource resource : resources) {
                lockIds.add(getLockId(resource));
            }
        }
        return lockIds;
    }
}
