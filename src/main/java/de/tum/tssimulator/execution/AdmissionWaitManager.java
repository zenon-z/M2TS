package de.tum.tssimulator.execution;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages event-driven admission waiting for C2PL preclaim.
 *
 * When a transaction cannot acquire all locks atomically, it registers
 * as a waiter on the first blocking lock. When that lock is released,
 * a bounded number of waiters (K=2) are woken in FIFO order to prevent
 * thundering herd effects.
 *
 * This eliminates exponential backoff storms and makes admission retries
 * deterministic and event-driven without creating admission thrashing.
 */
public class AdmissionWaitManager {

    // move-the-ceiling diagnostic, REFUTED: tested at 8 - the full
    // FootprintABExperiment sweep (A/B/B2/B3, all MPL points) came back BYTE-IDENTICAL to the
    // MAX_WAKEUPS_PER_RELEASE=4 baseline, ruling this out as the mechanism pinning
    // peakConcurrentGroups at ~4 for both Config A and Config B. Reverted to 4. That result was
    // a different (D,k,MPL,f) regime than the TGS static-locking validation's f=0 corner, so it
    // doesn't transfer directly - see AdmissionAttemptProvenance's javadoc for why the plateau
    // mechanism there has to be measured (a second, uncapped re-attempt channel exists:
    // [ADMISSION-RACE] in ACIDAdmissionController) rather than assumed from B alone.
    private static final int DEFAULT_MAX_WAKEUPS_PER_RELEASE = 4;  // Bounded wake to prevent thundering herd
    private final int maxWakeupsPerRelease;

    // Liveness fix: wakeWaiters() is the ONLY thing that ever un-parks a registered waiter, and
    // it only fires on a release of the SPECIFIC lock a waiter registered on. A parent blocked on
    // a lock held by a group that never releases it (stuck BLOCKED holding a broad footprint
    // mid-cascade, e.g.) has no bounded time to its next attempt - traced directly to depth-0
    // stuck-cold lineages waiting on deep-cascade holders. sweepExpiredWaiters() is the timeout
    // side of that fix: independent of any release event, bounded by registration age alone.
    private static final long DEFAULT_WAIT_TIMEOUT = 5_000L;
    private final long waitTimeout;

    private record TimedWaiter(Transaction txn, long registeredAt) {
    }

    private final Map<String, Deque<TimedWaiter>> waitersByLock;
    private final CentralQueueManager queueManager;

    public AdmissionWaitManager(CentralQueueManager queueManager) {
        this(queueManager, DEFAULT_MAX_WAKEUPS_PER_RELEASE, DEFAULT_WAIT_TIMEOUT);
    }

    /**
     * Overload exposing the wakeup batch size B, so a sensitivity slice can sweep it via
     * -Dts.admissionWait.maxWakeupsPerRelease (wired at the sole production construction site,
     * DefaultLockingBundle) without touching this class per run.
     */
    public AdmissionWaitManager(CentralQueueManager queueManager, int maxWakeupsPerRelease) {
        this(queueManager, maxWakeupsPerRelease, DEFAULT_WAIT_TIMEOUT);
    }

    /** Overload exposing the wait timeout too, mirroring the maxWakeupsPerRelease pattern - not
     * yet wired to a system property the way maxWakeupsPerRelease is (no -Dts.admissionWait.
     * timeout support at the production construction site yet). 5s default is a starting
     * point, not a measured value - it needs the same kind of sensitivity check
     * maxWakeupsPerRelease already got before being trusted. */
    public AdmissionWaitManager(CentralQueueManager queueManager, int maxWakeupsPerRelease, long waitTimeout) {
        this.waitersByLock = new ConcurrentHashMap<>();
        this.queueManager = queueManager;
        this.maxWakeupsPerRelease = maxWakeupsPerRelease;
        this.waitTimeout = waitTimeout;
    }

    /**
     * Register a transaction as waiting for a specific lock.
     * Transaction will be woken when this lock is released (FIFO order), or after waitTimeout
     * elapses, whichever comes first (see sweepExpiredWaiters()).
     *
     * @param lockId The lock that is blocking admission
     * @param txn The transaction waiting for admission
     * @param currentTime Virtual time of registration, for the timeout sweep
     */
    public void registerWaiter(String lockId, Transaction txn, long currentTime) {
        waitersByLock.computeIfAbsent(lockId, k -> new ArrayDeque<>())
                     .addLast(new TimedWaiter(txn, currentTime));  // FIFO: add to end, remove from front
    }

