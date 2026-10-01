package de.tum.tssimulator.bookkeeping;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.DirtyKey;
import de.tum.tssimulator.state.DirtyKeyUtil;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * BASE implementation of bookkeeper creation coordinator.
 * 
 * BASE Characteristics:
 * - Dirty set filtering: Skip bookkeepers for resources with pending reconciliation (fan-out collapse)
 * - Eventually consistent: Bookkeepers execute asynchronously on reconciliation processors
 * - Resource tracking: Clear dirty set when bookkeeper starts reconciliation
 */
class BASEBookkeeperCreator extends BookkeeperCreationCoordinator {
    private final DirtyStateTracker dirtySetManager;
    private TransactionPhaseInitializer phaseInitializer;  // Non-final to allow post-construction wiring
    private final boolean hasDedicatedReconcilers;
    private final LongSupplier virtualTimeClock;
    
    public BASEBookkeeperCreator(
        BookkeeperFactory factory,
        SimulationLogger logger,
        TransactionGroupRegistry registry,
        BookkeeperOrchestrator orchestrator,
        DirtyStateTracker dirtySetManager,
        TransactionPhaseInitializer phaseInitializer,
        boolean hasDedicatedReconcilers,
        LongSupplier virtualTimeClock
    ) {
        super(factory, logger, registry, orchestrator);
        this.dirtySetManager = dirtySetManager;
        this.phaseInitializer = phaseInitializer;
        this.hasDedicatedReconcilers = hasDedicatedReconcilers;
        this.virtualTimeClock = virtualTimeClock;
    }
    
    @Override
    protected List<BookkeeperTransaction> filterBookkeepers(
        List<BookkeeperTransaction> bookkeepers, 
        Transaction parent
    ) {
        // BASE: Filter via dirty set (fan-out collapse)
        if (dirtySetManager == null) {
            return bookkeepers; // No dirty set manager, return all
        }
        
        List<BookkeeperTransaction> filtered = new ArrayList<>();
        long virtualTime = virtualTimeClock.getAsLong();
        
        for (BookkeeperTransaction bk : bookkeepers) {
            DirtyKey key = DirtyKeyUtil.fromBookkeeper(bk);  // Use shared utility
            if (key != null) {
                // V3 collapsed_fraction denominator: this CU was OFFERED to the collapse. Counted
                // here, at the filter, and never derived from attemptedBookkeepersTotal - that
                // total is incremented before the visited-set filter and so also counts cascade
                // pruning, which PostgreSQL has no counterpart for. See V3's plan.
                logger.metrics().incrementCollapseOffered(1);
                // The occupancy THIS offer sees, before its own markDirty. Same site and same
                // condition as the offered counter, so the two cannot disagree about what an offer
                // is - and taken BEFORE the mark, because an offer cannot collide with itself.
                int dirtyNow = dirtySetManager.size();
                logger.metrics().recordOfferDirtySetSize(dirtyNow);
                // filtered.size() is EXACTLY this parent's own keys marked so far in this burst:
                // each admitted bookkeeper marked one, virtual time has not advanced inside this
                // loop, so none of them can have cleared.
                logger.metrics().recordOfferDirtySetSizeExclOwn(dirtyNow - filtered.size());
                // markDirty returns true if NOT already dirty
                if (dirtySetManager.markDirty(key, virtualTime)) {
                    filtered.add(bk);  // Resource not dirty - create bookkeeper
                    logger.logEvent(new LogEvent(
                        LogEvent.Type.DIRTY_MARKED, 
                        parent.getId(), 
                        virtualTime, 
                        "markedDirty=" + key
                    ));
                } else {
                    // Resource already dirty - skip (fan-out collapse). V3 numerator.
                    logger.metrics().incrementCollapseSkipped(1);
                    logger.logEvent(new LogEvent(
                        LogEvent.Type.DIRTY_SKIPPED, 
                        parent.getId(), 
                        virtualTime, 
                        "skippedDirty=" + key
                    ));
                }
            }
        }
        
        return filtered;
    }
    
    @Override
    public void onBookkeeperCompleted(BookkeeperTransaction bk) {
        // Dirty set clearing now happens in BASECompletionHandler (single clearing point)
        // This method is intentionally empty - DO NOT clear dirty set here
        // See: BASECompletionHandler.completeBookkeeper()
    }
    
    /**
     * Wire phaseInitializer after construction (resolves circular dependency).
     */
    public void setPhaseInitializer(TransactionPhaseInitializer phaseInitializer) {
        this.phaseInitializer = phaseInitializer;
    }
    
    /**
     * Prepare bookkeeper phases before queueing (BASE-specific).
     * Called by queue router before enqueueing.
     */
    public void preparePhases(List<BookkeeperTransaction> bookkeepers) {
        if (phaseInitializer == null) {
            return;
        }
        
        for (BookkeeperTransaction bk : bookkeepers) {
            // BASE bookkeepers are already in BACKGROUND_WORK state after creation
            // NO state transition needed - they bypass PENDING→RUNNING flow entirely
            
            // Initialize correct phase (skip LOCK phase in BASE mode)
            if (hasDedicatedReconcilers) {
                // Dedicated reconcilers: start in RECONCILIATION phase
                // ReconciliationProcessorUnit advances: RECONCILIATION → IO → CPU
                bk.preparePhase(ExecutionPhase.RECONCILIATION, phaseInitializer.getIOModel());
            } else {
                // Single processor inline: start in IO phase (no reconciliation)
                // CompositePhaseDispatcher advances: IO → CPU
                bk.preparePhase(ExecutionPhase.IO, phaseInitializer.getIOModel());
            }
        }
    }
}
