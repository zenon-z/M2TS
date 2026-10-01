package test.java.scheduling;

import java.util.ArrayDeque;
import java.util.Deque;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.scheduling.Scheduler;

public class LifoScheduler implements Scheduler {
    private final Deque<Transaction> stack = new ArrayDeque<>();

    public void push(Transaction t) { stack.addLast(t); }

    @Override
    public Transaction nextTransaction() {
        return stack.pollLast();
    }

    @Override
    public boolean hasReady() {
        return !stack.isEmpty();
    }
}
