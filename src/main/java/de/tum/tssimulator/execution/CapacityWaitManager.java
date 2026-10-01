package de.tum.tssimulator.execution;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.config.ReadyWaiterOrder;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.VictimTraceLog;

/**
 * Event-driven capacity wait manager.
 * Separate queues for READY (ready to execute) vs PENDING (need admission).
 * READY uses LIFO (recent failures first), PENDING uses FIFO (fairness).
 *
 * Threading model:
 * This class is intentionally single-threaded and is mutated by the simulator
 * loop. The ArrayDeque fields are not synchronized and should not be accessed
 * concurrently without a broader runtime concurrency redesign.
 */
public class CapacityWaitManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CapacityWaitManager.class);

    private final Deque<TransactionGroup> readyWaiters = new ArrayDeque<>();
    private final Deque<TransactionGroup> pendingWaiters = new ArrayDeque<>();
    private final AbstractAdmissionController dispatcher;

    // k: number of waiters drained per wakeNext() call (ready queue first, then pending).
    // Default 1 preserves the original single-wake-per-completion/abort behavior.
    private int admissionBatchSize = 1;
    public static final boolean DIAG_READY_DEPTH =
            Boolean.getBoolean("ts.diag.readyDepth");
    public static final AtomicLong READY_DEPTH_SAMPLES = new AtomicLong();
    public static final AtomicLong READY_DEPTH_GE2 = new AtomicLong();
    public static final AtomicLong READY_DEPTH_MAX = new AtomicLong();
    public static final AtomicLong READY_REGISTERED = new AtomicLong();
    public static final AtomicLong WAKE_CALLS = new AtomicLong();
    static {
        if (DIAG_READY_DEPTH) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.printf(
                    "READY_DEPTH wakeNextCalls=%d readyRegistered=%d readyWakes=%d "
                            + "with>=2waiters=%d maxdepth=%d%n",
                    WAKE_CALLS.get(), READY_REGISTERED.get(), READY_DEPTH_SAMPLES.get(),
                    READY_DEPTH_GE2.get(), READY_DEPTH_MAX.get())));
        }
    }
    /** Historical behaviour; every committed campaign row was produced under LIFO. */
    private ReadyWaiterOrder readyWaiterOrder = ReadyWaiterOrder.LIFO;
    // Diagnostic tracking
    private static final Map<Long, Integer> wakeCount = new ConcurrentHashMap<>();
    private static final boolean ENABLE_DIAGNOSTICS = false;

    public CapacityWaitManager(AbstractAdmissionController dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void setReadyWaiterOrder(ReadyWaiterOrder order) {
        this.readyWaiterOrder = order == null
                ? ReadyWaiterOrder.LIFO : order;
    }

    public ReadyWaiterOrder getReadyWaiterOrder() {
        return readyWaiterOrder;
    }

    public void setAdmissionBatchSize(int admissionBatchSize) {
        if (admissionBatchSize < 1) {
            throw new IllegalArgumentException("admissionBatchSize must be >= 1");
        }
        this.admissionBatchSize = admissionBatchSize;
    }

    public int getAdmissionBatchSize() {
        return admissionBatchSize;
    }
    public void registerReadyWaiter(TransactionGroup group) {
        if (ENABLE_DIAGNOSTICS) {
            LOGGER.debug("[CAPACITY] REGISTER_READY gid={} state={}",
                    group.getGroupId(), group.getParent().getState());
        }
        if (DIAG_READY_DEPTH) {
            READY_REGISTERED.incrementAndGet();
        }
        if (!readyWaiters.contains(group)) {
            readyWaiters.addLast(group); // LIFO: add to tail, remove from tail
        }
    }

    public void registerPendingWaiter(TransactionGroup group) {
        if (ENABLE_DIAGNOSTICS) {
            LOGGER.debug("[CAPACITY] REGISTER_PENDING gid={} state={}",
                    group.getGroupId(), group.getParent().getState());
        }
        VictimTraceLog.log("CAPACITY_REGISTER_PENDING", group.getParent().getId(),
                "gid=" + group.getGroupId() + " state=" + group.getParent().getState()
                        + " alreadyQueued=" + pendingWaiters.contains(group));
        if (!pendingWaiters.contains(group)) {
            pendingWaiters.addLast(group); // FIFO: add to tail, remove from head
        }
    }

    /**
     * Wake ONE waiting group when capacity frees up.
     * Try READY first (priority), then PENDING.
     */
    /**
     * Wake the next batch of admittable work when capacity frees up: up to
     * {@code admissionBatchSize} waiters, READY first (priority, LIFO), then PENDING (FIFO).
     * With the default admissionBatchSize=1 this wakes exactly one waiter, same as before k
     * existed.
     */
    public void wakeNext() {
        if (DIAG_READY_DEPTH) {
            WAKE_CALLS.incrementAndGet();
        }
        int woken = 0;
        while (woken < admissionBatchSize && !readyWaiters.isEmpty()) {
            // DIAGNOSTIC, off unless -Dts.diag.readyDepth=true. Whether the wake ORDER can
            // matter at all is decided by how deep this deque is: with 0 or 1 waiter, LIFO and
            // FIFO select the same group by construction, and no experiment can separate them.
            // Counting the depth answers that directly instead of inferring it from a null result.
            if (DIAG_READY_DEPTH) {
                READY_DEPTH_SAMPLES.incrementAndGet();
                if (readyWaiters.size() >= 2) {
                    READY_DEPTH_GE2.incrementAndGet();
                }
                READY_DEPTH_MAX.accumulateAndGet(readyWaiters.size(), Math::max);
            }
            // LIFO takes the tail, FIFO the head. LIFO is the historical behaviour and the
            // default; the alternative exists so the choice can be measured rather than
            // asserted - see ReadyWaiterOrder.
            TransactionGroup group =
                    readyWaiterOrder == ReadyWaiterOrder.FIFO
                            ? readyWaiters.removeFirst()
                            : readyWaiters.removeLast();

            // SKIP validity check: if group/parent is ABORTED, don't wake
            if (group.getParent().getState() == TransactionState.ABORTED ||
                    group.getParent().getState() == TransactionState.COMPLETED) {
                if (ENABLE_DIAGNOSTICS) {
                    System.out.println("[CAPACITY] Skip waking terminal state group: " + group.getGroupId());
                }
                continue;
            }
            long gid = group.getGroupId();

            if (ENABLE_DIAGNOSTICS) {
                int count = wakeCount.merge(gid, 1, Integer::sum);
                TransactionState state = group.getParent().getState();
                LOGGER.debug("[CAPACITY] WAKE_READY gid={} count={} state={}", gid, count, state);

                assert count == 1 : "[CAPACITY] ERROR: Group " + gid + " woken " + count + " times!";

                if (state == TransactionState.COMPLETED || state == TransactionState.ABORTED) {
                    LOGGER.error("[CAPACITY] Waking group {} in terminal state {}", gid, state);
                }
            }

            dispatcher.admitReadyTransactionGroup(group);
            woken++;
        }

        while (woken < admissionBatchSize && !pendingWaiters.isEmpty()) {
            TransactionGroup group = pendingWaiters.removeFirst(); // FIFO

            // SKIP validity check
            if (group.getParent().getState() == TransactionState.ABORTED ||
                    group.getParent().getState() == TransactionState.COMPLETED) {
                continue;
            }
            long gid = group.getGroupId();

            if (ENABLE_DIAGNOSTICS) {
                int count = wakeCount.merge(gid, 1, Integer::sum);
                TransactionState state = group.getParent().getState();
                LOGGER.debug("[CAPACITY] WAKE_PENDING gid={} count={} state={}", gid, count, state);

                assert count == 1 : "[CAPACITY] ERROR: Group " + gid + " woken " + count + " times!";

                if (state == TransactionState.COMPLETED || state == TransactionState.ABORTED) {
                    LOGGER.error("[CAPACITY] Waking group {} in terminal state {}", gid, state);
                }
            }

            dispatcher.enqueuePendingTransactionGroup(group);
            woken++;
        }
    }

    /**
     * Wakes as many waiters as possible. Called when capacity expands.
     */
    public void onCapacityAvailable() {
        int toWake = readyWaiters.size() + pendingWaiters.size();
        for (int i = 0; i < toWake; i++) {
            wakeNext();
        }
    }

    /**
     * Remove group from capacity wait queues (completed/aborted).
     */
    public void removeGroup(TransactionGroup group) {
        if (ENABLE_DIAGNOSTICS) {
            boolean removedReady = readyWaiters.remove(group);
            boolean removedPending = pendingWaiters.remove(group);
            if (removedReady || removedPending) {
                LOGGER.debug("[CAPACITY] REMOVE_FROM_WAIT gid={} fromReady={} fromPending={}",
                        group.getGroupId(), removedReady, removedPending);
            }
        } else {
            boolean removedReady = readyWaiters.remove(group);
            boolean removedPending = pendingWaiters.remove(group);
            if (group.getParent() != null && (removedReady || removedPending)) {
                VictimTraceLog.log("CAPACITY_REMOVE_GROUP", group.getParent().getId(),
                        "gid=" + group.getGroupId() + " removedReady=" + removedReady
                                + " removedPending=" + removedPending);
            }
        }
    }

    public int size() {
        return readyWaiters.size() + pendingWaiters.size();
    }
}
