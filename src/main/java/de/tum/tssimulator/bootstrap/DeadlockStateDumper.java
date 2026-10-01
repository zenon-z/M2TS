package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;

/**
 * Shared implementation of Simulator.dumpDeadlockStateIfEnabled(), extracted so the SAME dump can
 * be triggered from a normal (TIME_LIMIT) run end via SimulationRunner.captureOutcome(), not only
 * from a watchdog trip. The watchdog only fires on total-system stall; a run that ends normally
 * can still have a large censored population (e.g. k=50/mpl=16 showed 95% of depth-0 groups
 * censored at TIME_LIMIT with zero watchdog trips across 10 seeds) - the watchdog-only dump can't
 * see that population at all. Opt-in via -Dts.diag.watchdogStateDump=true, same flag as before.
 */
public final class DeadlockStateDumper {

    private DeadlockStateDumper() {
    }

    public static void dumpIfEnabled(SimulatorContext ctx, String label) {
        if (!Boolean.getBoolean("ts.diag.watchdogStateDump")) {
            return;
        }
        var orphans = OrphanTransactionAudit.audit(ctx);
        if (!orphans.isEmpty()) {
            System.out.println("--- " + OrphanTransactionAudit.describe(orphans) + " ---");
        }
        SS2PLLockManager lm = SS2PLLockManager.unwrap(ctx.locking().lockManager());
        System.out.println("=== STATE DUMP (" + label + ", vt=" + ctx.virtualTime + ") ===");
        System.out.println("--- Live groups ---");
        for (var group : ctx.registry().getActiveGroups()) {
            var parent = group.getParent();
            System.out.printf("group parent=%s state=%s phase=%s resources=%s maxBkDepth=%d%n",
                    parent.getId(), parent.getState(), parent.getPhase(), parent.getResources(),
                    group.getMaxBookkeeperDepth());
            for (var bk : group.getBookkeepers()) {
                System.out.printf("  bookkeeper=%s state=%s phase=%s resources=%s%n",
                        bk.getId(), bk.getState(), bk.getPhase(), bk.getResources());
            }
        }
        if (lm != null) {
            System.out.println("--- Lock table (holders) ---");
            for (String lockId : lm.getLockIdsWithHolders()) {
                var holders = lm.getLockHolders(lockId);
                if (!holders.isEmpty()) {
                    System.out.println("  lock=" + lockId + " heldBy="
                            + holders.stream().map(t -> t.getId()).toList());
                }
            }
            System.out.println("--- Lock waiters by lock (SS2PLLockManager - bookkeeper-side waits) ---");
            for (var entry : lm.getLockWaiters().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.out.println("  lock=" + entry.getKey() + " waitedBy="
                            + entry.getValue().stream().map(t -> t.getId()).toList());
                }
            }
        }
        AdmissionWaitManager awm = ctx.locking().getAdmissionWaitManager();
        if (awm != null) {
            System.out.println("--- Admission-wait registrations (parent blocked at admission check) ---");
            for (var entry : awm.waitersByLockForDiagnostics().entrySet()) {
                System.out.println("  lock=" + entry.getKey() + " admissionWaiters=" + entry.getValue());
            }
        }
        System.out.println("=== END STATE DUMP (" + label + ") ===");
    }
}
