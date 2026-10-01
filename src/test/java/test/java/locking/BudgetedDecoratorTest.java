package test.java.locking;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

public class BudgetedDecoratorTest {

    private static class FakeLockManager implements LockManager {
        LockDecision next = new GrantedDecision();
        int released = 0;
        @Override public LockDecision requestLock(Transaction t, String resource, LockType type) { return next; }
        @Override public void releaseLocks(Transaction t) { released++; }
        @Override public boolean causesDeadlock(Transaction t, String resource) { return false; }
        @Override public void abortTransactionGroup(TransactionGroup group) {}
        @Override public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) { return next.granted(); }
        @Override public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) { return null; }
        @Override public void registerWaiter(Transaction txn, String lockId) {}
        @Override public Set<Transaction> getWaitersForTransaction(Transaction holder) { return Set.of(); }
    }

    @Test
    void deniesWhenNoTokens() {
        FakeLockManager base = new FakeLockManager();
        GlobalLockBudget budget = new GlobalLockBudget(0, 100);
        BudgetedLockManagerDecorator dec = new BudgetedLockManagerDecorator(base, budget);
        Transaction txn = new Transaction("1", null, null);
        txn.initializeState();

        assertTrue(dec.requestLock(txn, "R1", LockType.SHARED) instanceof SoftDenyDecision);
        assertEquals(0, budget.getAvailableTokens());
    }

    @Test
    void deductsOnGrantAndRefundsOnNonGrant() {
        FakeLockManager base = new FakeLockManager();
        GlobalLockBudget budget = new GlobalLockBudget(1, 100);
        BudgetedLockManagerDecorator dec = new BudgetedLockManagerDecorator(base, budget);
        Transaction txn = new Transaction("1", null, null);
        txn.initializeState();

        assertEquals(1, budget.getAvailableTokens());
        // grant path
        base.next = new GrantedDecision();
        assertTrue(dec.requestLock(txn, "R1", LockType.SHARED) instanceof GrantedDecision);
        assertEquals(0, budget.getAvailableTokens(), "token deducted on grant");

        // refund path
        budget.release(1); // reset tokens to 1
        base.next = new HardDenyDecision(null);
        assertTrue(dec.requestLock(txn, "R1", LockType.SHARED) instanceof HardDenyDecision);
        assertEquals(1, budget.getAvailableTokens(), "refunded on non-grant");
    }

    @Test
    void refundsOnRelease() {
        FakeLockManager base = new FakeLockManager();
        GlobalLockBudget budget = new GlobalLockBudget(1, 100);
        BudgetedLockManagerDecorator dec = new BudgetedLockManagerDecorator(base, budget);
        Transaction txn = new Transaction("1", null, null);
        txn.initializeState();

        base.next = new GrantedDecision();
        dec.requestLock(txn, "R1", LockType.SHARED);
        assertEquals(0, budget.getAvailableTokens());

        dec.releaseLocks(txn);
        assertEquals(1, budget.getAvailableTokens(), "refunded on release");
        assertEquals(1, base.released);
    }
}
