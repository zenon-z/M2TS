package de.tum.tssimulator.config;

/**
 * WHERE the inlined (D-oracle) arm puts the CPR closure inside the parent's operation list. Only
 * meaningful when {@code oracleClosurePreclaim} is true; ignored otherwise.
 *
 * <p>Both values produce the SAME resource SET - the seeds plus the transitive closure of the seeds
 * the runtime cascade would source from - so work per logical transaction is identical and the two
 * differ in acquisition ORDER alone. That is the whole point: it separates the cost of inlining's
 * STRUCTURE (one transaction instead of a parent plus CUs) from the cost of its TIMING (when each
 * closure lock is taken relative to the operation that requires it).
 *
 * <p><b>Neither value changes anything at {@code operationBatchSize = 0}.</b> There the whole
 * footprint is acquired in one call, so order cannot affect when any lock is taken. This enum only
 * becomes observable once the S2PL operation cursor is walking the list.
 */
public enum OracleClosurePlacement {
    /**
     * Seeds first, then the closure - the historical behaviour, and the one whose lock timing lines
     * up with the triggered arm: the parent takes its own k operations before any closure lock,
     * exactly as a triggered parent does before its CUs start.
     */
    AT_END,

    /**
     * Each closure target immediately after the WRITE seed that sources it. Models a transaction
     * that performs each consistency repair at the point the change requiring it is made, rather
     * than batching all repairs at the end.
     */
    INTERLEAVED
}
