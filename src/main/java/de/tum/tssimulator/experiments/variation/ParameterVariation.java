package de.tum.tssimulator.experiments.variation;

import de.tum.tssimulator.config.SimulatorParameters;

/**
 * Defines how to vary a parameter in experiments.
 * Extracted from ExperimentRunner to follow Single Responsibility Principle.
 */
public interface ParameterVariation {
    String getParameterName();
    Object[] getValues();
    SimulatorParameters applyVariation(SimulatorParameters base, Object value);
}

