package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;

final class RuntimeModeAssemblers {
    private RuntimeModeAssemblers() {
    }

    static RuntimeModeAssembler forConfig(SimulatorConfig config) {
        return config.getConcurrencyModel() == ConcurrencyModel.BASE
                ? new BaseRuntimeAssembler()
                : new AcidRuntimeAssembler();
    }
}
