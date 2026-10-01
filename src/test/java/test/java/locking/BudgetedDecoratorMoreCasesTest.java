package test.java.locking;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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

class BudgetedDecoratorMoreCasesTest {

    private static class GrantAll implements LockManager {
        @Override public LockDecision requestLock(Transaction t, String resource, LockType type) { return new GrantedDecision(); }
        @Override public void releaseLocks(Transaction t) { }
        @Override public boolean causesDeadlock(Transaction t, String blockedOn) { return false; }
        @Override public void abortTransactionGroup(TransactionGroup group) { }
        @Override public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) { return true; }
        @Override public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) { return null; }
        @Override public void registerWaiter(Transaction txn, String lockId) {}
        @Override public Set<Transaction> getWaitersForTransaction(Transaction holder) { return Set.of(); }
    }

    private static class DenyAll implements LockManager {
        @Override public LockDecision requestLock(Transaction t, String resource, LockType type) { return new HardDenyDecision(null); }
        @Override public void releaseLocks(Transaction t) { }
        @Override public boolean causesDeadlock(Transaction t, String blockedOn) { return false; }
        @Override public void abortTransactionGroup(TransactionGroup group) { }
        @Override public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) { return false; }
        @Override public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) { return "DENIED"; }
        @Override public void registerWaiter(Transaction txn, String lockId) {}
        @Override public Set<Transaction> getWaitersForTransaction(Transaction holder) { return Set.of(); }
    }

    @Test
    void multipleReleasesDoNotOverRefund() {
        GlobalLockBudget budget = new GlobalLockBudget(1, 1);
        LockManager lm = new BudgetedLockManagerDecorator(new GrantAll(), budget);
        Transaction tx = new Transaction("t", null, null);
        tx.initializeState();
        assertTrue(lm.requestLock(tx, "R", LockType.SHARED).granted());
        assertEquals(0, budget.getAvailableTokens());
        lm.releaseLocks(tx);
        assertEquals(1, budget.getAvailableTokens());
        lm.releaseLocks(tx);
        assertEquals(1, budget.getAvailableTokens(), "should not exceed max tokens");
    }

    @Test
    void denyThenGrantReusesRefundedToken() {
        GlobalLockBudget budget = new GlobalLockBudget(1, 1);
        LockManager lm = new BudgetedLockManagerDecorator(new DenyAll(), budget);
        Transaction tx = new Transaction("t", null, null);
        tx.initializeState();
        assertFalse(lm.requestLock(tx, "R", LockType.SHARED).granted());
        assertEquals(1, budget.getAvailableTokens(), "refunded after denial");
        lm = new BudgetedLockManagerDecorator(new GrantAll(), budget);
        assertTrue(lm.requestLock(tx, "R", LockType.SHARED).granted());
        assertEquals(0, budget.getAvailableTokens(), "deducted after grant");
    }

    @Test
    void abortPathDoesNotChangeBudget() {
        // We simulate a hard deny decision that calls abort on the group.
        // Budget should only be impacted by request (deduct on grant, refund on non-grant) and release.
        GlobalLockBudget budget = new GlobalLockBudget(1, 1);
        LockManager lm = new BudgetedLockManagerDecorator(new DenyAll(), budget);
        Transaction tx = new Transaction("t", null, null);
        tx.initializeState();
        // Hard deny will not change budget because of refund on non-grant
        assertFalse(lm.requestLock(tx, "R", LockType.EXCLUSIVE).granted());
        assertEquals(1, budget.getAvailableTokens());
    }
}
