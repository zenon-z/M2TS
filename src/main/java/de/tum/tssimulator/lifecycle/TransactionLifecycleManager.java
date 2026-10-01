package de.tum.tssimulator.lifecycle;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.execution.AbstractAdmissionController;
import de.tum.tssimulator.execution.AdaptiveConcurrencyManager;
import de.tum.tssimulator.execution.MplAdaptationManager;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;

public class TransactionLifecycleManager implements TransactionLifecyclePort, BlockedTransactionRetrier {
    /** Temporary localisation aid for the service-demand invariance check: -Dts.diag.demandTrace=<path>. */
    private static final PrintWriter DEMAND_TRACE = openDemandTrace();

    private static PrintWriter openDemandTrace() {
        String path = System.getProperty("ts.diag.demandTrace");
        if (path == null) {
            return null;
        }
        try {
            PrintWriter writer = new PrintWriter(new BufferedWriter(
                    new FileWriter(path)));
            Runtime.getRuntime().addShutdownHook(new Thread(writer::close));
            writer.println("id\tresources\toperations\tcursor\tio\tcpu");
            return writer;
        } catch (IOException e) {
            throw new IllegalStateException("cannot open demand trace at " + path, e);
        }
    }

    private final SimulatorConfig config;
    private final SimulationLogger logger;
    private final QueuePort queueManager;
    private final int maxConcurrent;
    private AbstractAdmissionController intakeDispatcher;

    public TransactionLifecycleManager(QueuePort queueManager,
            SimulatorConfig config,
            SimulationLogger logger,
            int maxConcurrent) {
        this.queueManager = queueManager;
        this.config = config;
        this.logger = logger;
        this.maxConcurrent = maxConcurrent;
    }

    /**
     * Wire the intake dispatcher for lock budget-based admission control.
     * Establishes the event-driven wake mechanism: when transactions complete,
     * this manager notifies the intake dispatcher to wake waiting transactions.
     */
    public void setIntakeDispatcher(AbstractAdmissionController intakeDispatcher) {
        this.intakeDispatcher = intakeDispatcher;
    }

