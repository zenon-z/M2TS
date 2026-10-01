package test.java.experiments.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.CuQueuePolicy;
import de.tum.tssimulator.config.OracleClosurePlacement;
import de.tum.tssimulator.config.ReadyWaiterOrder;
import de.tum.tssimulator.config.ResourceAccessDistribution;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.config.TransactionMode;
import de.tum.tssimulator.config.TransactionSizeProfile;
import de.tum.tssimulator.config.WorkloadMix;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;
import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import de.tum.tssimulator.locking.policy.LockingProtocol;

/**
 * Reflection-based round-trip test for
 * {@link SeedSweepRunner#copyBuilder(SimulatorParameters)}. Exists because
 * that method silently dropped {@code propertiesPerModel}/{@code instancePoolSize} from its copy
 * list - {@link SimulatorParameters.Builder#build()} falls back to defaults for unset fields
 * rather than erroring, so the omission produced a runnable, plausible-looking, WRONG config
 * instead of a crash. It cost two rounds of A/B footprint analysis (Config B silently ran a
 * closure~49 graph instead of the calibrated closure~98 one) before a suspicious
 * too-identical-to-be-real comparison surfaced it.
 *
 * <p>Every public field on {@link SimulatorParameters} is set to a value distinct from both the
 * {@code Builder}'s own default AND from every other field of the same type (so a copy-paste
 * mix-up between two same-typed fields would also be caught, not just an outright omission), then
 * round-tripped through {@code copyBuilder(...).build()} and compared field-by-field via
 * reflection. A future field addition to {@code SimulatorParameters} that isn't wired into
 * {@code copyBuilder()} fails this test automatically instead of silently corrupting the next
 * experiment that relies on {@code copyBuilder()} to preserve a non-default value.
 */
class SeedSweepRunnerCopyBuilderRoundTripTest {

