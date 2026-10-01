package de.tum.tssimulator.bootstrap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.client.Terminal;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.TransactionMode;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.execution.StepMode;
import de.tum.tssimulator.execution.StepOutcome;
import de.tum.tssimulator.execution.StepStrategy;
import de.tum.tssimulator.execution.StepStrategyFactory;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMonitor;
import de.tum.tssimulator.queue.QueueType;

public class Simulator {
    private static final Logger LOGGER = LoggerFactory.getLogger(Simulator.class);

    private final SimulatorContext ctx;
    private final StepStrategy stepStrategy;
    private final SimulationWatchdog watchdog;
    private final SchedulingBundle scheduling;
    private final SimulatorConfig config;
    private final SimulationLogger logger;
    private final SimulationMonitor monitor;

    private List<Terminal> terminals;
    private List<Transaction> activeTerminalTransactions;

    public Simulator(SimulatorContext ctx, StepMode mode) {
        this.ctx = ctx;
        this.scheduling = ctx.scheduling();
        this.config = ctx.config();
        this.logger = ctx.logger();
        this.monitor = ctx.monitor();
        this.stepStrategy = StepStrategyFactory.create(mode, ctx);
        // Slice-passing form: the stall budget is simulated time, so the guard means the same
        // thing at every quantum. See SimulationWatchdog's field javadoc.
        this.watchdog = SimulationWatchdog.fromSystemProperties(config.stepTimeSlice);
        Transaction.resetMetrics();
        TransactionStateCounter.reset();
        TransactionGroup.resetIdGenerator();
        // Same reason as the line above: statics that feed an output column, not just reporting.
        CautiousWaitingPolicy.resetCounters();
        // ASSERT, not trust. A reset that silently stops being called is the failure this guards,
        // and it is cheap: two reads of a long, once per run.
        if (CautiousWaitingPolicy.getEvaluationsTotal() != 0
                || CautiousWaitingPolicy.getHardDenyFiredTotal() != 0) {
            throw new IllegalStateException(
                    "CautiousWaitingPolicy counters are non-zero at run start; hard_deny_rate "
                            + "would report the run-so-far rather than this run");
        }

        if (config.transactionMode == TransactionMode.TERMINAL) {
            initializeTerminals();
        }
    }

    private void initializeTerminals() {
        this.terminals = new ArrayList<>();
        this.activeTerminalTransactions = new ArrayList<>();
        int numOfTerminals = config.numTerminals;
        long delay = config.meanInterArrivalDelay;

        Random terminalRandom = (config.transactionSamplingSeed != null)
                ? new Random(config.transactionSamplingSeed)
                : new Random();
        
        for (int i = 0; i < numOfTerminals; i++) {
            terminals.add(new Terminal(i, delay, terminalRandom));
        }
    }

