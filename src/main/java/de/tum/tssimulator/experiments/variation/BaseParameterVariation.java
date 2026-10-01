package de.tum.tssimulator.experiments.variation;

import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * Base class for parameter variations that eliminates code duplication.
 * Uses builder pattern to copy all parameters while varying specific ones.
 */
public abstract class BaseParameterVariation implements ParameterVariation {
    protected final String parameterName;
    protected final Object[] values;

    protected BaseParameterVariation(String parameterName, Object[] values) {
        this.parameterName = parameterName;
        this.values = values;
    }

    @Override
    public String getParameterName() {
        return parameterName;
    }

    @Override
    public Object[] getValues() {
        return values;
    }

    /**
     * Delegates to {@link SeedSweepRunner#copyBuilder}.
     *
     * <p><b>A hand-maintained copy list here erases the caller's customisations.</b>
     * {@code Builder.build()} substitutes a default for anything left unset, so a list covering 38
     * of SimulatorParameters' 53 fields silently reverts the other 15 - among them
     * {@code writeFraction}, {@code warmupSeconds}, {@code operationBatchSize},
     * {@code lockingProtocol}, {@code instancePoolSize} and {@code propertiesPerModel}.
     *
     * <p>Measured with a base whose dropped fields were set away from their defaults:
     * writeFraction 0.37 -> 1.0, warmupSeconds 7 -> 0, operationBatchSize 3 -> 0, instancePoolSize
     * 1234 -> 100, and the result hashing IDENTICAL to an untouched defaults base. Every parameter
     * variation routed through this class depends on the copy being complete.
     */
    protected SimulatorParameters.Builder createBaseBuilder(SimulatorParameters base) {
        return SeedSweepRunner.copyBuilder(base);
    }
}
