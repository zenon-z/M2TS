package test.java.locking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.impl.BudgetedLockManagerDecorator;
import de.tum.tssimulator.locking.policy.GrantedDecision;
import de.tum.tssimulator.locking.policy.HardDenyDecision;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.locking.policy.SoftDenyDecision;

class BudgetedDecoratorEdgeCasesTest {

    private static class GrantAllLockManager implements LockManager {
        @Override public LockDecision requestLock(Transaction t, String resource, LockType type) { return new GrantedDecision(); }
        @Override public void releaseLocks(Transaction t) { /* no-op */ }
        @Override public boolean causesDeadlock(Transaction t, String blockedOn) { return false; }
        @Override public void abortTransactionGroup(TransactionGroup group) { /* no-op */ }
        @Override public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) { return true; }
        @Override public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) { return null; }
        @Override public void registerWaiter(Transaction txn, String lockId) {}
        @Override public Set<Transaction> getWaitersForTransaction(Transaction holder) { return Set.of(); }
    }

    @Test
    void hardDenyWithoutDelegationWhenOutOfTokens() {
        GlobalLockBudget budget = new GlobalLockBudget(0, 100);
        LockManager decorated = new BudgetedLockManagerDecorator(new GrantAllLockManager(), budget);
        
        Transaction tx = new Transaction("t1", null, null);
        tx.initializeState();
        TransactionGroup tg = new TransactionGroup(tx);
        tx.group = tg;

        LockDecision d = decorated.requestLock(tx, "R1", LockType.SHARED);
        assertTrue(d instanceof SoftDenyDecision, "Expected soft deny when no tokens available");
        // Ensure budget unchanged
        assertEquals(0, budget.getAvailableTokens());
    }

    @Test
    void deductOnGrantRefundOnNonGrant() {
        GlobalLockBudget budget = new GlobalLockBudget(1, 100);
        // Delegate that denies (soft/hard) to test refund
        LockManager denyAll = new LockManager() {
            @Override public LockDecision requestLock(Transaction t, String resource, LockType type) { return new HardDenyDecision(null); }
            @Override public void releaseLocks(Transaction t) { }
            @Override public boolean causesDeadlock(Transaction t, String blockedOn) { return false; }
            @Override public void abortTransactionGroup(TransactionGroup group) { }
            @Override public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) { return false; }
            @Override public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) { return "BLOCKED"; }
            @Override public void registerWaiter(Transaction txn, String lockId) {}
            @Override public Set<Transaction> getWaitersForTransaction(Transaction holder) { return Set.of(); }
        };
        LockManager decorated = new BudgetedLockManagerDecorator(denyAll, budget);
        Transaction tx = new Transaction("t1", null, null);
        tx.initializeState();
        TransactionGroup tg = new TransactionGroup(tx);
        tx.group = tg;

        // Attempt: token acquired then denied by delegate -> refunded immediately
        LockDecision d1 = decorated.requestLock(tx, "R1", LockType.SHARED);
        assertTrue(d1 instanceof HardDenyDecision);
        assertEquals(1, budget.getAvailableTokens(), "Token should be refunded on non-grant");

        // Switch to grant-all delegate to verify deduction on grant
        LockManager grantAll = new GrantAllLockManager();
        decorated = new BudgetedLockManagerDecorator(grantAll, budget);
        LockDecision d2 = decorated.requestLock(tx, "R1", LockType.SHARED);
        assertTrue(d2 instanceof GrantedDecision);
        assertEquals(0, budget.getAvailableTokens(), "Token should be deducted on grant");
    }

    @Test
    void refundOnRelease() {
        GlobalLockBudget budget = new GlobalLockBudget(1, 100);
        LockManager delegate = new GrantAllLockManager();
        LockManager decorated = new BudgetedLockManagerDecorator(delegate, budget);
        Transaction tx = new Transaction("t1", null, null);
        tx.initializeState();
        TransactionGroup tg = new TransactionGroup(tx);
        tx.group = tg;

        LockDecision d = decorated.requestLock(tx, "R1", LockType.EXCLUSIVE);
        assertTrue(d instanceof GrantedDecision);
        assertEquals(0, budget.getAvailableTokens(), "Token deducted on grant");

        decorated.releaseLocks(tx);
        assertEquals(1, budget.getAvailableTokens(), "Token refunded on release");
    }
}
