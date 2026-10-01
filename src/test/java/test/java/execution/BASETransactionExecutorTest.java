package test.java.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.BASETransactionExecutor;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.BookkeeperService;
import de.tum.tssimulator.execution.LockingService;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMetrics;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestObjectFactory;

/**
 * Tests for BASETransactionExecutor - BASE execution strategy (eventual consistency).
 */
public class BASETransactionExecutorTest {
    
    private TransactionProcessor mockProcessor;
    private TransactionLifecycleManager mockLifecycle;
    private TransactionGroupRegistry mockRegistry;
    private BookkeeperOrchestrator mockOrchestrator;
    private LockingService mockLockingService;
    private BookkeeperService mockBookkeeperService;
    private SimulationLogger mockLogger;
    private BASETransactionExecutor executor;
    
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
        when(mockLogger.metrics()).thenReturn(new SimulationMetrics(0, 10));
        
        executor = new BASETransactionExecutor(
            mockProcessor,
            mockLifecycle,
            mockRegistry,
            mockOrchestrator,
            null  // dirtySetManager (not needed for this test)
        );
    }
    
    @Test
    void testGetMode_ReturnsBASE() {
        // Act
        ConcurrencyModel mode = executor.getMode();
        
        // Assert
        assertEquals(ConcurrencyModel.BASE, mode);
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
        Transaction txn = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.IO);
        when(mockProcessor.process(txn)).thenReturn(true);
        
        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            executor.execute(txn);
        });
        
        assertTrue(ex.getMessage().contains("not at COMPLETE phase"));
        assertTrue(ex.getMessage().contains("T1"));
    }
    
    @Test
    void testExecute_ParentTransaction_CompletesImmediately() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        when(mockProcessor.process(parent)).thenReturn(true);
        
        // Act
        boolean result = executor.execute(parent);
        
        // Assert
        assertTrue(result, "Parent should complete immediately in BASE mode");
        verify(mockProcessor).process(parent);
        verify(mockBookkeeperService).createAndQueueBookkeepers(parent);
        verify(mockLockingService).release(parent);
        verify(mockLifecycle).complete(parent);
    }
    
    @Test
    void testExecute_BookkeeperTransaction_CompletesIndependently() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.COMPLETE);
        
        when(mockProcessor.process(bookkeeper)).thenReturn(true);
        
        // Act
        boolean result = executor.execute(bookkeeper);
        
        // Assert
        assertTrue(result, "Bookkeeper should complete independently in BASE mode");
        verify(mockProcessor).process(bookkeeper);
        verify(mockBookkeeperService).createAndQueueBookkeepers(bookkeeper);
        verify(mockLockingService).release(bookkeeper);
        verify(mockLifecycle).complete(bookkeeper);
    }
    
    @Test
    void testExecute_ParentTransaction_CreatesBookkeepers() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        when(mockProcessor.process(parent)).thenReturn(true);
        
        // Act
        executor.execute(parent);
        
        // Assert
        verify(mockBookkeeperService).createAndQueueBookkeepers(parent);
    }
    
    @Test
    void testExecute_IntegrationFlow_ProcessThenCompleteImmediately() {
        // Arrange
        Transaction txn = TestObjectFactory.createTransaction("T1");
        
        // Simulate processor advancing transaction to COMPLETE
        when(mockProcessor.process(txn)).thenAnswer(invocation -> {
            TestObjectFactory.advanceToPhase(txn, ExecutionPhase.COMPLETE);
            return true;
        });
        
        // Act
        boolean result = executor.execute(txn);
        
        // Assert
        assertTrue(result);
        assertEquals(ExecutionPhase.COMPLETE, txn.getPhase());
        verify(mockProcessor).process(txn);
        verify(mockBookkeeperService).createAndQueueBookkeepers(txn);
        verify(mockLockingService).release(txn);
        verify(mockLifecycle).complete(txn);
    }
}
