package test.java.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.monitoring.RuntimeHealthSnapshot;
import test.java.util.TestConfigFactory;

class ExperimentRuntimeSnapshotTest {

    private static final List<Model> MODEL_POOL = new ModelFactory(1).createModels(List.of(1));

    @Test
    void capturesExperimentFacingRuntimeSnapshotFromContext() {
        SimulatorContext context = SimulatorContext.create(TestConfigFactory.create(
            1, 1,
            0L, 0L,
            0, 1, 1,
            MODEL_POOL,
            0, 0,
            0L, 0L,
            1, 1,
            1
        ));

        ExperimentRuntimeSnapshot snapshot = context.experimentSnapshot();
        RuntimeHealthSnapshot expectedHealth = context.monitor().snapshot(context.virtualTime);

        assertNotNull(snapshot);
        assertEquals(expectedHealth, snapshot.healthSnapshot());
        assertEquals(context.logger().getTotalLockTime(), snapshot.totalLockTime());
        assertEquals(context.logger().getTotalAborted(), snapshot.totalAbortedGroups());
        assertEquals(context.logger().getTotalBookkeepers(), snapshot.totalBookkeepers());
        assertEquals(0, snapshot.abortBackoffQueueSize());
    }

    @Test
    void defensivelyCopiesDepthDistribution() {
        SimulatorContext context = SimulatorContext.create(TestConfigFactory.create(
            1, 1,
            0L, 0L,
            0, 1, 1,
            MODEL_POOL,
            0, 0,
            0L, 0L,
            1, 1,
            1
        ));
        context.logger().recordBookkeeperDepth(3);

        ExperimentRuntimeSnapshot snapshot = context.experimentSnapshot();
        int[] depthCounts = snapshot.bookkeeperDepthCounts();
        depthCounts[3] = 99;

        assertEquals(1, snapshot.depthCountAt(3));
    }
}
