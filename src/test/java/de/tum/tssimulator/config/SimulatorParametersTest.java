package de.tum.tssimulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * admissionBatchSize (k) validation and the default-preserves-old-behavior guarantee.
 */
class SimulatorParametersTest {

    @Test
    void defaultAdmissionBatchSizeIsOnePreservingSingleWakeBehavior() {
        SimulatorParameters params = SimulatorParameters.builder().build();
        assertEquals(1, params.admissionBatchSize);
    }

    @Test
    void defaultParametersAlsoDefaultsAdmissionBatchSizeToOne() {
        assertEquals(1, SimulatorParameters.defaultParameters().admissionBatchSize);
    }

    @Test
    void builderAcceptsAnExplicitAdmissionBatchSize() {
        SimulatorParameters params = SimulatorParameters.builder().admissionBatchSize(4).build();
        assertEquals(4, params.admissionBatchSize);
    }

    @Test
    void admissionBatchSizeOfOneIsValid() {
        SimulatorParameters params = SimulatorParameters.builder().admissionBatchSize(1).build();
        assertEquals(1, params.admissionBatchSize);
    }

    @Test
    void admissionBatchSizeZeroThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SimulatorParameters.builder().admissionBatchSize(0).build());
        assertTrue(ex.getMessage().contains("admissionBatchSize"));
    }

    @Test
    void negativeAdmissionBatchSizeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulatorParameters.builder().admissionBatchSize(-1).build());
    }
}
