package de.tum.tssimulator.scheduling;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class TransactionScheduler {
    private int transactionCounter = 0;
    private final SimulatorConfig config;
    private final QueuePort queueManager;
    private TransactionGroupRegistry registry;

    private final TransactionFactory transactionFactory;
    private static final int UNKNOWN_TERMINAL_ID = -1;

    public TransactionScheduler(SimulatorConfig config, QueuePort queueManager,
            TransactionFactory transactionFactory, TransactionGroupRegistry registry) {
        this.config = config;
        this.queueManager = queueManager;
        this.transactionFactory = transactionFactory;
        this.registry = registry;
    }

    public void generateInitialTransactions(int count) {
        for (int i = 0; i < count; i++) {
            Transaction t = createTransaction();
            queueManager.enqueue(t, QueueType.PENDING);
        }
    }

    public Transaction createTransaction() {
        return createTransaction(UNKNOWN_TERMINAL_ID);
    }

    public Transaction createTransaction(int sourceTerminalId) {
        // Scheduler owns group creation
        TransactionGroup group = new TransactionGroup();

        // Delegate transaction creation to factory with the group
        Transaction t = transactionFactory.createTransaction(config, group, sourceTerminalId);
        t.setSubmissionTime(currentVirtualTime());
        // A fresh admission - lineage origin and this attempt's own start coincide.
        t.setFirstAdmissionTime(currentVirtualTime());
        // Four-way lineage time decomposition: a freshly created transaction is immediately
        // enqueued to PENDING (by the caller) - this is where lineage time-tracking starts.
        t.markPhase(Transaction.LineagePhase.PENDING_QUEUE, currentVirtualTime());

        // Now register once parent exists and link
        registry.registerParent(group, t);

        transactionCounter++;
        return t;
    }

    public int getTransactionCounter() {
        return transactionCounter;
    }

    private long currentVirtualTime() {
        if (queueManager instanceof CentralQueueManager centralQueueManager) {
            return centralQueueManager.getCurrentTime();
        }
        return 0L;
    }
    
    public Transaction restartTransaction(Transaction original) {
        // Use factory to create a fresh transaction with same parameters
        Transaction newTxn = transactionFactory.restartTransaction(original);
        // BUGFIX: only createTransaction() (the fresh-admission path) set
        // submissionTime - a restarted transaction kept the field's Java default (0), so
        // anything reading it (e.g. AbGroupLifetimeExperiment's lifetime/censoring export) saw a
        // restarted attempt as having been submitted at virtual time 0, wildly inflating its
        // apparent lifetime. Set it here too, consistently with createTransaction().
        newTxn.setSubmissionTime(currentVirtualTime());
        // carry the lineage's TRUE first-admission time forward
        // across the restart, never reset it - symmetric to how TransactionFactory.
        // restartTransaction() carries lockRetryCount forward. This is what group-lifetime/
        // Kaplan-Meier analysis needs (total lineage age); submissionTime above stays
        // reset-per-attempt for its own, different, pre-existing per-attempt-latency consumers.
        newTxn.setFirstAdmissionTime(original.getFirstAdmissionTime());
        // Lineage time decomposition: close the old transaction's open interval (it
        // was almost always RUNNING - that's what just got aborted - but could in principle be
        // any phase) into its totals, carry those totals forward, then mark the new transaction
        // as entering BACKOFF - restartTransaction()'s caller (TransactionGroupAbortCoordinator)
        // enqueues it into the backoff queue immediately after this returns.
        newTxn.copyLineageTimeAccountingFrom(original, currentVirtualTime());
        newTxn.markPhase(Transaction.LineagePhase.BACKOFF, currentVirtualTime());

        // Register the new transaction's group
        registry.registerParent(newTxn.group, newTxn);
        
        // Increment counter - restarted transaction gets new ID and should be counted
        transactionCounter++;
        
        return newTxn;
    }
    
    public void maintainPoolSize(int maxLive, int currentLive) {
        int deficit = maxLive - currentLive;
        if (deficit > 0) {
            generateInitialTransactions(deficit);
        }
    }

}
