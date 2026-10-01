package test.java.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;

/**
 * V2 (z60): every public field of {@link SimulatorParameters} must reach the engine.
 *
 * <p><b>The hazard this closes has no handle any copy audit can catch.</b> A field can be added to
 * {@code SimulatorParameters}, faithfully copied by every {@code copyBuilder}, hashed by
 * {@code EffectiveConfigHasher}, written into a CSV column - and still reach nothing that runs.
 * {@code deadlockDetectionBetaThreshold} did exactly that: accepted, stored, copied, swept by
 * {@code WfgAdaptiveBetaExperiment} as its independent variable, and read by no engine code at any
 * commit in the history. The resulting CSV had 58 of 59 measured columns identical across all seven
 * scenarios - only {@code wall_clock_ms} moved - and was plotted as an overhead-reduction result.
 *
 * <p><b>The rule:</b> every public field of {@link SimulatorConfig} must be mentioned by some
 * source outside {@code config/} and {@code experiments/}. Those two are excluded on purpose -
 * {@code config/} declares and copies fields without consuming them, and {@code experiments/} SETS
 * them, which is exactly what the beta threshold had and what proved worthless.
 *
 * <p><b>Why it gates SimulatorConfig and not SimulatorParameters.</b> Names change at the boundary:
 * {@code params.numberOfIoProcessors} becomes {@code .numIoProcessors(...)} on the config, so
 * name-matching on the params side reports live parameters as dead. The config field name is the
 * one the engine actually uses, so that is the side where a missing reader means a dead knob. The
 * params-to-config half is audited by hand in V2 (z59) - 49 of 53 read, 2 indirect, 2 retired - and
 * is NOT automated here; a rename-aware version of that is a separate piece of work.
 *
 * <p>Fields that genuinely cannot satisfy that go in {@link #RETIRED_OR_INDIRECT} <b>with a stated
 * reason</b>. Adding a name there is a deliberate act that shows up in review; forgetting to wire a
 * new field is not. That asymmetry is the point - same as
 * {@code SeedSweepRunnerCopyBuilderRoundTripTest}, which this mirrors.
 */
class SimulatorParameterReachabilityTest {

    /** Packages that declare, copy or SET parameters rather than consume them. */
    private static final Set<String> NON_CONSUMING = Set.of("config", "experiments");

    /**
     * Exempt fields, each with the reason it cannot have a direct engine reader.
     *
     * <p>RETIRED: accepted and stored, read by nothing. Kept so call sites compile - the
     * Dandamudi/Au cost-constant precedent in SimulatorParameters. V2 (z60).
     *
     * <p>INDIRECT: reaches the engine through a constructor argument rather than through
     * {@code SimulatorConfig.fromParameters}, so no {@code params.x} read exists to find. V2 (z59).
     */
    private static final Map<String, String> RETIRED_OR_INDIRECT = new LinkedHashMap<>();

    static {
        RETIRED_OR_INDIRECT.put("deadlockDetectionInterval",
                "RETIRED (z60): no engine reader at any commit; see RETRACTED_wfg_beta/");
        RETIRED_OR_INDIRECT.put("deadlockDetectionBetaThreshold",
                "RETIRED (z60): no engine reader at any commit; swept by an inert experiment");
        RETIRED_OR_INDIRECT.put("deadlockDetectionMinCooldown",
                "RETIRED (z60): no engine reader at any commit; sibling of the above");
    }

