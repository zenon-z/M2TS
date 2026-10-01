package de.tum.tssimulator.execution;

/**
 * Which code path caused a transaction's *next* call into
 * {@link ACIDAdmissionController#tryAdmitWithLockCheck}. Distinguishes two structurally
 * different re-attempt channels that both re-enqueue to {@code QueueType.PENDING} but behave
 * very differently under contention:
 *
 * <ul>
 *   <li>{@link #FRESH} - the transaction's very first pass through admission (dequeued via the
 *       scheduler's normal intake, never yet touched by either setter below). Always coincides
 *       with {@code Transaction.getAdmissionAttemptIndex() == 0}.</li>
 *   <li>{@link #WOKEN} - re-enqueued by {@link AdmissionWaitManager#wakeWaiters}, bounded to at
 *       most {@code MAX_WAKEUPS_PER_RELEASE} waiters per lock release.</li>
 *   <li>{@link #RACE_RETRY} - re-enqueued by {@link ACIDAdmissionController}'s
 *       {@code [ADMISSION-RACE]} branch: {@code checkLocksAvailable} found every requested lock
 *       free, but {@code tryAcquireAllLocks} then lost a race before the acquisition completed.
 *       This path has NO {@code MAX_WAKEUPS_PER_RELEASE}-style cap - at high contention it can
 *       dominate attempt volume in a way the wakeup path does not, which is exactly why a
 *       closed-form wakeup-batch-based plateau prediction can't be assumed without first
 *       measuring the FRESH:WOKEN:RACE_RETRY split (see the TGS validation plan's Stage 0).</li>
 *   <li>{@link #WAIT_TIMEOUT} - re-enqueued by {@link AdmissionWaitManager#sweepExpiredWaiters},
 *       the liveness fix for the gap {@code wakeWaiters} alone left open: a waiter registered on a
 *       lock has no bounded time to its next attempt if that specific lock never releases (a
 *       parent stuck BLOCKED holding a broad footprint mid-cascade indefinitely, e.g.). This is
 *       the ONLY channel that fires independently of any release event.</li>
 * </ul>
 */
public enum AdmissionAttemptProvenance {
    FRESH,
    WOKEN,
    RACE_RETRY,
    WAIT_TIMEOUT
}
