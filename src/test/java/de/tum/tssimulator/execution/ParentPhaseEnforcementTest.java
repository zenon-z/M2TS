package de.tum.tssimulator.execution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * Tests that parent transactions must be at COMPLETE phase before bookkeeper creation.
 * Verifies the phase enforcement added in Step 1 of bug fixes.
 */
public class ParentPhaseEnforcementTest {

    private static class StubProcessor extends TransactionProcessor {
        public StubProcessor() { super(null, null, null, null, null, null); }
        @Override public void createAndQueueBookkeepers(Transaction txn) { /* no-op */ }
        @Override public void release(Transaction txn) { /* no-op */ }
    }

    private static class StubLifecycle extends TransactionLifecycleManager {
        public StubLifecycle() { super(null, null, null, 100); }
        @Override public void complete(Transaction t) { /* no-op */ }
        @Override public void moveToWaitingRoom(Transaction t) { /* no-op */ }
        @Override public void moveToAwaitingUserAction(Transaction t) { /* no-op */ }
    }

    private TransactionGroupCompletionHandler handler;
    private TransactionGroupRegistry registry;
    private BookkeeperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        StubProcessor processor = new StubProcessor();
        StubLifecycle lifecycle = new StubLifecycle();
        registry = new TransactionGroupRegistry();
        handler = new TransactionGroupCompletionHandler(
            processor, 
            lifecycle, 
            registry, 
            ConcurrencyModel.ACID
        );
        orchestrator = new BookkeeperOrchestrator(null);
    }

    @Test
    void testParentMustBeCompleteBeforeBookkeeperCreation() {
        // Arrange - create parent not at COMPLETE phase
        Transaction parent = new Transaction("parent-1", null, null);
        parent.initializeState();
        TransactionGroup group = new TransactionGroup(parent);
        registry.registerParent(group, parent);
        
        // Parent starts at null phase, not COMPLETE
        
        // Act & Assert - should throw IllegalStateException
        IllegalStateException exception = assertThrows(
            IllegalStateException.class, 
            () -> handler.completeTransaction(parent, orchestrator),
            "Should throw when parent not at COMPLETE phase"
        );
        
        assertTrue(exception.getMessage().contains("not at COMPLETE phase"),
            "Exception message should indicate phase enforcement failure");
        assertTrue(exception.getMessage().contains(parent.getId()),
            "Exception message should include transaction ID");
    }

    @Test
    void testParentAtCompletePhaseProceedsNormally() {
        // Arrange - create parent at COMPLETE phase
        Transaction parent = new Transaction("parent-2", null, null);
        parent.initializeState();
        TransactionGroup group = new TransactionGroup(parent);
        registry.registerParent(group, parent);
        
        // Set parent to COMPLETE phase
        while (parent.getPhase() != ExecutionPhase.COMPLETE) {
            parent.advancePhase();
        }
        
        // Act & Assert - should NOT throw
        assertDoesNotThrow(
            () -> handler.completeTransaction(parent, orchestrator),
            "Should not throw when parent is at COMPLETE phase"
        );
    }

    @Test
    void testExceptionMessageIncludesTransactionDetails() {
        // Arrange - create parent with specific ID
        Transaction parent = new Transaction("test-txn-123", null, null);
        parent.initializeState();
        TransactionGroup group = new TransactionGroup(parent);
        registry.registerParent(group, parent);
        
        // Act - get the exception
        IllegalStateException exception = assertThrows(
            IllegalStateException.class, 
            () -> handler.completeTransaction(parent, orchestrator)
        );
        
        // Assert - verify exception message has useful information
        String message = exception.getMessage();
        assertTrue(message.contains("test-txn-123"),
            "Exception should include transaction ID");
        assertTrue(message.contains("not at COMPLETE phase"),
            "Exception should explain the phase requirement");
        assertTrue(message.contains("Current phase"),
            "Exception should show current phase");
    }
}
