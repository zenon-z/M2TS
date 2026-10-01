package de.tum.tssimulator.core.program;

import java.util.List;
import java.util.Random;

/**
 * Cumulative-weight roulette selection over a list of {@link TransactionProgram}s.
 */
public class WeightedProgramSelector {

    private WeightedProgramSelector() {
    }

    public static TransactionProgram select(List<TransactionProgram> programs, Random random) {
        if (programs == null || programs.isEmpty()) {
            throw new IllegalArgumentException("programs must not be null or empty");
        }

        double totalWeight = 0.0;
        for (TransactionProgram program : programs) {
            totalWeight += program.weight();
        }

        double target = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (TransactionProgram program : programs) {
            cumulative += program.weight();
            if (target < cumulative) {
                return program;
            }
        }
        // Guards against floating-point rounding landing target just past the last cumulative bound.
        return programs.get(programs.size() - 1);
    }
}
