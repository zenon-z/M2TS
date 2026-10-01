package de.tum.tssimulator.bookkeeping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * Abstract coordinator for bookkeeper creation.
 * Provides common creation logic with template methods for ACID/BASE-specific filtering.
 * 
 * Responsibilities:
 * - Create bookkeepers from parent/bookkeeper transactions
 * - Enforce depth limits
 * - Handle ON_RESTRICT policy
 * - Register bookkeepers with groups
 * - Filter bookkeepers (mode-specific via template method)
 */
public abstract class BookkeeperCreationCoordinator {
    // Common fields (all subclasses need these)
    protected final BookkeeperFactory factory;
    protected final SimulationLogger logger;
    protected final TransactionGroupRegistry registry;
    protected final BookkeeperOrchestrator orchestrator;

    /**
     * Constructor with common dependencies.
     */
    public BookkeeperCreationCoordinator(
        BookkeeperFactory factory,
        SimulationLogger logger,
        TransactionGroupRegistry registry,
        BookkeeperOrchestrator orchestrator
    ) {
        this.factory = factory;
        this.logger = logger;
        this.registry = registry;
        this.orchestrator = orchestrator;
    }

    /**
     * Main entry point: create bookkeepers from a transaction.
     * This method implements the template method pattern with common logic
     * and calls abstract methods for mode-specific behavior.
     *
     * <p>No depth check: cascade termination is enforced entirely by
     * {@link #filterAlreadyCoveredResources} - the visited-set of resources already covered by
     * this cascade's group, which already spans the whole chain (every cascaded
     * {@link BookkeeperTransaction} carries {@code parent.group}, tracing back transitively to
     * the one root transaction's group).
     */
    public final List<BookkeeperTransaction> createBookkeepers(Transaction txn) {
        // 1. Get/create group (common)
        TransactionGroup group = getOrCreateGroup(txn);

        // 2. Create bookkeepers via factory (common)
        List<BookkeeperTransaction> created = factory.createFrom(txn);
        group.addAttemptedBookkeepers(created.size());
        List<BookkeeperTransaction> distinct = filterAlreadyCoveredResources(created, txn, group);
        
        // 3. Filter bookkeepers (ACID/BASE-specific)
        List<BookkeeperTransaction> filtered = filterBookkeepers(distinct, txn);

        // 4. ON_RESTRICT enforcement (common)
        if (orchestrator != null && !orchestrator.shouldCreateBookkeepers(txn, filtered.size())) {
            return Collections.emptyList();
        }

        // 5. Register bookkeepers (common)
        registerBookkeepers(group, filtered, created.size());

        return filtered;
    }

    /**
     * Enforce resource-level distinctness within one transaction cascade.
     * A parent transaction and all CPRs spawned from it should touch distinct resources.
     * If a newly created CPR targets a resource already covered by the group, it would only
     * revisit already synchronized work, so we skip it. This also collapses duplicate targets
     * produced by multiple source resources within the same batch.
     */
    private List<BookkeeperTransaction> filterAlreadyCoveredResources(
        List<BookkeeperTransaction> created,
        Transaction sourceTxn,
        TransactionGroup group
    ) {
        if (created.isEmpty()) {
            return created;
        }
        Set<Resource> covered = collectCoveredResources(group);
        List<BookkeeperTransaction> distinct = new ArrayList<>();
        for (BookkeeperTransaction bk : created) {
            Resource target = extractTargetResource(bk);
            if (target != null && covered.add(target)) {
                distinct.add(bk);
            } else if (target != null) {
                // Cascade pruning, NOT fan-out collapse. Counted separately so V3 can assert it is
                // zero at its design point (fan-out 1, LINE, 2 models: a parent reaches each target
                // once), which is what makes the two arms' collapse denominators the same
                // population by measurement rather than by definition. See V3's plan.
                logger.metrics().incrementVisitedSetDropped(1);
            }
        }
        return distinct;
    }

