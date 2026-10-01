package de.tum.tssimulator.experiments.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.config.SimulatorParameters;

/**
 * VLDB reviewer response, config-integrity follow-up: hashes the FULLY
 * MATERIALIZED {@link SimulatorParameters} that a run actually used - not the config the caller
 * intended, and not a manually-curated subset of "the fields that matter". Exists because
 * {@code SeedSweepRunner.copyBuilder()} silently dropped two fields
 * (propertiesPerModel/instancePoolSize) for an entire investigation before a suspicious
 * too-identical-to-be-real A/B comparison surfaced it - see
 * SeedSweepRunnerCopyBuilderRoundTripTest's javadoc for the full incident. A hash written into
 * every raw row turns "two configs silently collapsed onto the same effective parameters" from
 * something noticed by luck into something the analysis pipeline (D2) can assert against
 * mechanically: rows sharing a config_id must share a hash, and distinct config_ids must not
 * collide.
 *
 * <p>Reflects over every public instance field of {@link SimulatorParameters} - the same field
 * enumeration {@link SeedSweepRunnerCopyBuilderRoundTripTest} uses for its round-trip assertion
 * lives in test code, not here, but the principle is the same: whatever fields exist on the
 * class are covered automatically, so a future field addition doesn't need this class touched to
 * stay protected. Fields are hashed in a fixed (name-sorted) order so the digest is stable across
 * JVM versions/reflection-ordering quirks, not just across runs within one process.
 */
public final class EffectiveConfigHasher {
    // Deliberately excluded: these two fields are EXPECTED to differ across seeds within the
    // same config_id (that's what a seed sweep is), so including them would make every row's
    // hash unique even for a correctly-configured sweep, defeating the "rows sharing a
    // config_id share a config_hash" check this class exists to support. Every other field is
    // config-defining and must be identical across seeds of one config cell.
    private static final Set<String> SEED_VARYING_FIELDS = Set.of(
            "transactionSamplingSeed", "semanticOverlapSeed");

    /**
     * The cost basis V0 fits and every higher rung FREEZES - the fields that set service time per
     * resource and the scheduling quantum. V1's criterion 4 ("parameters frozen") is an assertion
     * over exactly these, and it needs its own digest because {@link #hash} covers every
     * config-defining field: a V1 cell legitimately differs from V0 in semanticOverlap,
     * totalModels and totalResources, so a whole-config hash comparison between rungs always
     * differs and asserts nothing. This subset differing between rungs, by contrast, VOIDS the
     * ladder - refitting at a higher rung turns the validation into an interpolation.
     *
     * <p>Per V0's identifiability note, io and cpu per resource are not separately identifiable -
     * only their sum is - but both are hashed anyway: the digest exists to detect CHANGE, and a
     * change that leaves the sum fixed still changes the io/cpu split the ladder is meant to hold.
     */
    private static final Set<String> COST_FIELDS = Set.of(
            "ioTimePerResource", "cpuTimePerResource",
            "lockIOTimePerResource", "lockCPUTimePerResource",
            "rollbackCPUTimePerResource", "stepTimeSlice");

    private EffectiveConfigHasher() {
    }

    /** Returns the first 16 hex characters of the SHA-256 digest of every public
     * config-defining field's toString(), name-sorted (excludes the two per-seed RNG seed
     * fields - see SEED_VARYING_FIELDS) - short enough for a CSV column, long enough that an
     * accidental collision between two genuinely-different configs is not a practical concern. */
    public static String hash(SimulatorParameters params) {
        String serialized = Arrays.stream(SimulatorParameters.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !SEED_VARYING_FIELDS.contains(f.getName()))
                .sorted(Comparator.comparing(Field::getName))
                .map(f -> f.getName() + "=" + fieldValueToString(f, params))
                .collect(Collectors.joining(";"));
        return digest(serialized);
    }

    private static String digest(String serialized) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(serialized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JLS Standard Names) - unreachable in
            // practice, but fail loudly rather than silently returning a non-hash placeholder
            // that would defeat the whole point of this class.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Digest over {@link #COST_FIELDS} only - the frozen cost basis, for V1 criterion 4. Emitted
     * as a column so "the params here match V0's fitted values" is a string comparison between two
     * archived rows rather than an argument from reading the code. */
    public static String hashCostFields(SimulatorParameters params) {
        return digest(Arrays.stream(SimulatorParameters.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> COST_FIELDS.contains(f.getName()))
                .sorted(Comparator.comparing(Field::getName))
                .map(f -> f.getName() + "=" + fieldValueToString(f, params))
                .collect(Collectors.joining(";")));
    }

    private static String fieldValueToString(Field field, SimulatorParameters params) {
        try {
            Object value = field.get(params);
            return String.valueOf(value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unreadable public field '" + field.getName() + "'", e);
        }
    }
}
