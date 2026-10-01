package test.java.scheduling;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.execution.TransactionIntakePhaseDispatcher;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.locking.coordinator.C2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.GrantedDecision;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.FIFOScheduler;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestIOModelFactory;

public class SchedulerHappyPathIntakeTest {

    private static class NoopIO extends IOModel {
        public NoopIO() { super(test.java.util.TestConfigFactory.create(0,0,0,0,0,0,0, List.of(), 0,0,0,0,0,0,0)); }
        @Override public long getIOTime(Transaction t) { return 0; }
        @Override public long getCPUTime(Transaction t) { return 0; }
        @Override public long getLockAcquisitionTime(Transaction t) { return 0; }
        @Override public long getTotalTime(Transaction t) { return 0; }
    }

    private static class GrantAllLockManager implements LockManager {
        @Override public void releaseLocks(Transaction tx) {}
        @Override public GrantedDecision requestLock(Transaction tx, String resource, LockType type) { return new GrantedDecision(); }
        @Override public boolean causesDeadlock(Transaction tx, String resource) { return false; }
		@Override
		public void abortTransactionGroup(TransactionGroup group) {
		}
		@Override
		public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) {
			return true;
		}
		@Override
		public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) {
			return null;
		}
		@Override
		public void registerWaiter(Transaction txn, String lockId) {}
		@Override
		public Set<Transaction> getWaitersForTransaction(Transaction holder) { return Set.of(); }
    }

    private static class SimpleProcessor extends TransactionProcessor {
        public boolean processed = false;
        private static final List<Model> MODEL_POOL = new ModelFactory(1000).createModels(List.of(1));
        private static final TransactionLockCoordinator MOCK_COORDINATOR =
            new C2PLTransactionLockCoordinator(new GrantAllLockManager(), null, null,
                new LockGranularityMapper(1000, MODEL_POOL), null);
        
        public SimpleProcessor(TransactionLockCoordinator c, TransactionPhaseInitializer i, 
                             BookkeeperCreationCoordinator coord, BookkeeperQueueRouter router) { 
            super(c, i, coord, router, null, null); 
        }
        
        @Override 
        public boolean process(Transaction txn) { 
            processed = true; 
            return true; 
        }
        
        @Override 
        public TransactionLockCoordinator getLockCoordinator() { 
            return MOCK_COORDINATOR;
        }
    }

    @Test
    public void schedulerCreatesRegisteredGroupsAndIntakeProcesses() {
        CentralQueueManager qm = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        Scheduler scheduler = new FIFOScheduler(qm);
        SimulatorConfig cfg = test.java.util.TestConfigFactory.create(0,0,0,0,0,0,1, List.of(), 0,0,0,0,0,0,0);
        TransactionGroupRegistry registry = new TransactionGroupRegistry();
        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator());

        // Use TransactionScheduler to create and enqueue
        TransactionScheduler ts = new TransactionScheduler(cfg, qm, factory, registry);
        Transaction parent = ts.createTransaction();
        qm.enqueue(parent, QueueType.PENDING);

        // processor doesn't care about locks/phase init for this test
        SimpleProcessor processor = new SimpleProcessor(null, null, null, null);
        // Pass queueManager to constructor so it's not null
        TransactionIntakePhaseDispatcher disp = new TransactionIntakePhaseDispatcher(
            scheduler, processor, new NoopIO(), null, null, qm);

        long used = disp.dispatch(10, 0);  // Pass virtualTime = 0
        Assertions.assertEquals(0, used);
        Assertions.assertTrue(processor.processed, "transaction should have been processed by intake");
        Assertions.assertNotNull(parent.getGroup(), "scheduler-created txn must have group");
        Assertions.assertNotNull(registry.get(parent), "group should be registered");
    }
}
