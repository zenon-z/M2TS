package de.tum.tssimulator.locking.policy;

/**
 * Which lock-acquisition protocol a simulation runs under. Replaces the previous
 * ACIDAdmissionController.ENABLE_C2PL mutable static flag plus the scattered
 * txn.getPhase()==LOCK conditionals inside TransactionLockCoordinator - those made "which
 * protocol" an implicit property of a phase check rather than an explicit choice, and the phase
 * check was applied uniformly regardless of the flag, which is what let the S2PL arm silently
 * run C2PL-with-retry semantics for an entire session's worth of experiments (see
 * dynamic-footprint-variance-investigation memory).
 *
 * Two DIFFERENT TransactionLockCoordinator subclasses (C2PLTransactionLockCoordinator,
 * S2PLTransactionLockCoordinator) implement the protocol-specific decisions this enum selects
 * between at bootstrap (DefaultLockingBundle) - not a conditional inside one shared class.
 *
 * <p><b>SCOPE: this governs how ONE TRANSACTION takes ITS OWN footprint. It does NOT describe how a
 * GROUP acquires.</b> On any cell with a CPR cascade (semanticOverlap &gt; 0, i.e. every "dynamic"
 * and oracle cell) the group's lock set is acquired in WAVES, and the group holds everything
 * acquired so far while it takes more:
 *
 * <ol>
 *   <li>the parent acquires its own footprint - atomically under C2PL, incrementally under S2PL;</li>
 *   <li>the parent executes and completes, and ACIDCompletionHandler:66 creates its bookkeepers
 *       AT THAT POINT - they do not exist earlier and cannot have been preclaimed;</li>
 *   <li>the parent moves to the waiting room and KEEPS ITS LOCKS until the whole group finishes;</li>
 *   <li>each bookkeeper acquires its own footprint and, on completing, spawns further bookkeepers
 *       (ACIDCompletionHandler:105) - recursively, to cascade depth 15-18 on the k=25/k=50 schemas.</li>
 * </ol>
 *
 * <p>So a cascading group is doing claim-as-needed with hold-and-wait AT THE GROUP LEVEL no matter
 * which value this enum holds. {@code C2PL} on such a cell means "each transaction preclaims its own
 * footprint atomically", not "the group preclaims". A reader of the {@code locking_protocol} CSV
 * column will naturally take it to name the cell's discipline; for a cascading cell it does not, and
 * that column should be read together with the workload name.
 *
 * <p>Consequence for the validation ladder: the dynamic arm has ALWAYS been partly incremental, so
 * rung 1 adds operation-level interleaving to something already interleaved at group granularity.
 * The static cell (semanticOverlap = 0, single-member groups) is the only place where this enum's
 * value and the cell's actual discipline coincide - which is exactly why the S2PL &lt;= C2PL
 * cross-arm invariant is scoped to the static cell.
 */
public enum LockingProtocol {
    /**
     * Conservative 2PL: atomic upfront preclaim (ACIDAdmissionController.
     * tryAdmitWithLockCheck()'s tryAcquireAllLocks() path). On any denial - whether the direct
     * requester or a group member forced to block by a sibling's denial - locks already held are
     * released; the transaction retries from scratch once woken. No partial progress ever
     * survives a block.
     */
    C2PL,

    /**
     * Strict/Strong 2PL: claim-as-needed, hold-and-wait. Locks already acquired are NEVER
     * released on a soft deny, for the direct requester or for any other group member forced to
     * block - the transaction (or group member) blocks while continuing to hold everything it
     * already has, and resumes acquiring where it left off once woken.
     */
    S2PL
}
