package de.tum.tssimulator.execution;

/**
 * TGS static-locking validation: the three-valued result of one call into
 * {@link ACIDAdmissionController#tryAdmitWithLockCheck}. Deliberately not a boolean - a
 * transaction whose lock check found everything free but that then lost the check-then-acquire
 * race (see {@code [ADMISSION-RACE]} in {@code tryAdmitWithLockCheck}) was neither blocked nor
 * admitted, and coding that outcome as "blocked" would inflate measured P(blocked) by the race
 * rate, a contention-correlated bias that would look like the lock manager failing to match the
 * Tay/Goodman/Suri prediction when the actual cause is unrelated.
 *
 * <p>P1's primary metric (see the TGS analysis plan) is defined as
 * {@code count(BLOCKED_AT_CHECK) / count(ADMITTED + BLOCKED_AT_CHECK + LOST_RACE)} - the check
 * ran in all three cases, but only BLOCKED_AT_CHECK is what the hypergeometric formula predicts.
 */
public enum AdmissionOutcome {
    /** checkLocksAvailable found everything free AND tryAcquireAllLocks succeeded. */
    ADMITTED,
    /** checkLocksAvailable found at least one requested lock held by another transaction/group. */
    BLOCKED_AT_CHECK,
    /** checkLocksAvailable found everything free, but tryAcquireAllLocks lost the race. */
    LOST_RACE
}
