package de.tum.tssimulator.experiments.support;

/**
 * The fields of a {@link RawRunRow} that are constant across every seed of one config cell
 * (f, mu, m, topology, mpl, k, D, instancePoolSize, propertiesPerModel, totalModels,
 * k_eff_static) - computed once per config by the calling experiment, then threaded through
 * {@link SeedSweepRunner#runAndWriteRawRows} for every seed in that cell.
 */
public record RawRunRowContext(
        String configId,
        double f,
        double mu,
        int m,
        String topology,
        int mpl,
        int k,
        int instancePoolSize,
        int propertiesPerModel,
        int totalModels,
        int d,
        double kEffStatic) {
}
