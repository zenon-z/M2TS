package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.execution.AdmissionAttemptProvenance;

/**
 * TGS static-locking validation: Transaction's admission-attempt bookkeeping
 * (admissionAttemptIndex, admissionAttemptProvenance) - the raw material
 * ACIDAdmissionController.tryAdmitWithLockCheck() reads/mutates on every attempt. See
 * AdmissionAttemptProvenance's javadoc for what each provenance value means.
 */
class TransactionAdmissionAttemptTest {

    private static Transaction newTxn() {
        Transaction txn = new Transaction("t1", List.of(), new TransactionGroup());
        txn.initializeState();
        return txn;
    }

    @Test
    void defaultsToZeroIndexAndFreshProvenance() {
        Transaction txn = newTxn();
        assertEquals(0, txn.getAdmissionAttemptIndex());
        assertEquals(AdmissionAttemptProvenance.FRESH, txn.getAdmissionAttemptProvenance());
    }

    @Test
    void incrementAdmissionAttemptIndexCountsUpFromZero() {
        Transaction txn = newTxn();
        assertEquals(0, txn.getAdmissionAttemptIndex());
        txn.incrementAdmissionAttemptIndex();
        assertEquals(1, txn.getAdmissionAttemptIndex());
        txn.incrementAdmissionAttemptIndex();
        txn.incrementAdmissionAttemptIndex();
        assertEquals(3, txn.getAdmissionAttemptIndex());
    }

    @Test
    void provenanceRoundTripsThroughSetterAndGetter() {
        Transaction txn = newTxn();
        txn.setAdmissionAttemptProvenance(AdmissionAttemptProvenance.WOKEN);
        assertEquals(AdmissionAttemptProvenance.WOKEN, txn.getAdmissionAttemptProvenance());

        txn.setAdmissionAttemptProvenance(AdmissionAttemptProvenance.RACE_RETRY);
        assertEquals(AdmissionAttemptProvenance.RACE_RETRY, txn.getAdmissionAttemptProvenance());
    }
}
