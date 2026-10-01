package test.java.util;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.model.IOModel;
import java.util.List;

/**
 * Factory for creating IOModel instances in tests.
 */
public class TestIOModelFactory {
    
    /**
     * Creates a minimal IOModel for testing with default timing parameters.
     */
    public static IOModel createTestIOModel() {
        SimulatorConfig cfg = TestConfigFactory.create(
            0, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, 0, 0, 0
        );
        return new IOModel(cfg);
    }
    
    /**
     * Creates an IOModel for testing with custom config.
     */
    public static IOModel createTestIOModel(SimulatorConfig config) {
        return new IOModel(config);
    }
}
