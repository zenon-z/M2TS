package de.tum.tssimulator.monitoring;

/**
 * Discards every line. For experiment/seed sweeps: each individual simulation run constructs its
 * own SimulationLogger (see SimulatorContext.create()), and the default file sink
 * (PrintWriterSimulationEventSink at logs/simulation.log) logs one line per resource release plus
 * phase/lifecycle events for every transaction - at high mpl/throughput this is on the order of
 * ~1GB of disk I/O for a SINGLE seed's run, none of which a sweep's aggregated-CSV pipeline ever
 * reads. That I/O is pure wall-clock overhead stacked on top of whatever the simulation itself
 * costs - confirmed directly: logs/simulation.log observed growing ~1GB within a couple of
 * minutes for one static/k=10/mpl=512 seed during a DynamicFootprintExperiment sweep. See
 * SimulationLogDestinationFactory's ts.simulator.log.discard property for how sweeps opt into
 * this.
 */
public final class NullSimulationEventSink implements SimulationEventSink {
    @Override
    public void writeLine(String line) {
        // Discard.
    }

    @Override
    public void close() {
        // Nothing to close.
    }
}
