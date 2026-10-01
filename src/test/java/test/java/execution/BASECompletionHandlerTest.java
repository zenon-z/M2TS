package test.java.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.BASECompletionHandler;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.BookkeeperService;
import de.tum.tssimulator.execution.LockingService;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMetrics;
import test.java.util.TestObjectFactory;

/**
 * Tests for BASECompletionHandler.
 */
class BASECompletionHandlerTest {
    
    private LockingService mockLockingService;
    private BookkeeperService mockBookkeeperService;
    private TransactionLifecycleManager mockLifecycle;
    private SimulationLogger mockLogger;
    private BookkeeperOrchestrator mockOrchestrator;
    private BASECompletionHandler handler;
    
    @BeforeEach
    void setUp() {
        mockLockingService = mock(LockingService.class);
        mockBookkeeperService = mock(BookkeeperService.class);
        mockLifecycle = mock(TransactionLifecycleManager.class);
        mockLogger = mock(SimulationLogger.class);
        mockOrchestrator = mock(BookkeeperOrchestrator.class);
        
        when(mockLifecycle.getLogger()).thenReturn(mockLogger);
        when(mockLogger.metrics()).thenReturn(new SimulationMetrics(0, 10));
        
        handler = new BASECompletionHandler(
            mockLockingService,
            mockBookkeeperService,
            mockLifecycle,
            null  // dirtySetManager (not needed for these tests)
        );
    }
    
    @Test
    void testCompleteParent_NotAtCompletePhase_ThrowsException() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.IO);
        
        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            handler.completeParent(parent, mockOrchestrator);
        });
        
        assertTrue(ex.getMessage().contains("not at COMPLETE phase"));
    }
    
    @Test
    void testCompleteParent_CompletesImmediately() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        
        // Act
        boolean result = handler.completeParent(parent, mockOrchestrator);
        
        // Assert
        assertTrue(result, "Parent should complete immediately in BASE mode");
        verify(mockBookkeeperService).createAndQueueBookkeepers(parent);
        verify(mockLockingService).release(parent);
        verify(mockLifecycle).complete(parent);
    }
    
    @Test
    void testCompleteBookkeeper_NotAtCompletePhase_ThrowsException() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.CPU);
        
        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            handler.completeBookkeeper(bookkeeper);
        });
        
        assertTrue(ex.getMessage().contains("not at COMPLETE phase"));
    }
    
    @Test
    void testCompleteBookkeeper_CompletesIndependently() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.COMPLETE);
        
        // Act
        boolean result = handler.completeBookkeeper(bookkeeper);
        
        // Assert
        assertTrue(result, "Bookkeeper should complete independently in BASE mode");
        verify(mockBookkeeperService).createAndQueueBookkeepers(bookkeeper);
        verify(mockLockingService).release(bookkeeper);
        verify(mockLifecycle).complete(bookkeeper);
    }
    
    @Test
    void testCompleteBookkeeper_CreatesChildBookkeepers() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.COMPLETE);
        
        // Act
        handler.completeBookkeeper(bookkeeper);
        
        // Assert
        verify(mockBookkeeperService).createAndQueueBookkeepers(bookkeeper);
    }
}
