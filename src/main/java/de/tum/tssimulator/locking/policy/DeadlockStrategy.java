package de.tum.tssimulator.locking.policy;

public enum DeadlockStrategy {
    CAUTIOUS_WAITING,
    NO_WAIT,
    WAIT_DIE,
    WOUND_WAIT,
    ADAPTIVE,
    ONLY_SOFT_DENY
}

