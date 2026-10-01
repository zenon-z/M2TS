package test.java.locking;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;
import de.tum.tssimulator.locking.coordinator.C2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.coordinator.S2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.policy.LockingProtocol;

/**
 * End-to-end wiring check for {@code lockingProtocol}: a config asking for S2PL must actually
 * produce an {@link S2PLTransactionLockCoordinator}, all the way through
 * {@code SimulatorParameters -> SimulatorConfig -> SimulatorContext -> DefaultLockingBundle}.
 *
 * <p>Exists because the protocol was, until now, selectable in three places and honoured in none
 * of the ones that mattered for the experiment grid. {@code SeedSweepRunner.copyBuilder()} silently
 * dropped {@code lockingProtocol} from its ~45-field copy list, and
 * {@code DynamicFootprintExperiment.runConfigSweep()} rebuilds its parameters per-MPL through that
 * method - so every actually-executed run in the {@code s2pl} and {@code s2pl-oracle} arms reverted
 * to the Builder's C2PL default. The arms were named S2PL, logged as S2PL, and ran C2PL. Every
 * committed S2PL result predating this test is C2PL data.
 *
 * <p>{@code SeedSweepRunnerCopyBuilderRoundTripTest} now guards the copy list itself. This test
 * guards the other end: that the field, once preserved, is actually acted on. The two together
 * cover the whole path, which is what "confirm the S2PL arms really run S2PL" requires - neither
 * alone would have caught the incident, since the field was always set correctly at the source and
 * always honoured at the sink; only the middle dropped it.
 */
class LockingProtocolWiringTest {

    @Test
    void s2plConfigProducesS2plCoordinator() {
        assertInstanceOf(S2PLTransactionLockCoordinator.class,
                coordinatorFor(LockingProtocol.S2PL),
                "lockingProtocol=S2PL must select the S2PL coordinator");
    }

    @Test
    void c2plConfigProducesC2plCoordinator() {
        assertInstanceOf(C2PLTransactionLockCoordinator.class,
                coordinatorFor(LockingProtocol.C2PL),
                "lockingProtocol=C2PL must select the C2PL coordinator");
    }

    /**
     * The protocol must survive a {@code copyBuilder()} round trip on the way, because that is the
     * step the experiment grid actually goes through - asserting on a freshly-built params object
     * would pass even with the historical bug present.
     */
    @Test
    void protocolSurvivesTheCopyBuilderRoundTripTheExperimentGridUses() {
        SimulatorParameters direct = paramsWith(LockingProtocol.S2PL);
        SimulatorParameters viaSweepRebuild = SeedSweepRunner.copyBuilder(direct)
                .initialTransactions(direct.initialTransactions + 1) // what runConfigSweep() does per MPL
                .build();

        assertInstanceOf(S2PLTransactionLockCoordinator.class,
                coordinatorFor(viaSweepRebuild),
                "a per-MPL copyBuilder() rebuild must not silently revert the arm to C2PL");
    }

    private static Object coordinatorFor(LockingProtocol protocol) {
        return coordinatorFor(paramsWith(protocol));
    }

    private static Object coordinatorFor(SimulatorParameters params) {
        List<Model> modelPool = new ModelFactory(params.instancePoolSize)
                .createModels(params.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext ctx = SimulatorContext.create(config);
        return ctx.locking().txnCoordinator();
    }

    /** Deliberately small - this test builds a context, it never runs a simulation. */
    private static SimulatorParameters paramsWith(LockingProtocol protocol) {
        return SimulatorParameters.builder()
                .totalModels(1)
                .propertiesPerModel(List.of(1))
                // 0.0 required, not incidental: a single model cannot carry a cross-model CPR, and
                // SimulatorConfig rejects semanticOverlap > 0 in that shape. Same single-model,
                // zero-overlap shape DynamicFootprintExperiment.configStatic() uses.
                .semanticOverlap(0.0)
                .instancePoolSize(50)
                .totalLocks(50)
                .initialTransactions(4)
                .useConstantTransactionSize(true)
                .constantTransactionSize(2)
                .maxTransactionModels(1)
                .numberOfIoProcessors(2)
                .numberOfCpuProcessors(2)
                .simulationDurationSeconds(1)
                .transactionSamplingSeed(1L)
                .semanticOverlapSeed(1L)
                .lockingProtocol(protocol)
                .build();
    }
}
