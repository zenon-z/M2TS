package de.tum.tssimulator.locking.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;

class LockGranularityMapperTest {

    private static List<Model> pool() {
        return new ModelFactory(5).createModels(List.of(5, 4, 3));
    }

    @Test
    void globalOrdinalIsDeterministicAcrossInstances() {
        List<Model> modelsA = pool();
        List<Model> modelsB = pool();
        LockGranularityMapper mapperA = new LockGranularityMapper(10, modelsA);
        LockGranularityMapper mapperB = new LockGranularityMapper(10, modelsB);

        for (int i = 0; i < modelsA.size(); i++) {
            Model a = modelsA.get(i);
            Model b = modelsB.get(i);
            for (int j = 0; j < a.resourceCount(); j++) {
                Resource ra = a.getOwnedResources().get(j);
                Resource rb = b.getOwnedResources().get(j);
                assertEquals(mapperA.globalOrdinal(ra), mapperB.globalOrdinal(rb));
            }
        }
    }

    @Test
    void globalOrdinalIsOffsetPlusLocalOrdinalContiguousPerModel() {
        List<Model> models = pool(); // 5,4,3 properties -> 5,4,3 resources at instancePoolSize=1...
        // instancePoolSize=5 here, so resourceCount = properties*5: 25,20,15
        LockGranularityMapper mapper = new LockGranularityMapper(1000, models);

        Model model1 = models.get(0);
        Model model2 = models.get(1);
        Model model3 = models.get(2);

        // Model1's resources occupy ordinals [0, resourceCount1)
        for (Resource r : model1.getOwnedResources()) {
            assertEquals(model1.localOrdinal(r), mapper.globalOrdinal(r));
        }
        // Model2's resources are offset by model1's resourceCount
        int offset2 = model1.resourceCount();
        for (Resource r : model2.getOwnedResources()) {
            assertEquals(offset2 + model2.localOrdinal(r), mapper.globalOrdinal(r));
        }
        // Model3's resources are offset by model1+model2's resourceCount
        int offset3 = model1.resourceCount() + model2.resourceCount();
        for (Resource r : model3.getOwnedResources()) {
            assertEquals(offset3 + model3.localOrdinal(r), mapper.globalOrdinal(r));
        }
    }

    @Test
    void lockIdIsStableAcrossRepeatedCalls() {
        List<Model> models = pool();
        LockGranularityMapper mapper = new LockGranularityMapper(10, models);
        Resource r = models.get(1).getOwnedResources().get(3);

        String first = mapper.getLockId(r);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, mapper.getLockId(r));
        }
    }

    @Test
    void sameConfigurationProducesSameContiguousBlockGrouping() {
        // Coarse-grained: totalLocks=3, models each get their own contiguous block of locks
        // under the modulo strategy, shape unchanged from the old resourceNum-based scheme.
        List<Model> modelsA = pool();
        List<Model> modelsB = pool();
        LockGranularityMapper mapperA = new LockGranularityMapper(3, modelsA);
        LockGranularityMapper mapperB = new LockGranularityMapper(3, modelsB);

        for (int i = 0; i < modelsA.size(); i++) {
            for (int j = 0; j < modelsA.get(i).resourceCount(); j++) {
                Resource ra = modelsA.get(i).getOwnedResources().get(j);
                Resource rb = modelsB.get(i).getOwnedResources().get(j);
                assertEquals(mapperA.getLockId(ra), mapperB.getLockId(rb));
            }
        }
    }

    @Test
    void rejectsResourceOutsideModelPool() {
        LockGranularityMapper mapper = new LockGranularityMapper(10, pool());
        // pool() only has Model1..Model3 - Model4 (from a larger pool) is a genuine outsider;
        // a fresh independent ModelFactory call would instead collide by both naming their sole
        // model "Model1" (name-based equality), which is not what this test wants to exercise.
        Model outsider = new ModelFactory(5).createModels(List.of(1, 1, 1, 1)).get(3);
        Resource resource = outsider.getOwnedResources().get(0);

        assertThrows(IllegalArgumentException.class, () -> mapper.globalOrdinal(resource));
    }

    @Test
    void rejectsEmptyModelPool() {
        assertThrows(IllegalArgumentException.class, () -> new LockGranularityMapper(10, List.of()));
    }
}
