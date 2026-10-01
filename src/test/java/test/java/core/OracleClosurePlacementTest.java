package test.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.config.OracleClosurePlacement;
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
 * {@link OracleClosurePlacement#INTERLEAVED} puts each closure target directly after the WRITE seed
 * that sources it, and changes NOTHING ELSE.
 *
 * <p>Both halves matter and they are asserted separately:
 *
 * <ul>
 *   <li><b>Same set as AT_END.</b> This is what makes a throughput difference between the two
 *       inlined arms a TIMING result. If placement changed the footprint the comparison would be
 *       measuring work, and in the direction that flatters whichever arm has less of it.</li>
 *   <li><b>Position.</b> Every closure operation directly follows its own source. A set assertion is
 *       blind to this by construction - the same reason
 *       {@code OracleClosureOperationOrderTest} is indexed rather than set-based.</li>
 * </ul>
 */
class OracleClosurePlacementTest {

    private static final int K = 10;
    private static final long SEED = 1000003L;

    private static SimulatorParameters params(OracleClosurePlacement placement) {
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
                .oracleClosurePlacement(placement)
                .build();
    }

    private static CprSchema schema(SimulatorConfig config) {
        return new CprSchemaBuilder(config.modelPool, config.cprTopologyMode, config.semanticOverlap,
                config.avgCprsPerResource, config.maxCprsPerResource, config.semanticOverlapSeed,
                config.cprInitialAttractiveness, config.cprAcyclicTotalOrder)
                .build();
    }

    private static List<Transaction> generate(OracleClosurePlacement placement, int count) {
        SimulatorParameters p = params(placement);
        List<Model> pool = new ModelFactory(p.instancePoolSize).createModels(p.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(p, pool);
        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(),
                p.transactionSamplingSeed, schema(config));
        List<Transaction> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(factory.createTransaction(config, new TransactionGroup()));
        }
        return out;
    }

    private static List<Resource> resources(Transaction t) {
        List<Resource> rs = new ArrayList<>(t.getOperations().size());
        for (Operation op : t.getOperations()) {
            rs.add(op.getResource());
        }
        return rs;
    }

    /** Work parity: identical resource SET per transaction, so only the order differs. */
    @Test
    void interleavedTouchesExactlyTheSameResourcesAsAtEnd() {
        List<Transaction> atEnd = generate(OracleClosurePlacement.AT_END, 300);
        List<Transaction> inter = generate(OracleClosurePlacement.INTERLEAVED, 300);
        int reordered = 0;
        for (int i = 0; i < atEnd.size(); i++) {
            List<Resource> a = resources(atEnd.get(i));
            List<Resource> b = resources(inter.get(i));
            assertEquals(new HashSet<>(a), new HashSet<>(b), String.format(
                    "txn %d: placement must not change WHICH resources are touched (%d vs %d)",
                    i, a.size(), b.size()));
            assertEquals(a.size(), b.size(),
                    "placement must not change the footprint SIZE either (dedup must match)");
            if (!a.equals(b)) {
                reordered++;
            }
        }
        assertTrue(reordered > 0,
                "no transaction was reordered - INTERLEAVED is not reaching TransactionFactory, and "
                + "the set assertions above would pass vacuously against AT_END");
    }

    /**
     * Position: every closure operation directly follows its source, contiguously.
     *
     * <p>The seed set is taken EXACTLY, from the AT_END run's first k positions - which
     * {@code OracleClosureOperationOrderTest} pins as the seed prefix - rather than inferred from
     * the schema. An earlier version of this test inferred it ("a resource nobody else can source"),
     * which misclassified every LEAF closure target as a seed and failed on correct output. The
     * factory was right and the test's own heuristic was wrong; taking the set from the paired run
     * removes the guess.
     */
    @Test
    void everyClosureOperationDirectlyFollowsItsSource() {
        SimulatorParameters p = params(OracleClosurePlacement.INTERLEAVED);
        List<Model> pool = new ModelFactory(p.instancePoolSize).createModels(p.propertiesPerModel);
        CprSchema cprSchema = schema(SimulatorConfig.fromParameters(p, pool));

        List<Transaction> atEnd = generate(OracleClosurePlacement.AT_END, 300);
        List<Transaction> inter = generate(OracleClosurePlacement.INTERLEAVED, 300);

        int closureOps = 0;
        for (int i = 0; i < inter.size(); i++) {
            Set<Resource> seeds = new LinkedHashSet<>(resources(atEnd.get(i)).subList(0, K));
            List<Resource> rs = resources(inter.get(i));
            List<Operation> ops = inter.get(i).getOperations();

            // The source that owns the current run of closure operations. Set when a WRITE seed is
            // passed, cleared by a READ seed - which owns nothing, since a read is not a CPR source.
            Resource owner = null;
            for (int j = 0; j < rs.size(); j++) {
                Resource r = rs.get(j);
                if (seeds.contains(r)) {
                    owner = ops.get(j).getType() == OperationType.WRITE ? r : null;
                    continue;
                }
                closureOps++;
                assertTrue(owner != null, String.format(
                        "txn %s op %d: closure resource %s appears with no WRITE seed owning the run "
                        + "it is in - it does not follow its source", inter.get(i).getId(), j, r));
                assertTrue(canSource(cprSchema, owner, r), String.format(
                        "txn %s op %d: closure resource %s is in the run owned by %s but is not in "
                        + "that seed's closure - the interleaving attached it to the wrong source",
                        inter.get(i).getId(), j, r, owner));
            }
        }
        assertTrue(closureOps > 0, "no closure operation seen - test would pass vacuously");
    }

    /**
     * The contrast that gives the test above its teeth: under AT_END the same check FAILS, because
     * the closure sits in one block after the last seed rather than after its own source. Without
     * this, a placement flag that silently did nothing would satisfy the positional test whenever
     * the last seed happened to source the whole closure.
     */
    @Test
    void atEndPlacementDoesNotSatisfyTheDirectlyFollowsProperty() {
        SimulatorParameters p = params(OracleClosurePlacement.AT_END);
        List<Model> pool = new ModelFactory(p.instancePoolSize).createModels(p.propertiesPerModel);
        CprSchema cprSchema = schema(SimulatorConfig.fromParameters(p, pool));

        int violations = 0;
        for (Transaction t : generate(OracleClosurePlacement.AT_END, 300)) {
            List<Resource> rs = resources(t);
            List<Operation> ops = t.getOperations();
            Set<Resource> seeds = new LinkedHashSet<>(rs.subList(0, K));
            Resource owner = null;
            for (int j = 0; j < rs.size(); j++) {
                Resource r = rs.get(j);
                if (seeds.contains(r)) {
                    owner = ops.get(j).getType() == OperationType.WRITE ? r : null;
                    continue;
                }
                if (owner == null || !canSource(cprSchema, owner, r)) {
                    violations++;
                }
            }
        }
        assertTrue(violations > 0,
                "AT_END satisfied the directly-follows property on every operation, so the "
                + "positional test above cannot distinguish the two placements");
    }

    private static boolean canSource(CprSchema schema, Resource seed, Resource target) {
        return seed.instanceId() == target.instanceId()
                && schema.closure(seed.property()).contains(target.property());
    }



}
