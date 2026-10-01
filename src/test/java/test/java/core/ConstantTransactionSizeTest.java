package test.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;

/**
 * {@code constantTransactionSize(k)} must produce exactly k resources, whatever
 * {@code maxTransactionModels} is set to.
 *
 * <p>It did not. {@code createRandomOperationsTransaction} drew {@code numModels} in
 * {@code [1, maxTransactionModels]} and divided {@code numResources} by that DRAWN count, but
 * {@code pickRandomUnique} clamps model selection to the pool size - so whenever
 * {@code maxTransactionModels} exceeded {@code totalModels}, the surplus per-model shares were
 * never allocated. With a 1-model pool and the default {@code maxTransactionModels = 5},
 * {@code constantTransactionSize(8)} produced 8/4/3/2/2 resources depending on the draw: a mean of
 * 3.8 against a requested 8.
 *
 * <p>Found while asserting lock-count accounting, where a transaction configured for 8 resources
 * was reporting a mean of 3.83 locks - the accounting was right and the workload was wrong.
 *
 * <p>The multi-model case is the one that regresses if the divisor is ever "simplified" back:
 * a single-model pool makes drawn and selected counts coincide at 1, so it cannot see the bug.
 */
class ConstantTransactionSizeTest {

    private static final int K = 8;

    private static List<Transaction> generate(int totalModels, int maxTransactionModels, int count) {
        List<Integer> propertiesPerModel = new ArrayList<>();
        for (int i = 0; i < totalModels; i++) {
            propertiesPerModel.add(1);
        }
        SimulatorParameters params = SimulatorParameters.builder()
                .totalModels(totalModels)
                .propertiesPerModel(propertiesPerModel)
                .semanticOverlap(0.0)
                .instancePoolSize(500)
                .totalLocks(500 * totalModels)
                .useConstantTransactionSize(true)
                .constantTransactionSize(K)
                .maxTransactionModels(maxTransactionModels)
                .useRandomResourceSelection(true)
                .transactionSamplingSeed(1000003L)
                .semanticOverlapSeed(1000003L)
                .build();

        List<Model> pool = new ModelFactory(params.instancePoolSize).createModels(params.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(params, pool);
        TransactionFactory factory = new TransactionFactory(
                new TransactionIdGenerator(), params.transactionSamplingSeed);

        List<Transaction> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(factory.createTransaction(config, new TransactionGroup()));
        }
        return out;
    }

    /** The shape the bug needed: drawn model count can exceed the 1-model pool on most draws. */
    @Test
    void singleModelPoolWithLargerMaxModelsStillYieldsK() {
        for (Transaction t : generate(1, 5, 400)) {
            assertEquals(K, t.getResources().size(),
                    "constantTransactionSize must be honoured regardless of maxTransactionModels");
        }
    }

    /** Multi-model, drawn count still able to exceed the pool. */
    @Test
    void threeModelPoolWithLargerMaxModelsStillYieldsK() {
        for (Transaction t : generate(3, 8, 400)) {
            assertEquals(K, t.getResources().size());
        }
    }

    /** The already-correct case, pinned so a fix cannot regress it. */
    @Test
    void maxModelsWithinThePoolStillYieldsK() {
        for (Transaction t : generate(4, 4, 400)) {
            assertEquals(K, t.getResources().size());
        }
    }

    /**
     * Premise guard: with maxTransactionModels=5 over a 1-model pool the draw must actually vary,
     * otherwise the first test above never exercises the shortfall path it exists for. Checked via
     * resource identity spread rather than the draw itself, which is not observable from here.
     */
    @Test
    void fixtureExercisesVaryingDrawnModelCounts() {
        List<Transaction> txns = generate(1, 5, 200);
        assertTrue(txns.stream().map(t -> t.getResources().get(0)).distinct().count() > 1,
                "fixture generated identical transactions - it cannot observe the draw at all");
    }
}
