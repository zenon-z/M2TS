package de.tum.tssimulator.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionIdGenerator {
    private final AtomicInteger counter = new AtomicInteger();

    private final AtomicLong logicalClock = new AtomicLong(0);

    /**
     * A transaction ID carries identity and nothing else.
     *
     * <p>No "-RO" suffix for read-only transactions: that would make a derived property part of
     * an identifier and give the codebase a second, stringly-typed source of truth for it. Under
     * {@code RANDOM_OPERATIONS} the only flag computable at this point is {@code txCounter % 2},
     * parity with no connection to the operations, so at {@code writeFraction = 1.0} it would
     * label half of an all-WRITE workload read-only. Use {@link Transaction#isReadOnly()}, which
     * reads the operations.
     *
     * <p>Uniqueness comes from the counter, never from a suffix.
     */
    public String nextId() {
        int id = counter.getAndIncrement();
        return "T" + id;
    }

    public long nextTimestamp() {
        return logicalClock.getAndIncrement();
    }

    public void reset() {
        counter.set(0);
    }
}
