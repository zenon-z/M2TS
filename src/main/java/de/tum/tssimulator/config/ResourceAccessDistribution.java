package de.tum.tssimulator.config;

/**
 * How a transaction's footprint is drawn from the resource space.
 *
 * <p><b>Skew is applied to {@code instanceId}, not to a per-model resource ordinal.</b> A
 * {@code Resource} is an {@code (instanceId, property)} coordinate and {@code instanceId} repeats
 * across every model and property; {@code SchemaCprFactory} preserves it when building a CU target
 * ({@code new Resource(resource.instanceId(), link.target())}). So skewing the instance id makes a
 * hot instance hot in <em>every</em> model at once, and cascades land on hot targets by
 * construction. Skewing a flat per-model ordinal instead would give each model an independent hot
 * set, which is a different experiment.
 *
 * <p><b>{@code ZIPF} at {@code zipfTheta == 0.0} short-circuits to the {@code UNIFORM} code path
 * verbatim</b>, so it is bit-identical rather than merely equal in distribution. That is what lets
 * theta = 0 be "today's behaviour" as an experiment's leftmost point.
 *
 * <p>{@code ZIPF} requires {@code useRandomResourceSelection = true}: the sequential path takes a
 * contiguous block, and contiguity over resource ordinals is a second clustering mechanism that
 * would interact with skew over instance ids.
 */
public enum ResourceAccessDistribution {
    /** Uniform draw over the model's owned resources - the historical behaviour. */
    UNIFORM,
    /** Zipf({@code zipfTheta}) over instance ids, property drawn uniformly. */
    ZIPF
}
