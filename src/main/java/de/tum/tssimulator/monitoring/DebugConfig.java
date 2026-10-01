package de.tum.tssimulator.monitoring;

/**
 * Global debug configuration for toggleable instrumentation.
 * Set ENABLE_STALL_TRACKING to true to enable verbose instrumentation.
 */
public class DebugConfig {
    // Master switch - keep false for normal builds and tests
    public static final boolean ENABLE_STALL_TRACKING = false;
    
    // Fine-grained control (only active if master switch is on)
    public static final boolean TRACK_DISPATCH_OUTCOMES = ENABLE_STALL_TRACKING && true;
    public static final boolean TRACK_QUEUE_SIZES = ENABLE_STALL_TRACKING && true;
    public static final boolean PRINT_DISPATCH_SUMMARY = ENABLE_STALL_TRACKING && true;
    public static final boolean PRINT_RUNTIME_DEBUG_LOGS = ENABLE_STALL_TRACKING && true;
    public static final boolean PRINT_LIFECYCLE_ASSERTIONS = ENABLE_STALL_TRACKING && true;
    
    // Sampling rate to reduce overhead
    public static final int QUEUE_SIZE_SAMPLE_INTERVAL = 100; // Sample every N dispatches
    
    /**
     * Helper method for conditional debug logging.
     */
    public static boolean isEnabled() {
        return ENABLE_STALL_TRACKING;
    }

    public static boolean printRuntimeDebugLogs() {
        return PRINT_RUNTIME_DEBUG_LOGS;
    }

    public static boolean printLifecycleAssertions() {
        return PRINT_LIFECYCLE_ASSERTIONS;
    }
}
