package de.tum.tssimulator.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LifecycleAccountingReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleAccountingReporter.class);

    public void report(SimulationMetrics metrics) {
        LOGGER.warn("=== LIFECYCLE ACCOUNTING ASSERTIONS ===");
        LOGGER.warn("Groups: started={}, completed={}, aborted={}",
                metrics.getGroupsStartedTotal(), metrics.getGroupsCompletedTotal(), metrics.getGroupsAbortedTotal());
        LOGGER.warn("Bookkeepers: created={}, completed={}, aborted={}, terminated={}",
                metrics.getBookkeepersCreatedTotal(), metrics.getBookkeepersCompletedTotal(), metrics.getBookkeepersAbortedTotal(),
                metrics.getBookkeepersTerminatedTotal());
        LOGGER.warn("Abort reasons: cautious_wait={}, deadlock={}, other={}",
                metrics.getCautiousWaitAborts(), metrics.getDeadlockAborts(), metrics.getOtherAborts());

        int unaccountedBookkeepers = metrics.getBookkeepersCreatedTotal() - metrics.getBookkeepersTerminatedTotal();
        int incompleteGroups = metrics.getGroupsStartedTotal()
                - metrics.getGroupsCompletedTotal()
                - metrics.getGroupsAbortedTotal();

        if (unaccountedBookkeepers > 0) {
            LOGGER.warn("{} bookkeepers created but not terminated", unaccountedBookkeepers);
            LOGGER.warn("Incomplete groups at simulation end: {}", incompleteGroups);

            if (incompleteGroups > 0) {
                LOGGER.warn("Expected: Bookkeepers in incomplete groups are not formally terminated.");
                LOGGER.warn("These bookkeepers may be in PENDING, RUNNING, COMPLETED, or WAITING_FOR_GROUP.");
                LOGGER.warn("This is normal if simulation ended with work in progress.");
            } else {
                LOGGER.error("Unexpected: all groups accounted for but bookkeepers missing");
                LOGGER.error("Possible causes: bookkeepers created but never added to groups, double-counting in group abort/completion, or bookkeepers dropped from processors without termination tracking");
            }
        } else if (unaccountedBookkeepers < 0) {
            LOGGER.error("Terminated bookkeepers ({}) > Created ({})",
                    metrics.getBookkeepersTerminatedTotal(), metrics.getBookkeepersCreatedTotal());
            LOGGER.error("This is an accounting bug - double-counting terminations.");
        } else {
            LOGGER.info("All bookkeepers accounted for (created == terminated)");
            if (incompleteGroups > 0) {
                LOGGER.info("Note: {} incomplete groups at simulation end (normal)", incompleteGroups);
            }
        }
        LOGGER.warn("{}", "=".repeat(50));
    }
}
