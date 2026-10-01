package de.tum.tssimulator.config;

/**
 * How operation types are assigned to a transaction's sampled resources.
 *
 * <p>{@code PER_OPERATION} is the historical behaviour: each resource independently becomes a WRITE
 * with probability {@code writeFraction}, resolved by the stateless SplitMix64 hash
 * {@code TransactionFactory.operationTypeFor}. There is no transaction class - at
 * {@code writeFraction = 0.5} and k = 10, {@code P(transaction is read-only) = 0.5^10}, i.e.
 * essentially zero.
 *
 * <p>{@code TWO_CLASS} draws a transaction <em>class</em> instead:
 * <ul>
 *   <li><b>read-only</b>, with probability {@code readOnlyTransactionFraction}: k reads, 0 writes;</li>
 *   <li><b>update</b>, otherwise: exactly {@code ceil(n/2)} writes and {@code floor(n/2)} reads over
 *       the realised footprint size {@code n}, positions shuffled rather than prefixed.</li>
 * </ul>
 *
 * <p><b>Why a fixed {@code ceil(n/2)} rather than a per-operation coin flip or an all-WRITE update
 * class.</b> {@code writeOnlyCprSources = true} means only WRITE resources seed bookkeepers, so
 * sweeping {@code writeFraction} moves the read mix and the CPR fire rate together and the
 * resulting curve cannot be read. An all-READ/all-WRITE split swings the per-transaction CPR count
 * between 0 and k, which is too coarse. A deterministic-count update class holds the CPR count per
 * <em>firing</em> transaction constant, so sweeping {@code readOnlyTransactionFraction} moves only
 * <em>how many</em> transactions fire.
 *
 * <p>{@code TWO_CLASS} and a non-default {@code writeFraction} are mutually exclusive and rejected
 * at validation: under {@code TWO_CLASS} the class sampler assigns every operation type, so
 * {@code writeFraction} would be silently inert.
 */
public enum WorkloadMix {
    /** Per-resource WRITE probability via {@code writeFraction} - the historical behaviour. */
    PER_OPERATION,
    /** Read-only / update transaction classes via {@code readOnlyTransactionFraction}. */
    TWO_CLASS
}
