package de.tum.tssimulator.monitoring;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TGS static-locking validation: SimulationMetrics.recordAdmissionAttempt()'s two outputs -
 * first-attempt-only parallel arrays (what P1/P2 are computed on) and the all-attempts
 * (heldLockUnits, provenance, outcome) histogram (what the first-vs-all diagnostic and the
 * FRESH:WOKEN:RACE_RETRY split are computed from). See SimulationMetrics' field javadoc for why
 * these are separate outputs at very different granularity.
 *
 * <p>admissionAttemptTraceEnabled is read via Boolean.getBoolean() at field-initialization time
 * (construction), not per-call - so each test that needs the trace ON must set the system
 * property BEFORE constructing its own SimulationMetrics instance, and clear it in @AfterEach so
 * it doesn't leak into other test classes sharing this JVM.
 */
class SimulationMetricsAdmissionAttemptTest {
    private static final String PROPERTY = "ts.metrics.admissionAttemptTrace";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void disabledByDefaultIsANoOpAndReturnsEmptyArrays() {
        System.clearProperty(PROPERTY);
        SimulationMetrics metrics = new SimulationMetrics(100, 10);

        assertFalse(metrics.isAdmissionAttemptTraceEnabled());
        metrics.recordAdmissionAttempt(100L, 2, 1, 2, 0, "FRESH", "ADMITTED");

        assertEquals(0, metrics.getAdmissionAttemptVirtualTime().length);
        assertEquals(0, metrics.getAdmissionAttemptK().length);
        assertEquals(0, metrics.getAdmissionAttemptNRunning().length);
        assertEquals(0, metrics.getAdmissionAttemptHeldLockUnits().length);
        assertEquals(0, metrics.getAdmissionAttemptOutcome().length);
        assertEquals(0, metrics.getAdmissionAttemptHistogramCount().length);
    }

    @Test
    void onlyFirstAttemptsBecomeRows() {
        System.setProperty(PROPERTY, "true");
        SimulationMetrics metrics = new SimulationMetrics(100, 10);
        assertTrue(metrics.isAdmissionAttemptTraceEnabled());

        // Transaction A: single attempt, admitted immediately.
        metrics.recordAdmissionAttempt(100L, 2, 1, 2, /*attemptIndex*/ 0, "FRESH", "ADMITTED");
        // Transaction B: blocked on its first attempt, woken and blocked again, then admitted on
        // its third attempt. Only the attemptIndex==0 row should become a first-attempt row.
        metrics.recordAdmissionAttempt(150L, 2, 3, 6, /*attemptIndex*/ 0, "FRESH", "BLOCKED_AT_CHECK");
        metrics.recordAdmissionAttempt(200L, 2, 3, 6, /*attemptIndex*/ 1, "WOKEN", "BLOCKED_AT_CHECK");
        metrics.recordAdmissionAttempt(250L, 2, 2, 4, /*attemptIndex*/ 2, "WOKEN", "ADMITTED");

        assertArrayEquals(new long[] {100L, 150L}, metrics.getAdmissionAttemptVirtualTime());
        assertArrayEquals(new int[] {2, 2}, metrics.getAdmissionAttemptK());
        assertArrayEquals(new int[] {1, 3}, metrics.getAdmissionAttemptNRunning());
        assertArrayEquals(new int[] {2, 6}, metrics.getAdmissionAttemptHeldLockUnits());
        assertArrayEquals(new String[] {"ADMITTED", "BLOCKED_AT_CHECK"}, metrics.getAdmissionAttemptOutcome());
    }

    @Test
    void histogramCountsEveryAttemptRegardlessOfIndexAndMergesDuplicateKeys() {
        System.setProperty(PROPERTY, "true");
        SimulationMetrics metrics = new SimulationMetrics(100, 10);

        metrics.recordAdmissionAttempt(100L, 2, 1, 2, 0, "FRESH", "ADMITTED");
        metrics.recordAdmissionAttempt(150L, 2, 3, 6, 0, "FRESH", "BLOCKED_AT_CHECK");
        metrics.recordAdmissionAttempt(200L, 2, 3, 6, 1, "WOKEN", "BLOCKED_AT_CHECK");
        metrics.recordAdmissionAttempt(250L, 2, 2, 4, 2, "WOKEN", "ADMITTED");
        metrics.recordAdmissionAttempt(120L, 1, 1, 1, 1, "RACE_RETRY", "LOST_RACE");
        // A second attempt with the SAME (H, provenance, outcome) key as the third call above -
        // exercises the merge path, not a fresh first-attempt row (attemptIndex != 0).
        metrics.recordAdmissionAttempt(999L, 2, 3, 6, 5, "WOKEN", "BLOCKED_AT_CHECK");

        // Two of these six calls have H=6/WOKEN/BLOCKED_AT_CHECK, so 5 distinct keys total.
        assertEquals(5, metrics.getAdmissionAttemptHistogramCount().length);

        int total = 0;
        for (int count : metrics.getAdmissionAttemptHistogramCount()) {
            total += count;
        }
        assertEquals(6, total, "histogram should count all 6 attempts, not just the first-attempt ones");

        // Find the merged key's count.
        int[] hs = metrics.getAdmissionAttemptHistogramHeldLockUnits();
        String[] provenances = metrics.getAdmissionAttemptHistogramProvenance();
        String[] outcomes = metrics.getAdmissionAttemptHistogramOutcome();
        int[] counts = metrics.getAdmissionAttemptHistogramCount();
        boolean foundMergedKey = false;
        for (int i = 0; i < hs.length; i++) {
            if (hs[i] == 6 && provenances[i].equals("WOKEN") && outcomes[i].equals("BLOCKED_AT_CHECK")) {
                assertEquals(2, counts[i], "the two H=6/WOKEN/BLOCKED_AT_CHECK attempts should merge into one count of 2");
                foundMergedKey = true;
            }
        }
        assertTrue(foundMergedKey, "expected to find the merged (H=6, WOKEN, BLOCKED_AT_CHECK) histogram key");
    }

    @Test
    void threeValuedOutcomeIsPreservedDistinctly() {
        // Guards the fix this test exists for: LOST_RACE must never collapse into
        // BLOCKED_AT_CHECK (that would inflate measured P(blocked) by the race rate - see
        // AdmissionOutcome's javadoc) or into ADMITTED (the race loser wasn't admitted either).
        System.setProperty(PROPERTY, "true");
        SimulationMetrics metrics = new SimulationMetrics(100, 10);

        metrics.recordAdmissionAttempt(100L, 1, 1, 0, 0, "FRESH", "ADMITTED");
        metrics.recordAdmissionAttempt(101L, 1, 1, 1, 0, "FRESH", "BLOCKED_AT_CHECK");
        metrics.recordAdmissionAttempt(102L, 1, 1, 0, 0, "FRESH", "LOST_RACE");

        assertArrayEquals(new String[] {"ADMITTED", "BLOCKED_AT_CHECK", "LOST_RACE"},
                metrics.getAdmissionAttemptOutcome());
    }
}
