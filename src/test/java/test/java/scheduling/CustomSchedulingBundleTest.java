package test.java.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperCoordinatorFactory;
import de.tum.tssimulator.bookkeeping.BookkeeperFactory;
import de.tum.tssimulator.bootstrap.DefaultExecutionBundle;
import de.tum.tssimulator.bootstrap.DefaultLockingBundle;
import de.tum.tssimulator.bootstrap.DefaultRuntimeServices;
import de.tum.tssimulator.bootstrap.SchedulingBundle;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestIOModelFactory;

public class CustomSchedulingBundleTest {

    private static class NoReadyScheduler implements Scheduler {
        @Override public Transaction nextTransaction() { return null; }
        @Override public boolean hasReady() { return false; }
    }

    private static class TestSchedulingBundle implements SchedulingBundle {
        private final QueuePort queues;
        private final Scheduler scheduler;
        private final TransactionScheduler txnScheduler;
        TestSchedulingBundle(SimulatorConfig cfg, TransactionFactory factory, TransactionGroupRegistry registry) {
            CentralQueueManager qm = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
            this.queues = qm;
            this.scheduler = new NoReadyScheduler();
            this.txnScheduler = new TransactionScheduler(cfg, qm, factory, registry);
        }
        @Override public QueuePort queues() { return queues; }
        @Override public Scheduler scheduler() { return scheduler; }
        @Override public TransactionScheduler txnScheduler() { return txnScheduler; }
    }

    @Test
    void canSwapSchedulerViaBundle() {
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

        SchedulingBundle customScheduling = new TestSchedulingBundle(cfg, factory, registry);
        var schedulingQueues = (CentralQueueManager) customScheduling.queues();

        // Create locking and execution bundles using the custom queue and scheduler
        var logger = new SimulationLogger("test.log");
        var locking = new DefaultLockingBundle(cfg, schedulingQueues, customScheduling.scheduler(), logger);
        
        var coordinator = BookkeeperCoordinatorFactory.createCreationCoordinator(
                ConcurrencyModel.ACID,
                (BookkeeperFactory) txn -> List.of(), // no-op factory - this test exercises wiring, not CPR behavior
                logger,
                registry,
                null,  // orchestrator
                null,  // dirtySetManager
                null,  // phaseInitializer
                false,  // hasDedicatedReconcilers
                null  // virtualTimeClock (not needed for ACID)
        );
        
        var router = BookkeeperCoordinatorFactory.createQueueRouter(
                ConcurrencyModel.ACID,
                logger,
                schedulingQueues,
                false  // hasDedicatedReconcilers
        );
        
        // Wire locking bundle before using txnCoordinator (Issue #2 fix)
        locking.wireRegistry(registry);
        locking.wireScheduler(customScheduling.txnScheduler());
        
        var execution = new DefaultExecutionBundle(cfg, schedulingQueues, customScheduling.scheduler(), 
                locking.txnCoordinator(), coordinator, router, logger);

        DefaultRuntimeServices runtimeServices = new DefaultRuntimeServices(
            cfg,
            logger,
            null,
            registry,
            null);

        SimulatorContext ctx = new SimulatorContext.Builder()
            .with(runtimeServices)
            .with(customScheduling)
            .with(locking)
            .with(execution)
            .build();

        assertNotNull(ctx);
        assertNotNull(ctx.scheduling());
        assertFalse(ctx.scheduling().scheduler().hasReady(), "Custom NoReadyScheduler should report no ready txns");
    }
}
