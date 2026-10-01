package de.tum.tssimulator.monitoring;

class SimulationSnapshot {
    private final long virtualTime;
    private final long totalExecuted;
    private final long totalAborted;
    private final int pending;
    private final int blocked;

    public SimulationSnapshot(long virtualTime, long totalExecuted, long totalAborted, int pending, int blocked) {
        this.virtualTime = virtualTime;
        this.totalExecuted = totalExecuted;
        this.totalAborted = totalAborted;
        this.pending = pending;
        this.blocked = blocked;
    }

    public long getVirtualTime() { return virtualTime; }
    public long getTotalExecuted() { return totalExecuted; }
    public long getTotalAborted() { return totalAborted; }
    public int getPending() { return pending; }
    public int getBlocked() { return blocked; }
}
