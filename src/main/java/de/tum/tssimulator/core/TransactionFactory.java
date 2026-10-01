package de.tum.tssimulator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.math3.distribution.ZipfDistribution;
import org.apache.commons.math3.random.Well19937c;

import de.tum.tssimulator.config.OracleClosurePlacement;
import de.tum.tssimulator.config.ResourceAccessDistribution;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.config.TransactionSizeProfile;
import de.tum.tssimulator.config.WorkloadMix;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.core.program.Step;
import de.tum.tssimulator.core.program.TransactionProgram;
import de.tum.tssimulator.core.program.WeightedProgramSelector;
import de.tum.tssimulator.cpr.CprSchema;

public class TransactionFactory {
    private final Random random;
    private final Random programSelectionRandom;
    /** Zipf instance-id draws; separate stream - see {@link #ACCESS_SKEW_SEED_SALT}. */
    private final Well19937c accessSkewRandom;
    /** Transaction-class and write-position draws; separate stream. */
    private final Random workloadClassRandom;
    /**
     * Cached Zipf, keyed by instance-pool size. {@link org.apache.commons.math3.distribution.ZipfDistribution}
     * precomputes its normalisation, so rebuilding it per transaction would dominate generation
     * cost. Null until the first ZIPF draw; the pool size is fixed per run in practice, but the
     * map keeps this correct if models ever differ.
     */
    private final Map<Integer, ZipfDistribution> zipfByPoolSize = new LinkedHashMap<>();

    // TEMPORARY diagnostic (pairing check between the dynamic and D-oracle arms, see
    // dynamic-footprint-variance-investigation follow-up): when true, every RANDOM_OPERATIONS
    // root-parent's pre-expansion seed-resource set is appended here in creation order, across
    // every TransactionFactory instance in this JVM. Off by default (zero cost); a diagnostic
    // driver flips it on, runs one short simulation, reads this list, then flips it off. Delete
    // once the pairing question is answered.
    public static volatile boolean DEBUG_RECORD_SEED_TRACE = false;
    public static final List<String> DEBUG_SEED_TRACE =
            Collections.synchronizedList(new ArrayList<>());
    // Same gate: post-expansion resource-set size of every oracle-arm root-parent (|closure|,
    // seeds included) in creation order - the ACTUAL per-transaction footprint the oracle arm
    // preclaims, rather than the mean-over-all-properties analytical estimate.
    public static final List<Integer> DEBUG_ORACLE_CLOSURE_SIZES =
            Collections.synchronizedList(new ArrayList<>());

    private final TransactionIdGenerator idGen;
    // D-oracle arm only (SimulatorParameters.oracleClosurePreclaim): the same CprSchema
    // SchemaCprFactory uses for the runtime bookkeeper cascade, injected here so a
    // RANDOM_OPERATIONS-mode transaction's resource set can be expanded to its full transitive
    // closure BEFORE admission instead of discovered hop-by-hop. Null for every non-oracle caller.
    private final CprSchema oracleSchema;
    private int txCounter = 0;

    private static final int UNKNOWN_TERMINAL_ID = -1;
    private static final long PROGRAM_SELECTION_SEED_SALT = 0x9E3779B9L;
    private static final long OPERATION_TYPE_SEED_SALT = 0xD1B54A32D192ED03L;
    /**
     * Salts for the two W1-W4 streams. Both are SEPARATE from {@link #random} on purpose: drawing
     * skew or workload-class values out of the resource stream would shift every existing
     * experiment's draw sequence, so a knob nobody enabled would still change their numbers.
     */
    private static final long ACCESS_SKEW_SEED_SALT = 0x7F4A7C15B5026F1DL;
    private static final long WORKLOAD_CLASS_SEED_SALT = 0x2B3E8C1D9A47F6E5L;
    /** Odd multiplier, so resource identity cannot cancel against the transaction key. */
    private static final long RESOURCE_MIX_PRIME = 0x2545F4914F6CDD1DL;

