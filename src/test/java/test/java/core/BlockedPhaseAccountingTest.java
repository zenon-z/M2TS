package test.java.core;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction.LineagePhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionAggregateMetrics;

/**
 * BLOCKED must RESTORE the phase it interrupted, and must not survive a restart.
 *
 * <p>Every assertion here pins a failure mode that the run-level gates CANNOT see. The
 * equilibrium and Little's-law gates check that the phase intervals PARTITION the lifetime, and
 * a misfiled interval still partitions it - the totals close to the tick either way. Both defects
 * below were therefore invisible to every check the campaign runs, and the first one was caught
 * only by diffing one seed's segments across two binaries.
 *
 * <ol>
 *   <li><b>Restoring RUNNING unconditionally.</b> A parent blocks from WAITING_ROOM as readily as
 *       from RUNNING - it holds its locks while its cascade runs, which is exactly when a
 *       sibling's denial force-blocks it. Reopening RUNNING moved 45,236 ticks of waiting-room
 *       time into the RUNNING residual on one 24-core seed, with both gates still at 0.999993.
 *   <li><b>Carrying phaseBeforeBlock across a restart.</b> It is not a lineage total, it is a
 *       pointer into the previous attempt's open interval. A transaction that aborts while
 *       blocked would hand its successor a stale phase to resume.
 * </ol>
 */
public class BlockedPhaseAccountingTest {

    @BeforeEach
    public void resetMetrics() {
        Transaction.resetMetrics();
    }

    private static Transaction txn(String id) {
        Transaction t = new Transaction(id, List.of(), null);
        t.initializeState();
        return t;
    }

    /** Population count for one phase - the quantity every figure integrates. */
    private static int pop(LineagePhase p) {
        return Transaction.phasePopulation()[p.ordinal()];
    }

    @Test
    public void blockingFromRunningResumesRunning() {
        Transaction t = txn("A");
        t.markPhase(LineagePhase.RUNNING, 1_000L);
        t.markBlocked(2_000L);

        // The BUCKET matters, not just that something is blocked: blocked-from-running is lock
        // contention on the transaction's own work, and is the only bucket that may be read as
        // such.
        Assertions.assertEquals(1, pop(LineagePhase.BLOCKED_RUNNING), "blocked out of RUNNING");
        Assertions.assertEquals(0, pop(LineagePhase.BLOCKED_WAITING_ROOM));

        t.markUnblocked(5_000L);
        Assertions.assertEquals(1, pop(LineagePhase.RUNNING), "resumed RUNNING");
        Assertions.assertEquals(0, pop(LineagePhase.BLOCKED_RUNNING));
    }

    @Test
    public void blockingFromWaitingRoomResumesWaitingRoom() {
        Transaction t = txn("B");
        t.markPhase(LineagePhase.WAITING_ROOM, 1_000L);
        t.markBlocked(2_000L);

        Assertions.assertEquals(1, pop(LineagePhase.BLOCKED_WAITING_ROOM),
                "blocked out of WAITING_ROOM, not out of RUNNING");
        Assertions.assertEquals(0, pop(LineagePhase.BLOCKED_RUNNING));

        t.markUnblocked(5_000L);
        // THE REGRESSION. Hardcoding RUNNING here is what silently reattributed cascade delay as
        // processor-wait residual, and no gate moved.
        Assertions.assertEquals(1, pop(LineagePhase.WAITING_ROOM),
                "must resume WAITING_ROOM - resuming RUNNING books cascade delay as running time");
        Assertions.assertEquals(0, pop(LineagePhase.RUNNING));
    }

    @Test
    public void blockingFromAdmissionWaitIsItsOwnBucket() {
        Transaction t = txn("C");
        t.markPhase(LineagePhase.ADMISSION_WAIT, 1_000L);
        t.markBlocked(2_000L);

        // Folded into BLOCKED_RUNNING this would be indistinguishable from a parent blocked
        // mid-execution. Kept separate so "it is negligible" stays a reading rather than an
        // assumption - the same zero-vs-unwired discipline the dispatch-queue segment gets.
        Assertions.assertEquals(1, pop(LineagePhase.BLOCKED_OTHER));
        Assertions.assertEquals(0, pop(LineagePhase.BLOCKED_RUNNING));

        t.markUnblocked(5_000L);
        Assertions.assertEquals(1, pop(LineagePhase.ADMISSION_WAIT), "resumed ADMISSION_WAIT");
    }