    public void run() {
        if (config.transactionMode == TransactionMode.EQUILIBRIUM) {
            scheduling.txnScheduler().generateInitialTransactions(config.initialTransactionCount);
        }

        StepOutcome outcome;
        boolean finished = false;
        long lastVirtualTime = 0;

        try {
            while (!finished) {
                outcome = stepStrategy.step();
                lastVirtualTime = outcome.getNewVirtualTime();
                // SimulatorContext.virtualTime - the field
                // SimulationRunner.captureOutcome()/runFixedTransactionAllowPartial() read as
                // "now" for censored-lifetime computation and TIME_LIMIT-vs-WALL_CLOCK_RUNAWAY
                // classification - was NEVER actually being set here. Both step strategies only
                // set StepContext.virtualTime (a same-named but DIFFERENT field, on the narrower
                // ctx object each strategy holds for backoff timing) - a same-name collision that
                // made an earlier fix attempt this session (setting it inside
                // FixedTransactionStepStrategy, mirroring FixedTimeStepStrategy's identical,
                // equally-wrong-for-this-purpose line) look like it worked without ever verifying
                // ctx.virtualTime (this SimulatorContext) actually moved. Caught by an explicit
                // debug trace after a smoke-test cell terminated=WALL_CLOCK_RUNAWAY despite only
                // 1.8s of real wall time elapsing (nowhere near the 180s guard) - the trace showed
                // ctx.virtualTime=0 after a run that reached 5756s of simulated time. Fixed here,
                // once, in the one place that legitimately holds the real SimulatorContext and the
                // authoritative per-step virtual time, instead of in each StepStrategy.
                ctx.virtualTime = lastVirtualTime;

                if (config.transactionMode == TransactionMode.TERMINAL) {
                    processTerminalLogic(lastVirtualTime);
                }

                logger.metrics().recordHealthSnapshot(monitor.snapshot(lastVirtualTime));

                // F4 (VLDB reviewer response): mark the warm-up boundary the first step at or
                // past warmup. markWarmupBoundary() is idempotent, so no extra "already
                // marked" state is needed here - config.warmup=0 (the default) means this
                // fires on the very first step, so getMeasurementWindowS()/getWindowedThroughput()
                // etc. still return sane full-run values when warm-up windowing isn't in use.
                if (lastVirtualTime >= config.warmup) {
                    logger.metrics().markWarmupBoundary();
                }

                finished = outcome.isFinished();
                if (finished) {
                    continue;
                }

                int currentCompleted = TransactionStateCounter
                        .getCount(TransactionState.COMPLETED);
                int currentAborted = TransactionStateCounter
                        .getCount(TransactionState.ABORTED);
                final long watchdogVirtualTime = lastVirtualTime;
                final int watchdogCompleted = currentCompleted;
                final int watchdogAborted = currentAborted;
                SimulationWatchdogDiagnostics diagnostics = watchdog.observe(
                        lastVirtualTime,
                        currentCompleted,
                        currentAborted,
                        () -> SimulationWatchdogDiagnostics.capture(
                                ctx,
                                watchdogVirtualTime,
                                watchdogCompleted,
                                watchdogAborted,
                                watchdog.maxStepsWithoutProgress()));
                if (diagnostics != null) {
                    dumpDeadlockStateIfEnabled(lastVirtualTime);
                    throw watchdogFailure(diagnostics);
                }
            }
        } finally {
            logger.logEvent(new LogEvent(LogEvent.Type.TICK, "sim", lastVirtualTime, ""));
            logger.summary();
            monitor.printStatus();
        }
    }

