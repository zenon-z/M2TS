package test.java.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.BookkeepingReason;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.locking.core.LockGranularityMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for ACID bookkeeper lock inheritance fix.
 * 
 * Verifies that bookkeepers correctly:
 * 1. Inherit locks from parent when resources are a subset
 * 2. Require additional locks when needed
 * 3. Handle cascading bookkeepers correctly
 * 4. Handle partial overlap scenarios
 * 
 * Note: These tests focus on the lock ID comparison logic, not the full admission flow.
 */
public class ACIDBookkeeperLockInheritanceTest {

    // Single model, single property, 100 instances: res(n) reproduces the old "resN" numbering
    // exactly (getOwnedResources().get(n-1) has instanceId == n).
    private static final Model MODEL = new ModelFactory(100).createModels(List.of(1)).get(0);

    // Ordinal n (0-based) -> "lockN" in the fine-grained (totalLocks>=totalResources) path,
    // reproducing this test's original 1-based "resN"->"lockN" hardcoded expectations exactly.
    private static Resource res(int n) {
        return MODEL.getOwnedResources().get(n);
    }

    private LockGranularityMapper granularityMapper;

    @BeforeEach
    void setUp() {
        // Fine-grained locking: 100 resources, 100 locks (one lock per resource)
        int totalLocks = 100;
        granularityMapper = new LockGranularityMapper(totalLocks, List.of(MODEL));
    }
    
