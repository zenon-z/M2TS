package de.tum.tssimulator.monitoring;

import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.impl.BudgetedLockManagerDecorator;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WaitForGraphUtils {

    /**
     * Generates a Wait-For Graph (WFG) in GraphViz DOT format and saves it to a file.
     *
     * @param lockManager The lock manager to extract dependencies from.
     * @param filename    The target filename (e.g., "wait_for_graph.dot").
     */
    public static void generateDotFile(SS2PLLockManager lockManager, String filename) {
        String dotContent = generateDot(lockManager);
        try (PrintWriter out = new PrintWriter(new FileWriter(filename))) {
            out.println(dotContent);
            System.out.println("Wait-For Graph generated at: " + filename);
        } catch (IOException e) {
            System.err.println("Failed to write Wait-For Graph: " + e.getMessage());
            return;
        }

        // Attempt to render as PNG using Graphviz
        try {
            String pngFilename = filename.replace(".dot", ".png");
            
            // Try standard 'dot' first, then common paths if not in Environment PATH
            String dotCommand = "dot";
            if (new File("/opt/homebrew/bin/dot").exists()) {
                dotCommand = "/opt/homebrew/bin/dot";
            } else if (new File("/usr/local/bin/dot").exists()) {
                dotCommand = "/usr/local/bin/dot";
            } else if (new File("/usr/bin/dot").exists()) {
                dotCommand = "/usr/bin/dot";
            }
            
            ProcessBuilder pb = new ProcessBuilder(dotCommand, "-Gdpi=300", "-Tpng", filename, "-o", pngFilename);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            
            if (exitCode == 0) {
                System.out.println("Rendered Wait-For Graph image at: " + pngFilename);
            } else {
                System.err.println("Graphviz 'dot' command failed with exit code: " + exitCode);
            }
        } catch (Exception e) {
            System.err.println("Failed to render Wait-For Graph PNG. Please ensure Graphviz is installed.");
        }
    }

    /**
     * Generates a Wait-For Graph (WFG) in GraphViz DOT format based on the current
     * state of the LockManager.
     *
     * @param lockManager The LockManager instance (must be SS2PLLockManager or compatible).
     * @return A string containing the DOT representation of the graph.
     */
    public static String generateDot(SS2PLLockManager lockManager) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph WaitForGraph {\n");
        sb.append("    rankdir=LR;\n"); // Left-to-right layout
        sb.append("    node [shape=circle, fontname=\"Helvetica\", style=filled, fillcolor=lightblue];\n");
        sb.append("    edge [fontname=\"Helvetica\", fontsize=10];\n\n");

        if (lockManager == null) {
            sb.append("    // Lock Manager is null\n");
            sb.append("}\n");
            return sb.toString();
        }

        Map<String, Set<Transaction>> lockWaiters = lockManager.getLockWaiters();

        // If there are no waiters, the graph won't have any wait edges.
        if (lockWaiters == null || lockWaiters.isEmpty()) {
            sb.append("    // No waiters currently.\n");
        } else {
            Set<String> declaredTransactions = new HashSet<>();
            Set<String> drawnEdges = new HashSet<>();
            StringBuilder edgesSb = new StringBuilder();

            // Edges logic: Waiter -> Holder (Process to Process directly)
            for (Map.Entry<String, Set<Transaction>> entry : lockWaiters.entrySet()) {
                String lockId = entry.getKey();
                Set<Transaction> waiters = entry.getValue();

                if (waiters != null && !waiters.isEmpty()) {
                    Set<Transaction> holders = lockManager.getLockHolders(lockId);
                    
                    if (holders != null && !holders.isEmpty()) {
                        for (Transaction waiter : waiters) {
                            String wId = waiter.getGroup() != null && waiter.getGroup().getParent() != null 
                                    ? waiter.getGroup().getParent().getId() 
                                    : waiter.getId();

                            for (Transaction holder : holders) {
                                String hId = holder.getGroup() != null && holder.getGroup().getParent() != null 
                                        ? holder.getGroup().getParent().getId() 
                                        : holder.getId();

                                // Skip self loops (e.g., bookkeepers from the same group waiting on each other)
                                if (wId.equals(hId)) continue;
                                
                                // Declare waiter node if we haven't seen it yet
                                if (declaredTransactions.add(wId)) {
                                    // Colour by the derived property, not by an ID substring. The
                                    // "-RO" suffix is gone; matching on it here would silently
                                    // colour every node the same rather than fail.
                                    String color = nodeColor(waiter);
                                    sb.append(String.format("    \"%s\" [shape=circle, style=filled, fillcolor=%s];\n", wId, color));
                                }

                                // Declare holder node if we haven't seen it yet
                                if (declaredTransactions.add(hId)) {
                                    String color = nodeColor(holder);
                                    sb.append(String.format("    \"%s\" [shape=circle, style=filled, fillcolor=%s];\n", hId, color));
                                }

                                // Directed edge: Waiter -> Holder directly, annotated with the resource name
                                String edgeStr = String.format("    \"%s\" -> \"%s\" [label=\"%s\"];\n", wId, hId, lockId);
                                if (drawnEdges.add(edgeStr)) {
                                    edgesSb.append(edgeStr);
                                }
                            }
                        }
                    }
                }
            }

            sb.append("\n");
            sb.append(edgesSb.toString());
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Helper to extract the SS2PLLockManager from SimulatorContext via reflection,
     * since it might be encapsulated or hidden in a bundle.
     */
    public static SS2PLLockManager extractLockManager(SimulatorContext ctx) {
        try {
            LockManager lockManager = ctx.locking().lockManager();
            // In SimulatorContext, check if lockManager is reachable
            if (lockManager instanceof BudgetedLockManagerDecorator) {
                LockManager internal = ((BudgetedLockManagerDecorator) lockManager).getDelegate();
                if (internal instanceof SS2PLLockManager) {
                    return (SS2PLLockManager) internal;
                }
            }
            if (lockManager instanceof SS2PLLockManager) {
                return (SS2PLLockManager) lockManager;
            }
        } catch (Exception e) {
            System.err.println("Could not extract SS2PLLockManager: " + e.getMessage());
        }
        return null;
    }

    /**
     * Read-only nodes light blue, writers light pink - keyed on the GROUP PARENT, matching the node
     * identity the graph is built from (a CU is drawn as its parent).
     */
    private static String nodeColor(Transaction txn) {
        Transaction subject = txn.getGroup() != null && txn.getGroup().getParent() != null
                ? txn.getGroup().getParent()
                : txn;
        return subject.isReadOnly() ? "lightblue" : "lightpink";
    }
}
