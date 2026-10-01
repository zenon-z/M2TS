package test.java.client;

import de.tum.tssimulator.client.Terminal;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TerminalTest {

    @Test
    public void testTerminalLifecycle() {
        long meanDelay = 1000; // milliseconds
        Terminal terminal = new Terminal(1, meanDelay, new Random());

        assertEquals(Terminal.State.IDLE, terminal.getState(), "Should start in IDLE");

        long futureTime = terminal.getNextSubmissionTime() + 100;
        assertTrue(terminal.tryStartTransaction(futureTime), "Should be ready after delay");

        assertEquals(Terminal.State.BUSY, terminal.getState(), "Should be BUSY after submit");
        assertFalse(terminal.tryStartTransaction(futureTime + 1000), "Should not be ready when BUSY");

        long finishTime = futureTime + 500;
        terminal.onTransactionFinished(finishTime);

        assertEquals(Terminal.State.IDLE, terminal.getState(), "Should be back to IDLE");
        assertTrue(terminal.getNextSubmissionTime() >= finishTime, "Next time should be in future");

    }

    @Test
    public void testPoissonDistribution() {
        long meanDelay = 100;
        int lowerBound = (int) (meanDelay * 0.9);
        int upperBound = (int) (meanDelay * 1.10);

        Terminal terminal = new Terminal(2, meanDelay, new Random(5487));

        long totalDelay = 0;
        int iterations = 10000;

        for (int i = 0; i < iterations; i++) {
            long now = 1000;
            terminal.onTransactionFinished(now);
            totalDelay += (terminal.getNextSubmissionTime() - now);
        }

        double average = totalDelay / (double) iterations;
        assertTrue(average > lowerBound && average < upperBound,
                "Average delay should be close to mean inter-arrival delay");
    }

    // Create two terminals and check their initial submission times will not both
    // be zero or check that initial nextSubmissionTime > 0
    @Test
    public void testInitialRandomStart() {

        long meanDelay = 1000;
        Terminal t1 = new Terminal(1, meanDelay, new Random(8745));

        assertTrue(t1.getNextSubmissionTime() > 0,
                "Initial start time should be delayed (not 0)");

    }
}