    @Test
    void testBookkeeperInheritsParentLocks() {
        // Setup: Parent with [res1, res2]
        Transaction parent = createTransaction("parent1", List.of(res(1), res(2)));
        
        // Setup: Bookkeeper with [res1] (subset of parent)
        BookkeeperTransaction bk = BookkeeperTransaction.createAcid(
            parent, List.of(res(1)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: Bookkeeper locks are subset of parent locks
        Set<String> parentLockIds = parent.getLockIds(granularityMapper);
        Set<String> bookkeeperLockIds = bk.getLockIds(granularityMapper);
        
        assertTrue(parentLockIds.containsAll(bookkeeperLockIds),
            "All bookkeeper locks should be contained in parent locks");
        
        assertEquals(2, parentLockIds.size(), "Parent should need 2 locks");
        assertEquals(1, bookkeeperLockIds.size(), "Bookkeeper should need 1 lock");
    }
    
    @Test
    void testBookkeeperRequiresAdditionalLocks() {
        // Setup: Parent with [res1, res2]
        Transaction parent = createTransaction("parent2", List.of(res(1), res(2)));
        
        // Setup: Bookkeeper with [res3] (NOT subset of parent)
        BookkeeperTransaction bk = BookkeeperTransaction.createAcid(
            parent, List.of(res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: Bookkeeper needs additional locks
        Set<String> parentLockIds = parent.getLockIds(granularityMapper);
        Set<String> bookkeeperLockIds = bk.getLockIds(granularityMapper);
        
        assertFalse(parentLockIds.containsAll(bookkeeperLockIds),
            "Bookkeeper should need locks not held by parent");
        
        // Check specifically for res3 lock
        assertTrue(bookkeeperLockIds.contains("lock3"),
            "Bookkeeper should need lock for res3");
        assertFalse(parentLockIds.contains("lock3"),
            "Parent should not hold lock for res3");
    }
    
    @Test
    void testCascadingBookkeepersRequireLocks() {
        // Setup: Parent with [res1]
        Transaction parent = createTransaction("parent5", List.of(res(1)));
        
        // Setup: Level-1 bookkeeper with [res2]
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Setup: Level-2 bookkeeper (child of bk1) with [res3]
        BookkeeperTransaction bk2 = BookkeeperTransaction.createAcid(
            bk1, List.of(res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: bk2 checks against root parent, not immediate parent
        Transaction root = bk2.getRoot();
        assertEquals(parent, root, "Root should be the original parent, not bk1");
        
        // Verify: Each level needs different locks
        Set<String> rootLockIds = root.getLockIds(granularityMapper);
        Set<String> bk1LockIds = bk1.getLockIds(granularityMapper);
        Set<String> bk2LockIds = bk2.getLockIds(granularityMapper);
        
        // Parent has lock1
        assertEquals(1, rootLockIds.size());
        assertTrue(rootLockIds.contains("lock1"));
        
        // bk1 needs lock2 (not in parent's set)
        assertEquals(1, bk1LockIds.size());
        assertTrue(bk1LockIds.contains("lock2"));
        assertFalse(rootLockIds.containsAll(bk1LockIds),
            "bk1 needs lock2 which parent doesn't hold");
        
        // bk2 needs lock3 (not in parent OR bk1's set)
        assertEquals(1, bk2LockIds.size());
        assertTrue(bk2LockIds.contains("lock3"));
        assertFalse(rootLockIds.containsAll(bk2LockIds),
            "bk2 needs lock3 which root parent doesn't hold");
        assertFalse(bk1LockIds.containsAll(bk2LockIds),
            "bk2 needs lock3 which bk1 doesn't hold");
        
        // Verify: Combined group locks would be {lock1, lock2, lock3}
        Set<String> allGroupLocks = new HashSet<>();
        allGroupLocks.addAll(rootLockIds);
        allGroupLocks.addAll(bk1LockIds);
        assertEquals(2, allGroupLocks.size(), "Group (parent + bk1) holds 2 locks before bk2");
        
        // bk2 needs a lock not held by anyone in the group yet
        assertFalse(allGroupLocks.containsAll(bk2LockIds),
            "bk2 needs lock3 which no group member holds yet");
    }
    
    @Test
    void testBookkeeperInheritsFromSiblingBookkeeper() {
        // This tests the group-level check: if a sibling bookkeeper already holds
        // a required lock, the new bookkeeper should be able to inherit it
        
        // Setup: Parent with [res1]
        Transaction parent = createTransaction("parent_sibling", List.of(res(1)));
        
        // Setup: First bookkeeper with [res2, res3]
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2), res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Add bk1 to the group (simulating it being processed first)
        parent.getGroup().addBookkeeper(bk1);
        
        // Setup: Second bookkeeper with [res3, res4]
        // res3 is already held by bk1 (sibling), so only res4 is additional
        BookkeeperTransaction bk2 = BookkeeperTransaction.createAcid(
            parent, List.of(res(3), res(4)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: Group holds locks for res1 (parent), res2, res3 (bk1)
        Set<String> groupLocks = new HashSet<>();
        groupLocks.addAll(parent.getLockIds(granularityMapper));
        groupLocks.addAll(bk1.getLockIds(granularityMapper));
        
        assertTrue(groupLocks.contains("lock1"), "Group should hold lock1 from parent");
        assertTrue(groupLocks.contains("lock2"), "Group should hold lock2 from bk1");
        assertTrue(groupLocks.contains("lock3"), "Group should hold lock3 from bk1");
        assertFalse(groupLocks.contains("lock4"), "Group doesn't hold lock4 yet");
        
        // Verify: bk2 needs lock3 (already in group) and lock4 (new)
        Set<String> bk2Locks = bk2.getLockIds(granularityMapper);
        assertTrue(bk2Locks.contains("lock3"), "bk2 needs lock3");
        assertTrue(bk2Locks.contains("lock4"), "bk2 needs lock4");
        
        // The key insight: bk2 only needs to acquire lock4 (lock3 inherited from bk1)
        boolean needsLock3 = !groupLocks.contains("lock3");
        boolean needsLock4 = !groupLocks.contains("lock4");
        
        assertFalse(needsLock3, "bk2 should NOT need to acquire lock3 (inherited from bk1)");
        assertTrue(needsLock4, "bk2 SHOULD need to acquire lock4 (not in group)");
    }
    
    @Test
    void testBookkeeperWithPartialOverlap() {
        // Setup: Parent with [res1, res2, res3]
        Transaction parent = createTransaction("parent6", List.of(res(1), res(2), res(3)));
        
        // Setup: Bookkeeper with [res2, res3, res4] (partial overlap)
        BookkeeperTransaction bk = BookkeeperTransaction.createAcid(
            parent, List.of(res(2), res(3), res(4)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: Bookkeeper needs additional locks (res4)
        Set<String> parentLockIds = parent.getLockIds(granularityMapper);
        Set<String> bookkeeperLockIds = bk.getLockIds(granularityMapper);
        
        assertFalse(parentLockIds.containsAll(bookkeeperLockIds),
            "Bookkeeper with partial overlap should need additional locks");
        
        // Parent has lock1, lock2, lock3
        assertTrue(parentLockIds.contains("lock1"));
        assertTrue(parentLockIds.contains("lock2"));
        assertTrue(parentLockIds.contains("lock3"));
        assertFalse(parentLockIds.contains("lock4"));
        
        // Bookkeeper needs lock2, lock3, lock4
        assertTrue(bookkeeperLockIds.contains("lock2"));
        assertTrue(bookkeeperLockIds.contains("lock3"));
        assertTrue(bookkeeperLockIds.contains("lock4"));
        assertFalse(bookkeeperLockIds.contains("lock1"));
    }
    
    @Test
    void testMultipleBookkeepersWithDifferentLocks() {
        // Setup: Parent with [res1]
        Transaction parent = createTransaction("parent7", List.of(res(1)));
        
        // Setup: Three bookkeepers with different resources
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        BookkeeperTransaction bk2 = BookkeeperTransaction.createAcid(
            parent, List.of(res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        BookkeeperTransaction bk3 = BookkeeperTransaction.createAcid(
            parent, List.of(res(4)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: Each bookkeeper needs different locks
        Set<String> parentLockIds = parent.getLockIds(granularityMapper);
        Set<String> bk1LockIds = bk1.getLockIds(granularityMapper);
        Set<String> bk2LockIds = bk2.getLockIds(granularityMapper);
        Set<String> bk3LockIds = bk3.getLockIds(granularityMapper);
        
        // None of the bookkeeper locks are in parent
        assertFalse(parentLockIds.containsAll(bk1LockIds));
        assertFalse(parentLockIds.containsAll(bk2LockIds));
        assertFalse(parentLockIds.containsAll(bk3LockIds));
        
        // All bookkeepers need different locks
        assertTrue(bk1LockIds.contains("lock2"));
        assertTrue(bk2LockIds.contains("lock3"));
        assertTrue(bk3LockIds.contains("lock4"));
    }
    
    @Test
    void testBookkeeperWithSameResourcesAsParent() {
        // Setup: Parent with [res1, res2, res3]
        Transaction parent = createTransaction("parent8", List.of(res(1), res(2), res(3)));
        
        // Setup: Bookkeeper with exact same resources
        BookkeeperTransaction bk = BookkeeperTransaction.createAcid(
            parent, List.of(res(1), res(2), res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: Bookkeeper locks are exactly the same as parent
        Set<String> parentLockIds = parent.getLockIds(granularityMapper);
        Set<String> bookkeeperLockIds = bk.getLockIds(granularityMapper);
        
        assertTrue(parentLockIds.containsAll(bookkeeperLockIds),
            "Bookkeeper with same resources should not need additional locks");
        assertEquals(parentLockIds, bookkeeperLockIds,
            "Lock IDs should be exactly the same");
    }
    
    @Test
    void testCoarseGrainedLocking() {
        // Setup: Coarse-grained locking - all resources map to one lock
        LockGranularityMapper coarseMapper = new LockGranularityMapper(1, List.of(MODEL));
        
        // Setup: Parent with [res1, res2]
        Transaction parent = createTransaction("parent9", List.of(res(1), res(2)));
        
        // Setup: Bookkeeper with [res50, res60]
        BookkeeperTransaction bk = BookkeeperTransaction.createAcid(
            parent, List.of(res(50), res(60)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        
        // Verify: With coarse-grained locking, everything maps to lock0
        Set<String> parentLockIds = parent.getLockIds(coarseMapper);
        Set<String> bookkeeperLockIds = bk.getLockIds(coarseMapper);
        
        assertEquals(1, parentLockIds.size());
        assertEquals(1, bookkeeperLockIds.size());
        assertTrue(parentLockIds.contains("lock0"));
        assertTrue(bookkeeperLockIds.contains("lock0"));
        
        // Verify: Bookkeeper doesn't need additional locks (all share lock0)
        assertTrue(parentLockIds.containsAll(bookkeeperLockIds),
            "With coarse-grained locking, bookkeeper should inherit the single lock");
    }
    
    // Helper method to create transactions
    private Transaction createTransaction(String id, List<Resource> resources) {
        List<Model> models = new ArrayList<>();
        TransactionGroup group = new TransactionGroup(null);
        Transaction txn = new Transaction(id, resources, group);
        txn.initializeState();
        group.setParent(txn);
        return txn;
    }
}