    /**
     * Diagnostic only (opt-in via -Dts.diag.watchdogStateDump=true): full per-transaction lock
     * state at the moment the watchdog fires - every live group's members (parent + bookkeepers)
     * with their FSM state/phase, and the full lock table's holders/waiters. Direct test of
     * whether a watchdog hang is a genuine cross-group circular wait: parents preclaim
     * atomically (cannot deadlock on their own), but a SoftDenyDecision on a bookkeeper blocks
     * the bookkeeper's WHOLE GROUP - including the parent - without releasing the parent's
     * already-held locks (TransactionLockCoordinator.blockEntireTransactionGroup(): "Do NOT
     * release locks - blocked transactions keep their locks (2PL, not C2PL)"). Two groups each
     * holding a lock the other's blocked bookkeeper wants is a structural deadlock that
     * SS2PLLockManager.causesDeadlock() (unconditionally "return false; // Optional") cannot
     * detect or break - the watchdog is the only thing that ever notices.
     */
    private void dumpDeadlockStateIfEnabled(long lastVirtualTimeForDump) {
        if (!Boolean.getBoolean("ts.diag.watchdogStateDump")) {
            return;
        }
        SS2PLLockManager lm = SS2PLLockManager.unwrap(ctx.locking().lockManager());
        System.out.println("=== WATCHDOG STATE DUMP (vt=" + ctx.virtualTime + ") ===");
        System.out.println("--- Live groups ---");
        for (var group : ctx.registry().getActiveGroups()) {
            Transaction parent = group.getParent();
            System.out.printf("group parent=%s state=%s phase=%s resources=%s%n",
                    parent.getId(), parent.getState(), parent.getPhase(), parent.getResources());
            for (var bk : group.getBookkeepers()) {
                System.out.printf("  bookkeeper=%s state=%s phase=%s resources=%s%n",
                        bk.getId(), bk.getState(), bk.getPhase(), bk.getResources());
            }
        }
        if (lm != null) {
            System.out.println("--- Lock table (holders) ---");
            for (String lockId : lm.getLockIdsWithHolders()) {
                var holders = lm.getLockHolders(lockId);
                if (!holders.isEmpty()) {
                    System.out.println("  lock=" + lockId + " heldBy="
                            + holders.stream().map(Transaction::getId).toList());
                }
            }
            System.out.println("--- Lock waiters ---");
            for (Transaction waiter : lm.getAllUniqueWaiters()) {
                System.out.println("  waiter=" + waiter.getId() + " state=" + waiter.getState());
            }
            // Diagnostic-only: lockId -> waiters, the missing half needed to actually build the
            // wait-for graph (getLockHolders(lockId) is the other half) instead of just listing
            // holders and waiters as separate flat sets with no edge between them.
            System.out.println("--- Lock waiters by lock (for wait-for graph reconstruction) ---");
            for (var entry : lm.getLockWaiters().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.out.println("  lock=" + entry.getKey() + " waitedBy="
                            + entry.getValue().stream().map(Transaction::getId).toList());
                }
            }
        }
        // Diagnostic-only: the SEPARATE admission-blocking wait registry - a PENDING parent
        // blocked at ACIDAdmissionController.tryAdmitWithLockCheck() shows up here, not in
        // SS2PLLockManager's own waitTracker above (that one only sees bookkeeper-side
        // acquireAllLocks() waits). This is what "who is a depth-0 stuck-cold parent actually
        // waiting on" requires.
        AdmissionWaitManager awm = ctx.locking().getAdmissionWaitManager();
        if (awm != null) {
            System.out.println("--- Admission-wait registrations (parent blocked at admission check) ---");
            for (var entry : awm.waitersByLockForDiagnostics().entrySet()) {
                System.out.println("  lock=" + entry.getKey() + " admissionWaiters=" + entry.getValue());
            }
        }
        System.out.println("=== END WATCHDOG STATE DUMP ===");
    }

    private SimulationWatchdogException watchdogFailure(SimulationWatchdogDiagnostics diagnostics) {
        logger.metrics().recordWatchdogFailure(diagnostics.virtualTime());
        logger.log(diagnostics.structuredLogLine());
        LOGGER.error("Simulation watchdog triggered: {}", diagnostics.exceptionMessage());
        LOGGER.error("Watchdog diagnostics {}", diagnostics.structuredLogLine());
        return new SimulationWatchdogException(diagnostics.exceptionMessage(), diagnostics.structuredLogLine());
    }

    private void processTerminalLogic(long currentVirtualTime) {
        if (terminals == null || activeTerminalTransactions == null) {
            return;
        }

        // A. Check for completed transactions and notify Terminals
        Iterator<Transaction> it = activeTerminalTransactions.iterator();
        while (it.hasNext()) {
            Transaction txn = it.next();
            if (txn.getState() == TransactionState.COMPLETED || txn.getState() == TransactionState.ABORTED) {
                int terminalId = txn.getSourceTerminalId();
                if (terminalId >= 0 && terminalId < terminals.size()) {
                    terminals.get(terminalId).onTransactionFinished(currentVirtualTime);
                } else {
                    LOGGER.warn("Completed txn {} reported invalid terminalId={} at time {}",
                            txn.getId(), terminalId, currentVirtualTime);
                }
                it.remove();
            }
        }

        // B. Drive Terminals to send new transactions
        for (Terminal terminal : terminals) {
            if (terminal.tryStartTransaction(currentVirtualTime)) {
                Transaction newTxn = scheduling.txnScheduler().createTransaction(terminal.getId());
                activeTerminalTransactions.add(newTxn);
                scheduling.queues().enqueue(newTxn, QueueType.PENDING);
            }
        }
    }

    public StepOutcome step() {
        StepOutcome outcome = stepStrategy.step();
        if (config.transactionMode == TransactionMode.TERMINAL) {
            processTerminalLogic(outcome.getNewVirtualTime());
        }
        return outcome;
    }
}
