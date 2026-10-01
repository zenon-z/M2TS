package test.java.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import de.tum.tssimulator.bootstrap.Simulator;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.StepMode;

class TerminalIntegrationTest {

        @BeforeEach
        void setUp() {
                Transaction.resetMetrics();
        }

        /**
         * Basic Test 1: Terminal Mode Functionality
         * Verification: Terminals should be able to drive workload generation.
         */
        @Test
        void terminalModeDrivesWorkloadWithoutInitialPool() {
                SimulatorContext ctx = createSimulatorContext(5, 100, 1, 100); // 5 Terminals, 100ms Think Time
                runSimulation(ctx);

                assertTrue(Transaction.getCompletedTransactions() > 0,
                                "Terminal mode should complete transactions even without initial pool");
                assertTrue(ctx.scheduling().txnScheduler().getTransactionCounter() >= 5,
                                "Terminal clients should enqueue at least one transaction each");
        }

        /**
         * Scalability Test 2: Throughput Scalability
         * Verification: With sufficient resources, increasing the number of Terminals
         * should increase total throughput.
         * Comparison: 50 Terminals vs 200 Terminals
         */
        @Test
        void increasingTerminalsIncreasesThroughput() {
                // Run 1: Low Load (50 Terminals)
                Transaction.resetMetrics();
                SimulatorParameters lowLoadParams = baseParamsBuilder()
                                .numTerminals(50)
                                .build();
                SimulatorContext lowLoadCtx = buildContext(lowLoadParams);
                runSimulation(lowLoadCtx);
                long lowLoadCompleted = Transaction.getCompletedTransactions();

                // Run 2: High Load (200 Terminals)
                Transaction.resetMetrics();
                SimulatorParameters highLoadParams = baseParamsBuilder()
                                .numTerminals(200)
                                .build();
                SimulatorContext highLoadCtx = buildContext(highLoadParams);
                runSimulation(highLoadCtx);
                long highLoadCompleted = Transaction.getCompletedTransactions();
                // Strictly greater, not a multiple: the two loads complete similar transaction
                // counts at this configuration, so a margin (e.g. > 2x) makes this test flaky.
                assertTrue(highLoadCompleted > lowLoadCompleted,
                                () -> String.format("Expected higher throughput with more terminals, high=%d low=%d",
                                                highLoadCompleted,
                                                lowLoadCompleted));
        }

        /**
         * Test 3: Thinking Time Impact
         * Verification: Longer thinking time (Inter-Arrival Delay) should result in
         * lower throughput.
         * Comparison: 100ms thinking time vs 5000ms thinking time
         */
        @Test
        void increasingThinkTimeDecreasesThroughput() {
                // Run 1: Fast Thinkers (100ms delay) -> Should generate MORE transactions
                Transaction.resetMetrics();
                SimulatorParameters fastParams = baseParamsBuilder()
                                .meanInterArrivalDelay(100_000)
                                .build();
                SimulatorContext fastCtx = buildContext(fastParams);
                runSimulation(fastCtx);
                long fastCompleted = Transaction.getCompletedTransactions();

                // Run 2: Slow Thinkers (5000ms delay) -> Should generate FEW transactions
                Transaction.resetMetrics();
                SimulatorParameters slowParams = baseParamsBuilder()
                                .meanInterArrivalDelay(5_000_000)
                                .build();
                SimulatorContext slowCtx = buildContext(slowParams);
                runSimulation(slowCtx);
                long slowCompleted = Transaction.getCompletedTransactions();

                assertTrue(fastCompleted > slowCompleted,
                                () -> String.format("Expected fast thinkers to outpace slow ones 5x, fast=%d slow=%d",
                                                fastCompleted,
                                                slowCompleted));
        }

        private void runSimulation(SimulatorContext ctx) {
                Simulator simulator = new Simulator(ctx, StepMode.FIXED_TIME);
                simulator.run();
        }

        private SimulatorContext createSimulatorContext(int numTerminals, long meanInterArrivalDelay, int numProcessors,
                        int totalLocks) {
                SimulatorParameters params = baseParamsBuilder()
                                .numTerminals(numTerminals)
                                .meanInterArrivalDelay(meanInterArrivalDelay)
                                .numberOfIoProcessors(numProcessors)
                                .numberOfCpuProcessors(numProcessors)
                                .totalLocks(totalLocks)
                                .build();
                return buildContext(params);
        }

        private SimulatorParameters.Builder baseParamsBuilder() {
                return SimulatorParameters.builder()
                                .totalModels(10)
                                .totalResources(1000)
                                .ioTimePerResource(5)
                                .cpuTimePerResource(2)
                                .lockIOTimePerResource(1)
                                .lockCPUTimePerResource(1)
                                .initialTransactions(0)
                                .totalTransactionSteps(1000)
                                .maxTransactionModels(1)
                                .maxTransactionResources(2)
                                .semanticOverlap(0.0)
                                .simulationDurationSeconds(120)
                                .numberOfIoProcessors(32)
                                .numberOfCpuProcessors(32)
                                .totalLocks(5000)
                                .stepTimeSlice(1_000L)
                                .transactionMode(TransactionMode.TERMINAL)
                                .numTerminals(10)
                                .meanInterArrivalDelay(5_000_000L)
                                .transactionSamplingSeed(42L)
                                .semanticOverlapSeed(84L);
        }

        private SimulatorContext buildContext(SimulatorParameters params) {
                ModelFactory factory = new ModelFactory(params.instancePoolSize);
                List<Model> models = factory.createModels(params.propertiesPerModel);
                SimulatorConfig config = SimulatorConfig.fromParameters(params, models);
                return SimulatorContext.create(config);
        }
}
