package de.tum.tssimulator.bookkeeping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.PropertyLink;

/**
 * {@link BookkeeperFactory} that triggers bookkeepers by consulting a precomputed
 * {@link CprSchema}: for every resource a parent transaction <em>writes</em>, every
 * {@link PropertyLink} outgoing from that resource's property fires one bookkeeper, targeting
 * the same instance id on the link's target property (identity correspondence - same instance
 * id, source to target, always).
 *
 * <p>Read-only parents produce nothing: {@code CprSchema} is a static, property-level relation,
 * and a read never changes the source value a CPR is meant to keep in sync. Exception:
 * {@code RANDOM_OPERATIONS}-mode transactions (and any other transaction built without typed
 * {@code Operation}s at all) carry no READ/WRITE distinction to filter on - {@code getOperations()}
 * is simply empty - so every resource on the transaction is treated as a source, matching
 * {@code SimpleCPRFactory}'s undifferentiated behavior for that mode. Making
 * {@code RANDOM_OPERATIONS} generation itself operation-aware is out of scope for this plan;
 * this fallback only keeps its existing triggering behavior from silently going to zero.
 */
public final class SchemaCprFactory implements BookkeeperFactory {
    private final CprSchema schema;
    private final ConcurrencyModel concurrencyMode;
    private final boolean writeOnlyCprSources;
    private final OperationType cuOperationType;

    /** Legacy two-arg form: WRITE-filtered sources, WRITE C - the behaviour this class had
     * before the flags existed. */
    public SchemaCprFactory(CprSchema schema, ConcurrencyModel concurrencyMode) {
        this(schema, concurrencyMode, true, OperationType.WRITE);
    }

    public SchemaCprFactory(CprSchema schema, ConcurrencyModel concurrencyMode,
            boolean writeOnlyCprSources, OperationType cuOperationType) {
        this.schema = schema;
        this.concurrencyMode = concurrencyMode;
        this.writeOnlyCprSources = writeOnlyCprSources;
        this.cuOperationType = cuOperationType != null ? cuOperationType : OperationType.WRITE;
    }

    @Override
    public List<BookkeeperTransaction> createFrom(Transaction parent) {
        List<BookkeeperTransaction> created = new ArrayList<>();
        int bookkeeperIndex = 0;

        // Deduped, order-stable for determinism.
        Set<Resource> sourceResources = new LinkedHashSet<>();
        if (!writeOnlyCprSources || parent.getOperations().isEmpty()) {
            // Every resource is a candidate source. Gated on an EXPLICIT flag rather than on
            // "does this transaction have operations": operation synthesis makes operations always
            // present under RANDOM_OPERATIONS, so an isEmpty() test would silently start
            // restricting sources the moment writeFraction dropped below 1.0 - coupling CPR
            // fan-out to a parameter meant to control lock modes. The isEmpty() arm covers only
            // genuinely operation-less transactions, which have nothing to filter on.
            sourceResources.addAll(parent.getResources());
        } else {
            for (Operation op : parent.getOperations()) {
                if (op.getType() == OperationType.WRITE) {
                    sourceResources.add(op.getResource());
                }
            }
        }

        for (Resource resource : sourceResources) {
            for (PropertyLink link : schema.linksFrom(resource.property())) {
                Resource target = new Resource(resource.instanceId(), link.target()); // identity correspondence
                BookkeeperTransaction bk = (concurrencyMode == ConcurrencyModel.BASE)
                        ? BookkeeperTransaction.createBase(parent, List.of(target), BookkeepingReason.MODEL_INCONSISTENCY,
                                link.target().model(), bookkeeperIndex++, cuOperationType)
                        : BookkeeperTransaction.createAcid(parent, List.of(target), BookkeepingReason.MODEL_INCONSISTENCY,
                                link.target().model(), bookkeeperIndex++, cuOperationType);
                created.add(bk); // one bookkeeper per link - preserves the single-resource invariant DirtyKeyUtil relies on
            }
        }
        return created;
    }
}
