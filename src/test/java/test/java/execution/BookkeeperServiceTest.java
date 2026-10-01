package test.java.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.BookkeeperService;
import de.tum.tssimulator.model.IOModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for BookkeeperService.
 */
class BookkeeperServiceTest {
    
    private BookkeeperCreationCoordinator mockCreator;
    private BookkeeperQueueRouter mockRouter;
    private IOModel mockIOModel;
    private BookkeeperService bookkeeperService;
    private Transaction testTransaction;
    
    @BeforeEach
    void setUp() {
        mockCreator = mock(BookkeeperCreationCoordinator.class);
        mockRouter = mock(BookkeeperQueueRouter.class);
        mockIOModel = mock(IOModel.class);
        bookkeeperService = new BookkeeperService(mockCreator, mockRouter, mockIOModel);
        testTransaction = new Transaction("T1", null, null);
        testTransaction.initializeState();
    }
    
    @Test
    void testCreateAndQueueBookkeepers_WithBookkeepers() {
        // Arrange
        List<BookkeeperTransaction> bookkeepers = new ArrayList<>();
        BookkeeperTransaction bk1 = mock(BookkeeperTransaction.class);
        BookkeeperTransaction bk2 = mock(BookkeeperTransaction.class);
        bookkeepers.add(bk1);
        bookkeepers.add(bk2);
        
        when(mockCreator.createBookkeepers(testTransaction)).thenReturn(bookkeepers);
        
        // Act
        bookkeeperService.createAndQueueBookkeepers(testTransaction);
        
        // Assert
        verify(mockCreator, times(1)).createBookkeepers(testTransaction);
        verify(mockRouter, times(1)).queueBookkeepers(bookkeepers);
    }
    
    @Test
    void testCreateAndQueueBookkeepers_NoBookkeepers() {
        // Arrange
        List<BookkeeperTransaction> emptyList = Collections.emptyList();
        when(mockCreator.createBookkeepers(testTransaction)).thenReturn(emptyList);
        
        // Act
        bookkeeperService.createAndQueueBookkeepers(testTransaction);
        
        // Assert
        verify(mockCreator, times(1)).createBookkeepers(testTransaction);
        verify(mockRouter, never()).queueBookkeepers(any());
    }
    
    @Test
    void testCreateAndQueueBookkeepers_PreparesPhasesWhenCreatorRequestsIt() {
        // Arrange
        BookkeeperCreationCoordinator creator = mock(BookkeeperCreationCoordinator.class);
        BookkeeperService baseService = new BookkeeperService(creator, mockRouter, mockIOModel);
        
        List<BookkeeperTransaction> bookkeepers = new ArrayList<>();
        BookkeeperTransaction bk1 = mock(BookkeeperTransaction.class);
        bookkeepers.add(bk1);
        
        when(creator.createBookkeepers(testTransaction)).thenReturn(bookkeepers);
        
        // Act
        baseService.createAndQueueBookkeepers(testTransaction);
        
        // Assert
        verify(creator, times(1)).createBookkeepers(testTransaction);
        verify(creator, times(1)).preparePhases(bookkeepers);
        verify(mockRouter, times(1)).queueBookkeepers(bookkeepers);
    }
    
    @Test
    void testCreateAndQueueBookkeepers_EmptyList_DoesNotPreparePhases() {
        // Arrange
        BookkeeperCreationCoordinator creator = mock(BookkeeperCreationCoordinator.class);
        BookkeeperService baseService = new BookkeeperService(creator, mockRouter, mockIOModel);
        
        List<BookkeeperTransaction> emptyList = Collections.emptyList();
        when(creator.createBookkeepers(testTransaction)).thenReturn(emptyList);
        
        // Act
        baseService.createAndQueueBookkeepers(testTransaction);
        
        // Assert
        verify(creator, times(1)).createBookkeepers(testTransaction);
        verify(creator, never()).preparePhases(any());
        verify(mockRouter, never()).queueBookkeepers(any());
    }
    
    @Test
    void testGetCreator_ReturnsCreator() {
        // Act
        BookkeeperCreationCoordinator result = bookkeeperService.getCreator();
        
        // Assert
        assertSame(mockCreator, result, "Should return the same creator");
    }
    
    @Test
    void testGetRouter_ReturnsRouter() {
        // Act
        BookkeeperQueueRouter result = bookkeeperService.getRouter();
        
        // Assert
        assertSame(mockRouter, result, "Should return the same router");
    }
}
