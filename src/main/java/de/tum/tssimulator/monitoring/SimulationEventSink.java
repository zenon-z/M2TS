package de.tum.tssimulator.monitoring;

public interface SimulationEventSink extends AutoCloseable {
    void writeLine(String line);

    @Override
    void close();
}
