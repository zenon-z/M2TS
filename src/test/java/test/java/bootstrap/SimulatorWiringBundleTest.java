package test.java.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperCoordinatorFactory;
import de.tum.tssimulator.bookkeeping.BookkeeperFactory;
import de.tum.tssimulator.bootstrap.DefaultExecutionBundle;
import de.tum.tssimulator.bootstrap.DefaultLockingBundle;
import de.tum.tssimulator.bootstrap.DefaultRuntimeServices;
import de.tum.tssimulator.bootstrap.DefaultSchedulingBundle;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestIOModelFactory;

public class SimulatorWiringBundleTest {

    @Test
    void bundlesWireMinimalContext() {
        List<Model> modelPool = new ModelFactory(1).createModels(List.of(1));
        SimulatorConfig cfg = test.java.util.TestConfigFactory.create(
            1, 1,
            0L, 0L,
            0, 0, 1,
            modelPool,
            0, 0,
            0L, 0L,
            1, 1,
            1
        );

        TransactionGroupRegistry registry = new TransactionGroupRegistry();
        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator());

        DefaultSchedulingBundle scheduling = new DefaultSchedulingBundle(cfg, factory, registry, TestIOModelFactory.createTestIOModel());
        DefaultLockingBundle locking = new DefaultLockingBundle(cfg, (CentralQueueManager) scheduling.queues(), scheduling.scheduler(), null);
        
        var coordinator = BookkeeperCoordinatorFactory.createCreationCoordinator(
                ConcurrencyModel.ACID,
                (BookkeeperFactory) txn -> List.of(), // no-op factory - this test exercises wiring, not CPR behavior
                null,
                registry,
                null,  // orchestrator
                null,  // dirtySetManager
                null,  // phaseInitializer
                false,  // hasDedicatedReconcilers
                null  // virtualTimeClock (not needed for ACID)
        );
        
        var router = BookkeeperCoordinatorFactory.createQueueRouter(
                ConcurrencyModel.ACID,
                null,
                (CentralQueueManager) scheduling.queues(),
                false  // hasDedicatedReconcilers
        );
        
        // Wire locking bundle before using txnCoordinator (Issue #2 fix)
        locking.wireRegistry(registry);
        locking.wireScheduler(scheduling.txnScheduler());
        
        DefaultExecutionBundle execution = new DefaultExecutionBundle(cfg, (CentralQueueManager) scheduling.queues(), 
                scheduling.scheduler(), locking.txnCoordinator(), coordinator, router, null);

        DefaultRuntimeServices runtimeServices = new DefaultRuntimeServices(
            cfg,
            null,
            null,
            registry,
            null);

        SimulatorContext ctx = new SimulatorContext.Builder()
            .with(runtimeServices)
            .with(locking)
            .with(scheduling)
            .with(execution)
            .build();

        assertNotNull(ctx);
        assertNotNull(ctx.execution());
        assertNotNull(ctx.execution().processor());
        assertNotNull(ctx.execution().executor());
        assertNotNull(ctx.execution().dispatcher());
        assertNotNull(ctx.scheduling());
        assertNotNull(ctx.scheduling().queues());
        assertNotNull(ctx.scheduling().scheduler());
    }
}