    @Test
    void copyBuilderPreservesEveryField() throws IllegalAccessException {
        SimulatorParameters original = SimulatorParameters.builder()
                .totalModels(7)
                .totalResources(12345)
                .propertiesPerModel(List.of(3, 4, 5, 6, 7, 8, 9))
                .instancePoolSize(42)
                .ioTimePerResource(201)
                .cpuTimePerResource(102)
                .lockIOTimePerResource(203)
                .lockCPUTimePerResource(104)
                .rollbackFixed(51L)
                .rollbackCPUTimePerResource(11L)
                .initialTransactions(211)
                .totalTransactionSteps(10007)
                .maxTransactionModels(6)
                .maxTransactionResources(53)
                .useConstantTransactionSize(true)
                .constantTransactionSize(54)
                .transactionSizeProfile(TransactionSizeProfile.BIMODAL)
                .smallTransactionSize(11)
                .largeTransactionSize(101)
                .largeTransactionProbability(0.37)
                .useRandomResourceSelection(true)
                .semanticOverlap(0.061)
                .maxCprsPerResource(9)
                .avgCprsPerResource(3.5)
                .cprTopologyMode(CprTopologyMode.RING)
                .referentialAction(ReferentialAction.ON_RESTRICT)
                .concurrencyModel(ConcurrencyModel.BASE)
                .admissionBatchSize(5)
                .warmupSeconds(13)
                .simulationDurationSeconds(10013)
                .numberOfIoProcessors(16)
                .numberOfCpuProcessors(17)
                .numberOfReconcilerProcessors(2)
                .totalLocks(2001)
                .transactionSamplingSeed(999001L)
                .semanticOverlapSeed(999002L)
                .stepTimeSlice(1013L)
                .transactionMode(TransactionMode.TERMINAL)
                .numTerminals(213)
                .meanInterArrivalDelay(1017L)
                .transactionGenerationMode(TransactionGenerationMode.TRANSACTION_PROGRAMS)
                // Added after the lockingProtocol incident (below): these six were never set here,
                // which is exactly why this test did not catch that copyBuilder() dropped
                // lockingProtocol - see assertNoFieldLeftAtItsDefault().
                .deadlockStrategy(DeadlockStrategy.WOUND_WAIT)
                .deadlockDetectionInterval(5003L)
                .deadlockDetectionBetaThreshold(0.31)
                .deadlockDetectionMinCooldown(1009L)
                .oracleClosurePreclaim(true)
                .oracleClosurePlacement(OracleClosurePlacement.INTERLEAVED)
                .lockPoolSize(7)
                .lockingProtocol(LockingProtocol.S2PL)
                .writeFraction(0.37)
                .cuOperationType(OperationType.READ)
                .writeOnlyCprSources(false)
                .operationBatchSize(3)
                .groupUnitAffinity(false)
                // W1-W4 access-skew knobs. Orthogonal to workloadMix, so they live in this
                // fixture; the two workload-mix fields cannot (see twoClassFixture()).
                .accessDistribution(ResourceAccessDistribution.ZIPF)
                .zipfTheta(0.73)
                // Non-null and <= initialTransactions (211 above): the Builder default is null
                // ("derive from processors"), so an unset value would make both sides equal.
                .maxConcurrentRunning(199)
                // Non-null and > 0 (alpha = 0 is rejected at validation), and the total order
                // non-default, so both are actually exercised by the round trip.
                .cprInitialAttractiveness(0.37)
                .cprAcyclicTotalOrder(true)
                // Non-1.0 and > 0 (validation rejects <= 0). Dropped from copyBuilder, this field
                // would silently collapse a backoff-sensitivity sweep to three runs of the SAME
                // policy that all still carry distinct config_hash values - three configurations
                // the output claims to distinguish and does not. That is the failure mode this
                // whole test exists for, arriving through a knob added after it was written.
                .backoffScale(2.5)
                // Non-default and multi-element: the Builder default is List.of(), so an
                // unset value would make both sides equal and test nothing -- which is what
                // assertNoFieldLeftAtItsDefault() below exists to refuse. totalModels is 7
                // here, so 0 and 2 are valid indices, and two entries also exercise the
                // List.copyOf() in build() rather than just a reference carry.
                .transactionModelIndices(List.of(0, 2))
                .build();

        // The round-trip comparison below is only meaningful for fields whose value here DIFFERS
        // from the Builder's own default - if a field is left unset, both sides get the same
        // default and the comparison passes without testing anything. That is not hypothetical:
        // it is precisely how this test kept passing while copyBuilder() silently dropped
        // lockingProtocol, reverting every s2pl-arm run in DynamicFootprintExperiment to C2PL.
        // Check the premise before relying on it.
        // Two fixtures, because workloadMix=TWO_CLASS and a non-default writeFraction are
        // MUTUALLY EXCLUSIVE by validation (under TWO_CLASS the class sampler assigns every
        // operation type, so writeFraction would be inert, and a knob that looks live but is dead
        // is the failure that rule exists for). No single fixture can therefore leave every field
        // non-default. Coverage is asserted over their UNION rather than by exempting the three
        // fields by name - an exemption list would let a genuinely-forgotten field hide behind a
        // legitimate-looking entry, which is the same shape as the bug this whole test guards.
        assertEveryFieldNonDefaultInSomeFixture(original, twoClassFixture());

        SimulatorParameters copy = SeedSweepRunner.copyBuilder(original).build();

        for (Field field : SimulatorParameters.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object originalValue = field.get(original);
            Object copyValue = field.get(copy);
            if (!Objects.equals(originalValue, copyValue)) {
                fail("copyBuilder() dropped field '" + field.getName() + "': original=" + originalValue
                        + " copy=" + copyValue + " - add ." + field.getName() + "(params." + field.getName()
                        + ") to SeedSweepRunner.copyBuilder()");
            }
        }
        // Also assert on the field most directly responsible for the bug this test guards
        // against, so a failure here points straight at the historical incident, not just at
        // reflection output.
        assertEquals(original.propertiesPerModel, copy.propertiesPerModel);
        assertEquals(original.instancePoolSize, copy.instancePoolSize);
        // Same, for the second incident of the identical bug - a dropped lockingProtocol turned
        // every "S2PL" arm into a C2PL run, invisibly, for an entire investigation.
        assertEquals(original.lockingProtocol, copy.lockingProtocol);
    }

