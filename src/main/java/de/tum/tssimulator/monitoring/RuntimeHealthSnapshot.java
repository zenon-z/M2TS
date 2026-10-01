package de.tum.tssimulator.monitoring;

public record RuntimeHealthSnapshot(
        long virtualTime,
        int pendingQueueDepth,
        int blockedQueueDepth,
        int readyQueueDepth,
        int lockQueueDepth,
        int ioQueueDepth,
        int cpuQueueDepth,
        int rollbackQueueDepth,
        int reconciliationQueueDepth,
        int awaitingUserActionQueueDepth,
        int waitingForGroupQueueDepth,
        int pendingStateCount,
        int runningStateCount,
        int blockedStateCount,
        int readyStateCount,
        int awaitingUserActionStateCount,
        int completedStateCount,
        int abortedStateCount,
        // AdmissionWaitManager's own waiter registry size - the ACID preclaim admission-wait
        // population that neither blockedQueueDepth (QueueType.BLOCKED_TXN, wired to the
        // DYNAMIC-2PL/bookkeeper blocking path only) nor blockedStateCount
        // (TransactionState.BLOCKED, never fired by AdmissionWaitManager.registerWaiter()) can
        // see. A transaction registered here sits in neither a queue nor a distinct FSM state, so
        // without this field it is invisible to every blocked-population metric. This is the
        // quantity Thomasian-style thrashing thresholds are a fraction of.
        int admissionWaiterCount,
        // TGS Postgres-anchor comparison: SS2PLLockManager.getLocksHeld() (lock TABLE size, i.e.
        // H, the count of distinct lock units currently held by anyone) sampled at this same
        // cadence - the simulator-side counterpart to polling `SELECT count(*) FROM pg_locks
        // WHERE locktype='advisory'` on the Postgres side. Deliberately NOT derived from
        // meanRunningStateCountSampled*k (that's an approximation assuming every running
        // transaction holds exactly k locks and none are bookkeepers holding additional ones) -
        // this is a direct read of the same underlying quantity both systems' balance equations
        // are actually about, per the "measure it, don't derive it" correction.
        int locksHeldCount) {
}
