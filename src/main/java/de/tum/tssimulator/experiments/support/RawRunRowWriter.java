package de.tum.tssimulator.experiments.support;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * CSV appender for {@link RawRunRow}, shared by every E-A..E-E experiment class instead of each
 * hand-rolling its own PrintWriter block (the anti-pattern that let the pre-existing E1-E4 CSVs'
 * schemas drift apart). Writes the header once, then one flushed line per row so a crash
 * mid-sweep doesn't lose already-collected seeds.
 *
 * Enforces the decomposition-sum gate from the VLDB reviewer response at write time:
 * frac_blocked + frac_waiting_room + wasted_cpu_io_frac + residual_frac must be 1 +/- 0.02 (it
 * always sums to exactly 1 by construction per RawRunRow's residualFrac derivation - this gate
 * instead catches the three mechanism fractions individually leaving [0,1], e.g. wasted_cpu_io
 * exceeding total processor time). Throws by default (fail fast during the smoke-test/dev
 * phase); pass -Dts.rawRow.warnOnly=true so a single bad cell doesn't abort a multi-hour sweep
 * once the full ~20,000-run job is running unattended.
 */
public final class RawRunRowWriter implements AutoCloseable {
    private static final String WARN_ONLY_PROPERTY = "ts.rawRow.warnOnly";
    private static final double DECOMPOSITION_TOLERANCE = 0.02;

    private final PrintWriter writer;
    private final boolean warnOnly;

    public RawRunRowWriter(String filePath) throws IOException {
        this.writer = new PrintWriter(new FileWriter(filePath));
        this.warnOnly = Boolean.getBoolean(WARN_ONLY_PROPERTY);
        writer.println(RawRunRow.csvHeader());
        writer.flush();
    }

    public void write(RawRunRow row) {
        checkDecomposition(row);
        writer.println(row.toCsvRow());
        writer.flush();
    }

    private void checkDecomposition(RawRunRow row) {
        double sum = row.fracBlocked + row.fracWaitingRoom + row.wastedCpuIoFrac + row.residualFrac;
        if (Math.abs(sum - 1.0) > DECOMPOSITION_TOLERANCE) {
            String message = String.format(Locale.US,
                    "RawRunRow decomposition check failed for config_id=%s seed=%d: "
                            + "frac_blocked(%.4f) + frac_waiting_room(%.4f) + wasted_cpu_io_frac(%.4f) "
                            + "+ residual_frac(%.4f) = %.4f, expected 1.0 +/- %.2f",
                    row.configId, row.seed, row.fracBlocked, row.fracWaitingRoom, row.wastedCpuIoFrac,
                    row.residualFrac, sum, DECOMPOSITION_TOLERANCE);
            if (warnOnly) {
                System.out.println("[RawRunRowWriter WARNING] " + message);
            } else {
                throw new IllegalStateException(message);
            }
        }
    }

    @Override
    public void close() {
        writer.close();
    }
}