    /**
     * The TWO_CLASS half of the fixture pair: exercises {@code workloadMix} and
     * {@code readOnlyTransactionFraction}, which the main fixture cannot set because it uses a
     * non-default {@code writeFraction} and the two are mutually exclusive by validation.
     *
     * <p>Round-tripped in its own test below; also fed to
     * {@link #assertEveryFieldNonDefaultInSomeFixture} so the pair's union covers every field.
     */
    private static SimulatorParameters twoClassFixture() {
        return SimulatorParameters.builder()
                .workloadMix(WorkloadMix.TWO_CLASS)
                .readOnlyTransactionFraction(0.41)
                // FIFO because the Builder's default is LIFO: a field left at its default makes
                // the round-trip comparison vacuous for that field, which is what
                // assertEveryFieldNonDefaultInSomeFixture refuses. Which order is correct is not
                // at issue here - only that copyBuilder() carries whichever one was set.
                .readyWaiterOrder(ReadyWaiterOrder.FIFO)
                .cuQueuePolicy(CuQueuePolicy.APPEND)
                // writeFraction deliberately left at its 1.0 default - setting it would throw.
                .build();
    }

    @Test
    void copyBuilderPreservesTwoClassWorkloadFields() {
        SimulatorParameters original = twoClassFixture();
        SimulatorParameters copy = SeedSweepRunner.copyBuilder(original).build();

        assertEquals(original.workloadMix, copy.workloadMix,
                "copyBuilder() dropped workloadMix - add .workloadMix(params.workloadMix)");
        assertEquals(original.readOnlyTransactionFraction, copy.readOnlyTransactionFraction,
                "copyBuilder() dropped readOnlyTransactionFraction");
    }

    /**
     * Guards the premise of {@link #copyBuilderPreservesEveryField()}: every public instance field
     * must differ from a bare {@code builder().build()} in AT LEAST ONE of the given fixtures.
     *
     * <p>Union rather than per-fixture, because mutually-exclusive settings make a single
     * all-non-default fixture impossible. A field left at its default in every fixture still
     * fails, so adding a fixture can only ever widen coverage.
     *
     * <p>Without this guard the round-trip loop degrades silently: a field nobody sets is default
     * on both sides of the comparison, so it is "preserved" no matter what {@code copyBuilder()}
     * does with it - the loop reports success on a field it never exercised. The lockingProtocol
     * incident was exactly this shape: the field existed, the loop covered it by reflection, and
     * the test still passed for as long as the field was absent from the fixture. So a new field
     * added to {@link SimulatorParameters} fails HERE, with an instruction, rather than being
     * waved through.
     */
    private static void assertEveryFieldNonDefaultInSomeFixture(SimulatorParameters... fixtures)
            throws IllegalAccessException {
        SimulatorParameters defaults = SimulatorParameters.builder().build();
        for (Field field : SimulatorParameters.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            boolean coveredSomewhere = false;
            for (SimulatorParameters fixture : fixtures) {
                if (!Objects.equals(field.get(fixture), field.get(defaults))) {
                    coveredSomewhere = true;
                    break;
                }
            }
            if (!coveredSomewhere) {
                fail("Field '" + field.getName() + "' is left at the Builder's default ("
                        + field.get(defaults) + ") in EVERY fixture, so the round-trip comparison"
                        + " cannot detect copyBuilder() dropping it - both sides would simply get"
                        + " the default. Set ." + field.getName() + "(<some non-default value>) on"
                        + " one of the fixtures.");
            }
        }
    }

}
