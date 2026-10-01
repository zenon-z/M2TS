package test.java.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;

/**
 * Simple functional tests to verify ACID/BASE concurrency switching works.
 * These tests verify the configuration and factory patterns without complex integration.
 */
class ConcurrencyModelSwitchingTest {

    @Test
    void testConcurrencyModelEnumExists() {
        // Verify enum values exist
        assertNotNull(ConcurrencyModel.ACID);
        assertNotNull(ConcurrencyModel.BASE);
        assertEquals(2, ConcurrencyModel.values().length);
    }
    
    @Test
    void testDefaultParametersUseACID() {
        // Arrange & Act
        SimulatorParameters params = SimulatorParameters.defaultParameters();
        
        // Assert
        assertNotNull(params.concurrencyModel);
        assertEquals(ConcurrencyModel.ACID, params.concurrencyModel);
    }
    
    @Test
    void testCanConfigureBASEMode() {
        // Arrange & Act
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .build();
        
        // Assert
        assertEquals(ConcurrencyModel.BASE, params.concurrencyModel);
        assertEquals(1, params.numberOfReconcilerProcessors);
    }
    
    @Test
    void testCanConfigureACIDMode() {
        // Arrange & Act
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.ACID)
            .build();
        
        // Assert
        assertEquals(ConcurrencyModel.ACID, params.concurrencyModel);
    }
    
    @Test
    void testDefaultReconciliationProcessors() {
        // Arrange & Act
        SimulatorParameters params = SimulatorParameters.defaultParameters();
        
        // Assert
        assertEquals(1, params.numberOfReconcilerProcessors, 
            "Default reconciliation processors should be 1");
    }
    
    @Test
    void testCustomReconciliationProcessors() {
        // Arrange & Act
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .numberOfReconcilerProcessors(2)
            .build();
        
        // Assert
        assertEquals(2, params.numberOfReconcilerProcessors);
    }
}
