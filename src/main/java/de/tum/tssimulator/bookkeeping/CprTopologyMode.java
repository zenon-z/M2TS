package de.tum.tssimulator.bookkeeping;

/**
 * Controls the Stage-1 model graph {@code CprSchemaBuilder} builds - which models may link to
 * which - before Stage 2 draws property-level {@code PropertyLink}s over it.
 *
 * <ul>
 *   <li>{@code LINE} - a single chain, model i -&gt; model i+1.</li>
 *   <li>{@code RING} - LINE closed into a cycle.</li>
 *   <li>{@code HOTSPOT} - every non-hub model -&gt; the hub (one-directional in-star; an
 *       aggregate, not invertible).</li>
 *   <li>{@code FAN_OUT} - hub &lt;-&gt; every other model (bidirectional star).</li>
 *   <li>{@code UNIFORM} - the complete cross-model digraph: every model -&gt; every other model.
 *       The no-bias control the other topologies deviate from, not a constrained shape itself.</li>
 * </ul>
 */
public enum CprTopologyMode {
    LINE,
    FAN_OUT,
    HOTSPOT,
    UNIFORM,
    RING
}
