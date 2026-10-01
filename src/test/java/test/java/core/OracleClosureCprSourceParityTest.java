package test.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.config.WorkloadMix;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;

/**
 * The inlined (D-oracle) arm must expand from the SAME CPR sources the runtime cascade fires from.
 *
 * <p>{@code SchemaCprFactory} under {@code writeOnlyCprSources = true} - the default - seeds
 * bookkeepers only from resources the parent WRITES: a read never changes the source value a CPR
 * exists to propagate. {@code expandToOracleClosure} expanded from every seed regardless of
 * operation type, so under any workload with reads the inlined arm inlined closures the triggered
 * arm never reaches, and its footprint was strictly larger.
 *
 * <p><b>Why this is not a small discrepancy at the campaign's operating point.</b>
 * {@code WorkloadMix.TWO_CLASS} with {@code readOnlyTransactionFraction = 0.5} makes half of all
 * transactions entirely read-only and gives the rest {@code ceil(k/2)} writes. So the triggered arm
 * sources from about a quarter of all seeds, and a read-only transaction spawns no CU at all while
 * the pre-fix inlined arm still carried a full closure for it - roughly doubling the inlined
 * footprint per logical transaction.
 *
 * <p><b>Why it moves throughput the way a real finding does.</b> More resources per transaction is
 * more work and more lock contention, so the inlined arm looks slower - which is exactly the
 * direction an "inlining is expensive" conclusion would be drawn in. Same signature as the three
 * defects recorded on this work: the bug moves the number the way the hypothesis wants.
 *
 * <p>Read-only transactions are the sharpest case and are asserted separately, because a footprint
 * EQUALITY over the mixed population can be satisfied on average while being wrong per class.
 */
class OracleClosureCprSourceParityTest {

    private static final int K = 10;
    private static final long SEED = 1000003L;

    /** The campaign's mix: TWO_CLASS, half the transactions read-only, WRITE-only CPR sourcing. */
    private static SimulatorParameters params() {
        int totalModels = 50;
        List<Integer> propertiesPerModel = Collections.nCopies(totalModels, 10);
        return SimulatorParameters.builder()
                .transactionGenerationMode(TransactionGenerationMode.RANDOM_OPERATIONS)
                .useRandomResourceSelection(true)
                .totalModels(totalModels)
                .propertiesPerModel(propertiesPerModel)
                .instancePoolSize(10)
                .totalLocks(5000)
                .maxTransactionModels(1)
                .useConstantTransactionSize(true)
                .constantTransactionSize(K)
                .semanticOverlap(0.25)
                .avgCprsPerResource(2.0)
                .maxCprsPerResource(6)
                .cprTopologyMode(CprTopologyMode.UNIFORM)
                .semanticOverlapSeed(SEED)
                .transactionSamplingSeed(SEED)
                .workloadMix(WorkloadMix.TWO_CLASS)
                .readOnlyTransactionFraction(0.5)
                .writeOnlyCprSources(true)
                .cuOperationType(OperationType.WRITE)
                .oracleClosurePreclaim(true)
                .build();
    }

    private static CprSchema schema(SimulatorConfig config) {
        return new CprSchemaBuilder(config.modelPool, config.cprTopologyMode, config.semanticOverlap,
                config.avgCprsPerResource, config.maxCprsPerResource, config.semanticOverlapSeed,
                config.cprInitialAttractiveness, config.cprAcyclicTotalOrder)
                .build();
    }

    private record Generated(List<Transaction> txns, CprSchema schema) {
    }

    private static Generated generate(int count) {
        SimulatorParameters p = params();
        List<Model> pool = new ModelFactory(p.instancePoolSize).createModels(p.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(p, pool);
        CprSchema cprSchema = schema(config);
        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(),
                p.transactionSamplingSeed, cprSchema);
        List<Transaction> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(factory.createTransaction(config, new TransactionGroup()));
        }
        return new Generated(out, cprSchema);
    }

    /**
     * A transaction with no WRITE among its own k operations has no CPR source, so the triggered arm
     * spawns nothing for it - and the inlined arm must therefore carry exactly its k seeds.
     */
    @Test
    void readOnlyTransactionsGetNoInlinedClosure() {
        Generated g = generate(400);
        int readOnly = 0;
        for (Transaction t : g.txns()) {
            List<Operation> ops = t.getOperations();
            boolean anyWriteSeed = false;
            for (int i = 0; i < K; i++) {
                anyWriteSeed |= ops.get(i).getType() == OperationType.WRITE;
            }
            if (anyWriteSeed) {
                continue;
            }
            readOnly++;
            assertEquals(K, t.getResources().size(), String.format(
                    "read-only transaction %s has no CPR source, so the triggered arm spawns no CU "
                    + "for it - the inlined arm must carry exactly its %d seeds, not %d resources",
                    t.getId(), K, t.getResources().size()));
        }
        assertTrue(readOnly > 0, "no read-only transaction generated - test would pass vacuously");
    }

    /**
     * Over the whole population: the inlined footprint is the seeds plus the transitive closure of
     * the WRITE seeds only - the exact set the runtime cascade converges to.
     */
    @Test
    void inlinedFootprintExpandsFromWriteSeedsOnly() {
        Generated g = generate(400);
        int expanded = 0;
        for (Transaction t : g.txns()) {
            List<Operation> ops = t.getOperations();
            List<Resource> actual = new ArrayList<>(ops.size());
            for (Operation op : ops) {
                actual.add(op.getResource());
            }
            List<Resource> seeds = actual.subList(0, K);

            Set<Resource> expected = new LinkedHashSet<>(seeds);
            for (int i = 0; i < K; i++) {
                if (ops.get(i).getType() != OperationType.WRITE) {
                    continue; // a READ is not a CPR source under writeOnlyCprSources
                }
                Resource seed = seeds.get(i);
                for (var target : g.schema().closure(seed.property())) {
                    expected.add(new Resource(seed.instanceId(), target));
                }
            }
            if (expected.size() > K) {
                expanded++;
            }
            assertEquals(new ArrayList<>(expected), actual, String.format(
                    "transaction %s: inlined footprint must be seeds + closure(WRITE seeds). "
                    + "Expected %d resources, got %d", t.getId(), expected.size(), actual.size()));
        }
        assertTrue(expanded > 0, "nothing expanded - test would pass vacuously");
    }
}