    /**
     * Wake up to K transactions waiting for a specific lock (FIFO order).
     * Moves them to PENDING queue for admission retry. Bounded wakeup
     * prevents thundering herd effects.
     *
     * @param lockId The lock that was released
     * @return List of woken transactions (for logging)
     */
    public List<Transaction> wakeWaiters(String lockId) {
        Deque<TimedWaiter> waiters = waitersByLock.get(lockId);
        if (waiters == null || waiters.isEmpty()) {
            return Collections.emptyList();
        }

        // Four-way lineage time decomposition: no new parameter needed - queueManager already
        // tracks current virtual time (set once per dispatch() call, at CentralQueueManager.
        // setCurrentTime()), and every release path runs within an active dispatch cycle.
        long currentTime = queueManager.getCurrentTime();

        // Wake up to K waiters (FIFO order - oldest first)
        List<Transaction> result = new ArrayList<>();
        int wakeCount = 0;
        while (wakeCount < maxWakeupsPerRelease && !waiters.isEmpty()) {
            TimedWaiter waiter = waiters.pollFirst();  // FIFO: remove from front (oldest)
            if (waiter != null) {
                Transaction txn = waiter.txn();
                result.add(txn);
                // TGS static-locking validation: tag WHY the next tryAdmitWithLockCheck() call
                // is happening before re-enqueueing, so the admission-attempt trace can tell this
                // channel apart from the uncapped [ADMISSION-RACE] re-enqueue in
                // ACIDAdmissionController - see AdmissionAttemptProvenance's javadoc.
                txn.setAdmissionAttemptProvenance(AdmissionAttemptProvenance.WOKEN);
                txn.markPhase(Transaction.LineagePhase.PENDING_QUEUE, currentTime);
                queueManager.enqueue(txn, QueueType.PENDING);
                wakeCount++;
            }
        }

        // Clean up empty queue
        if (waiters.isEmpty()) {
            waitersByLock.remove(lockId);
        }

        return result;
    }

    /**
     * The timeout side of the liveness fix: independent of any lock release, re-attempt any
     * waiter that has been registered for at least waitTimeout. Unlike wakeWaiters() this scans
     * every lock's queue (a waiter can be starved on ANY lock, not just one wakeWaiters() happens
     * to be called for), removing expired entries from wherever in the deque they sit - an
     * expired waiter is not necessarily at the front if its lock has a mix of ages registered.
     *
     * Bounded to at most maxWakeupsPerRelease releases per call, oldest-registered-first across
     * ALL locks - NOT "release everyone past the timeout in one shot". Releasing an unbounded
     * batch would be exactly the thundering-herd case wakeWaiters() already guards against on the
     * release path; the timeout path needs the same discipline. Called every dispatch() (every
     * step), so whatever isn't released this call is picked up on a later one - draining at a
     * bounded rate, not in a single spike. (Verified via CrossModeAgreementTest bisection that
     * this class is NOT responsible for that test's pre-existing FIXED_TIME/FIXED_TRANSACTION
     * divergence, which turned out to be present on unmodified trunk too - but the bounded-
     * release discipline here is correct on its own merits regardless, matching wakeWaiters()'s
     * existing cap.)
     *
     * @param currentTime current virtual time
     * @return transactions that timed out and were re-enqueued to PENDING (for logging)
     */
    public List<Transaction> sweepExpiredWaiters(long currentTime) {
        List<TimedWaiter> allExpired = new ArrayList<>();
        for (Deque<TimedWaiter> waiters : waitersByLock.values()) {
            for (TimedWaiter waiter : waiters) {
                if (currentTime - waiter.registeredAt() >= waitTimeout) {
                    allExpired.add(waiter);
                }
            }
        }
        if (allExpired.isEmpty()) {
            return Collections.emptyList();
        }
        allExpired.sort(Comparator.comparingLong(TimedWaiter::registeredAt));

        List<Transaction> released = new ArrayList<>();
        for (TimedWaiter waiter : allExpired) {
            if (released.size() >= maxWakeupsPerRelease) {
                break;
            }
            Transaction txn = waiter.txn();
            released.add(txn);
            txn.setAdmissionAttemptProvenance(AdmissionAttemptProvenance.WAIT_TIMEOUT);
            txn.markPhase(Transaction.LineagePhase.PENDING_QUEUE, currentTime);
            queueManager.enqueue(txn, QueueType.PENDING);
        }

        Set<Transaction> releasedSet = new HashSet<>(released);
        Iterator<Map.Entry<String, Deque<TimedWaiter>>> lockIt = waitersByLock.entrySet().iterator();
        while (lockIt.hasNext()) {
            Deque<TimedWaiter> waiters = lockIt.next().getValue();
            waiters.removeIf(w -> releasedSet.contains(w.txn()));
            if (waiters.isEmpty()) {
                lockIt.remove();
            }
        }
        return released;
    }

    /**
     * Get the number of transactions waiting for a specific lock.
     * For debugging/monitoring purposes.
     */
    public int getWaiterCount(String lockId) {
        Deque<TimedWaiter> waiters = waitersByLock.get(lockId);
        return waiters == null ? 0 : waiters.size();
    }

    /**
     * Get total number of waiting transactions across all locks.
     * For debugging/monitoring purposes.
     */
    public int getTotalWaiterCount() {
        return waitersByLock.values().stream()
                            .mapToInt(Deque::size)
                            .sum();
    }

    /** Diagnostic-only: lockId -> waiting transaction ids, FIFO order preserved (oldest first).
     * This is the PARENT admission-blocking wait registry - distinct from SS2PLLockManager's own
     * internal waitTracker/getLockWaiters(), which only sees bookkeeper-side acquireAllLocks()
     * waits. A parent blocked at tryAdmitWithLockCheck() shows up ONLY here. */
    public Map<String, List<String>> waitersByLockForDiagnostics() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var entry : waitersByLock.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.put(entry.getKey(), entry.getValue().stream().map(w -> w.txn().getId()).toList());
            }
        }
        return out;
    }
}
