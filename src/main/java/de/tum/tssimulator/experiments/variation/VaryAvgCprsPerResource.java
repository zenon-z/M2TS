package de.tum.tssimulator.experiments.variation;

import de.tum.tssimulator.config.SimulatorParameters;

/**
 * Varies the expected outgoing CPR count under the global fan-out model.
 */
public class VaryAvgCprsPerResource extends BaseParameterVariation {
    public VaryAvgCprsPerResource(Double... values) {
        super("avgCprsPerResource", values);
    }

    @Override
    public SimulatorParameters applyVariation(SimulatorParameters base, Object value) {
        return createBaseBuilder(base)
                .avgCprsPerResource((Double) value)
                .build();
    }
}