    /** Seed for operation-type derivation; see {@link #operationTypeFor}. */
    private final long operationTypeSeed;

    public TransactionFactory(TransactionIdGenerator idGen) {
        this(idGen, null);
    }

    public TransactionFactory(TransactionIdGenerator idGen, Long seed) {
        this(idGen, seed, null);
    }

    public TransactionFactory(TransactionIdGenerator idGen, Long seed, CprSchema oracleSchema) {
        this.idGen = idGen;
        this.oracleSchema = oracleSchema;
        // If seed is provided, use it for deterministic sampling; otherwise use System.nanoTime()
        this.random = new Random(seed != null ? seed : System.nanoTime());
        // Separate stream for program selection so changing the program mix doesn't perturb resource draws
        this.programSelectionRandom = new Random(seed != null ? seed ^ PROGRAM_SELECTION_SEED_SALT : System.nanoTime());
        this.operationTypeSeed = seed != null ? seed ^ OPERATION_TYPE_SEED_SALT : System.nanoTime();
        // Well19937c matches CprSchemaBuilder.sampleBinomial's generator choice.
        this.accessSkewRandom = new Well19937c(
                seed != null ? seed ^ ACCESS_SKEW_SEED_SALT : System.nanoTime());
        this.workloadClassRandom = new Random(
                seed != null ? seed ^ WORKLOAD_CLASS_SEED_SALT : System.nanoTime());
    }

    /**
     * Zipf({@code theta}) over instance ids {@code 1..poolSize}, built once per pool size.
     *
     * <p>{@code ZipfDistribution} is 1-based, which is exactly the instance-id range
     * {@link Model#resourceAt} produces, so no offset arithmetic is needed - and the absence of
     * it is the point: an off-by-one would produce ids that name no resource while every count-
     * and distinctness-based check still passed.
     */
    private ZipfDistribution zipfFor(int poolSize, double theta) {
        return zipfByPoolSize.computeIfAbsent(poolSize,
                n -> new ZipfDistribution(accessSkewRandom, n, theta));
    }

