package de.tum.tssimulator.config;

/**
 * Order in which groups waiting on admission capacity are woken when a slot frees.
 *
 * <p>{@code CapacityWaitManager.wakeNext()} takes READY waiters from the tail of its deque. That
 * is selectable rather than fixed so the choice can be measured instead of asserted; LIFO is the
 * default because every committed campaign row was produced under it.
 *
 * <p>PENDING waiters are FIFO in both modes and deliberately left alone. This knob is about the
 * READY queue, where a group has already been admitted once and waits only for capacity; the
 * PENDING queue is the arrival order of new work, and reordering it would be a different
 * experiment.
 */
public enum ReadyWaiterOrder {
    /** Take from the tail: the most recently enqueued READY group is woken first. */
    LIFO,
    /** Take from the head: the longest-waiting READY group is woken first. */
    FIFO
}
