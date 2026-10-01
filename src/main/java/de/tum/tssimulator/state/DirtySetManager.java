package de.tum.tssimulator.state;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the dirty set for BASE reconciliation with recovery latency tracking.
 * 
 * The dirty set tracks which (modelId, resourceId) pairs need reconciliation work,
 * along with the timestamp when they became dirty.
 * 
 * Fan-out collapse: Multiple bookkeeper triggers to the same target collapse to a single dirty entry.
 * 
 * Lifecycle:
 * 1. When parent commits and triggers bookkeepers: check if target is dirty
 * 2. If dirty: skip bookkeeper creation (work already pending)
 * 3. If not dirty: add to dirty set WITH TIMESTAMP, create bookkeeper
 * 4. When bookkeeper completes: remove from dirty set and record recovery latency
 * 5. If another parent writes to the same target during reconciliation: it's still marked dirty
 *    (subsumption semantics: one reconciliation covers all pending writes)
 * 
 * Recovery Latency Tracking:
 * - markDirty() stores the virtualTime when resource became dirty
 * - clearDirty() calculates recovery latency = clearTime - dirtyTime
 * - Callback to RecoveryLatencyRecorder for aggregation
 */
public class DirtySetManager implements DirtyStateTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirtySetManager.class);

    // Map from DirtyKey -> timestamp when first marked dirty (milliseconds)
    private final ConcurrentHashMap<DirtyKey, Long> dirtySince = new ConcurrentHashMap<>();
    
    // Callback for recording recovery latencies (injected, avoids circular dependency)
    private volatile RecoveryLatencyRecorder latencyRecorder = null;
    
    // Strict mode: throw on timestamp violations (enabled via -Dts.simulator.strict.timestamps=true)
    private static final boolean STRICT_MODE = 
        Boolean.getBoolean("ts.simulator.strict.timestamps");
    
    /**
     * Callback interface for recording recovery latencies.
     * Avoids circular dependency between DirtySetManager and SimulationLogger.
     */
    public interface RecoveryLatencyRecorder {
        void recordRecoveryLatency(long latency);
    }
    
    /**
     * Inject the latency recorder (typically SimulationLogger::recordRecoveryLatency).
     * Should be called during initialization.
     */
    public void setLatencyRecorder(RecoveryLatencyRecorder recorder) {
        this.latencyRecorder = recorder;
    }
    
    /**
     * Check if a target is currently dirty (has pending reconciliation work).
     */
    @Override
    public boolean isDirty(DirtyKey key) {
        return dirtySince.containsKey(key);
    }
    
    /**
     * Mark a target as dirty (needs reconciliation) with timestamp.
     * 
     * @param key The dirty key (model, resource)
     * @param virtualTime Virtual time when resource became dirty
     * @return true if newly marked dirty (bookkeeper should be created),
     *         false if already dirty (skip bookkeeper - fan-out collapse)
     */
    @Override
    public boolean markDirty(DirtyKey key, long virtualTime) {
        // putIfAbsent returns null if newly added, non-null if already present
        return dirtySince.putIfAbsent(key, virtualTime) == null;
    }
    
    /**
     * Remove a target from the dirty set and record recovery latency.
     * Called when bookkeeper completes (consistency restored).
     * 
     * @param key The dirty key to clear
     * @param virtualTime Virtual time when cleared (bookkeeper completion time)
     */
    @Override
    public void clearDirty(DirtyKey key, long virtualTime) {
        // Distinct name, NOT resolved by scope: the field 'dirtySince' is the map, and a local
        // of the same name shadows it silently in Java. Caught here only because the
        // initializer self-references on one line; split across two it would have compiled
        // and read the wrong thing.
        Long dirtiedAtTick = dirtySince.remove(key);
        
        if (dirtiedAtTick != null && latencyRecorder != null) {
            long recoveryLatency = virtualTime - dirtiedAtTick;
            
            if (recoveryLatency < 0) {
                String msg = String.format(
                    "[TIMESTAMP] Negative recovery latency: %d ms " +
                    "(key=%s, dirtySince=%d, clearedAt=%d). " +
                    "Time went backward or clock issue.",
                    recoveryLatency, key, dirtiedAtTick, virtualTime);
                
                if (STRICT_MODE) {
                    throw new IllegalStateException(msg);
                } else {
                    LOGGER.warn(msg);
                    // Don't record negative latencies
                    return;
                }
            }
            
            // Record valid latency
            latencyRecorder.recordRecoveryLatency(recoveryLatency);
        }
    }
    
    /**
     * Get the current dirty set size (for metrics).
     */
    @Override
    public int size() {
        return dirtySince.size();
    }
    
    /**
     * Get count of resources never cleared (right-censored data).
     * Should be called at simulation end.
     */
    public int getNeverClearedCount() {
        return dirtySince.size();
    }
}