    /**
     * {@code count} distinct resources from {@code model}, instance ids drawn Zipf({@code theta})
     * and properties drawn uniformly.
     *
     * <p><b>Distinctness is on the {@code Resource} pair, not on the instance id.</b> Two
     * properties of one instance are different resources, and letting a hot instance contribute
     * several of them is precisely what skew should do - deduping on the id instead would cap a
     * footprint at one resource per instance and flatten the very effect being measured.
     *
     * <p>The rejection loop is bounded because at high theta and large {@code count} the duplicate
     * rate gets severe; on exhaustion it falls back to a uniform fill over the remaining
     * resources, so a footprint is always the requested size.
     */
    /**
     * The WRITE subset of a {@link WorkloadMix#TWO_CLASS} transaction's
     * seed resources, or an empty set if this transaction is drawn as read-only.
     *
     * <p>Two draws from {@link #workloadClassRandom}: the class, then - for an update transaction -
     * which {@code ceil(n/2)} of the {@code n} seed resources are writes, by a partial
     * Fisher-Yates over indices. <b>Positions are shuffled, not prefixed</b>, so write-ness is
     * uncorrelated with a resource's position in the footprint; a prefix would put every write on
     * the low-ordinal resources and interact with both lock ordering and any skew on the sampler.
     *
     * <p>{@code n} is the REALISED seed count, which can be below the configured transaction size
     * when the sampler's dedup collapses resources. Taking {@code ceil} of the realised count
     * keeps the read/write split balanced per transaction rather than drifting with dedup, which
     * is why {@code writes_per_txn_mean} is reported rather than assumed to be {@code ceil(k/2)}.
     */
    private Set<Resource> drawTwoClassWriteSet(Set<Resource> seedResources, double readOnlyFraction) {
        if (workloadClassRandom.nextDouble() < readOnlyFraction) {
            return Collections.emptySet(); // read-only class: every operation is a READ
        }
        List<Resource> seeds = new ArrayList<>(seedResources);
        int n = seeds.size();
        int writeCount = (n + 1) / 2; // ceil(n/2)
        if (writeCount >= n) {
            return new LinkedHashSet<>(seeds);
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Set<Resource> writes = new LinkedHashSet<>(writeCount * 2);
        for (int i = 0; i < writeCount; i++) {
            int j = i + workloadClassRandom.nextInt(n - i);
            int tmp = idx[i];
            idx[i] = idx[j];
            idx[j] = tmp;
            writes.add(seeds.get(idx[i]));
        }
        return writes;
    }

    private List<Resource> pickZipfSkewedUnique(Model model, int count, double theta) {
        List<Resource> modelResources = model.getOwnedResources();
        if (count >= modelResources.size()) {
            return modelResources;
        }
        List<Property> properties = model.properties();
        ZipfDistribution zipf = zipfFor(model.instancePoolSize(), theta);

        Set<Resource> picked = new LinkedHashSet<>(count * 2);
        int attempts = 0;
        int maxAttempts = 20 * count;
        while (picked.size() < count && attempts < maxAttempts) {
            attempts++;
            int instanceId = zipf.sample(); // 1..instancePoolSize
            Property property = properties.get(random.nextInt(properties.size()));
            picked.add(new Resource(instanceId, property));
        }
        if (picked.size() < count) {
            // Uniform top-up. Uses `random`, the same stream the UNIFORM path draws from, so the
            // fallback is the ordinary uniform sampler rather than a third behaviour.
            for (Resource candidate : pickRandomUnique(modelResources, count)) {
                if (picked.size() >= count) {
                    break;
                }
                picked.add(candidate);
            }
        }
        return new ArrayList<>(picked);
    }

    /**
     * READ/WRITE for one seed resource of one transaction. A pure function of
     * {@code (resource identity, transaction identity, seed)} - deliberately NOT a draw from any
     * RNG stream.
     *
     * <p><b>Why not a stream.</b> The D-oracle arm expands each transaction to its full CPR closure
     * (~43-129 resources at k=25-50) while the dynamic arm keeps only its {@code kInitial} seeds. A
     * sequential draw would therefore consume a different number of values per transaction in each
     * arm and desynchronise after the first one, giving every subsequent transaction a different
     * type mix per arm - while a per-transaction seed-set assertion still passed. Stateless hashing
     * has no ordering dependence at all, so the two arms agree by construction rather than by luck,
     * and a restart cannot re-derive a different answer.
     *
     * <p><b>Why the transaction component is the seed-set key.</b> Hashing resource identity alone
     * would make each resource permanently read-only or write-only for a whole run, partitioning
     * the resource space by mode. That is not a neutral simplification: a permanently-READ resource
     * never receives an X request, so the "co-readers accumulate, per-request denial probability
     * rises with holder count" interaction that per-operation modes exist to study becomes
     * unobservable. Counters are unusable as the transaction component - {@code restartTransaction}
     * advances {@code idGen} and {@code txCounter}, and the dynamic arm restarts far more often
     * than oracle, so any counter diverges between arms after the first abort.
     * {@code seedResourceTraceKey} is a content key over the pre-expansion seed set, already
     * verified identical across arms in generation order.
     */
    private OperationType operationTypeFor(Resource resource, String txnKey, double writeFraction) {
        long mixed = mix64(resource.hashCode() * RESOURCE_MIX_PRIME
                ^ (long) txnKey.hashCode()
                ^ operationTypeSeed);
        // Top 53 bits to a double in [0,1), the same construction Random.nextDouble() uses.
        double u = (mixed >>> 11) * 0x1.0p-53;
        return u < writeFraction ? OperationType.WRITE : OperationType.READ;
    }

    /** SplitMix64 finaliser. {@link Resource#hashCode()} and {@link String#hashCode()} are both
     * built on JLS-specified {@code String.hashCode}, so this is stable across JV and JDKs -
     * which is the whole point: a workload that changes between runs is not a workload. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // Factory creates only a Transaction, never a group. Group is provided by
    // caller.
    public Transaction createTransaction(SimulatorConfig config, TransactionGroup group) {
        return createTransaction(config, group, UNKNOWN_TERMINAL_ID);
    }

    public Transaction createTransaction(SimulatorConfig config, TransactionGroup group, int sourceTerminalId) {
        if (config.transactionGenerationMode == TransactionGenerationMode.TRANSACTION_PROGRAMS) {
            return createProgramTransaction(config, group, sourceTerminalId);
        }
        return createRandomOperationsTransaction(config, group, sourceTerminalId);
    }

    private Transaction createRandomOperationsTransaction(SimulatorConfig config, TransactionGroup group, int sourceTerminalId) {
        // No read-only flag in the ID: anything computable here is parity over txCounter,
        // unrelated to the operations this method is about to synthesise, so at
        // writeFraction = 1.0 it would tag half of an all-WRITE workload "-RO".
        // Transaction.isReadOnly() derives it from the operations instead.
        String id = idGen.nextId();
        long timestamp = idGen.nextTimestamp();
        txCounter++;

        // Determine transaction size based on config
        int numModels;
        int numResources;

        numModels = 1 + random.nextInt(Math.max(1, config.maxTransactionModels));
        numResources = sampleTransactionResourceCount(config);

        // Step 1: Pick models
        // Draw only from models the workload is allowed to write. Null/empty = the full pool,
        // so every existing configuration is untouched. V1 pins this to the emitting model:
        // PostgreSQL's parent samples all k ids from model_a, while an unpinned simulator spreads
        // its footprint across both models, leaving only part of it in the model that emits CPRs.
        // That is not merely a mean mismatch (k*f/2 vs k*f) -- with the parent HOLDING resources in
        // the target model, CUs aimed at parent-held resources get dropped by
        // BookkeeperCreationCoordinator's visited set, a mechanism PostgreSQL has no counterpart
        // for and one that grows with f, i.e. along the axis V1 sweeps.
        List<Model> drawPool = config.transactionModelPool();
        List<Model> selectedModels = pickRandomUnique(drawPool, numModels);

        // Step 2: Pick resources from each model (SEQUENTIAL or RANDOM based on config)
        List<Resource> selectedResources = new ArrayList<>();
        // Divide by the models actually SELECTED, not the count drawn. pickRandomUnique clamps to
        // the pool size, so when maxTransactionModels exceeds totalModels the drawn count is larger
        // than the loop below can consume - and dividing by it left the surplus shares unallocated.
        // constantTransactionSize(8) with a 1-model pool and the default maxTransactionModels=5
        // produced 8/4/3/2/2 resources depending on the draw (mean 3.8), silently ignoring the
        // size the caller asked for.
        int effectiveModels = Math.max(1, selectedModels.size());
        int resourcesPerModel = numResources / effectiveModels;
        int leftover = numResources % effectiveModels;

        if (config.useRandomResourceSelection) {
            // RANDOM: Pick random unique resources (not sequential) - reduces spatial lock conflicts
            for (int i = 0; i < selectedModels.size(); i++) {
                Model model = selectedModels.get(i);
                int toTake = resourcesPerModel + (i < leftover ? 1 : 0);

                List<Resource> modelResources = model.getOwnedResources();
                if (modelResources.isEmpty()) continue;

                // theta == 0.0 falls through to pickRandomUnique VERBATIM rather than to a
                // Zipf(0) draw. Zipf(0) is uniform in distribution, but it would consume a
                // different RNG sequence - so this is what makes theta=0 BIT-IDENTICAL to the
                // historical path, not merely equal in distribution, and therefore usable as an
                // experiment's leftmost point and as a regression anchor.
                boolean skewed = config.accessDistribution
                        == ResourceAccessDistribution.ZIPF
                        && config.zipfTheta > 0.0;
                List<Resource> randomResources = skewed
                        ? pickZipfSkewedUnique(model, toTake, config.zipfTheta)
                        : pickRandomUnique(modelResources, toTake);
                selectedResources.addAll(randomResources);
            }
        } else {
            // SEQUENTIAL: Pick contiguous block of resources (original behavior)
            // Each model owns a contiguous block of resources, and transactions should
            // access sequential resources within each model's block (not random scatter)
            for (int i = 0; i < selectedModels.size(); i++) {
                Model model = selectedModels.get(i);
                int toTake = resourcesPerModel + (i < leftover ? 1 : 0);

                // Pick a random starting point within this model's resources
                List<Resource> modelResources = model.getOwnedResources();
                if (modelResources.isEmpty()) continue;

                int startIdx = random.nextInt(modelResources.size());

                // Take sequential resources starting from that point
                for (int j = 0; j < toTake && j < modelResources.size(); j++) {
                    int idx = (startIdx + j) % modelResources.size();
                    selectedResources.add(modelResources.get(idx));
                }
            }
        }

        if (DEBUG_RECORD_SEED_TRACE) {
            DEBUG_SEED_TRACE.add(seedResourceTraceKey(selectedResources));
        }

        // Captured BEFORE expansion: which resources are SEEDS (hash-typed) versus closure-derived
        // (all-WRITE). Both arms must agree on this split. The dynamic arm never expands here - its
        // closure resources arrive later as CU resources, which are all-WRITE by cuOperationType -
        // so typing oracle's closure resources by hash instead would leave the two arms with
        // different mode mixes over an identical resource set, a contention difference unrelated to
        // closure knowledge, which is the one variable the oracle/dynamic comparison isolates.
        Set<Resource> seedResources = new LinkedHashSet<>(selectedResources);
        String txnKey = seedResourceTraceKey(selectedResources);

        // SEED TYPES ARE DRAWN BEFORE EXPANSION, because expansion now depends on them.
        //
        // Its position relative to the oracle expansion is RNG-neutral in both directions:
        // drawTwoClassWriteSet draws from workloadClassRandom, expansion draws from nothing at all
        // (it is a pure CprSchema lookup), and txnKey is computed above from the PRE-expansion
        // seed list. So the triggered arm is bit-identical either way and the committed C2PL
        // campaign data stays valid - asserted, not assumed, by the unchanged
        // fixtures in OracleClosureOperationOrderTest's triggered leg.
        //
        // TWO_CLASS assigns the SEED resources' types by transaction class rather than per
        // resource, so it needs the whole seed set up front - unlike operationTypeFor, which is a
        // pure per-resource function. Null under PER_OPERATION.
        Set<Resource> twoClassWrites = config.workloadMix == WorkloadMix.TWO_CLASS
                ? drawTwoClassWriteSet(seedResources, config.readOnlyTransactionFraction)
                : null;

        // D-oracle arm: expand the sampled seed resources to their full CPR transitive closure
        // now, atomically, instead of leaving discovery to the runtime bookkeeper cascade.
        //
        // EXPANDED FROM THE CPR SOURCES ONLY, not from every seed. SchemaCprFactory under
        // writeOnlyCprSources fires bookkeepers only from resources the parent WRITES, so expanding
        // from a READ seed inlines a closure the triggered arm never reaches and the two arms stop
        // touching the same resources. Under the campaign's TWO_CLASS mix at
        // readOnlyTransactionFraction = 0.5 that roughly DOUBLED the inlined footprint: half the
        // transactions are entirely read-only and spawn no CU at all, yet still carried a full
        // closure here. It moves throughput the way an "inlining is expensive" finding would, which
        // is why it needed a test that fails on it (OracleClosureCprSourceParityTest) rather than a
        // plausible-looking number.
        if (config.oracleClosurePreclaim && oracleSchema != null) {
            selectedResources = expandToOracleClosure(selectedResources,
                    cprSourceSeeds(selectedResources, twoClassWrites, txnKey, config),
                    config.oracleClosurePlacement);
            if (DEBUG_RECORD_SEED_TRACE) {
                DEBUG_ORACLE_CLOSURE_SIZES.add(selectedResources.size());
            }
        }

        // One operation per resource, in resource order. A closure target that coincides with a
        // seed resource is deduped into the seed prefix by expandToOracleClosure's LinkedHashSet
        // and is therefore a seed in BOTH arms - symmetric, and the right call.
        List<Operation> operations = new ArrayList<>(selectedResources.size());
        for (Resource resource : selectedResources) {
            OperationType type;
            if (!seedResources.contains(resource)) {
                type = config.cuOperationType; // closure-derived, both arms
            } else if (twoClassWrites != null) {
                type = twoClassWrites.contains(resource) ? OperationType.WRITE : OperationType.READ;
            } else {
                type = operationTypeFor(resource, txnKey, config.writeFraction);
            }
            operations.add(new Operation(type, resource));
        }

        // Create parent transaction within the provided group. Operations are attached once, here,
        // and never re-derived: restartTransaction() copies them verbatim, so a retry keeps the
        // exact type mix of its original attempt.
        Transaction txn = new Transaction(id, timestamp, selectedResources, group, sourceTerminalId,
                operations, null /* programName - not program-generated */);
        txn.initializeState();  // REQUIRED: Move transaction out of UNINITIALIZED
        return txn;
    }

    /**
     * D-oracle arm: full CPR transitive closure of {@code seedResources}, under the same
     * identity correspondence {@code SchemaCprFactory} uses at runtime (same instanceId, source
     * property -> target property) - so this converges to exactly the resource set the dynamic
     * arm's hop-by-hop cascade would eventually reach, just committed in one atomic set instead
     * of discovered incrementally.
     */
    /**
     * Canonical (sorted, dedup-stable) string key for a seed-resource SET, comparable across two
     * independent runs/JV.
     *
     * <p><b>It sorts, so it is order-blind by construction.</b> Two resource lists with the same
     * members in different orders produce the same key. Any digest built on this therefore proves
     * <em>set equality and nothing more</em> - never that generation produced the same
     * <em>sequence</em>. That distinction is not academic: a {@code Set.copyOf} closure-ordering
     * bug hid behind it, closure contents and sizes always correct while closure ORDER varied per
     * JVM - so this key matched on every run while the D-oracle arm's lock-acquisition sequence,
     * and its throughput, differed by ~12%. A determinism check that reduces to this key cannot
     * see that class of bug. To test ordering, compare the sequences themselves (see
     * {@code EngineEventTrace}).
     *
     * <p>This is a load-bearing workload-identity function rather than a diagnostic: operation
     * synthesis mixes it into per-operation type derivation, so the oracle and dynamic arms assign
     * the same type to the same seed resource without depending on generation order. Changing it
     * invalidates comparisons against any already-generated data.
     */
    private static String seedResourceTraceKey(List<Resource> resources) {
        return resources.stream()
                .map(r -> r.instanceId() + "@" + r.property())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private List<Resource> expandToOracleClosure(List<Resource> seedResources,
            Set<Resource> cprSources, OracleClosurePlacement placement) {
        // BOTH BRANCHES PRODUCE THE SAME SET, and that is a requirement rather than a coincidence:
        // AT_END seeds the LinkedHashSet with every seed and then appends closures, INTERLEAVED
        // walks the seeds in order emitting each seed followed by its own closure. Every seed is
        // added either way, every source's closure is added either way, and the LinkedHashSet
        // dedupes either way - so the two differ in ORDER alone and work per logical transaction is
        // identical. OracleClosurePlacementTest asserts that set equality directly, because it is
        // what makes a throughput difference between the two arms a timing result.
        Set<Resource> closure = new LinkedHashSet<>();
        if (placement == OracleClosurePlacement.INTERLEAVED) {
            for (Resource seed : seedResources) {
                closure.add(seed);
                if (!cprSources.contains(seed)) {
                    continue;
                }
                // Directly after its source. A target that happens to BE a later seed is emitted
                // here and skipped when that seed comes round, so it moves earlier in the list -
                // the set is unchanged, and the "repair follows the write that caused it" property
                // still holds for it.
                for (Property target : oracleSchema.closure(seed.property())) {
                    closure.add(new Resource(seed.instanceId(), target));
                }
            }
            return new ArrayList<>(closure);
        }
        // AT_END. EVERY seed stays in the prefix, whether or not it is a CPR source - the parent
        // reads or writes all of them either way. Only which seeds CONTRIBUTE a closure is
        // filtered, so the prefix-then-closure ordering asserted by
        // OracleClosureOperationOrderTest is unchanged.
        closure.addAll(seedResources);
        for (Resource seed : seedResources) {
            if (!cprSources.contains(seed)) {
                continue;
            }
            for (Property target : oracleSchema.closure(seed.property())) {
                closure.add(new Resource(seed.instanceId(), target));
            }
        }
        return new ArrayList<>(closure);
    }

    /**
     * Which seeds the runtime cascade would fire bookkeepers from - the set the inlined arm must
     * expand from if the two arms are to touch the same resources.
     *
     * <p>Mirrors {@code SchemaCprFactory.createFrom}'s source selection exactly: under
     * {@code writeOnlyCprSources} only WRITE resources are sources, otherwise every resource is.
     * The two must be read together; a change to either without the other silently re-opens the
     * footprint asymmetry.
     *
     * <p>Both branches are pure functions of already-drawn state - {@code twoClassWrites} is
     * drawn by the caller and {@code operationTypeFor} is a stateless hash - so this consumes no
     * random draw and cannot desynchronise the arms' seed streams.
     */
    private Set<Resource> cprSourceSeeds(List<Resource> seeds, Set<Resource> twoClassWrites,
            String txnKey, SimulatorConfig config) {
        if (!config.writeOnlyCprSources) {
            return new LinkedHashSet<>(seeds);
        }
        Set<Resource> sources = new LinkedHashSet<>();
        for (Resource seed : seeds) {
            OperationType type = twoClassWrites != null
                    ? (twoClassWrites.contains(seed) ? OperationType.WRITE : OperationType.READ)
                    : operationTypeFor(seed, txnKey, config.writeFraction);
            if (type == OperationType.WRITE) {
                sources.add(seed);
            }
        }
        return sources;
    }

    private Transaction createProgramTransaction(SimulatorConfig config, TransactionGroup group, int sourceTerminalId) {
        TransactionProgram program = WeightedProgramSelector.select(config.programPool, programSelectionRandom);
        Model model = program.model();
        int instancePoolSize = model.instancePoolSize();

        // Group variables by declared property, batching the draw per group so distinctness is
        // guaranteed within it. Distinctness
        // is per PROPERTY, not per transaction - two variables on different properties may
        // legitimately share an instance id (e.g. account17.balance and account17.limit).
        Map<Property, List<String>> variablesByProperty = new LinkedHashMap<>();
        for (String variable : program.variables()) {
            variablesByProperty.computeIfAbsent(program.variableProperties().get(variable), p -> new ArrayList<>())
                    .add(variable);
        }

        List<Integer> instanceIdPool = instanceIdPool(instancePoolSize);
        Map<String, Resource> variableResource = new LinkedHashMap<>();
        for (Map.Entry<Property, List<String>> entry : variablesByProperty.entrySet()) {
            Property property = entry.getKey();
            List<String> propertyVariables = entry.getValue();
            List<Integer> instanceIds = pickRandomUnique(instanceIdPool, propertyVariables.size());
            for (int i = 0; i < propertyVariables.size(); i++) {
                variableResource.put(propertyVariables.get(i), new Resource(instanceIds.get(i), property));
            }
        }

        // Resolve steps in program order; operations may repeat a resource when steps share a variable.
        List<Operation> operations = new ArrayList<>(program.steps().size());
        for (Step step : program.steps()) {
            Resource resource = variableResource.get(step.getVariable());
            operations.add(new Operation(step.getType(), resource));
        }

        // Distinct resources, one per declared variable - keeps IOModel/locking cost model
        // consistent with the dedup invariant RANDOM_OPERATIONS mode already guarantees.
        List<Resource> resources = new ArrayList<>(program.variables().size());
        for (String variable : program.variables()) {
            resources.add(variableResource.get(variable));
        }

        String id = idGen.nextId();
        long timestamp = idGen.nextTimestamp();
        Transaction txn = new Transaction(id, timestamp, resources, group, sourceTerminalId, operations,
                program.id());
        txn.initializeState();  // REQUIRED: Move transaction out of UNINITIALIZED
        return txn;
    }

    private List<Integer> instanceIdPool(int instancePoolSize) {
        List<Integer> pool = new ArrayList<>(instancePoolSize);
        for (int i = 1; i <= instancePoolSize; i++) {
            pool.add(i);
        }
        return pool;
    }

    private <T> List<T> pickRandomUnique(List<T> pool, int count) {
        int size = pool.size();
        if (count >= size)
            return pool;

        // Dynamically allocate index buffer based on pool size
        int[] indexBuffer = new int[size];
        for (int i = 0; i < size; i++)
            indexBuffer[i] = i;

        for (int i = 0; i < count; i++) {
            int j = i + random.nextInt(size - i);
            int tmp = indexBuffer[i];
            indexBuffer[i] = indexBuffer[j];
            indexBuffer[j] = tmp;
        }

        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(pool.get(indexBuffer[i]));
        }

        return result;
    }

    private int sampleTransactionResourceCount(SimulatorConfig config) {
        if (config.useConstantTransactionSize || config.transactionSizeProfile == TransactionSizeProfile.CONSTANT) {
            return config.constantTransactionSize;
        }
        if (config.transactionSizeProfile == TransactionSizeProfile.BIMODAL) {
            boolean chooseLarge = random.nextDouble() < config.largeTransactionProbability;
            return chooseLarge ? config.largeTransactionSize : config.smallTransactionSize;
        }
        return 1 + random.nextInt(Math.max(1, config.maxTransactionResources));
    }

    /**
     * Restart a transaction with the same parameters (models and resources).
     * Creates a new transaction ID and a new transaction group.
     * Does NOT copy metrics, state, or bookkeepers - those start fresh.
     */
    public Transaction restartTransaction(Transaction original) {
        // Fresh ID from the same counter, so uniqueness is preserved. Nothing to carry forward:
        // the ID no longer encodes read-only-ness, and the restarted transaction copies the
        // original's operations verbatim, so Transaction.isReadOnly() gives the same answer.
        String newId = idGen.nextId();
        Long timestamp = idGen.nextTimestamp();
        txCounter++;

        // Create new group
        TransactionGroup newGroup = new TransactionGroup();

        // Create new transaction with same models and resources (and, for program-generated
        // transactions, the same operations/programName - copied directly, not recomputed,
        // so resources and operations can't drift apart after a retry)
        Transaction newTxn;
        if (!original.getOperations().isEmpty()) {
            newTxn = new Transaction(
                newId,
                timestamp,
                new ArrayList<>(original.getResources()),
                newGroup,
                UNKNOWN_TERMINAL_ID,
                new ArrayList<>(original.getOperations()),
                original.getProgramName()
            );
        } else {
            newTxn = new Transaction(
                newId,
                timestamp,
                new ArrayList<>(original.getResources()),        // Copy list
                newGroup
            );
        }
        newTxn.initializeState();  // REQUIRED: Move transaction out of UNINITIALIZED

        // Copy retry count from original and increment for this abort
        // This preserves exponential backoff across restarts
        int originalRetryCount = original.getLockRetryCount();
        for (int i = 0; i <= originalRetryCount; i++) {
            newTxn.incrementLockRetryCount();
        }

        return newTxn;
    }
}
