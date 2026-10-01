package test.java.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.execution.ACIDTransactionExecutor;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.BookkeeperService;
import de.tum.tssimulator.execution.LockingService;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestObjectFactory;

/**
 * Tests for ACIDTransactionExecutor - ACID execution strategy.
 */
public class ACIDTransactionExecutorTest {
    
    private TransactionProcessor mockProcessor;
    private TransactionLifecycleManager mockLifecycle;
    private TransactionGroupRegistry mockRegistry;
    private BookkeeperOrchestrator mockOrchestrator;
    private LockingService mockLockingService;
    private BookkeeperService mockBookkeeperService;
    private SimulationLogger mockLogger;
    private ACIDTransactionExecutor executor;
    
    @BeforeEach
    void setUp() {
        mockProcessor = mock(TransactionProcessor.class);
        mockLifecycle = mock(TransactionLifecycleManager.class);
        mockRegistry = mock(TransactionGroupRegistry.class);
        mockOrchestrator = mock(BookkeeperOrchestrator.class);
        mockLockingService = mock(LockingService.class);
        mockBookkeeperService = mock(BookkeeperService.class);
        mockLogger = mock(SimulationLogger.class);
        
        // Setup processor to return services
        when(mockProcessor.getLockingService()).thenReturn(mockLockingService);
        when(mockProcessor.getBookkeeperService()).thenReturn(mockBookkeeperService);
        when(mockLifecycle.getLogger()).thenReturn(mockLogger);
        
        executor = new ACIDTransactionExecutor(
            mockProcessor,
            mockLifecycle,
            mockRegistry,
            mockOrchestrator
        );
    }
    
    @Test
    void testGetMode_ReturnsACID() {
        // Act
        ConcurrencyModel mode = executor.getMode();
        
        // Assert
        assertEquals(ConcurrencyModel.ACID, mode);
    }
    
    @Test
    void testExecute_ProcessingFails_ReturnsFalse() {
        // Arrange
        Transaction txn = TestObjectFactory.createTransaction("T1");
        when(mockProcessor.process(txn)).thenReturn(false);
        
        // Act
        boolean result = executor.execute(txn);
        
        // Assert
        assertFalse(result, "Should return false when processing fails");
        verify(mockProcessor).process(txn);
        verifyNoInteractions(mockLifecycle, mockRegistry, mockOrchestrator);
    }
    
    @Test
    void testExecute_NotAtCompletePhase_ThrowsException() {
        // Arrange
        Transaction txn = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.CPU);
        when(mockProcessor.process(txn)).thenReturn(true);
        
        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            executor.execute(txn);
        });
        
        assertTrue(ex.getMessage().contains("not at COMPLETE phase"));
        assertTrue(ex.getMessage().contains("T1"));
    }
    
    @Test
    void testExecute_ParentTransactionNoBookkeepers_CompletesImmediately() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        when(mockProcessor.process(parent)).thenReturn(true);
        when(mockRegistry.get(parent)).thenReturn(null);
        
        // Act
        boolean result = executor.execute(parent);
        
        // Assert
        assertTrue(result, "Parent without bookkeepers should complete immediately");
        verify(mockProcessor).process(parent);
        verify(mockLockingService).release(parent);
        verify(mockLifecycle).complete(parent);
    }
    
    @Test
    void testExecute_BookkeeperTransaction_DelegatesToCompletionHandler() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.COMPLETE);
        
        when(mockProcessor.process(bookkeeper)).thenReturn(true);
        when(mockRegistry.get(bookkeeper)).thenReturn(mock(TransactionGroup.class));
        
        // Act
        boolean result = executor.execute(bookkeeper);
        
        // Assert
        verify(mockProcessor).process(bookkeeper);
        verify(mockBookkeeperService).createAndQueueBookkeepers(bookkeeper);
    }
    
    @Test
    void testExecute_ParentTransaction_CallsCompletionHandler() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        when(mockProcessor.process(parent)).thenReturn(true);
        when(mockRegistry.get(parent)).thenReturn(null);
        
        // Act
        executor.execute(parent);
        
        // Assert
        verify(mockProcessor).process(parent);
        verify(mockLockingService).release(parent);
        verify(mockLifecycle).complete(parent);
    }
    
    @Test
    void testExecute_IntegrationFlow_ProcessThenComplete() {
        // Arrange
        Transaction txn = TestObjectFactory.createTransaction("T1");
        
        // Simulate processor advancing transaction to COMPLETE
        when(mockProcessor.process(txn)).thenAnswer(invocation -> {
            TestObjectFactory.advanceToPhase(txn, ExecutionPhase.COMPLETE);
            return true;
        });
        when(mockRegistry.get(txn)).thenReturn(null);
        
        // Act
        boolean result = executor.execute(txn);
        
        // Assert
        assertTrue(result);
        assertEquals(ExecutionPhase.COMPLETE, txn.getPhase());
        verify(mockProcessor).process(txn);
        verify(mockLockingService).release(txn);
        verify(mockLifecycle).complete(txn);
    }
}
