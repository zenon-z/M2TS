package de.tum.tssimulator.locking.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.util.Map;

import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;

/**
 * Derives the lock ids, and the mode per lock id, that a transaction needs.
 *
 * <p>Separate from {@code ACIDAdmissionController} so the derivation is testable on its own.
 * While it lived there as two private methods, a rename silently made a bookkeeper report its
 * PARENT's lock count: it compiled, no test covered it, and the value it corrupts
 * ({@code Transaction.setLockCountHeld}) is the numerator of the mean-lock-hold metric that the
 * operation-level ladder's hold-time criterion depends on. A derivation this load-bearing should
 * not live only inside a 600-line controller.
 *
 * <p>Everything here is order-stable: lock ids are sorted, and the mode map is a
 * {@link LinkedHashMap} built in that order. Iteration order IS acquisition order downstream, and
 * the sort is what makes it deterministic.
 */
public final class OperationLockModes {

    private OperationLockModes() {
    }

    /** Distinct lock ids for {@code txn}'s resources, sorted for deterministic acquisition order. */
    public static List<String> lockIds(Transaction txn, LockGranularityMapper mapper) {
        Set<String> unique = new LinkedHashSet<>();
        for (Resource resource : txn.getResources()) {
            unique.add(lockIdOf(resource, mapper));
        }
        List<String> lockIds = new ArrayList<>(unique);
        Collections.sort(lockIds);
        return lockIds;
    }

    /**
     * {@link #lockIds} as a mode map with every entry EXCLUSIVE.
     *
     * <p>This is the pre-operation-modes behaviour, kept explicit rather than implicit: the preclaim
     * path is mode-blind (it tests only whether a lock has a foreign holder), so EXCLUSIVE here
     * reproduces it exactly. Folding real per-operation modes is a separate, behaviour-changing
     * step; keeping the two apart is what makes a movement in numbers there attributable.
     */
    /**
     * Lock id -> mode folded from the transaction's operations: READ wants SHARED, WRITE wants
     * EXCLUSIVE, and where several operations map to the same lock id the STRONGER mode wins.
     *
     * <p>Folding is what lets a transaction have more than one mode. It replaces
     * {@code Transaction.getLockType()}, which returned a single mode for the whole transaction and,
     * for operation-less transactions, guessed it from whether the id contained the substring "RO".
     *
     * <p><b>First-touch order, deliberately NOT sorted.</b> Iteration order here IS lock-acquisition
     * order downstream, and the two protocols use different ones: the C2PL preclaim sorts (see
     * {@link #foldByLockIdSorted}, matching {@code collectRequiredLockIds}' long-standing
     * deadlock-avoidance contract), while the S2PL coordinator acquires in the order the
     * transaction touches its resources. Sorting here silently unified them and moved every S2PL
     * cell of the ladder by -3%..+4% at a workload that was supposed to be unchanged - caught by
     * diffing an EngineEventTrace, where the very first lock request already differed.
     *
     * <p>Operation-less transactions fall back to all-EXCLUSIVE. Nothing at runtime is in that
     * state - both generation modes attach operations, bookkeepers attach one per resource, and
     * restarts copy them verbatim - so this only covers hand-built test fixtures, where the
     * conservative mode is the safe guess.
     */
    public static LinkedHashMap<String, LockType> foldByLockId(Transaction txn, LockGranularityMapper mapper) {
        if (txn.getOperations().isEmpty()) {
            return allExclusive(txn, mapper);
        }
        return foldOperations(txn.getOperations(), mapper);
    }

    /** {@link #foldByLockId} re-ordered by lock id. */
    public static LinkedHashMap<String, LockType> foldByLockIdSorted(Transaction txn, LockGranularityMapper mapper) {
        LinkedHashMap<String, LockType> folded = foldByLockId(txn, mapper);
        List<String> ordered = new ArrayList<>(folded.keySet());
        Collections.sort(ordered);
        LinkedHashMap<String, LockType> sorted = new LinkedHashMap<>();
        for (String lockId : ordered) {
            sorted.put(lockId, folded.get(lockId));
        }
        return sorted;
    }

    /** {@link #foldByLockId}'s rule applied to an arbitrary slice of operations - one batch of an
     * interleaved acquisition. Same first-touch ordering, same stronger-mode-wins fold. */
    public static LinkedHashMap<String, LockType> foldOperations(List<Operation> operations,
            LockGranularityMapper mapper) {
        LinkedHashMap<String, LockType> folded = new LinkedHashMap<>();
        for (Operation op : operations) {
            String lockId = lockIdOf(op.getResource(), mapper);
            LockType wanted = op.getType() == OperationType.WRITE ? LockType.EXCLUSIVE : LockType.SHARED;
            if (folded.get(lockId) != LockType.EXCLUSIVE) {
                folded.put(lockId, wanted);
            }
        }
        return folded;
    }

    private static String lockIdOf(Resource resource, LockGranularityMapper mapper) {
        return mapper != null ? mapper.getLockId(resource) : resource.toString();
    }

    public static LinkedHashMap<String, LockType> allExclusive(Transaction txn, LockGranularityMapper mapper) {
        LinkedHashMap<String, LockType> requested = new LinkedHashMap<>();
        for (String lockId : lockIds(txn, mapper)) {
            requested.put(lockId, LockType.EXCLUSIVE);
        }
        return requested;
    }
}