    @Override
    public void complete(Transaction txn) {
        // Release the group's processor slot: this member is done with IO/CPU for good. One of
        // three lifecycle release sites (with abort/rollback and force-block); the processor
        // unit only ever takes the slot.
        if (txn.getGroup() != null) {
            txn.getGroup().releaseProcessor(txn);
        }

        // Remove from all queues before transitioning to terminal state
        if (queueManager instanceof CentralQueueManager) {
            ((CentralQueueManager) queueManager).remove(txn);
        }

        // Fire COMPLETED event to transition FSM to COMPLETED state
        txn.transitionTo(TransactionEvent.COMPLETED);
        txn.onComplete();
        if (logger != null) {
            logger.logEvent(new LogEvent(LogEvent.Type.TRANSACTION_COMPLETED, txn.getId(), 0, "name=executed delta=1"));
            logger.metrics().incrementExecuted();
            if (txn instanceof BookkeeperTransaction) {
                logger.recordCompletedBookkeeperResourceReferences(txn.getResources().size());
            } else {
                logger.recordCompletedParentResourceReferences(txn.getResources().size());
            }
            // Record the actual lock time spent during LOCK phase
            logger.metrics().addLockTime(txn.getSimulatedLockTime());
            // Same call site, same shape: service demand actually charged to this completion. Read
            // only by the service-demand invariance check - a transaction split into batches must
            // charge the same total as one acquired whole.
            logger.metrics().addCompletedIoTime(txn.getSimulatedIOTime());
            logger.metrics().addCompletedCpuTime(txn.getSimulatedCPUTime());
            logger.metrics().recordExecutedOperations(txn.getExecutedOperationCount());
            if (DEMAND_TRACE != null) {
                DEMAND_TRACE.printf("%s\t%d\t%d\t%d\t%d\t%d%n", txn.getId(), txn.getResources().size(),
                        txn.getOperations().size(), txn.getOperationCursor(),
                        txn.getSimulatedIOTime(), txn.getSimulatedCPUTime());
            }
            if (!(txn instanceof BookkeeperTransaction)) {
                long latency = Math.max(0L, txn.getCompletionTime() - txn.getSubmissionTime());
                logger.metrics().recordParentTransactionLatency(latency);
            }
        }

        AdaptiveConcurrencyManager.getInstance().recordCommit();
        MplAdaptationManager.getInstance().recordCommit();

        // Wake next capacity waiter ONLY ONCE per group
        if (intakeDispatcher != null && txn.getGroup() != null) {
            TransactionGroup group = txn.getGroup();
            intakeDispatcher.getCapacityWaitManager().removeGroup(group);

            // Only wake if this is the first completion in the group
            if (!group.hasWokenCapacityWaiter()) {
                group.markCapacityWaiterWoken();
                intakeDispatcher.getCapacityWaitManager().wakeNext();
            }
        }
    }
    @Override
    public void abort(Transaction txn) {
        // Remove from all queues before transitioning to terminal state
        if (queueManager instanceof CentralQueueManager) {
            ((CentralQueueManager) queueManager).remove(txn);
        }

        // Fire ABORTED event to transition FSM to ABORTED state
        txn.transitionTo(TransactionEvent.ABORTED);

        if (logger != null) {
            logger.logEvent(new LogEvent(LogEvent.Type.TRANSACTION_ABORTED, txn.getId(), 0, "aborted"));
            logger.metrics().incrementAborted();
        }

        AdaptiveConcurrencyManager.getInstance().recordAbort();
        MplAdaptationManager.getInstance().recordAbort();

        // Wake next capacity waiter ONLY ONCE per group
        if (intakeDispatcher != null && txn.getGroup() != null) {
            TransactionGroup group = txn.getGroup();
            intakeDispatcher.getCapacityWaitManager().removeGroup(group);

            // Only wake if this is the first abort in the group
            if (!group.hasWokenCapacityWaiter()) {
                group.markCapacityWaiterWoken();
                intakeDispatcher.getCapacityWaitManager().wakeNext();
            }
        }
    }
    @Override
    public void moveToAwaitingUserAction(Transaction txn) {
        // Remove from all queues (transaction is in CPU queue when callback happens)
        if (queueManager instanceof CentralQueueManager) {
            ((CentralQueueManager) queueManager).remove(txn);
        }

        // Enqueue to AWAITING_USER_ACTION queue
        queueManager.enqueue(txn, QueueType.AWAITING_USER_ACTION);

        if (logger != null) {
            logger.logEvent(new LogEvent(LogEvent.Type.TRANSACTION_COMPLETED, txn.getId(), 0, 
                "moved to AWAITING_USER_ACTION"));
            logger.metrics().incrementAwaitingUserAction();
        }
    }