    @Test
    @DisplayName("every public SimulatorConfig field reaches the engine, or is exempted by name")
    void everyFieldIsReachable() throws IOException {
        List<Path> consumers = consumerSources();
        assertTrue(consumers.size() > 50,
                "expected to scan the engine's sources; found only " + consumers.size()
                + " - the source-root resolution in this test is wrong, not the code under test");

        List<String> sources = new ArrayList<>();
        for (Path p : consumers) {
            sources.add(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        }

        List<String> unreachable = new ArrayList<>();
        Map<String, Integer> carriedBy = new LinkedHashMap<>();
        for (Field f : SimulatorConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            String name = f.getName();
            if (RETIRED_OR_INDIRECT.containsKey(name)) {
                continue;
            }
            Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            boolean seen = sources.stream().anyMatch(s -> word.matcher(s).find());
            if (!seen) {
                // ONE-HOP ACCESSOR RULE. A field can reach the engine through a config accessor
                // with a different name: transactionModelIndices is read only by
                // SimulatorConfig.transactionModelPool(), which TransactionFactory calls. Counting
                // that as unreachable would report a live knob as dead, so check whether any
                // config method that mentions the field is itself called from outside.
                String via = reachedViaAccessor(name, sources);
                if (via != null) {
                    seen = true;
                    carriedBy.merge(via, 1, Integer::sum);
                }
            }
            if (!seen) {
                unreachable.add(name);
            }
        }

        if (!unreachable.isEmpty()) {
            fail("These SimulatorConfig fields are built from parameters and read by NOTHING "
                    + "outside config/ and experiments/ - so a run ignores them silently, exactly "
                    + "as deadlockDetectionBetaThreshold did (V2 (z60)). Wire each into the "
                    + "engine, or retire it and add it to RETIRED_OR_INDIRECT with a reason: "
                    + unreachable);
        }

        // A single method must not account for many fields' reachability. If one does, it is a
        // dump-style method the DUMP_METHODS list does not name, and it has made this gate
        // vacuous for every field it mentions.
        carriedBy.forEach((method, count) -> assertTrue(count <= 3,
                "SimulatorConfig." + method + "() alone accounts for the reachability of "
                + count + " fields. A method that touches that many is a dump, not an "
                + "accessor, and it makes this gate inert for all of them - add it to "
                + "DUMP_METHODS, or give those fields real readers."));
    }

    @Test
    @DisplayName("the exemption list stays honest: no stale names, every entry has a reason")
    void exemptionsAreCurrent() {
        Set<String> live = Stream.of(SimulatorConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        List<String> stale = RETIRED_OR_INDIRECT.keySet().stream()
                .filter(n -> !live.contains(n))
                .toList();
        assertTrue(stale.isEmpty(),
                "exempted names that are no longer fields on SimulatorConfig - delete them so "
                + "the list cannot silently excuse a future field of the same name: " + stale);

        List<String> unexplained = RETIRED_OR_INDIRECT.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();
        assertTrue(unexplained.isEmpty(),
                "every exemption states why the field cannot be reachable: " + unexplained);
    }


    /**
     * True when some {@code SimulatorConfig} method mentioning {@code field} is called from outside
     * {@code config/} and {@code experiments/}. One hop only - deliberately: a deeper search would
     * start finding paths that exist but carry nothing, which is the failure mode this gate exists
     * to catch.
     */
    /** Methods that mention every field and so would make the accessor rule vacuous. */
    private static final Set<String> DUMP_METHODS = Set.of(
            "toString", "equals", "hashCode", "describe", "dump", "summary", "format");

    private static String reachedViaAccessor(String field, List<String> consumerSources)
            throws IOException {
        Path cfg = Paths.get("src", "main", "java", "de", "tum", "tssimulator", "config",
                "SimulatorConfig.java");
        if (!Files.isRegularFile(cfg)) {
            cfg = Paths.get("..").resolve(cfg);
        }
        String src = new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8);

        // public methods whose body mentions the field
        Matcher m = Pattern.compile(
                "public\\s+[\\w<>,\\[\\]\\. ]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{").matcher(src);
        List<String> exposing = new ArrayList<>();
        while (m.find()) {
            String method = m.group(1);
            int open = src.indexOf('{', m.end() - 1);
            int depth = 0;
            int i = open;
            for (; i < src.length(); i++) {
                char c = src.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            String body = src.substring(open, Math.min(i + 1, src.length()));
            if (DUMP_METHODS.contains(method)) {
                // A toString()/describe() mentions every field, so counting it would make the
                // rule vacuous - one such method added later would silently make the whole
                // gate inert without any test failing.
                continue;
            }
            if (Pattern.compile("\\b" + Pattern.quote(field) + "\\b").matcher(body).find()) {
                exposing.add(method);
            }
        }

        for (String method : exposing) {
            Pattern call = Pattern.compile("\\." + Pattern.quote(method) + "\\s*\\(");
            if (consumerSources.stream().anyMatch(s -> call.matcher(s).find())) {
                return method;
            }
        }
        return null;
    }

    /** Every main source outside the declaring/copying/setting packages. */
    private static List<Path> consumerSources() throws IOException {
        Path candidate = Paths.get("src", "main", "java", "de", "tum", "tssimulator");
        if (!Files.isDirectory(candidate)) {
            candidate = Paths.get("..", "src", "main", "java", "de", "tum", "tssimulator");
        }
        final Path root = candidate;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        Path rel = root.relativize(p);
                        return rel.getNameCount() < 2
                                || !NON_CONSUMING.contains(rel.getName(0).toString());
                    })
                    .toList();
        }
    }
}