    @Test
    public void reBlockingKeepsTheOriginalInterruptedPhase() {
        Transaction t = txn("D");
        t.markPhase(LineagePhase.WAITING_ROOM, 1_000L);
        t.markBlocked(2_000L);
        // Blocked on a second lock before the first is released: a real path, since
        // blockGroupMember sweeps the group on every sibling denial.
        t.markBlocked(3_000L);

        Assertions.assertEquals(1, pop(LineagePhase.BLOCKED_WAITING_ROOM),
                "still one blocked member, still in its original bucket");
        t.markUnblocked(6_000L);
        Assertions.assertEquals(1, pop(LineagePhase.WAITING_ROOM),
                "a second block must not make BLOCKED its own predecessor");
    }

    @Test
    public void unblockingSomethingThatNeverBlockedDoesNothing() {
        Transaction t = txn("E");
        t.markPhase(LineagePhase.RUNNING, 1_000L);

        // The unblock site drains BLOCKED_TXN, and not every route into that queue marks a phase
        // (the bookkeeper stuck-admission path, and the Wait-Die / Wound-Wait policies this
        // campaign does not use). An unguarded restore would reopen a phase for a transaction
        // that never blocked.
        t.markUnblocked(4_000L);

        Assertions.assertEquals(1, pop(LineagePhase.RUNNING), "RUNNING left untouched");
        Assertions.assertEquals(0, pop(LineagePhase.BLOCKED_RUNNING));
        Assertions.assertEquals(0, pop(LineagePhase.BLOCKED_WAITING_ROOM));
        Assertions.assertEquals(0, pop(LineagePhase.BLOCKED_OTHER));
    }

    @Test
    public void abortingWhileBlockedDoesNotHandAStalePhaseToTheRestart() {
        Transaction original = txn("F");
        original.markPhase(LineagePhase.WAITING_ROOM, 1_000L);
        original.markBlocked(2_000L);

        // The restart path: the old object's open interval is closed into its own totals and the
        // lineage totals are copied onto the successor, which then opens BACKOFF.
        Transaction restarted = txn("F'");
        restarted.copyLineageTimeAccountingFrom(original, 3_000L);
        restarted.markPhase(LineagePhase.BACKOFF, 3_000L);

        Assertions.assertNull(restarted.phaseBeforeBlock(),
                "phaseBeforeBlock points into the PREVIOUS attempt's open interval - copying it "
                + "lets a successor that never blocked resume a phase from a dead attempt");

        // And the guard holds even if something drains this successor from BLOCKED_TXN: it is not
        // in a blocked phase, so there is nothing to restore and BACKOFF must survive.
        restarted.markUnblocked(4_000L);
        Assertions.assertEquals(1, pop(LineagePhase.BACKOFF), "BACKOFF left untouched");
        Assertions.assertEquals(0, pop(LineagePhase.WAITING_ROOM),
                "the dead attempt's WAITING_ROOM must not be reopened on the successor");
    }

    @Test
    public void blockedTimeIsChargedToTheBlockedBucketNotToWhatItInterrupted() {
        Transaction t = txn("G");
        t.markPhase(LineagePhase.WAITING_ROOM, 1_000L);
        t.markBlocked(2_000L);
        t.markUnblocked(9_000L);
        t.finalizeLineageTimeAccounting(10_000L);

        // Population is back to empty once the lineage is finalised - the partition's own
        // precondition, and the thing a leak breaks. assertPhasePopulationClear throws with the
        // per-phase counts, which localises a leak better than an equality on a total.
        TransactionAggregateMetrics.assertPhasePopulationClear();
    }
}
