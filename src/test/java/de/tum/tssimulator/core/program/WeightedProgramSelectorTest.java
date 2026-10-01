package de.tum.tssimulator.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;

class WeightedProgramSelectorTest {

    private static final Model MODEL = new ModelFactory(10).createModels(List.of(1)).get(0);

    private static TransactionProgram program(String name, double weight) {
        Property x = MODEL.properties().get(0);
        return new TransactionProgram(name + "@" + MODEL.getName(), MODEL, List.of("x"), Map.of("x", x),
                List.of(new Step(OperationType.READ, "x")), weight);
    }

    @Test
    void rejectsEmptyProgramList() {
        assertThrows(IllegalArgumentException.class,
                () -> WeightedProgramSelector.select(List.of(), new Random(1)));
    }

    @Test
    void singleProgramAlwaysSelected() {
        TransactionProgram only = program("Only", 1.0);
        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            assertEquals(only, WeightedProgramSelector.select(List.of(only), random));
        }
    }

    @Test
    void distributionApproximatesWeightsUnderSeededRandom() {
        TransactionProgram heavy = program("Heavy", 3.0);
        TransactionProgram light = program("Light", 1.0);
        List<TransactionProgram> programs = List.of(heavy, light);

        Random random = new Random(1234);
        Map<String, Integer> counts = new HashMap<>();
        int trials = 100_000;
        for (int i = 0; i < trials; i++) {
            TransactionProgram selected = WeightedProgramSelector.select(programs, random);
            counts.merge(selected.id(), 1, Integer::sum);
        }

        double heavyRatio = counts.getOrDefault(heavy.id(), 0) / (double) trials;
        // Expected ratio is 0.75; allow generous tolerance for a seeded-random sanity check.
        assertTrue(heavyRatio > 0.70 && heavyRatio < 0.80,
                "Expected Heavy program selection ratio near 0.75, got " + heavyRatio);
    }
}
