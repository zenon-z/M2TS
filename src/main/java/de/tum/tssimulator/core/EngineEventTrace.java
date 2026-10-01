package de.tum.tssimulator.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered log of engine decisions, for localising run-to-run divergence.
 *
 * <p>Built for a concrete failure: the S2PL D-oracle arm does not reproduce - same seed, same
 * binary, different JV give throughput 8.7-10.0 - while the static, dynamic and C2PL arms are
 * bit-identical. {@code OracleDeterminismProbe} already established that workload *generation* is
 * deterministic (identical prefix digests), so the divergence is inside the engine. Identity-hash
 * iteration order was then eliminated by rerunning under {@code -XX:hashCode=2}, which pins every
 * identity hash to a constant and changed nothing.
 *
 * <p>Guessing at candidate sites one at a time is expensive. This records the engine's decision
 * sequence instead, so two runs can be diffed for the FIRST index at which they differ - which
 * names the deciding call site directly rather than by elimination. Same technique as the probe's
 * prefix digests, one layer down.
 *
 * <p>Off unless {@code -Dts.diag.engineTrace=<output-path>} is set, and a no-op when off: the
 * {@link #enabled()} check is a static boolean read, so leaving the call sites in place costs
 * nothing in normal runs.
 */
public final class EngineEventTrace {

    private static final String PROPERTY = "ts.diag.engineTrace";
    private static final String OUTPUT_PATH = System.getProperty(PROPERTY);
    private static final boolean ENABLED = OUTPUT_PATH != null && !OUTPUT_PATH.isBlank();

    /** Bounded: a 200-simulated-second oracle run makes millions of lock requests, and the first
     * divergence is invariably early. Cap keeps the trace diffable and memory bounded. */
    private static final int MAX_EVENTS = 400_000;

    private static final List<String> EVENTS = ENABLED ? new ArrayList<>(MAX_EVENTS) : List.of();
    private static long sequence = 0;

    private EngineEventTrace() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Records one decision. Callers must guard with {@link #enabled()} so the argument string is
     * not built on the hot path when tracing is off.
     */
    public static void record(String type, String subject, String detail) {
        if (!ENABLED || EVENTS.size() >= MAX_EVENTS) {
            return;
        }
        EVENTS.add(sequence++ + "\t" + type + "\t" + subject + "\t" + detail);
    }

    /** Writes the trace to the configured path. Safe to call when disabled (does nothing). */
    public static void dump() {
        if (!ENABLED) {
            return;
        }
        try {
            Path out = Path.of(OUTPUT_PATH);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                for (String e : EVENTS) {
                    w.println(e);
                }
            }
            System.err.println("[engineTrace] wrote " + EVENTS.size() + " events to " + out);
        } catch (IOException e) {
            System.err.println("[engineTrace] failed to write trace: " + e);
        }
    }
}
