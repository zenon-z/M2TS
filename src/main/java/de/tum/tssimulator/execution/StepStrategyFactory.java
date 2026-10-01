package de.tum.tssimulator.execution;

import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.TransactionMode;
import de.tum.tssimulator.lifecycle.TransactionPoolManager;

public class StepStrategyFactory {
    public static StepStrategy create(StepMode mode, SimulatorContext ctx) {
        var scheduling = ctx.scheduling();
        var locking = ctx.locking();
        var execution = ctx.execution();
        var config = ctx.config();

        int initialPoolSize = config.transactionMode == TransactionMode.TERMINAL
                ? 0
                : config.initialTransactionCount;
        var poolManager = new TransactionPoolManager(
            scheduling.txnScheduler(),
            ctx.logger(),
            initialPoolSize
        );
        var stepCtx = new StepContext(
            execution.dispatcher(),
            poolManager,
            execution.lifecycle(),
            ctx.dirtyState(),
            ctx.logger()
        );
        
        // Wire StepContext to intake dispatcher for exponential backoff.
        AbstractAdmissionController intakeDispatcher = execution.intakeDispatcher();
        if (intakeDispatcher != null) {
            intakeDispatcher.setStepContext(stepCtx);
            // Wire GlobalLockBudget for ACID admission control
            if (intakeDispatcher instanceof ACIDAdmissionController) {
                ((ACIDAdmissionController) intakeDispatcher)
                        .setLockBudget(locking.budget());
            }
            // Wire LockGranularityMapper for accurate C2PL admission control
            // (mapper is automatically set during intake dispatcher construction via processor)
        }

        return switch (mode) {
            case FIXED_TIME ->
                new FixedTimeStepStrategy(config.stepTimeSlice, config.simulationDuration, stepCtx);
            case FIXED_TRANSACTION ->
                new FixedTransactionStepStrategy(config.totalTransactionSteps, stepCtx,
                        ctx.simulatedTimeCap, ctx.wallClockDeadlineMs);
        };
    }
}
