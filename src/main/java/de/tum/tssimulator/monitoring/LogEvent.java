package de.tum.tssimulator.monitoring;

public class LogEvent {

    /**
     * virtualTime for an event logged where no virtual time is reachable.
     *
     * <p>NOT 0. Zero is a LEGITIMATE virtual time -- events genuinely occur at simulation start --
     * so using it as "unavailable" leaves real and placeholder timestamps indistinguishable to any
     * consumer, who would reasonably read a 0 as an actual time. Virtual time is non-negative, so
     * -1 cannot collide with a real value and the distinction is machine-checkable rather than
     * merely documented.
     *
     * <p>The eight diagnostic types below are the sites that need it; five of them have a real
     * timestamp in scope and pass it.
     */
    public static final long NO_VIRTUAL_TIME = -1L;
    public enum Type {
        TICK,
        PHASE_STARTED,
        PHASE_COMPLETED,
        /** A member cleared off a unit by group abort/force-block, NOT a genuine phase end. */
        PHASE_EVICTED,
        /** A group was force-blocked via blockAll -- the path that can evict members. */
        GROUP_FORCE_BLOCKED,
        /** clearFromProcessors returned early on the ABORTING guard -- no member was
         *  examined, which is a DIFFERENT cause of zero evictions than "all queued". */
        CLEAR_SUPPRESSED_ABORTING,
        /* ---- Non-phase diagnostics, distinct from PHASE_COMPLETED. ----
         *
         * None of these is a phase completion: a genuine one carries "phase=<X> duration=<n>" and
         * a REAL timestamp, and there are exactly two such sites (SimulatedProcessorUnit,
         * ReconciliationProcessorUnit). Logging them as PHASE_COMPLETED makes anything that counts
         * phase completions from the event stream count these too.
         *
         * Eight types rather than one DIAGNOSTIC bucket, deliberately: collapsing semantically
         * different events into a single type is the problem being avoided, one level down. */

        /** A bookkeeper was prepended to the PENDING queue (ACID priority path). */
        BOOKKEEPER_QUEUED,
        /** Bookkeepers registered for a parent group, with the created and total counts. */
        BOOKKEEPERS_CREATED,
        /** BASE: a resource key was marked dirty, so a bookkeeper was created for it. */
        DIRTY_MARKED,
        /** BASE: key already dirty, so bookkeeper creation was skipped -- fan-out collapse.
         *  Distinct from DIRTY_MARKED because the two counts are what make collapse visible. */
        DIRTY_SKIPPED,
        /** BASE: dirty flag cleared after a bookkeeper completed; consistency restored. */
        DIRTY_CLEARED,
        /** clearFromProcessors removed members from units, with the count cleared. Distinct from
         *  PHASE_EVICTED, which is per-member; this is the per-group summary of the same sweep. */
        CLEARED_FROM_PROCESSORS,
        /** A transaction entered the waiting room and is parked until its group is ready. */
        WAITING_FOR_GROUP,
        /** A lock was released. Completes the LOCK_REQUESTED / GRANTED / DENIED family, which
         *  had no release member -- which is part of why releases were logged as phase ends. */
        LOCK_RELEASED,
        LOCK_REQUESTED,
        LOCK_GRANTED,
        LOCK_DENIED,
        TRANSACTION_COMPLETED,
        TRANSACTION_ABORTED,
        WARNING  // For stuck transactions (don't pollute abort metrics)
    }

    private final Type type;
    private final String transactionId;
    private final long virtualTime;
    private final String message;

    public LogEvent(Type type, String transactionId, long virtualTime, String message) {
        this.type = type;
        this.transactionId = transactionId;
        this.virtualTime = virtualTime;
        this.message = message;
    }

    public Type getType() { return type; }
    public String getTransactionId() { return transactionId; }
    public long getVirtualTime() { return virtualTime; }
    public String getMessage() { return message; }
}