    private QueueType getQueueForPhase(ExecutionPhase phase) {
        switch (phase) {
            case LOCK:
                return QueueType.PENDING; // Will go through lock acquisition
            case IO:
                return QueueType.IO;
            case CPU:
                return QueueType.CPU;
            case COMPLETE:
                return QueueType.WAITING_FOR_GROUP; // Finished work, waiting for group
            default:
                return QueueType.PENDING; // Fallback
        }
    }
    @Override
    public void retrySpecificTransactions(Set<Transaction> txnsToRetry) {
        // Drain BLOCKED queue and move specific transactions to READY queue
        // Transition BLOCKED → READY, then READY → RUNNING happens during admission
        //
        // Kept as an immediate, synchronous transition - this is a documented, tested contract
        // (RetryBlockedPhaseRoutingTest, BlockedStatePhasePreservationTest,
        // RetryBlockedCompletedTransactionTest all assert it directly) relied on by every
        // deadlock strategy, not just S2PL's soft-deny cycle. The same-tick livelock risk this
        // could create once a woken transaction can genuinely fail reacquisition again (see
        // ACIDAdmissionController.admitReadyTransaction()) is handled THERE instead, throttling
        // only a transaction that has already failed a reacquisition attempt at least once
        // before - not the common single-cycle case this method's contract exists for.

        List<Transaction> temp = new ArrayList<>();
        Transaction txn;

        while ((txn = queueManager.dequeue(QueueType.BLOCKED_TXN)) != null) {
            if (txnsToRetry.contains(txn)) {
                // Transition BLOCKED → READY
                if (txn.getState() == TransactionState.BLOCKED) {
                    txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE);
                }

                // CLOSE THE BLOCKED INTERVAL. This marking is not optional bookkeeping: it is
                // the only close for the phase. admitReadyTransaction() reaches the processor
                // via processor.process() directly and never touches the markPhase(RUNNING) at
                // the admission site, so without this a parent that blocks once stays in
                // BLOCKED for the rest of its lineage and the MPL population gate fails.
                //
                // RESTORES the interrupted phase rather than naming one. A parent blocks from
                // WAITING_ROOM as readily as from RUNNING - it holds its locks while its cascade
                // runs, which is exactly when a sibling's denial force-blocks it - so reopening
                // RUNNING unconditionally books waiting-room time as RUNNING residual. Measured
                // at 45,236 ticks on one 24-core seed, with every gate still reading 0.999993:
                // see Transaction.phaseBeforeBlock's javadoc.
                //
                // RUNNING remains the fallback for a transaction that had no phase open when it
                // blocked, which is the only case where there is nothing to restore.
                if (!(txn instanceof BookkeeperTransaction)) {
                    long now = (queueManager instanceof CentralQueueManager cqm)
                            ? cqm.getCurrentTime() : 0L;
                    txn.markUnblocked(now);
                }

                // Enqueue to READY queue
                // READY → RUNNING transition will happen when admitted
                queueManager.enqueue(txn, QueueType.READY_TXN);
            } else {
                // Not being retried - put back in BLOCKED
                temp.add(txn);
            }
        }

        queueManager.enqueueAll(temp, QueueType.BLOCKED_TXN);
    }
    @Override
    public void moveToWaitingRoom(Transaction txn) {
        // Release the group's processor slot: this member has left CPU FOR GOOD, even though it
        // has not COMPLETED -- an ACID parent finishes its CPU phase and then waits for its own
        // cascade. Releasing only at complete() made the parent hold the slot for the whole
        // cascade, so its CUs could never take it: watchdog LIVELOCK_OR_STARVATION with IO and
        // CPU empty, everything queued in LOCK, and parents in WAITING_FOR_GROUP.
        if (txn.getGroup() != null) {
            txn.getGroup().releaseProcessor(txn);
        }

        // Transaction is finished with its execution but waiting for bookkeepers
        // It remains in RUNNING state and still counts toward processor limit (same
        // atomic unit)
        // Move to waiting room (holds locks until group completes)
        // lock_seconds_waiting_room: mark waiting-room entry. completionTime was already set
        // (by SimulatedProcessorUnit, immediately before this call) so it's reused here rather
        // than threading a new virtual-time parameter through.
        txn.setWaitingRoomEntry(txn.getCompletionTime());
        // Close the open RUNNING interval and open WAITING_ROOM. Without this, RUNNING never closes
        // for a cascading parent and silently absorbs the entire wait for its bookkeepers.
        // PARENT-ONLY, like every other markPhase call site. moveToWaitingRoom is called for
        // bookkeepers too (ACIDCompletionHandler.completeBookkeeper, AcidGroupCompletionPolicy),
        // and a bookkeeper never reaches finalizeLineageTimeAccounting - so an unguarded call
        // here put bookkeepers INTO the WAITING_ROOM population and never took them out. A
        // monotonic leak, invisible in any per-lineage statistic and immediately visible in the
        // per-snapshot population assertion.
        if (!(txn instanceof BookkeeperTransaction)) {
            txn.markPhase(Transaction.LineagePhase.WAITING_ROOM, txn.getCompletionTime());
        }
        queueManager.enqueue(txn, QueueType.WAITING_FOR_GROUP);
        if (logger != null) {
            logger.logEvent(new LogEvent(LogEvent.Type.WAITING_FOR_GROUP, txn.getId(), txn.getCompletionTime(),
                    "waiting-for-group"));
        }
    }

    public SimulationLogger getLogger() {
        return logger;
    }

}
