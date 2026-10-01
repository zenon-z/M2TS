package test.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;

/**
 * WHERE the inlined (D-oracle) arm's closure operations sit in the parent's operation list.
 *
 * <p>The triggered/inlined comparison rests on the two arms committing the SAME resources in a
 * comparable ORDER: the parent's own k seed resources first, the CPR closure after them. If
 * expansion prepended or interleaved the closure, the inlined arm's parent would take
 * closure-derived locks before its own - which is not what the triggered arm does at any batch
 * size, and would make an acquisition-order difference read as a dynamism result.
 *
 * <p><b>Position, not membership.</b> {@code seedResourceTraceKey} already proves the two arms
 * agree on the seed SET, and it sorts - so it is order-blind by construction and cannot see this
 * property at all (that is exactly how the {@code Set.copyOf} closure-ordering defect hid for a
 * session: contents right, order per-JVM, key matching on every run). Hence the assertions here
 * are all indexed.
 *
 * <p><b>This is a statement about the operation LIST, not about lock timing.</b> At
 * {@code operationBatchSize = 0} - the shipped default, and what both SS2PL arms run - the whole
 * footprint is acquired in one call, so this order has no effect on when any lock is taken. It
 * becomes load-bearing the moment {@code operationBatchSize > 0}, where the cursor walks the list
 * in exactly this order.
 */
class OracleClosureOperationOrderTest {

    private static final int K = 10;
    private static final long SEED = 1000003L;

    private static SimulatorParameters params(boolean preclaim) {
        int totalModels = 50;
        int propsPerModel = 10;
        List<Integer> propertiesPerModel = Collections.nCopies(totalModels, propsPerModel);
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
                .oracleClosurePreclaim(preclaim)
                .build();
    }

    /** The schema wired exactly as {@code SimulatorContext.create} wires it. */
    private static CprSchema schema(SimulatorConfig config) {
        return new CprSchemaBuilder(config.modelPool, config.cprTopologyMode, config.semanticOverlap,
                config.avgCprsPerResource, config.maxCprsPerResource, config.semanticOverlapSeed,
                config.cprInitialAttractiveness, config.cprAcyclicTotalOrder)
                .build();
    }

    private static List<Transaction> generate(boolean preclaim, int count) {
        SimulatorParameters p = params(preclaim);
        List<Model> pool = new ModelFactory(p.instancePoolSize).createModels(p.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(p, pool);
        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(),
                p.transactionSamplingSeed, preclaim ? schema(config) : null);
        List<Transaction> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(factory.createTransaction(config, new TransactionGroup()));
        }
        return out;
    }

    private static List<Resource> operationResources(Transaction t) {
        List<Resource> rs = new ArrayList<>(t.getOperations().size());
        for (Operation op : t.getOperations()) {
            rs.add(op.getResource());
        }
        return rs;
    }

    /**
     * The inlined arm's first k operations are the triggered arm's WHOLE operation list, in the
     * same positions - so everything after index k is closure, and nothing before it is.
     *
     * <p>Paired per transaction over 200 draws rather than checked on one: the two factories share
     * a sampling seed, and the expansion must not perturb the seed stream either.
     */
    @Test
    void closureOperationsFollowTheParentsOwnOperations() {
        List<Transaction> triggered = generate(false, 200);
        List<Transaction> inlined = generate(true, 200);

        int expandedAtLeastOnce = 0;
        for (int i = 0; i < triggered.size(); i++) {
            List<Resource> own = operationResources(triggered.get(i));
            List<Resource> all = operationResources(inlined.get(i));

            assertEquals(K, own.size(), "triggered arm's parent carries exactly k operations");
            assertTrue(all.size() >= own.size(),
                    "inlining can only add operations, never remove them");
            if (all.size() > own.size()) {
                expandedAtLeastOnce++;
            }

            // THE PREFIX, INDEX BY INDEX. A set comparison passes on a reversed list.
            for (int j = 0; j < own.size(); j++) {
                assertEquals(own.get(j), all.get(j), String.format(
                        "txn %d operation %d: inlined arm must carry the parent's own resources as "
                        + "its PREFIX (triggered=%s, inlined=%s)", i, j, own.get(j), all.get(j)));
            }

            // And the tail is closure only - no seed resource reappears after the prefix, which
            // is what "not interleaved" means positionally.
            Set<Resource> seeds = new LinkedHashSet<>(own);
            for (int j = own.size(); j < all.size(); j++) {
                assertFalse(seeds.contains(all.get(j)), String.format(
                        "txn %d operation %d: seed resource %s appears AFTER the prefix - the "
                        + "closure is interleaved with the parent's own operations", i, j,
                        all.get(j)));
            }
        }
        assertTrue(expandedAtLeastOnce > 0,
                "no transaction expanded at all - the test would pass vacuously; semanticOverlap "
                + "or the oracle schema is not reaching TransactionFactory");
    }

    /**
     * The tail is not merely "not seeds" - it is the schema's transitive closure of the seeds, at
     * the same instance id. Pins the identity correspondence the two arms share, so a change to
     * expansion that kept the prefix but produced different targets cannot pass.
     */
    @Test
    void theTailIsExactlyTheSchemaClosureOfThePrefix() {
        SimulatorParameters p = params(true);
        List<Model> pool = new ModelFactory(p.instancePoolSize).createModels(p.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(p, pool);
        CprSchema cprSchema = schema(config);

        for (Transaction t : generate(true, 50)) {
            List<Resource> all = operationResources(t);
            List<Resource> prefix = all.subList(0, K);

            Set<Resource> expected = new LinkedHashSet<>(prefix);
            for (Resource seed : prefix) {
                for (var target : cprSchema.closure(seed.property())) {
                    expected.add(new Resource(seed.instanceId(), target));
                }
            }
            assertEquals(new ArrayList<>(expected), all,
                    "inlined footprint must be prefix-then-closure, in expansion order");
        }
    }
}
