package test.java.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;

class TransactionTimestampTest {

    @Test
    void testTimestampMonotonicity() {
        TransactionIdGenerator idGen = new TransactionIdGenerator();
        TransactionFactory factory = new TransactionFactory(idGen);

        SimulatorParameters params = SimulatorParameters.builder()
                .totalModels(2)
                .totalResources(5000)
                .ioTimePerResource(200)
                .cpuTimePerResource(100)
                .lockIOTimePerResource(200)
                .lockCPUTimePerResource(100)
                .initialTransactions(200)
                .totalTransactionSteps(10000)
                .maxTransactionModels(1)
                .maxTransactionResources(50)
                .semanticOverlap(0)
                .referentialAction(ReferentialAction.ON_CASCADE)
                .simulationDurationSeconds(10000)
                .numberOfIoProcessors(30)
                .numberOfCpuProcessors(5)
                .totalLocks(200)
                .transactionSamplingSeed(42L)
                .build();

        List<Model> modelPool = new ModelFactory(params.instancePoolSize).createModels(params.propertiesPerModel);
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);

        Transaction t1 = factory.createTransaction(config, new TransactionGroup());
        Transaction t2 = factory.createTransaction(config, new TransactionGroup());

        System.out.printf("T1 Timestamp: %d, T2 Timestamp: %d%n", t1.getTimestamp(), t2.getTimestamp());

        assertTrue(t1.getTimestamp() < t2.getTimestamp(),
                String.format("Older transaction T1 (%d) should have smaller timestamp than younger T2 (%d)",
                        t1.getTimestamp(), t2.getTimestamp()));
    }
}
