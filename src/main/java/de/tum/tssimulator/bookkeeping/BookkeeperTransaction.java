package de.tum.tssimulator.bookkeeping;

import java.util.List;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;

public abstract class BookkeeperTransaction extends Transaction {
    private final Transaction parent;
    private final BookkeepingReason reason;
    private final int depth;
    private final Model targetModel;  // The model this bookkeeper targets for reconciliation

    public BookkeeperTransaction(Transaction parent, List<Resource> resources,
                                 BookkeepingReason reason, Model targetModel,
                                 int index) {
        this(parent, resources, reason, targetModel, index, OperationType.WRITE);
    }

    public BookkeeperTransaction(Transaction parent, List<Resource> resources,
                                 BookkeepingReason reason, Model targetModel,
                                 int index, OperationType cuOperationType) {
        // Attach an explicit WRITE operation per resource: a bookkeeper with no typed
        // operations produces nothing once triggering filters on WRITE (SchemaCprFactory),
        // which would kill a cascade after its first hop. Written as resources.stream().map(...)
        // rather than assuming a single resource, so this stays correct independent of the
        // single-resource invariant - that invariant is enforced downstream by
        // DirtyKeyUtil.fromBookkeeper, not by this constructor.
        super("BK" + index + "-" + parent.getId(), parent.getTimestamp(), resources, parent.group,
                -1 /* Transaction.UNKNOWN_TERMINAL_ID - bookkeepers have no source terminal */,
                resources.stream()
                        .map(r -> new Operation(cuOperationType == null ? OperationType.WRITE : cuOperationType, r))
                        .toList(),
                null /* programName - bookkeepers aren't program-generated */);
        this.parent = parent;
        this.reason = reason;
        this.depth = (parent instanceof BookkeeperTransaction)
            ? ((BookkeeperTransaction) parent).getDepth() + 1
            : 1;
        this.targetModel = targetModel;
        // Constructor leaves in UNINITIALIZED - factory will call initializeState()
    }

    @Override
    protected boolean countsTowardLimit() {
        return false;  // Bookkeepers don't count toward transaction limit
    }

    // Subclasses override to fire appropriate creation event (CREATED vs BACKGROUND_CREATED)
    // initializeState() is final in base Transaction class - cannot be overridden
    @Override
    protected abstract TransactionEvent creationEvent();
    
    // Static factory methods (handle two-phase initialization safely)
    public static BookkeeperTransaction createAcid(Transaction parent, List<Resource> resources,
                                                    BookkeepingReason reason, Model targetModel, 
                                                    int index) {
        return createAcid(parent, resources, reason, targetModel, index, OperationType.WRITE);
    }

    public static BookkeeperTransaction createAcid(Transaction parent, List<Resource> resources,
                                                    BookkeepingReason reason, Model targetModel,
                                                    int index, OperationType cuOperationType) {
        AcidBookkeeperTransaction bk = new AcidBookkeeperTransaction(
            parent, resources, reason, targetModel, index, cuOperationType);
        bk.initializeState();  // Safe: subclass fully constructed, guard always runs (final method)
        return bk;
    }
    
    public static BookkeeperTransaction createBase(Transaction parent, List<Resource> resources,
                                                    BookkeepingReason reason, Model targetModel, 
                                                    int index) {
        return createBase(parent, resources, reason, targetModel, index, OperationType.WRITE);
    }

    public static BookkeeperTransaction createBase(Transaction parent, List<Resource> resources,
                                                    BookkeepingReason reason, Model targetModel,
                                                    int index, OperationType cuOperationType) {
        BaseBookkeeperTransaction bk = new BaseBookkeeperTransaction(
            parent, resources, reason, targetModel, index, cuOperationType);
        bk.initializeState();  // Safe: subclass fully constructed, guard always runs (final method)
        return bk;
    }
    
    public int getDepth() {
        return depth;
    }
    
    public Transaction getParent() {
        return parent;
    }
    
    public Model getTargetModel() {
        return targetModel;
    }

    public boolean usesBackgroundWorkLifecycle() {
        return false;
    }
    
    // prepareForReconciliation() DELETED - QueueManager handles phase preparation now

    public Transaction getRoot() {
        Transaction current = this.parent;
        while (current instanceof BookkeeperTransaction) {
            current = ((BookkeeperTransaction) current).parent;
        }
        return current;
    }
}
