package test.java.execution;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.ACIDTransactionExecutor;
import de.tum.tssimulator.execution.BookkeeperService;
import de.tum.tssimulator.execution.LockingService;
import de.tum.tssimulator.execution.TransactionExecutor;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.TransactionGroupRegistry;

class TransactionExecutorTest {

    private TransactionProcessor mockProcessor;
    private TransactionLifecycleManager mockLifecycle;
    private TransactionGroupRegistry mockRegistry;
    private LockingService mockLockingService;
    private BookkeeperService mockBookkeeperService;
    private SimulationLogger mockLogger;
    private TransactionExecutor executor;

    @BeforeEach
    void setUp() {
        mockProcessor = mock(TransactionProcessor.class);
        mockLifecycle = mock(TransactionLifecycleManager.class);
        mockRegistry = mock(TransactionGroupRegistry.class);
        mockLockingService = mock(LockingService.class);
        mockBookkeeperService = mock(BookkeeperService.class);
        mockLogger = mock(SimulationLogger.class);

        // Setup processor to return services
        when(mockProcessor.getLockingService()).thenReturn(mockLockingService);
        when(mockProcessor.getBookkeeperService()).thenReturn(mockBookkeeperService);
        when(mockLifecycle.getLogger()).thenReturn(mockLogger);

        // Create strategy and executor
        var strategy = new ACIDTransactionExecutor(
            mockProcessor,
            mockLifecycle,
            mockRegistry,
            null
        );
        executor = new TransactionExecutor(strategy);
    }

    @Test
    void executeSuccessCallsReleaseCompleteAndRetry() {
        // Arrange
        // Empty resource list, not null: a Transaction with null resources is not a state any real
        // run can reach - IOModel.getLockTime and getRollbackTime both dereference getResources()
        // unconditionally - so the fixture is what needs to be valid, not production.
        Transaction txn = new Transaction("T1", List.of(), null);
        txn.initializeState();
        
        // Mock processor.process() to succeed and advance to COMPLETE phase
        when(mockProcessor.process(txn)).thenAnswer(invocation -> {
            while (txn.getPhase() != ExecutionPhase.COMPLETE) {
                txn.advancePhase();
            }
            return true;
        });

        // Act
        boolean result = executor.execute(txn);

        // Assert
        assertTrue(result);
        verify(mockProcessor).process(txn);
        verify(mockLockingService).release(txn);  // Release via LockingService
        verify(mockLifecycle).complete(txn);
    }

    @Test
    void executeFailureShortCircuits() {
        // Arrange
        Transaction txn = new Transaction("T1", List.of(), null);
        txn.initializeState();
        
        // Mock processor.process() to fail
        when(mockProcessor.process(txn)).thenReturn(false);

        // Act
        boolean result = executor.execute(txn);

        // Assert
        assertFalse(result);
        verify(mockProcessor).process(txn);
        verify(mockLockingService, never()).release(any());
        verify(mockLifecycle, never()).complete(any());
    }
}
