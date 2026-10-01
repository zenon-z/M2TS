package de.tum.tssimulator.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Targeted replay tracing for a known set of victim transaction IDs (the "3-4 wedged
 * transactions" mechanism hunt): every enqueue, dequeue, removal, phase transition, and state
 * transition touching one of the watched IDs, each with a trimmed stack trace naming the
 * responsible call site. Opt-in via -Dts.diag.victimTrace=id1,id2,id3 - a no-op (single Set
 * lookup) for every other transaction, so safe to leave instrumented in hot paths.
 *
 * Method: identify victim IDs from an existing collapsed run's census (DeadlockStateDumper),
 * then re-run the SAME seed with this trace enabled - deterministic sim, same seed reproduces
 * the same victims. The last DEQUEUE/REMOVE for a victim ID with no matching subsequent ENQUEUE
 * is the drop; the stack trace at that point is the responsible call site.
 */
public final class VictimTraceLog {

    private static final Set<String> WATCHED = load();

    private static Set<String> load() {
        String prop = System.getProperty("ts.diag.victimTrace", "");
        if (prop.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(prop.split(",")));
    }

    private VictimTraceLog() {
    }

    public static boolean isWatched(String txnId) {
        return !WATCHED.isEmpty() && WATCHED.contains(txnId);
    }

    public static boolean isTracingEnabled() {
        return !WATCHED.isEmpty();
    }

    public static void log(String event, String txnId, String detail) {
        if (!isWatched(txnId)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("VICTIM-TRACE ").append(event).append(" id=").append(txnId);
        if (detail != null && !detail.isEmpty()) {
            sb.append(" ").append(detail);
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // [0]=getStackTrace, [1]=this method, [2]=caller of log() - start from there, trim to a
        // handful of frames (enough to name the responsible call site without flooding output).
        sb.append("\n    at ");
        int end = Math.min(stack.length, 9);
        for (int i = 2; i < end; i++) {
            sb.append(stack[i]);
            if (i < end - 1) {
                sb.append("\n    at ");
            }
        }
        System.err.println(sb);
    }
}
