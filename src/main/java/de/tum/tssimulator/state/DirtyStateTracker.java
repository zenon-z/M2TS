package de.tum.tssimulator.state;

public interface DirtyStateTracker {
    boolean isDirty(DirtyKey key);

    boolean markDirty(DirtyKey key, long virtualTime);

    void clearDirty(DirtyKey key, long virtualTime);

    int size();
}
