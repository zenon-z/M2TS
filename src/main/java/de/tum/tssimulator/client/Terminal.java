package de.tum.tssimulator.client;

import java.util.Random;

/**
 * Represents a client terminal in a Closed Queuing Model as described in
 * Workflow:
 * 1. IDLE (Thinking): Waits for a random 'inter-arrival delay'.
 * 2. BUSY (Waiting): Submits transaction and waits for completion.
 * 3. Repeat.
 */

public class Terminal {
    public enum State {
        IDLE,
        BUSY,
        // OFFLINE
        // ERROR
    }

    private final int terminalId;
    private final long meanInterArrivalDelay;
    private final Random random;
    private static final double EPSILON = 1e-9;

    private State currentState;
    private long nextSubmissionTime;

    public Terminal(int terminalId, long meanInterArrivalDelay, Random sharedRandom) {
        this.terminalId = terminalId;
        this.meanInterArrivalDelay = meanInterArrivalDelay;
        this.random = sharedRandom;
        this.currentState = State.IDLE;
        // prevent all terminals from hitting the system simultaneously at t=0.
        this.nextSubmissionTime = (long) (random.nextDouble() * meanInterArrivalDelay);
    }

    /**
     * Try to start a transaction if the terminal is IDLE and the current virtual
     * time has reached the next submission time.
     * 
     * @param currentVirtualTime
     * @return true if the transaction is started, false otherwise
     */
    public boolean tryStartTransaction(long currentVirtualTime) {
        if (currentState == State.IDLE && currentVirtualTime >= nextSubmissionTime) {
            this.currentState = State.BUSY;
            return true;
        }
        return false;
    }

    /**
     * Callback when the system finishes processing the transaction.
     * The terminal enters the "thinking" phase (IDLE) for a duration
     * determined by the Poisson process (Exponentially distributed delay).
     * * @param currentVirtualTime The time when the transaction finished.
     */
    public void onTransactionFinished(long currentVirtualTime) {
        currentState = State.IDLE;
        long thinkDuration = sampleInterArrivalDelay();
        nextSubmissionTime = currentVirtualTime + thinkDuration;
    }

    /**
     * Generates a random delay following an Exponential Distribution.
     * This simulates the "Poisson distribution" arrival process mentioned in the
     * paper.
     * * Formula: t = -ln(U) * mean
     * * @return The delay duration in virtual time units.
     */
    private long sampleInterArrivalDelay() {
        double u = random.nextDouble();
        if (u <= 0.0) {
            u = EPSILON;
        }
        return (long) (-Math.log(u) * meanInterArrivalDelay);
    }

    // for testing
    public int getId() {
        return terminalId;
    }

    public State getState() {
        return currentState;
    }

    public long getNextSubmissionTime() {
        return nextSubmissionTime;
    }

    @Override
    public String toString() {
        return String.format("Terminal-%d[%s, next=%d]", terminalId, currentState, nextSubmissionTime);
    }
}
