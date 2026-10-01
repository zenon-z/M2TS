package de.tum.tssimulator.experiments.variation;

import de.tum.tssimulator.config.SimulatorParameters;

/**
 * Varies the fraction of eligible resources with at least one CPR.
 */
public class VaryCprFraction extends BaseParameterVariation {
    public VaryCprFraction(Double... values) {
        super("cprFraction", values);
    }

    @Override
    public SimulatorParameters applyVariation(SimulatorParameters base, Object value) {
        return createBaseBuilder(base)
                .semanticOverlap((Double) value)
                .build();
    }
}
