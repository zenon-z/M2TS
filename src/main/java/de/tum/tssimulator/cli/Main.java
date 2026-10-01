package de.tum.tssimulator.cli;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.bootstrap.ResolvedSimulatorConfiguration;
import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.bootstrap.Simulator;
import de.tum.tssimulator.bootstrap.SimulatorConfigurationException;
import de.tum.tssimulator.bootstrap.SimulatorConfigurationLoader;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.program.ProgramShape;
import de.tum.tssimulator.core.program.ProgramShapeRegistry;
import de.tum.tssimulator.core.program.TransactionProgram;
import de.tum.tssimulator.core.program.TransactionProgramFactory;
import de.tum.tssimulator.execution.StepMode;

/**
 * Main entry point for the Transaction Simulator.
 * 
 * This simulator models concurrent transactions competing for resources
 * with configurable CPU/IO processing and locking mechanisms.
 */
public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "1.0.0-SNAPSHOT";
    private static final String USAGE = """
            Usage:
              java -jar ts-simulator.jar [run] [--config <path>] [--profile <name>] [--set key=value ...]
              java -jar ts-simulator.jar <path-to-simulator.properties> [--profile <name>] [--set key=value ...]
              java -jar ts-simulator.jar help
              java -jar ts-simulator.jar version

            Supported commands:
              run       Start the simulator using layered config from defaults, file, env, and --set overrides
              help      Print this usage text
              version   Print the simulator version
            """;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] args) {
        try {
            CliOptions options = CliArgumentParser.parse(args);
            if (options.command() == CliOptions.Command.HELP) {
                LOGGER.info(System.lineSeparator() + USAGE.stripTrailing());
                return StartupExitCode.SUCCESS.code();
            }
            if (options.command() == CliOptions.Command.VERSION) {
                LOGGER.info("ts-simulator {}", VERSION);
                return StartupExitCode.SUCCESS.code();
            }

            ResolvedSimulatorConfiguration resolvedConfiguration = loadConfiguration(options);
            LOGGER.info("Effective simulator configuration: {}", resolvedConfiguration.describe());
            LOGGER.info("Starting simulation with {}", resolvedConfiguration.parameters());
            runSimulation(resolvedConfiguration.parameters());
            return StartupExitCode.SUCCESS.code();
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid command line arguments: {}", e.getMessage());
            LOGGER.info(System.lineSeparator() + USAGE.stripTrailing());
            return StartupExitCode.CONFIG_ERROR.code();
        } catch (SimulatorConfigurationException e) {
            LOGGER.error("Simulation startup failed because configuration is invalid", e);
            return StartupExitCode.CONFIG_ERROR.code();
        } catch (SimulationWatchdogException e) {
            LOGGER.error("Simulation watchdog detected a non-progressing run", e);
            return StartupExitCode.WATCHDOG_FAILURE.code();
        } catch (Exception e) {
            LOGGER.error("Simulation failed during runtime execution", e);
            return StartupExitCode.RUNTIME_ERROR.code();
        }
    }
    
    /**
     * Load configuration based on command line arguments or defaults
     */
    private static ResolvedSimulatorConfiguration loadConfiguration(CliOptions options) {
        return SimulatorConfigurationLoader.load(options);
    }
    
    /**
     * Create and execute the simulation with given parameters
     */
    private static void runSimulation(SimulatorParameters params) {
        // Create model pool using factory pattern
        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);

        // Build simulator configuration using the cleaner factory method
        List<ProgramShape> shapes = ProgramShapeRegistry.defaultShapes();
        List<TransactionProgram> programPool = TransactionProgramFactory.createPrograms(shapes, modelPool);
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool, programPool);

        // Create context and run simulation
        SimulatorContext ctx = SimulatorContext.create(config);
        Simulator simulator = new Simulator(ctx, StepMode.FIXED_TIME);
        
        long startTime = System.currentTimeMillis();
        simulator.run();
        long endTime = System.currentTimeMillis();
        
        LOGGER.info("Simulation completed in {}ms", endTime - startTime);
    }
}
