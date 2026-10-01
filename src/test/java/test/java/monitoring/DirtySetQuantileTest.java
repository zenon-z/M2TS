package test.java.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.monitoring.SimulationLogger;

/**
 * Unit tests for dirty set quantile calculation in SimulationLogger.
 * Tests the nearest-rank quantile method with exact assertions.
 */
public class DirtySetQuantileTest {

    @Test
    void testQuantileCalculation_KnownDistribution() {
        // Create logger with known samples: 0, 10, 20, ..., 90
        // With 100 totalResources, these become 0%, 10%, 20%, ..., 90%
        SimulationLogger logger = new SimulationLogger("test.log", 100, 10);
        
        // Add 10 samples with known dirty set sizes: 0, 10, 20, ..., 90
        for (int i = 0; i < 10; i++) {
            logger.sampleDirtySetSize(i * 10);
        }
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNotNull(stats, "Stats should not be null");
        assertEquals(10, stats.sampleCount, "Sample count should be 10");
        
        // Mean: (0+10+...+90)/10 = 450/10 = 45
        assertEquals(45.0, stats.meanPct, 0.01, "Mean should be 45%");
        
        // Nearest-rank p50: n=10, idx = ceil(0.5*10)-1 = ceil(5)-1 = 4 → sortedSamples[4] = 40%
        assertEquals(40.0, stats.p50Pct, 0.01, "p50 should be 40%");
        
        // p95: idx = ceil(0.95*10)-1 = ceil(9.5)-1 = 9 → sortedSamples[9] = 90%
        assertEquals(90.0, stats.p95Pct, 0.01, "p95 should be 90%");
        
        // p99: idx = ceil(0.99*10)-1 = ceil(9.9)-1 = 9 → sortedSamples[9] = 90%
        assertEquals(90.0, stats.p99Pct, 0.01, "p99 should be 90%");
        
        // Max
        assertEquals(90.0, stats.maxPct, 0.01, "Max should be 90%");
        
        // Verify quantile ordering
        assertTrue(stats.p50Pct <= stats.p95Pct, "p50 should be <= p95");
        assertTrue(stats.p95Pct <= stats.p99Pct, "p95 should be <= p99");
        assertTrue(stats.p99Pct <= stats.maxPct, "p99 should be <= max");
    }

    @Test
    void testQuantileCalculation_SkewedDistribution() {
        // Create logger with skewed distribution: 90 samples at 0%, 10 samples at 100%
        SimulationLogger logger = new SimulationLogger("test.log", 100, 100);
        
        // 90 samples at 0% dirty
        for (int i = 0; i < 90; i++) {
            logger.sampleDirtySetSize(0);
        }
        // 10 samples at 100% dirty
        for (int i = 0; i < 10; i++) {
            logger.sampleDirtySetSize(100);
        }
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNotNull(stats, "Stats should not be null");
        assertEquals(100, stats.sampleCount, "Sample count should be 100");
        
        // Mean: (0*90 + 100*10)/100 = 1000/100 = 10%
        assertEquals(10.0, stats.meanPct, 0.1, "Mean should be 10%");
        
        // Median: sorted = [0,0,...,0 (90x), 100,100,...,100 (10x)]
        // p50: idx = ceil(50)-1 = 49 → sortedSamples[49] = 0
        assertEquals(0.0, stats.p50Pct, 0.1, "p50 should be 0%");
        
        // p95: idx = ceil(95)-1 = 94 → sortedSamples[94] = 100 (within last 10)
        assertEquals(100.0, stats.p95Pct, 0.1, "p95 should be 100%");
        
        // p99: idx = ceil(99)-1 = 98 → sortedSamples[98] = 100
        assertEquals(100.0, stats.p99Pct, 0.1, "p99 should be 100%");
        
        // Max
        assertEquals(100.0, stats.maxPct, 0.1, "Max should be 100%");
        
        // Verify quantile ordering
        assertTrue(stats.p50Pct <= stats.p95Pct, "p50 should be <= p95");
        assertTrue(stats.p95Pct <= stats.p99Pct, "p95 should be <= p99");
        assertTrue(stats.p99Pct <= stats.maxPct, "p99 should be <= max");
    }

    @Test
    void testQuantileCalculation_EvenDistribution() {
        // Create logger with even distribution: 0 to 99 (percentages 0% to 99%)
        SimulationLogger logger = new SimulationLogger("test.log", 100, 100);
        
        // 100 samples from 0 to 99 (percentages 0% to 99%)
        for (int i = 0; i < 100; i++) {
            logger.sampleDirtySetSize(i);
        }
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNotNull(stats, "Stats should not be null");
        assertEquals(100, stats.sampleCount, "Sample count should be 100");
        
        // Mean of 0..99 = 49.5
        assertEquals(49.5, stats.meanPct, 0.1, "Mean should be 49.5%");
        
        // p50: idx = ceil(50)-1 = 49 → sortedSamples[49] = 49%
        assertEquals(49.0, stats.p50Pct, 0.1, "p50 should be 49%");
        
        // p95: idx = ceil(95)-1 = 94 → sortedSamples[94] = 94%
        assertEquals(94.0, stats.p95Pct, 0.1, "p95 should be 94%");
        
        // p99: idx = ceil(99)-1 = 98 → sortedSamples[98] = 98%
        assertEquals(98.0, stats.p99Pct, 0.1, "p99 should be 98%");
        
        // Max
        assertEquals(99.0, stats.maxPct, 0.1, "Max should be 99%");
        
        // Verify quantile ordering
        assertTrue(stats.p50Pct <= stats.p95Pct, "p50 should be <= p95");
        assertTrue(stats.p95Pct <= stats.p99Pct, "p95 should be <= p99");
        assertTrue(stats.p99Pct <= stats.maxPct, "p99 should be <= max");
    }

