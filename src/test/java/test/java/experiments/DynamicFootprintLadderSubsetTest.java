package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Covers {@code DynamicFootprintExperiment.LadderSubset.parse(...)}, the validation-ladder cell
 * selector.
 *
 * <p>The behaviour under test is mostly *rejection*. A ladder rung is only meaningful compared
 * against a rung-0 reference produced from the same cell, so a spec naming a cell the grid does not
 * contain (k=30, mpl=7) must abort rather than run something plausible-looking that no baseline
 * covers. Silent acceptance here would produce exactly the class of result this experiment has
 * already been burned by twice - runnable, believable, and not what it claims to be.
 *
 * <p>Reached by reflection: {@code LadderSubset} is a private implementation detail of the
 * experiment and should stay one. Testing it through {@code main()} would mean running simulations.
 */
class DynamicFootprintLadderSubsetTest {

    private static final int[] K_TARGETS = {10, 25, 50};
    private static final double[] F_VALUES = {0.40, 0.47, 0.50};
    private static final int[] MPL_VALUES =
            {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 640};

    @Test
    void absentSpecMeansFullGridUnchanged() throws Exception {
        assertNull(parse(null), "absent property must leave the grid untouched");
        assertNull(parse(""), "blank property must leave the grid untouched");
        assertNull(parse("   "), "whitespace-only property must leave the grid untouched");
    }

    @Test
    void parsesTheChosenLadderCell() throws Exception {
        Object subset = parse("25:0.47:4,16,64,256:5:rung0");

        assertEquals(25, invoke(subset, "k"));
        assertEquals(0.47, (double) invoke(subset, "f"), 1e-9);
        assertEquals(5, invoke(subset, "seedCount"));
        assertArrayEqualsInt(new int[] {4, 16, 64, 256}, (int[]) invoke(subset, "mplValues"));
    }

    /** Provenance string reaches both the output filename and every raw row, so pin its shape. */
    @Test
    void describeIdentifiesTheCell() throws Exception {
        assertEquals("rung0-k25-f0.47-mpl4.16.64.256-s5",
                invoke(parse("25:0.47:4,16,64,256:5:rung0"), "describe"));
    }

    @Test
    void rejectsOffGridK() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> parse("30:0.47:4:5:rung0")).getMessage();
        assertTrue(message.contains("30") && message.contains("[10, 25, 50]"),
                "the error must name both the bad value and the valid set, got: " + message);
    }

    @Test
    void rejectsOffGridMpl() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> parse("25:0.47:4,7,64:5:rung0")).getMessage();
        assertTrue(message.contains("7"),
                "the error must name the offending mpl, got: " + message);
    }

    /**
     * The grid pairs k and f by index, so k=25 is only ever run at f=0.47. A spec pairing k=25 with
     * f=0.50 describes a cell this experiment never produces, and would silently be run at whatever
     * the config builders were handed.
     */
    @Test
    void rejectsFThatDoesNotMatchItsK() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> parse("25:0.50:4:5:rung0")).getMessage();
        assertTrue(message.contains("0.47"),
                "the error must name the f this grid pairs with k=25, got: " + message);
    }

    @Test
    void rejectsMalformedSpecs() {
        assertThrows(IllegalArgumentException.class, () -> parse("25:0.47:4:5"));       // too few parts (rung missing)
        assertThrows(IllegalArgumentException.class, () -> parse("25:0.47:4:5:a:b"));   // too many
        assertThrows(IllegalArgumentException.class, () -> parse("x:0.47:4:5:r"));      // k not a number
        assertThrows(IllegalArgumentException.class, () -> parse("25:x:4:5:r"));        // f not a number
        assertThrows(IllegalArgumentException.class, () -> parse("25:0.47:x:5:r"));     // mpl not a number
        assertThrows(IllegalArgumentException.class, () -> parse("25:0.47:4:0:r"));     // seeds < 1
        assertThrows(IllegalArgumentException.class, () -> parse("25:0.47:4:5: "));    // blank rung
        assertThrows(IllegalArgumentException.class, () -> parse("25:0.47:4:5:a/b"));  // unsafe for a filename
    }

    // --- reflection plumbing -------------------------------------------------------------

    private static Object parse(String spec) throws Exception {
        Class<?> cls = Class.forName(
                "de.tum.tssimulator.experiments.DynamicFootprintExperiment$LadderSubset");
        Method parse = cls.getDeclaredMethod("parse", String.class, int[].class, double[].class, int[].class);
        parse.setAccessible(true);
        try {
            return parse.invoke(null, spec, K_TARGETS, F_VALUES, MPL_VALUES);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime; // surface the real IllegalArgumentException to assertThrows
            }
            throw e;
        }
    }

    private static Object invoke(Object subset, String accessor) throws Exception {
        Method m = subset.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return m.invoke(subset);
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }
}
