package test.java.locking.policy;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.HardDenyDecision;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.locking.policy.NoWaitPolicy;
import de.tum.tssimulator.locking.policy.SoftDenyDecision;
import de.tum.tssimulator.locking.policy.WaitDiePolicy;
import de.tum.tssimulator.locking.policy.WaitPolicyStrategy;
import de.tum.tssimulator.locking.policy.WoundWaitPolicy;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

class DeadlockPolicyTest {

    private Transaction oldTxn;
    private Transaction youngTxn;
    private QueueManager queueManager;
    private TransactionScheduler scheduler;
    private TransactionGroupRegistry registry;
    private TransactionFactory factory;

    @BeforeEach
    void setUp() {
        TransactionIdGenerator idGen = new TransactionIdGenerator();
        factory = new TransactionFactory(idGen);
        SimulatorParameters params = SimulatorParameters.builder()
                .totalModels(2)
                .totalResources(5000)
                .ioTimePerResource(200)
                .cpuTimePerResource(100)
                .lockIOTimePerResource(200)
                .lockCPUTimePerResource(100)
                .initialTransactions(200)
                .totalTransactionSteps(10000)
                .maxTransactionModels(1)
                .maxTransactionResources(50)
                .semanticOverlap(0)
                .referentialAction(ReferentialAction.ON_CASCADE)
                .simulationDurationSeconds(10000)
                .numberOfIoProcessors(30)
                .numberOfCpuProcessors(5)
                .totalLocks(200)
                .transactionSamplingSeed(42L)
                .build();

        List<Model> modelPool = new ModelFactory(params.instancePoolSize).createModels(params.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        queueManager = new CentralQueueManager(new IOModel(config));
        registry = new TransactionGroupRegistry();
        scheduler = new TransactionScheduler(config, queueManager, factory, registry);

        oldTxn = factory.createTransaction(config, new TransactionGroup());
        youngTxn = factory.createTransaction(config, new TransactionGroup());

        assertTrue(oldTxn.getTimestamp() < youngTxn.getTimestamp());
    }

    @Test
    void testNoWaitPolicy() {
        WaitPolicyStrategy policy = new NoWaitPolicy(scheduler);
        Set<Transaction> holders = Collections.singleton(youngTxn);

        LockDecision decision = policy.evaluate(oldTxn, holders);
        assertInstanceOf(HardDenyDecision.class, decision, "NoWait should always return HardDeny on conflict");
    }

    @Test
    void testWaitDiePolicy() {
        WaitPolicyStrategy policy = new WaitDiePolicy(QueueType.BLOCKED_TXN, scheduler);
        Set<Transaction> holderIsYoung = Collections.singleton(youngTxn);
        Set<Transaction> holderIsOld = Collections.singleton(oldTxn);

        // Case 1: Old waits
        LockDecision decision1 = policy.evaluate(oldTxn, holderIsYoung);
        assertInstanceOf(SoftDenyDecision.class, decision1, "Old should Wait (SoftDeny)");

        // Case 2: Young dies
        LockDecision decision2 = policy.evaluate(youngTxn, holderIsOld);
        assertInstanceOf(HardDenyDecision.class, decision2, "Young should Die (HardDeny)");
    }

    @Test
    void testWoundWaitPolicy() {
        WoundWaitPolicy policy = new WoundWaitPolicy(queueManager, QueueType.BLOCKED_TXN);
        policy.setScheduler(scheduler);
        policy.setLockManager(new StubLockManager());

        Set<Transaction> holderIsYoung = Collections.singleton(youngTxn);
        Set<Transaction> holderIsOld = Collections.singleton(oldTxn);

        // Case 1: Old wounds Young
        // Expected: Return SoftDeny (because it waits for the lock to be released)
        // but in a real environment the holder should be aborted (this requires Mock
        // verification)
        LockDecision decision1 = policy.evaluate(oldTxn, holderIsYoung);
        assertInstanceOf(SoftDenyDecision.class, decision1, "Old should Wait (after wounding)");

        // Case 2: Young waits
        LockDecision decision2 = policy.evaluate(youngTxn, holderIsOld);
        assertInstanceOf(SoftDenyDecision.class, decision2, "Young should Wait (SoftDeny)");
    }

    private static class StubLockManager implements LockManager {
        @Override
        public LockDecision requestLock(Transaction t, String resourceId, LockType type) {
            return null;
        }

        @Override
        public void releaseLocks(Transaction t) {
        }

        @Override
        public void abortTransactionGroup(TransactionGroup group) {
        }

        @Override
        public boolean causesDeadlock(Transaction t, String blockedOn) {
            return false;
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
        public void registerWaiter(Transaction txn, String lockId) {
        }

        @Override
        public Set<Transaction> getWaitersForTransaction(Transaction holder) {
            return Collections.emptySet();
        }
    }
}
