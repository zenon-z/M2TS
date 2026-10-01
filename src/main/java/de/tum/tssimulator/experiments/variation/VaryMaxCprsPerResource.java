package de.tum.tssimulator.experiments.variation;

import de.tum.tssimulator.config.SimulatorParameters;

/**
 * Varies the maximum outgoing CPR count per resource with at least one CPR.
 */
public class VaryMaxCprsPerResource extends BaseParameterVariation {
    public VaryMaxCprsPerResource(Integer... values) {
        super("maxCprsPerResource", values);
    }

    @Override
    public SimulatorParameters applyVariation(SimulatorParameters base, Object value) {
        return createBaseBuilder(base)
                .maxCprsPerResource((Integer) value)
                .build();
    }
}
