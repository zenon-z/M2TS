package de.tum.tssimulator.execution;

public class StepOutcome {
    private final long newVirtualTime;
    private final int transactionsStepped;
    private final boolean finished;

    public StepOutcome(long newVirtualTime, int transactionsStepped, boolean finished) {
        this.newVirtualTime = newVirtualTime;
        this.transactionsStepped = transactionsStepped;
        this.finished = finished;
    }

    public long getNewVirtualTime() { return newVirtualTime; }
    public int getTransactionsStepped() { return transactionsStepped; }
    public boolean isFinished() { return finished; }
}
