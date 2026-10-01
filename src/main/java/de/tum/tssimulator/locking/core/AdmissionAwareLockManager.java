package de.tum.tssimulator.locking.core;

import de.tum.tssimulator.execution.AdmissionWaitManager;

/**
 * Lock-manager capability for implementations that can wake admission waiters
 * when a blocking lock becomes available again.
 */
public interface AdmissionAwareLockManager {
    void setAdmissionWaitManager(AdmissionWaitManager manager);
}
