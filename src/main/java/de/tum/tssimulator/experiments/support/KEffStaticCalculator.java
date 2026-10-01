package de.tum.tssimulator.experiments.support;

import java.util.List;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;

/**
 * F5 (VLDB reviewer response): k_eff_static = expected distinct resources touched per logical
 * transaction = program/transaction size + expected CPR closure size, computed analytically
 * (well, exactly - see below) at config-build time, no simulation run needed.
 *
 * CprSchemaBuilder already computes EXACT transitive closures per property in CprSchema's
 * constructor (BFS/DFS over PropertyLinks, see CprSchema.computeAllClosures()) - this class just
 * builds the schema once per (f, mu, m, topology) combination and averages closure(p).size()
 * over the whole property pool, rather than re-deriving a closed-form approximation. This is
 * exact for the constructed schema (subject to the schema's own randomness for topologies whose
 * property-link stage draws from a Random - pass a fixed seed for reproducibility), not a
 * statistical estimate.
 */
public final class KEffStaticCalculator {

    private KEffStaticCalculator() {
    }

    /** One number per (models, topology, f, mu, m) config: mean closure size across every
     * property in the model pool, plus the transaction/program size. Constant across seeds and
     * across MPL - callers compute this once per config, not per seed-run. */
    public static double compute(List<Model> models, CprTopologyMode topology, double f, double mu, int m,
            long seed, int transactionSize) {
        CprSchema schema = new CprSchemaBuilder(models, topology, f, mu, m, seed, null, false).build();
        double meanClosureSize = meanClosureSize(models, schema);
        return transactionSize + meanClosureSize;
    }

    /** Mean closure size alone (no + transactionSize), exposed separately since some analysis
     * (D5's k_eff_static regression) wants the closure term on its own. */
    public static double meanClosureSize(List<Model> models, CprSchema schema) {
        long totalClosureSize = 0;
        long propertyCount = 0;
        for (Model model : models) {
            for (Property p : model.properties()) {
                totalClosureSize += schema.closure(p).size();
                propertyCount++;
            }
        }
        return propertyCount == 0 ? 0.0 : totalClosureSize / (double) propertyCount;
    }

    /**
     * UNIFORM-topology-only sanity cross-check against the branching-process closed form used
     * elsewhere in the repo (ValidationRun.java): S = 1/(1-f*mu). Does NOT generalize to
     * LINE/RING/HOTSPOT/FAN_OUT (those have structural constraints on reachability that the
     * branching-process approximation ignores) - callers must not use this outside UNIFORM.
     * Returns NaN (not an exception) when f*mu >= 1, since the closed form diverges there and a
     * NaN is a clearer "not applicable" signal in a CSV column than a thrown exception aborting
     * a whole sweep.
     */
    public static double uniformClosedFormApprox(double f, double mu) {
        double r = f * mu;
        return r >= 1.0 ? Double.NaN : 1.0 / (1.0 - r);
    }
}
