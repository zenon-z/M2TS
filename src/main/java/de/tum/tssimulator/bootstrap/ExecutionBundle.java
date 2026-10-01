package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.execution.AbstractAdmissionController;
import de.tum.tssimulator.execution.PhaseDispatcher;
import de.tum.tssimulator.execution.TransactionExecutor;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.model.IOModel;

public interface ExecutionBundle {
    IOModel ioModel();
    TransactionPhaseInitializer initializer();
    TransactionProcessor processor();
    PhaseDispatcher dispatcher();
    TransactionExecutor executor();
    TransactionLifecycleManager lifecycle();

    default AbstractAdmissionController intakeDispatcher() {
        return null;
    }
}
