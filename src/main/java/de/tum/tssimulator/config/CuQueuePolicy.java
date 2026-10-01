package de.tum.tssimulator.config;

/**
 * Where a triggered consistency unit (CU) is placed in the PENDING queue.
 *
 * <p>{@code ACIDBookkeeperQueueRouter} calls {@code queueManager.prepend(bk, PENDING)} for each CU
 * in a batch, which does two separable things at once:
 *
 * <ul>
 *   <li><b>Priority.</b> CUs go ahead of every fresh parent transaction already queued. This is
 *       deliberate: the parent holds locks in the waiting room while its CUs run, so delaying a CU
 *       extends the window in which those locks are held.
 *   <li><b>Reversal.</b> Prepending each element of a list in list order leaves the last CU at the
 *       front, so a group's CUs run in reverse. That falls out of the loop rather than being
 *       chosen.
 * </ul>
 *
 * <p>Conflating them is what makes "the CUs are scheduled LIFO" hard to answer: a single
 * LIFO-vs-FIFO comparison moves both at once, so a difference cannot be attributed and a null
 * cannot be explained. These three policies separate them - {@link #PREPEND_IN_ORDER} differs from
 * {@link #PREPEND} only in the reversal, and from {@link #APPEND} only in the priority.
 *
 * <p>Measured across three cells and 50 paired observations, the ordering has <b>no effect on
 * throughput</b>. The knob stays so the claim can be re-checked, and {@link #PREPEND} is the
 * default because every campaign row was produced under it.
 */
public enum CuQueuePolicy {
    /** Prepend each CU in list order, so the batch runs reversed, ahead of parents. Historical. */
    PREPEND,
    /** Prepend the batch but preserve its own order - priority without the reversal. */
    PREPEND_IN_ORDER,
    /** Append: CUs queue behind existing PENDING work, in batch order. No priority. */
    APPEND
}
