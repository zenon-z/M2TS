package de.tum.tssimulator.core;

import java.util.EnumMap;
import java.util.Map;

public class TransactionMetrics {
    private final Map<ExecutionPhase, Long> timeByPhase = new EnumMap<>(ExecutionPhase.class);

    public void record(ExecutionPhase phase, long delta) {
        timeByPhase.merge(phase, delta, Long::sum);
    }

    public long getTime(ExecutionPhase phase) {
        return timeByPhase.getOrDefault(phase, 0L);
    }
    
    public void clear() {
        timeByPhase.clear();
    }
}
