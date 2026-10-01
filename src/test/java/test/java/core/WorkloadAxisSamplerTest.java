package test.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.ResourceAccessDistribution;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
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

/**
 * The two W1-W4 workload knobs: Zipfian access skew on {@code instanceId}, and the two-class
 * read-only/update workload mix.
 *
 * <p>The load-bearing test here is {@link #zipfAtThetaZeroIsBitIdenticalToUniform()}. Every
 * experiment that is not sweeping theta runs at the base setting, so if theta = 0 were merely
 * <em>distributionally</em> uniform rather than bit-identical, enabling the knob would silently
 * move every other experiment's numbers - and the move would look like a result.
 */
class WorkloadAxisSamplerTest {

    private static final int TOTAL_MODELS = 4;
    private static final int PROPERTIES_PER_MODEL = 5;
    private static final int INSTANCE_POOL = 200;
    private static final int K = 10;
    private static final long SEED = 1000003L;

    private static SimulatorParameters.Builder base() {
        List<Integer> propertiesPerModel = new ArrayList<>();
        for (int i = 0; i < TOTAL_MODELS; i++) {
            propertiesPerModel.add(PROPERTIES_PER_MODEL);
        }
        int d = TOTAL_MODELS * PROPERTIES_PER_MODEL * INSTANCE_POOL;
        return SimulatorParameters.builder()
                .totalModels(TOTAL_MODELS)
                .propertiesPerModel(propertiesPerModel)
                .instancePoolSize(INSTANCE_POOL)
                .totalResources(d)
                .totalLocks(d)
                .semanticOverlap(0.0)
                .useConstantTransactionSize(true)
                .constantTransactionSize(K)
                .maxTransactionModels(3)
                .useRandomResourceSelection(true)
                .transactionSamplingSeed(SEED)
                .semanticOverlapSeed(SEED);
    }

