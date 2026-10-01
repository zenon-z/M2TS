package de.tum.tssimulator.experiments.support;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

/**
 * CSV appender for {@link AdmissionAttemptRow}. Unlike {@link RawRunRowWriter} (one row per
 * seed-run, flushed every write for crash-safety), first-attempt volume is one row per admitted-
 * or-ever-attempted transaction per seed - still large across a multi-hundred-cell grid, so this
 * buffers and flushes periodically instead of per-row.
 */
public final class AdmissionAttemptRowWriter implements AutoCloseable {
    private static final int FLUSH_EVERY_N_ROWS = 2000;

    private final BufferedWriter writer;
    private int rowsSinceFlush = 0;

    public AdmissionAttemptRowWriter(String filePath) throws IOException {
        this.writer = new BufferedWriter(new FileWriter(filePath));
        writer.write(AdmissionAttemptRow.csvHeader());
        writer.newLine();
    }

    public void write(AdmissionAttemptRow row) throws IOException {
        writer.write(row.toCsvRow());
        writer.newLine();
        if (++rowsSinceFlush >= FLUSH_EVERY_N_ROWS) {
            writer.flush();
            rowsSinceFlush = 0;
        }
    }

    @Override
    public void close() throws IOException {
        writer.flush();
        writer.close();
    }
}
