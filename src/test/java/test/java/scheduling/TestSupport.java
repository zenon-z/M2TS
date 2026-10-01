package test.java.scheduling;

import java.util.List;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Model;

public final class TestSupport {
    private TestSupport() {}

    public static SimulatorConfig testConfig() {
        return test.java.util.TestConfigFactory.create(
            3, 5,
            1, 1,
            0, 3, 1,
            List.<Model>of(),
            1, 1,
            0, 0,
            1, 1,
            100
        );
    }
}