    private static List<Transaction> generate(SimulatorParameters params, int count) {
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

    /** Ordered resource sequences - NOT the sorted trace key, which is order-blind. */
    private static List<List<Resource>> footprints(SimulatorParameters params, int count) {
        List<List<Resource>> out = new ArrayList<>(count);
        for (Transaction t : generate(params, count)) {
            out.add(new ArrayList<>(t.getResources()));
        }
        return out;
    }

    /**
     * ZIPF at theta = 0 must reproduce UNIFORM's draw SEQUENCE, not merely its distribution.
     *
     * <p>Compared as ordered lists on purpose. {@code TransactionFactory.seedResourceTraceKey}
     * sorts, so any digest built on it proves set equality and nothing more - which is exactly how
     * a closure-ordering defect stayed hidden through a determinism check while throughput
     * differed by ~12%. Order is the property at issue here, so order is what is asserted.
     */
    @Test
    void zipfAtThetaZeroIsBitIdenticalToUniform() {
        List<List<Resource>> uniform = footprints(
                base().accessDistribution(ResourceAccessDistribution.UNIFORM).build(), 300);
        List<List<Resource>> zipfZero = footprints(
                base().accessDistribution(ResourceAccessDistribution.ZIPF).zipfTheta(0.0).build(), 300);

        assertEquals(uniform, zipfZero,
                "ZIPF at theta=0 must short-circuit to the UNIFORM code path verbatim. A Zipf(0) "
                        + "draw is uniform in DISTRIBUTION but consumes a different RNG sequence, "
                        + "which would silently move every experiment not sweeping theta.");
    }

    /** The guard above is only meaningful if a nonzero theta actually changes the draw. */
    @Test
    void nonzeroThetaActuallyChangesTheDraw() {
        List<List<Resource>> uniform = footprints(
                base().accessDistribution(ResourceAccessDistribution.UNIFORM).build(), 300);
        List<List<Resource>> skewed = footprints(
                base().accessDistribution(ResourceAccessDistribution.ZIPF).zipfTheta(0.9).build(), 300);

        assertNotEquals(uniform, skewed, "theta=0.9 must not reproduce the uniform draw");
    }

    /**
     * check the RANGE at the point of generation, against the real container.
     * Count- and distinctness-based checks cannot see an off-by-one - a nonexistent id is
     * perfectly distinct, and the array is still full.
     */
    @Test
    void skewedInstanceIdsStayInsideThePool() {
        int minSeen = Integer.MAX_VALUE;
        int maxSeen = Integer.MIN_VALUE;
        for (List<Resource> footprint : footprints(
                base().accessDistribution(ResourceAccessDistribution.ZIPF).zipfTheta(0.9).build(), 2000)) {
            for (Resource r : footprint) {
                minSeen = Math.min(minSeen, r.instanceId());
                maxSeen = Math.max(maxSeen, r.instanceId());
            }
        }
        assertEquals(1, minSeen, "instance ids are 1-based; a 0 would name no resource");
        assertTrue(maxSeen <= INSTANCE_POOL,
                "instance id " + maxSeen + " exceeds instancePoolSize " + INSTANCE_POOL);
    }

    /** Skew must actually concentrate access, otherwise theta is wired but inert (C7/C15). */
    @Test
    void skewConcentratesAccessOnLowInstanceIds() {
        assertTrue(topDecileShare(0.9) > 1.6 * topDecileShare(0.0),
                "theta=0.9 must concentrate access materially more than theta=0");
    }

    private static double topDecileShare(double theta) {
        SimulatorParameters params = theta == 0.0
                ? base().accessDistribution(ResourceAccessDistribution.UNIFORM).build()
                : base().accessDistribution(ResourceAccessDistribution.ZIPF).zipfTheta(theta).build();
        int hot = 0;
        int total = 0;
        int hotCutoff = INSTANCE_POOL / 10;
        for (List<Resource> footprint : footprints(params, 2000)) {
            for (Resource r : footprint) {
                total++;
                if (r.instanceId() <= hotCutoff) {
                    hot++;
                }
            }
        }
        return hot / (double) total;
    }

    // ---- two-class workload -------------------------------------------------------------

    /**
     * An update transaction carries exactly {@code ceil(n/2)} writes over its realised footprint
     * size, and a read-only transaction carries none.
     */
    @Test
    void twoClassProducesExactWriteCounts() {
        SimulatorParameters params = base()
                .workloadMix(WorkloadMix.TWO_CLASS)
                .readOnlyTransactionFraction(0.5)
                .build();

        int readOnly = 0;
        int updates = 0;
        for (Transaction t : generate(params, 600)) {
            int n = t.getResources().size();
            long writes = t.getOperations().stream()
                    .filter(op -> op.getType() == OperationType.WRITE).count();
            if (writes == 0) {
                readOnly++;
            } else {
                updates++;
                assertEquals((n + 1) / 2, writes,
                        "an update transaction must carry exactly ceil(n/2) writes over its "
                                + "REALISED footprint size (n=" + n + ")");
            }
        }
        assertTrue(readOnly > 0 && updates > 0,
                "at readOnlyTransactionFraction=0.5 both classes must appear (saw "
                        + readOnly + " read-only, " + updates + " update)");
    }

    /** The realised read-only share must track the nominal fraction. */
    @Test
    void realisedReadOnlyShareTracksTheKnob() {
        for (double nominal : new double[] {0.0, 0.25, 0.9}) {
            SimulatorParameters params = base()
                    .workloadMix(WorkloadMix.TWO_CLASS)
                    .readOnlyTransactionFraction(nominal)
                    .build();
            int readOnly = 0;
            int n = 2000;
            for (Transaction t : generate(params, n)) {
                if (t.isReadOnly()) {
                    readOnly++;
                }
            }
            double realised = readOnly / (double) n;
            assertTrue(Math.abs(realised - nominal) < 0.04,
                    "realised read-only share " + realised + " should track nominal " + nominal);
        }
    }

    /**
     * Write POSITIONS are shuffled rather than prefixed. A prefix would put every write on the
     * low-ordinal resources of the footprint, interacting with lock ordering and with any skew on
     * the sampler - so this asserts writes appear in the back half too.
     */
    @Test
    void twoClassWritePositionsAreShuffledNotPrefixed() {
        SimulatorParameters params = base()
                .workloadMix(WorkloadMix.TWO_CLASS)
                .readOnlyTransactionFraction(0.0)
                .build();

        int writesInBackHalf = 0;
        for (Transaction t : generate(params, 400)) {
            List<Operation> ops = t.getOperations();
            for (int i = ops.size() / 2; i < ops.size(); i++) {
                if (ops.get(i).getType() == OperationType.WRITE) {
                    writesInBackHalf++;
                }
            }
        }
        assertTrue(writesInBackHalf > 0,
                "every write landed in the front half - positions are prefixed, not shuffled");
    }

    /**
     * a knob that is inert must be rejected, not silently ignored. Under
     * TWO_CLASS the class sampler assigns every operation type, so writeFraction does nothing.
     */
    @Test
    void twoClassRejectsANonDefaultWriteFraction() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> base().workloadMix(WorkloadMix.TWO_CLASS).writeFraction(0.5).build());
        assertTrue(e.getMessage().contains("inert"), "the error must name the failure: " + e.getMessage());
    }

    /** The mirror case: a read-only fraction set without TWO_CLASS would do nothing. */
    @Test
    void perOperationRejectsAReadOnlyFraction() {
        assertThrows(IllegalArgumentException.class,
                () -> base().readOnlyTransactionFraction(0.5).build());
    }

    /**
     * Zipf over instance ids and contiguous-block selection are different clustering mechanisms;
     * combining them would confound the theta axis, so it is refused rather than silently allowed.
     */
    @Test
    void zipfRequiresRandomResourceSelection() {
        assertThrows(IllegalArgumentException.class,
                () -> base().useRandomResourceSelection(false)
                        .accessDistribution(ResourceAccessDistribution.ZIPF)
                        .zipfTheta(0.5)
                        .build());
    }

    /** ...but theta = 0 is allowed with either selection mode, since it is the uniform path. */
    @Test
    void zipfAtThetaZeroIsAllowedWithSequentialSelection() {
        base().useRandomResourceSelection(false)
                .accessDistribution(ResourceAccessDistribution.ZIPF)
                .zipfTheta(0.0)
                .build();
    }
}
