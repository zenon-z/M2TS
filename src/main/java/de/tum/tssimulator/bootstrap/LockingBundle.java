package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.lifecycle.BlockedTransactionRetrier;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public interface LockingBundle {
    GlobalLockBudget budget();
    LockManager lockManager();
    TransactionLockCoordinator txnCoordinator();
    AdmissionWaitManager getAdmissionWaitManager();
    void wireLifecycleCallbacks(BlockedTransactionRetrier lifecycle);
    void wireRegistry(TransactionGroupRegistry registry);
    void wireScheduler(TransactionScheduler scheduler);
}
