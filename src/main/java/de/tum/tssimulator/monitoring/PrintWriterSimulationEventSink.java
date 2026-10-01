package de.tum.tssimulator.monitoring;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class PrintWriterSimulationEventSink implements SimulationEventSink {
    private final PrintWriter writer;

    public PrintWriterSimulationEventSink(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to open simulation log sink at " + path, e);
        }
    }

    @Override
    public void writeLine(String line) {
        writer.println(line);
    }

    @Override
    public void close() {
        writer.close();
    }
}
