package test.java.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.execution.ACIDCompletionHandler;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.BookkeeperService;
import de.tum.tssimulator.execution.LockingService;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMetrics;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestObjectFactory;

import java.util.List;

/**
 * Tests for ACIDCompletionHandler.
 */
class ACIDCompletionHandlerTest {
    
    private LockingService mockLockingService;
    private BookkeeperService mockBookkeeperService;
    private TransactionLifecycleManager mockLifecycle;
    private TransactionGroupRegistry mockRegistry;
    private BookkeeperOrchestrator mockOrchestrator;
    private SimulationLogger mockLogger;
    private ACIDCompletionHandler handler;
    
    @BeforeEach
    void setUp() {
        mockLockingService = mock(LockingService.class);
        mockBookkeeperService = mock(BookkeeperService.class);
        mockLifecycle = mock(TransactionLifecycleManager.class);
        mockRegistry = mock(TransactionGroupRegistry.class);
        mockOrchestrator = mock(BookkeeperOrchestrator.class);
        mockLogger = mock(SimulationLogger.class);
        
        // Setup lifecycle to return logger
        when(mockLifecycle.getLogger()).thenReturn(mockLogger);
        when(mockLogger.metrics()).thenReturn(new SimulationMetrics(0, 10));
        
        handler = new ACIDCompletionHandler(
            mockLockingService,
            mockBookkeeperService,
            mockLifecycle,
            mockRegistry
        );
    }
    
    @Test
    void testCompleteParent_NotAtCompletePhase_ThrowsException() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.CPU);
        
        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            handler.completeParent(parent, mockOrchestrator);
        });
        
        assertTrue(ex.getMessage().contains("not at COMPLETE phase"));
    }
    
    @Test
    void testCompleteParent_SingleTransaction_CompletesImmediately() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        when(mockRegistry.get(parent)).thenReturn(null);
        
        // Act
        boolean result = handler.completeParent(parent, mockOrchestrator);
        
        // Assert
        assertTrue(result, "Single transaction should complete immediately");
        verify(mockLockingService).release(parent);
        verify(mockLifecycle).complete(parent);
    }
    
    @Test
    void testCompleteParent_WithBookkeepers_MovesToWaitingRoom() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        BookkeeperTransaction bk = TestObjectFactory.createBookkeeper(parent, "BK1");
        
        TransactionGroup group = mock(TransactionGroup.class);
        List<BookkeeperTransaction> bookkeepers = List.of(bk);
        
        when(mockRegistry.get(parent)).thenReturn(group);
        when(group.isMarkedForUserAction()).thenReturn(false);
        when(group.getBookkeepers()).thenReturn(bookkeepers);
        
        // Act
        boolean result = handler.completeParent(parent, mockOrchestrator);
        
        // Assert
        assertFalse(result, "Parent should wait for bookkeepers");
        verify(mockLifecycle).moveToWaitingRoom(parent);
        verify(mockLockingService, never()).release(parent);
    }
    
    @Test
    void testCompleteBookkeeper_NotAtCompletePhase_ThrowsException() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.IO);
        
        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            handler.completeBookkeeper(bookkeeper);
        });
        
        assertTrue(ex.getMessage().contains("not at COMPLETE phase"));
    }
    
    @Test
    void testCompleteBookkeeper_NoGroup_ThrowsException() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.COMPLETE);
        when(mockRegistry.get(bookkeeper)).thenReturn(null);
        
        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            handler.completeBookkeeper(bookkeeper);
        });
        
        assertTrue(ex.getMessage().contains("No group"));
    }
    
    @Test
    void testCompleteBookkeeper_GroupNotComplete_MovesToWaitingRoom() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransaction("T1");
        BookkeeperTransaction bookkeeper = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.COMPLETE);
        
        TransactionGroup group = mock(TransactionGroup.class);
        when(mockRegistry.get(bookkeeper)).thenReturn(group);
        when(group.isComplete()).thenReturn(false);
        
        // Act
        boolean result = handler.completeBookkeeper(bookkeeper);
        
        // Assert
        assertFalse(result, "Bookkeeper should wait for group");
        verify(mockBookkeeperService).createAndQueueBookkeepers(bookkeeper);
        verify(group).markBookkeeperCompleted();
        verify(mockLifecycle).moveToWaitingRoom(bookkeeper);
    }
    
    @Test
    void testCompleteBookkeeper_GroupComplete_CompletesAtomically() {
        // Arrange
        Transaction parent = TestObjectFactory.createTransactionAtPhase("T1", ExecutionPhase.COMPLETE);
        BookkeeperTransaction bk1 = TestObjectFactory.createBookkeeperAtPhase(parent, "BK1", ExecutionPhase.COMPLETE);
        
        List<BookkeeperTransaction> bookkeepers = List.of(bk1);
        
        TransactionGroup group = mock(TransactionGroup.class);
        when(group.getGroupId()).thenReturn(1L);
        when(group.getParent()).thenReturn(parent);
        when(group.getBookkeepers()).thenReturn(bookkeepers);
        when(group.isComplete()).thenReturn(true);
        // A real TransactionGroup's bookkeepersAtDepth is a `final int[9]` initialized at field
        // declaration - it can never be null. Mockito's default answer for an unstubbed
        // int[]-returning method is null, not an empty array, so this stub is required to match
        // real TransactionGroup behavior; without it, ACIDCompletionHandler.recordGroupImpact()'s
        // real (and correct) `for (int i = 0; i < depthCounts.length; i++)` loop NPEs on a
        // test-fixture gap, not a production defect.
        when(group.getBookkeepersAtDepth()).thenReturn(new int[9]);
        when(group.getAttemptedBookkeepers()).thenReturn(0);

        when(mockRegistry.get(bk1)).thenReturn(group);
        
        // Act
        boolean result = handler.completeBookkeeper(bk1);
        
        // Assert
        assertTrue(result, "Group should complete atomically");
        verify(mockLockingService).release(parent);
        verify(mockLockingService).release(bk1);
        verify(mockLifecycle).complete(parent);
        verify(mockLifecycle).complete(bk1);
        verify(mockRegistry).clear(group);
    }
}