    private Set<Resource> collectCoveredResources(TransactionGroup group) {
        Set<Resource> covered = new HashSet<>();
        if (group == null) {
            return covered;
        }

        addResources(group.getParent(), covered);
        for (BookkeeperTransaction existingBk : group.getBookkeepers()) {
            addResources(existingBk, covered);
        }
        return covered;
    }

    private void addResources(Transaction txn, Set<Resource> covered) {
        if (txn == null || txn.getResources() == null) {
            return;
        }
        covered.addAll(txn.getResources());
    }

    private Resource extractTargetResource(BookkeeperTransaction bk) {
        List<Resource> resources = bk.getResources();
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        return resources.get(0);
    }
    
    /**
     * Get existing group from registry or create new one.
     */
    private TransactionGroup getOrCreateGroup(Transaction txn) {
        TransactionGroup group = (txn instanceof BookkeeperTransaction bookkeeper)
                ? registry.get(bookkeeper)
                : registry.get(txn);
        
        if (group == null) {
            group = txn.getGroup();
            if (group == null) {
                throw new IllegalStateException(
                    "Transaction " + txn.getId() + " has no associated group"
                );
            }
            
            // Always register with the root parent, not a bookkeeper
            Transaction rootParent = (txn instanceof BookkeeperTransaction) 
                ? group.getParent() 
                : txn;
            registry.registerParent(group, rootParent);
        }
        
        return group;
    }
    
    /**
     * Register bookkeepers with group and log creation event.
     */
    private void registerBookkeepers(
        TransactionGroup group, 
        List<BookkeeperTransaction> filtered,
        int totalCreated
    ) {
        String message = "createdBookkeepers=" + filtered.size() + 
                        " (total=" + totalCreated + ")";
        
        logger.logEvent(new LogEvent(
            LogEvent.Type.BOOKKEEPERS_CREATED, 
            group.getParent().getId(), 
            LogEvent.NO_VIRTUAL_TIME, 
            message
        ));
        
        // Track bookkeeper depths for analysis (per-group only)
        for (BookkeeperTransaction bk : filtered) {
            group.recordBookkeeperDepth(bk.getDepth());
        }
        
        // The single point at which CUs enter a group, for both ACID and BASE - so it is where
        // the live-CU count is incremented. See SimulationMetrics.cusLive for why that count is
        // maintained here rather than derived from bookkeepersCreatedTotal.
        logger.metrics().incrementCusRegistered(filtered.size());
        // CRITICAL: Append to existing bookkeepers (don't clear) to support depth > 1
        group.addBookkeepers(filtered);
        registry.registerBookkeepers(group, filtered);
    }
    
    /**
     * Template method: Filter bookkeepers based on mode-specific logic.
     * - ACID: No filtering (all bookkeepers created)
     * - BASE: Dirty set filtering (skip duplicates)
     * 
     * @param bookkeepers All bookkeepers created by factory
     * @param parent Parent transaction that triggered bookkeeper creation
     * @return Filtered list of bookkeepers to actually create
     */
    protected abstract List<BookkeeperTransaction> filterBookkeepers(
        List<BookkeeperTransaction> bookkeepers, 
        Transaction parent
    );
    
    /**
     * Template method: Clear resources after bookkeeper completes (BASE only).
     * Called by TransactionGroupCompletionHandler.
     * 
     * @param bookkeeper The bookkeeper that completed
     */
    public abstract void onBookkeeperCompleted(BookkeeperTransaction bookkeeper);

    /**
     * Optional hook for implementations that need post-construction phase initializer wiring.
     * Default is no-op for implementations that do not use it.
     */
    public void setPhaseInitializer(TransactionPhaseInitializer phaseInitializer) {
        // No-op by default.
    }

    /**
     * Optional hook for implementations that need to prepare phases before queueing.
     * Default is no-op for implementations that do not use it.
     */
    public void preparePhases(List<BookkeeperTransaction> bookkeepers) {
        // No-op by default.
    }
}