    @Test
    void testNoSamples_ReturnsNull() {
        // Create logger but don't sample anything
        SimulationLogger logger = new SimulationLogger("test.log", 100, 10);
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNull(stats, "Stats should be null when no samples collected");
        assertEquals(0, logger.getDirtySetSampleCount(), "Sample count should be 0");
    }

    @Test
    void testSingleSample() {
        SimulationLogger logger = new SimulationLogger("test.log", 100, 1);
        
        logger.sampleDirtySetSize(50);  // 50% dirty
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNotNull(stats, "Stats should not be null");
        assertEquals(1, stats.sampleCount, "Sample count should be 1");
        assertEquals(50.0, stats.meanPct, 0.01, "Mean should be 50%");
        assertEquals(50.0, stats.p50Pct, 0.01, "p50 should be 50%");
        assertEquals(50.0, stats.p95Pct, 0.01, "p95 should be 50%");
        assertEquals(50.0, stats.p99Pct, 0.01, "p99 should be 50%");
        assertEquals(50.0, stats.maxPct, 0.01, "Max should be 50%");
    }

    @Test
    void testAllZeros() {
        SimulationLogger logger = new SimulationLogger("test.log", 100, 10);
        
        for (int i = 0; i < 10; i++) {
            logger.sampleDirtySetSize(0);
        }
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNotNull(stats, "Stats should not be null");
        assertEquals(10, stats.sampleCount, "Sample count should be 10");
        assertEquals(0.0, stats.meanPct, 0.01, "Mean should be 0%");
        assertEquals(0.0, stats.p50Pct, 0.01, "p50 should be 0%");
        assertEquals(0.0, stats.p95Pct, 0.01, "p95 should be 0%");
        assertEquals(0.0, stats.p99Pct, 0.01, "p99 should be 0%");
        assertEquals(0.0, stats.maxPct, 0.01, "Max should be 0%");
    }

    @Test
    void testAllMax() {
        SimulationLogger logger = new SimulationLogger("test.log", 100, 10);
        
        for (int i = 0; i < 10; i++) {
            logger.sampleDirtySetSize(100);  // 100% dirty
        }
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNotNull(stats, "Stats should not be null");
        assertEquals(10, stats.sampleCount, "Sample count should be 10");
        assertEquals(100.0, stats.meanPct, 0.01, "Mean should be 100%");
        assertEquals(100.0, stats.p50Pct, 0.01, "p50 should be 100%");
        assertEquals(100.0, stats.p95Pct, 0.01, "p95 should be 100%");
        assertEquals(100.0, stats.p99Pct, 0.01, "p99 should be 100%");
        assertEquals(100.0, stats.maxPct, 0.01, "Max should be 100%");
    }

    @Test
    void testQuantileOrderingInvariant() {
        // Test with random-ish data to verify ordering invariant holds
        SimulationLogger logger = new SimulationLogger("test.log", 100, 20);
        
        // Add some varied samples: 10, 20, 5, 80, 30, 15, 90, 25, 40, 60, 70, 50, 35, 45, 55, 65, 75, 85, 95, 100
        int[] samples = {10, 20, 5, 80, 30, 15, 90, 25, 40, 60, 70, 50, 35, 45, 55, 65, 75, 85, 95, 100};
        for (int sample : samples) {
            logger.sampleDirtySetSize(sample);
        }
        
        SimulationLogger.DirtySetStats stats = logger.calculateDirtySetStats();
        
        assertNotNull(stats, "Stats should not be null");
        
        // Verify strict ordering
        assertTrue(stats.meanPct >= 0 && stats.meanPct <= 100, "Mean should be in [0, 100]");
        assertTrue(stats.p50Pct <= stats.p95Pct, "p50 <= p95");
        assertTrue(stats.p95Pct <= stats.p99Pct, "p95 <= p99");
        assertTrue(stats.p99Pct <= stats.maxPct, "p99 <= max");
        assertTrue(stats.maxPct <= 100.0, "Max should not exceed 100%");
        
        // Mean should be <= max (by definition)
        assertTrue(stats.meanPct <= stats.maxPct, "Mean should be <= max");
    }

    @Test
    void testDirtySetSampleCount() {
        SimulationLogger logger = new SimulationLogger("test.log", 100, 10);
        
        assertEquals(0, logger.getDirtySetSampleCount(), "Initial count should be 0");
        
        logger.sampleDirtySetSize(10);
        assertEquals(1, logger.getDirtySetSampleCount(), "Count should be 1 after one sample");
        
        logger.sampleDirtySetSize(20);
        assertEquals(2, logger.getDirtySetSampleCount(), "Count should be 2 after two samples");
        
        for (int i = 0; i < 8; i++) {
            logger.sampleDirtySetSize(i * 5);
        }
        assertEquals(10, logger.getDirtySetSampleCount(), "Count should be 10 after ten samples");
    }
}
