package de.tum.tssimulator.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockStateView;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.Scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Legacy TransactionIntakePhaseDispatcher - now extends AbstractAdmissionController.
 * This class is kept for backward compatibility with existing tests.
 * For production use, prefer using ACIDAdmissionController or BASEAdmissionController via AdmissionControllerFactory.
 * 
 * @deprecated Use ACIDAdmissionController or BASEAdmissionController instead
 */
@Deprecated
public class TransactionIntakePhaseDispatcher extends AbstractAdmissionController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionIntakePhaseDispatcher.class);

    private AdmissionWaitManager admissionWaitManager;
    private GlobalLockBudget lockBudget;
    private LockGranularityMapper granularityMapper;

    public TransactionIntakePhaseDispatcher(Scheduler scheduler,
                                            TransactionProcessor processor,
                                            IOModel ioModel) {
        this(scheduler, processor, ioModel, null, null, null);
    }

    public TransactionIntakePhaseDispatcher(Scheduler scheduler,
                                            TransactionProcessor processor,
                                            IOModel ioModel,
                                            SimulationLogger logger) {
        this(scheduler, processor, ioModel, logger, null, null);
    }
    
    public TransactionIntakePhaseDispatcher(Scheduler scheduler,
                                            TransactionProcessor processor,
                                            IOModel ioModel,
                                            SimulationLogger logger,
                                            PhaseDispatcher downstreamPhases,
                                            QueueManager queueManager) {
        this(scheduler, processor, ioModel, logger, downstreamPhases, Integer.MAX_VALUE, queueManager);
    }
    
    public TransactionIntakePhaseDispatcher(Scheduler scheduler,
                                            TransactionProcessor processor,
                                            IOModel ioModel,
                                            SimulationLogger logger,
                                            PhaseDispatcher downstreamPhases,
                                            int maxConcurrent,
                                            QueueManager queueManager) {
        this(scheduler, processor, ioModel, logger, downstreamPhases, maxConcurrent, queueManager, null);
    }
    
    public TransactionIntakePhaseDispatcher(Scheduler scheduler,
                                            TransactionProcessor processor,
                                            IOModel ioModel,
                                            SimulationLogger logger,
                                            PhaseDispatcher downstreamPhases,
                                            int maxConcurrent,
                                            QueueManager queueManager,
                                            AdmissionWaitManager admissionWaitManager) {
        super(scheduler, processor, ioModel, logger, downstreamPhases, maxConcurrent, queueManager);
        this.admissionWaitManager = admissionWaitManager;
        // Automatically wire granularityMapper from processor's coordinator
        if (processor != null && processor.getLockCoordinator() != null) {
            this.granularityMapper = processor.getLockCoordinator().getGranularityMapper();
        }
    }
    
    @Override
    protected void sweepExpiredAdmissionWaits(long currentTime) {
        if (admissionWaitManager != null) {
            admissionWaitManager.sweepExpiredWaiters(currentTime);
        }
    }

    public void setLockBudget(GlobalLockBudget lockBudget) {
        this.lockBudget = lockBudget;
    }

    public void setGranularityMapper(LockGranularityMapper mapper) {
        this.granularityMapper = mapper;
    }

    @Override
    protected long processPendingTransaction(Transaction txn) {
        int currentRunning = TransactionStateCounter.getCount(TransactionState.RUNNING);
        long currentTime = getCurrentVirtualTime();
        
        // BOOKKEEPERS: Check if additional locks needed beyond parent's locks
        if (txn instanceof BookkeeperTransaction) {
            BookkeeperTransaction bk = (BookkeeperTransaction) txn;
            if (bk.getRoot().getState() == TransactionState.RUNNING) {
                
                // Check if bookkeeper needs locks not held by parent
                boolean needsAdditionalLocks = bookkeeperRequiresAdditionalLocks(bk);
                
                if (!needsAdditionalLocks) {
                    // Bookkeeper inherits all locks from parent - skip lock acquisition
                    boolean success = processor.process(txn);
                    if (dispatchStats != null) {
                        dispatchStats.transactionsAdmitted++;
                        // Don't count as parent admission
                    }
                    if (logger != null) {
                        logger.log("[BOOKKEEPER-INHERITED] Txn " + txn.getId() + 
                                  " admitted with inherited locks");
                    }
                    return 0;  // No lock time (parent already paid it)
                }
                // Otherwise fall through to standard lock acquisition logic below
                if (logger != null) {
                    logger.log("[BOOKKEEPER-NEEDS-LOCKS] Txn " + txn.getId() + 
                              " needs additional locks beyond parent");
                }
            } else {
                LOGGER.warn("Bookkeeper {} root is not RUNNING; intake is stuck", txn.getId());
            }
        }
        
        if (!canAdmitPendingTransaction(txn, currentRunning)) {
            // Register group in capacity wait queue
            capacityWaitManager.registerPendingWaiter(txn.getGroup());
            if (dispatchStats != null) dispatchStats.recordNoWorkReason("CAPACITY_LIMIT");
            return -1;  // Signal to stop processing
        }
        
        // BASE mode: Skip lock acquisition entirely - optimistic execution
        if (processor.isBaseMode()) {
            // BASE: Always proceed immediately, no locks needed
            boolean success = processor.process(txn);
            if (dispatchStats != null) {
                dispatchStats.transactionsAdmitted++;
                dispatchStats.parentsAdmitted++;
            }
            // BASE always returns true, so we don't need lock time
            return 0;  // No lock time for BASE
        }
        
        // ACID mode: C2PL preclaim - collect required lock IDs and try to acquire all atomically
        List<String> requiredLockIds = collectRequiredLockIds(txn);
        
        // Use helper for event-driven admission with wait
        if (!tryAdmitWithLockCheck(txn, requiredLockIds)) {
            // Waiting for locks or requeued due to race
            return 0;  // Continue processing other transactions
        }
        
        // All locks acquired atomically - proceed with transaction initialization
        long lockAttemptTime = ioModel.getLockAcquisitionTime(txn);
        boolean success = processor.process(txn);
        
        if (dispatchStats != null) {
            if (success) {
                dispatchStats.transactionsAdmitted++;
                dispatchStats.parentsAdmitted++;
            } else {
                dispatchStats.transactionsBlocked++;
            }
        }
        
        if (!success) {
            // Lock failed - time doubled for releasing partial locks
            lockAttemptTime *= 2;
        }
        
        return lockAttemptTime;
    }
    
    /**
     * Try to admit a transaction with event-driven lock checking.
     * If locks are unavailable, registers as waiter instead of blind exponential backoff.
     * 
     * @param txn Transaction to admit
     * @param requiredLockIds Sorted list of lock IDs required
     * @return true if admitted, false if waiting or requeued
     */
    private boolean tryAdmitWithLockCheck(Transaction txn, List<String> requiredLockIds) {
        // Shape adaptation only: all EXCLUSIVE, same reasoning as
        // ACIDAdmissionController.collectRequiredLockModes(). This legacy dispatcher has no
        // protocol branch and is not on the experiment path, but it must keep compiling and
        // behaving identically.
        LinkedHashMap<String, LockType> requiredLocks = new LinkedHashMap<>();
        for (String lockId : requiredLockIds) {
            requiredLocks.put(lockId, LockType.EXCLUSIVE);
        }
        // Track admission attempt
        if (dispatchStats != null) dispatchStats.admissionAttemptsTotal++;
        
        // Check if all locks are available (don't acquire yet)
        String blockingLock = processor.getLockCoordinator().getLockManager()
                                       .checkLocksAvailable(txn, requiredLocks);
        
        if (blockingLock != null) {
            // Register waiter on first blocking lock
            if (admissionWaitManager != null) {
                admissionWaitManager.registerWaiter(blockingLock, txn, getCurrentVirtualTime());
                if (dispatchStats != null) dispatchStats.transactionsBackedOff++;
                if (logger != null) {
                    logger.log("[ADMISSION-WAIT] Txn " + txn.getId() + 
                              " waiting for lock: " + blockingLock);
                }
            } else {
                // Fallback: no admission wait manager, requeue
                queueManager.enqueue(txn, QueueType.PENDING);
            }
            return false;  // Admission failed
        }
        
        // All locks available - now acquire them atomically
        boolean success = processor.getLockCoordinator().getLockManager()
                                   .tryAcquireAllLocks(txn, requiredLocks);
        
        if (!success) {
            // Race condition: locks became unavailable between check and acquire
            // Rare but possible - requeue to retry
            queueManager.enqueue(txn, QueueType.PENDING);
            if (logger != null) {
                logger.log("[ADMISSION-RACE] Txn " + txn.getId() + 
                          " lost race, requeueing");
            }
            return false;
        }
        
        return true;  // Successfully admitted
    }
    
    /**
     * Collect the list of unique lock IDs required for this transaction's resources.
     * Uses granularityMapper to map resources to lock IDs.
     */
    private List<String> collectRequiredLockIds(Transaction txn) {
        Set<String> uniqueLockIds = new LinkedHashSet<>();
        
        if (granularityMapper != null) {
            // Use mapper to get accurate unique lock IDs
            for (Resource resource : txn.getResources()) {
                String lockId = granularityMapper.getLockId(resource);
                uniqueLockIds.add(lockId);
            }
        } else {
            // Fallback: use resource identities directly (fine-grained locking)
            for (Resource resource : txn.getResources()) {
                uniqueLockIds.add(resource.toString());
            }
        }
        
        // Return as sorted list for deterministic lock ordering (prevents deadlock)
        List<String> lockIds = new ArrayList<>(uniqueLockIds);
        Collections.sort(lockIds);
        return lockIds;
    }
    
    /**
     * Check if a bookkeeper requires additional locks beyond what the transaction group already holds.
     * 
     * This checks the actual lock table to see if group members ACTUALLY HOLD the required locks,
     * not just whether their resources would map to those lock IDs.
     * 
     * @param bookkeeper The bookkeeper transaction to check
     * @return true if bookkeeper needs additional locks, false if all locks already held by group
     */
    private boolean bookkeeperRequiresAdditionalLocks(BookkeeperTransaction bookkeeper) {
        if (granularityMapper == null) {
            // Conservative: assume needs locks if we can't verify
            return true;
        }
        
        // Get the lock IDs this bookkeeper needs
        Set<String> bookkeeperLockIds = bookkeeper.getLockIds(granularityMapper);
        
        // Collect all group members (parent + existing bookkeepers)
        Set<Transaction> groupMembers = new LinkedHashSet<>();
        Transaction root = bookkeeper.getRoot();
        groupMembers.add(root);
        
        if (root.getGroup() != null) {
            for (BookkeeperTransaction existingBk : root.getGroup().getBookkeepers()) {
                if (existingBk != bookkeeper) {  // Don't include self
                    groupMembers.add(existingBk);
                }
            }
        }
        
        // Get the actual lock manager to check lock table
        LockManager lockManager = processor.getLockCoordinator().getLockManager();
        if (lockManager instanceof LockStateView lockStateView) {
            for (String lockId : bookkeeperLockIds) {
                boolean lockHeldByGroup = false;

                Set<Transaction> lockHolders = lockStateView.getLockHolders(lockId);

                for (Transaction holder : lockHolders) {
                    if (groupMembers.contains(holder)) {
                        lockHeldByGroup = true;
                        break;
                    }
                }

                if (!lockHeldByGroup) {
                    return true;
                }
            }

            return false;  // All required locks are already held by the group
        }
        
        // Conservative: if we can't check the lock table, assume needs locks
        return true;
    }
}
