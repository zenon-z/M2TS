package test.java.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.ConcurrencyStrategy;
import de.tum.tssimulator.execution.LockingService;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;

class LockingServiceTest {

    private TransactionLockCoordinator mockLockCoordinator;
    private ConcurrencyStrategy mockStrategy;
    private LockingService lockingService;
    private Transaction testTransaction;

    @BeforeEach
    void setUp() {
        mockLockCoordinator = mock(TransactionLockCoordinator.class);
        mockStrategy = mock(ConcurrencyStrategy.class);
        lockingService = new LockingService(mockLockCoordinator, mockStrategy);
        testTransaction = new Transaction("T1", null, null);
    }

    @Test
    void processDelegatesToStrategy() {
        when(mockStrategy.process(eq(testTransaction))).thenReturn(true);

        boolean result = lockingService.process(testTransaction);

        assertTrue(result, "Should return true when strategy succeeds");
        verify(mockStrategy, times(1)).process(eq(testTransaction));
    }

    @Test
    void processReturnsFalseWhenBlocked() {
        when(mockStrategy.process(eq(testTransaction))).thenReturn(false);

        boolean result = lockingService.process(testTransaction);

        assertFalse(result, "Should return false when transaction is blocked");
        verify(mockStrategy, times(1)).process(eq(testTransaction));
    }

    @Test
    void releaseDelegatesToStrategy() {
        lockingService.release(testTransaction);

        verify(mockStrategy, times(1)).release(testTransaction);
    }

    @Test
    void periodicMaintenanceDelegatesToStrategy() {
        long virtualTime = 1000L;

        lockingService.periodicMaintenance(virtualTime);

        verify(mockStrategy, times(1)).periodicMaintenance(virtualTime);
    }

    @Test
    void getLockCoordinatorReturnsCoordinator() {
        TransactionLockCoordinator result = lockingService.getLockCoordinator();

        assertSame(mockLockCoordinator, result, "Should return the same lock coordinator");
    }

    @Test
    void isBaseModeWithBaseStrategy() {
        when(mockStrategy.getName()).thenReturn("BASE");

        boolean result = lockingService.isBaseMode();

        assertTrue(result, "Should return true for BASE strategy");
    }

    @Test
    void isBaseModeWithAcidStrategy() {
        when(mockStrategy.getName()).thenReturn("ACID");

        boolean result = lockingService.isBaseMode();

        assertFalse(result, "Should return false for ACID strategy");
    }

    @Test
    void isBaseModeWithNullStrategy() {
        LockingService serviceWithNull = new LockingService(mockLockCoordinator, null);

        boolean result = serviceWithNull.isBaseMode();

        assertFalse(result, "Should return false when strategy is null");
    }
}
