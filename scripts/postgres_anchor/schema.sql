-- TGS static-locking validation - PostgreSQL anchor (Part B). One table of D rows;
-- workload_client.py populates the actual row count per (D,k) cell via its --setup step (D is a
-- harness parameter, not fixed at container-init time), so this file only defines structure.

-- `v` exists for V0's write-only workload (mode="update"). The P1 parity passes never touch rows at
-- all - they use advisory locks only - so for those the table is vestigial and autovacuum is
-- irrelevant. For V0 it is written on every transaction, so autovacuum on a hot D-row table becomes
-- a real perturbation: either disable it for the table or report that it is on (V0's traps).
DROP TABLE IF EXISTS resources;
CREATE TABLE resources (
    id INTEGER PRIMARY KEY,
    v  INTEGER NOT NULL DEFAULT 0
);

-- All-or-nothing lock acquisition for the "advisory" arm (B1), ONE attempt: tries every id in ids
-- (already sorted by the caller, matching the simulator's deterministic lock-ordering deadlock
-- avoidance) via pg_try_advisory_xact_lock, returning FALSE the instant one fails. Running the
-- whole k-id attempt as ONE server-side call (one round trip) instead of k separate client
-- statements is what makes this genuinely all-or-nothing rather than "client observes a partial-
-- holding window between round trips" - see the TGS plan's B1 for why a client-side loop wouldn't
-- be a faithful analogue of the simulator's tryAcquireAllLocks().
--
-- pg_try_advisory_xact_lock's locks are transaction-scoped: on ROLLBACK, anything already
-- acquired within this call is released automatically - the caller doesn't need to explicitly
-- unlock on a FALSE return, just ROLLBACK the enclosing transaction and retry as a fresh attempt.
CREATE OR REPLACE FUNCTION try_acquire_all(ids BIGINT[]) RETURNS BOOLEAN AS $$
DECLARE
    lock_id BIGINT;
BEGIN
    FOREACH lock_id IN ARRAY ids LOOP
        IF NOT pg_try_advisory_xact_lock(lock_id) THEN
            RETURN FALSE;
        END IF;
    END LOOP;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Retrying variant (measurement-defect correction): the calibrated 20x hold:RTT ratio was
-- measured UNCONTENDED (W=1). At the plateau (n~30-50 for the D=5000,k=25 cell), block
-- probability is ~0.98 - each success costs ~50 failed attempts, and if every retry is a
-- client-side round trip (the try_acquire_all() + Python-loop design above), that's ~50*RTT of
-- retry churn competing with the hold time the acceptance condition was calibrated against,
-- inverting the ratio exactly where the interesting data is. Fix: loop server-side, so a retry
-- costs no round trip at all - only the client's ONE initial call pays RTT.
--
-- Partial-lock release between attempts uses a SUBTRANSACTION, not a bare SAVEPOINT statement -
-- PL/pgSQL doesn't accept raw SAVEPOINT/ROLLBACK TO SAVEPOINT as statements (confirmed by trying:
-- "syntax error at or near TO"). The only way to get savepoint semantics inside a function body is
-- the nested BEGIN...EXCEPTION...END block, which PL/pgSQL implements as an implicit subtransaction
-- (savepoint on entry, rollback-to-savepoint when the exception is caught). pg_try_advisory_xact_lock's
-- locks are released on transaction end OR on subtransaction rollback if acquired after it started
-- (documented Postgres behavior - advisory locks, unlike regular heavyweight locks, DO honor
-- savepoint/subtransaction rollback). Verified empirically (verify_savepoint_release.py) before
-- being trusted for the real sweep, not assumed from memory alone - see that script's output.
--
-- retry_delay_s is a small SERVER-SIDE pause between attempts (not a round trip) - bounds how
-- hard the retry loop hammers the lock table; default 1ms is far below both the hold time and
-- the original RTT, so it doesn't reintroduce the problem this function exists to fix.
-- Returns: number of attempts taken (>=1) on success, or -1 if max_attempts was exhausted without
-- acquiring (the caller should treat -1 as "gave up", not as a normal failed-attempt count).
CREATE OR REPLACE FUNCTION try_acquire_all_retry(
    ids BIGINT[],
    max_attempts INT DEFAULT 1000000,
    retry_delay_s FLOAT DEFAULT 0.001
) RETURNS INT AS $$
DECLARE
    lock_id BIGINT;
    attempt INT := 0;
    got_all BOOLEAN;
BEGIN
    LOOP
        attempt := attempt + 1;
        got_all := TRUE;
        BEGIN
            FOREACH lock_id IN ARRAY ids LOOP
                IF NOT pg_try_advisory_xact_lock(lock_id) THEN
                    got_all := FALSE;
                    RAISE EXCEPTION 'tgs_attempt_failed';
                END IF;
            END LOOP;
        EXCEPTION WHEN OTHERS THEN
            -- Implicit subtransaction rolled back here - releases any locks acquired earlier in
            -- THIS attempt's FOREACH loop (see the class-level comment on why this is safe).
            got_all := FALSE;
        END;
        IF got_all THEN
            RETURN attempt;
        END IF;
        IF attempt >= max_attempts THEN
            RETURN -1;
        END IF;
        PERFORM pg_sleep(retry_delay_s);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- ===========================================================================================
-- P1 parity plot (measured P(blocked|H) vs the exact hypergeometric, zero free parameters) -
-- Postgres side. A SEPARATE function and table from the main throughput sweep above, deliberately
-- not sharing code with try_acquire_all_retry(): this adds a pg_locks read to every single attempt
-- (not just successes), which is real per-attempt overhead the main sweep's proven numbers
-- shouldn't be exposed to. Used only for a smaller, dedicated data-collection pass (see
-- collect_p1_data.py), not the throughput/n-vs-load sweep.
--
-- REWRITTEN after the previous P1 pass was found to be measuring the wrong quantity entirely.
-- Two independent defects, fixed in two separable stages so the H=0 invariant can attribute what
-- moved (see the plan's "two staged runs, not one"):
--
--   Defect A (dominant) - the OLD version retried the SAME ids array on a 1ms tick and INSERTed a
--     row on EVERY attempt. A failure was logged once per retry, a success once per transaction,
--     so numerator and denominator counted different things and the aggregate measured rate
--     collapsed to the identity 1 - 1/(attempts per success): 0.9569 / 0.8499 / 0.9668 for the
--     three cells, matching that identity to four decimals. That quantity does not depend on D, k
--     or H at all, so it never measured P(blocked|H). The simulator has always guarded against
--     this - SimulationMetrics.recordAdmissionAttempt() gates materialization on
--     `attemptIndex == 0`, and AdmissionAttemptRow's javadoc states why - and the guard was never
--     ported here. Fixed in STAGE B (probe_attempt_once + client-side redraw), which makes every
--     logged row a first attempt BY CONSTRUCTION rather than by a filter that can drift again.
--
--   Defect B - h_before was read in one PL/pgSQL statement and the acquisition ran in the next,
--     while every worker retried on the same 1ms tick, so the readings clustered in the trough
--     between synchronized acquisition waves. Measured P(blocked | h_before = 0) was
--     0.051 / 0.023 / 0.068 - structurally impossible, since nothing can block when no advisory
--     lock is held. Fixed in STAGE A (the single-statement capture below).
--
-- NOTE for anyone tempted to "fix" Defect B with an isolation level: pg_locks is a view over
-- pg_lock_status(), a C set-returning function reading lock-manager shared memory. It is NOT MVCC
-- and does not respect the transaction snapshot, so REPEATABLE READ has no effect on what it
-- returns. Co-locating the read with the acquisition in one statement is the only lever.
--
-- LOCK SCOPE CHANGE (both stages): the P1 path now takes SESSION-level advisory locks
-- (pg_try_advisory_lock) released explicitly via pg_advisory_unlock_all(), where it used to take
-- transaction-level ones. Forced by the capture fix: a transaction-level advisory lock cannot be
-- released manually, so releasing a failed attempt's partial set requires the subtransaction/RAISE
-- dance the old code used - and that dance is incompatible with reading h_before and the grant
-- count out of the SAME statement, because it raises an unresolved question about whether the
-- PL/pgSQL variables assigned inside the block survive the subtransaction rollback. Session locks
-- release synchronously with no exception handling at all. Both scopes appear in pg_locks as
-- locktype='advisory', so h_before is unchanged by this. The throughput sweep's try_acquire_all /
-- try_acquire_all_retry above keep transaction-level locks and are NOT touched.
--
-- Because Stage A therefore changes two things (capture co-location AND lock scope), Stage A
-- carries a pre-registered check that the retry dynamics did not move: its aggregate block rate
-- must still reproduce 0.9569 / 0.8499 / 0.9668 to within +-0.02.
-- ===========================================================================================

DROP TABLE IF EXISTS attempt_log;
CREATE TABLE attempt_log (
    h_before INT NOT NULL,
    -- How many of the k requested ids were free. Enables gate G0, the capture cross-check:
    -- E[k - granted | H] = k*H/D exactly, zero free parameters. Linear and independent of the
    -- blocking outcome, so a capture bias shows up as a slope rather than being tangled with the
    -- curvature of a saturating function - which is why G0 is evaluated BEFORE the parity gates.
    granted INT NOT NULL,
    success BOOLEAN NOT NULL
);

-- Single-statement capture, shared by both stages.
--
-- The LATERAL is load-bearing and is NOT a stylistic choice over a plain CROSS JOIN or a
-- MATERIALIZED CTE. The inner subquery references h.c in its WHERE clause, so it CANNOT be
-- evaluated before h - that is a genuine execution-order dependency the planner must honour,
-- rather than a hint it may reinterpret. `h.c >= 0` is trivially true (a count is never negative)
-- and exists only to create that reference. Without it, the try-lock expression mentions only `u`
-- and could in principle be projected on the unnest side before the join, which would let a
-- backend count its own locks.
--
-- Verified behaviourally by verify_probe_capture.py, not by reading EXPLAIN - a plan dump
-- describes one invocation and would not tell us the guarantee still holds after a planner change.
CREATE OR REPLACE FUNCTION probe_attempt_once(
    ids BIGINT[],
    OUT h_before INT,
    OUT granted INT,
    OUT success BOOLEAN
) AS $$
BEGIN
    SELECT h.c, s.g
      INTO h_before, granted
      FROM (SELECT count(*)::int AS c FROM pg_locks WHERE locktype = 'advisory') AS h
      CROSS JOIN LATERAL (
          SELECT sum(CASE WHEN pg_try_advisory_lock(u) THEN 1 ELSE 0 END)::int AS g
            FROM unnest(ids) AS u
           WHERE h.c >= 0
      ) AS s;
    success := (granted = cardinality(ids));
    IF NOT success THEN
        -- Release the partial set immediately, server-side. Doing this here rather than leaving it
        -- to a client ROLLBACK matters: a client round trip is ~5ms against a 50ms hold, so a
        -- failed attempt would otherwise hold up to k-1 locks for a tenth of a hold time and
        -- inflate H exactly during contention.
        PERFORM pg_advisory_unlock_all();
    END IF;
END;
$$ LANGUAGE plpgsql;

-- MAIN PARITY PASS acquisition: SHORT-CIRCUITS on the first conflict, unlike probe_attempt_once()
-- above. Measured reason, not a preference.
--
-- probe_attempt_once() attempts all k so it can report `granted`, which is what gate G0's
-- E[k - granted | H] = k*H/D cross-check needs. But diagnose_h0_failures.py showed that at W=2,
-- 24 of 27 failures reporting h_before = 0 had NO advisory lock visible moments later - neither
-- ours nor anyone's. The blocker was another probe's TRANSIENT partial acquisition: a failing
-- all-k probe grabs the free subset and releases it immediately, and that window is invisible to
-- h_before both before (not yet taken) and after (already released). The full-k attempt was
-- manufacturing the very collisions the parity pass then measured.
--
-- Short-circuiting roughly halves the transient (a failing attempt holds the prefix before the
-- first conflict, ~k/2 on average, instead of k-1) and matches the mechanism the original
-- try_acquire_all() modelled. It does NOT eliminate transients: any all-or-nothing preclaim built
-- on try-locks must acquire in order to test, so a partial hold on the failure path is a property
-- of the mechanism, not an artifact. The simulator models the same thing via its LOST_RACE outcome.
--
-- h_before is still captured in the same statement as the FIRST acquisition - the tightest
-- co-location available once the loop has to short-circuit. G0 now runs as its own dedicated
-- low-W pass (collect_p1_data.py --stage g0) against probe_attempt_once(), so its transients can
-- no longer contaminate the headline data. That split follows V0 section L5's own rule: keep
-- instrumentation that perturbs the system in a separate function and a separate pass.
CREATE OR REPLACE FUNCTION probe_attempt_once_sc(
    ids BIGINT[],
    OUT h_before INT,
    OUT success BOOLEAN
) AS $$
DECLARE
    lock_id BIGINT;
    is_first BOOLEAN := TRUE;
BEGIN
    success := TRUE;
    FOREACH lock_id IN ARRAY ids LOOP
        IF is_first THEN
            SELECT h.c, s.ok
              INTO h_before, success
              FROM (SELECT count(*)::int AS c FROM pg_locks WHERE locktype = 'advisory') AS h
              CROSS JOIN LATERAL (
                  SELECT pg_try_advisory_lock(lock_id) AS ok WHERE h.c >= 0
              ) AS s;
            is_first := FALSE;
        ELSE
            success := pg_try_advisory_lock(lock_id);
        END IF;
        IF NOT success THEN
            PERFORM pg_advisory_unlock_all();
            RETURN;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- STAGE A only: the old server-side retry loop and per-attempt logging, kept EXACTLY as they were
-- except for the capture. Isolates Defect B, because at H=0 nothing is held and so any id set
-- succeeds regardless of retry inflation - which makes the H=0 bin a clean read on the capture
-- alone. Stage A's overall parity is expected to still fail; that is pre-registered, not a result.
--
-- Note this attempts all k rather than short-circuiting on the first conflict, which is what makes
-- `granted` available. A failed attempt therefore transiently holds up to k-1 locks for the
-- duration of one statement (microseconds against a 50ms hold), released immediately below.
CREATE OR REPLACE FUNCTION try_acquire_all_retry_logged(
    ids BIGINT[],
    max_attempts INT DEFAULT 1000000,
    retry_delay_s FLOAT DEFAULT 0.001
) RETURNS INT AS $$
DECLARE
    attempt INT := 0;
    probe RECORD;
BEGIN
    LOOP
        attempt := attempt + 1;
        SELECT * INTO probe FROM probe_attempt_once(ids);
        INSERT INTO attempt_log (h_before, granted, success)
             VALUES (probe.h_before, probe.granted, probe.success);
        IF probe.success THEN
            RETURN attempt;
        END IF;
        -- probe_attempt_once() already released the partial set on failure.
        IF attempt >= max_attempts THEN
            RETURN -1;
        END IF;
        PERFORM pg_sleep(retry_delay_s);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- ===========================================================================================
-- V0 matched-discipline arm: all-or-nothing preclaim with a FIFO QUEUEING wait.
--
-- Why this exists. The `advisory` arm retries by POLLING (try all k, sleep 1ms, repeat), which is
-- opportunistic: whoever polls while the set happens to be free wins, and nobody queues. The
-- simulator does something different - ACIDAdmissionController registers the blocked transaction as
-- a waiter on ONE specific lock (AdmissionWaitManager), waits FIFO, and re-tests the whole footprint
-- when woken. Comparing those two measures the cost of the DISCIPLINE, not the model: measured, the
-- simulator's wait-to-hold ratio exceeds PostgreSQL's by 1.02x / 1.38x / 2.09x at k = 10 / 25 / 50,
-- a k-dependence predicted in advance from head-of-line blocking (a 50-lock waiter obstructs a queue
-- far more than a 10-lock one) and confirmed on that axis.
--
-- This function matches the simulator's discipline while holding acquisition atomicity fixed:
--   * tries all k in ASCENDING id order - `ids` is sorted by the caller, and the simulator's
--     collectRequiredLockIds sorts "precisely so that order is deterministic", so both arms block on
--     the same (lowest) offending lock under identical contention;
--   * RELEASES EVERYTHING before waiting - this is what keeps it preclaim. Holding the prefix while
--     waiting would be hold-and-wait, i.e. the `for_update` protocol, and would reintroduce the
--     atomicity confound this arm exists to avoid;
--   * blocks on the offending lock via pg_advisory_lock, which queues FIFO in the lock manager;
--   * re-tests the WHOLE footprint on wake, matching AdmissionWaitManager.wakeWaiters(), which
--     pollFirst()s the waiter out of its queue and re-enqueues it for a full tryAdmitWithLockCheck.
--     The blocker is re-derived each wake and may be a different lock.
--
-- KNOWN, MEASURED ARTIFACT - report it with any result from this arm. PostgreSQL has no
-- wait-without-acquire for advisory locks, so a waiter necessarily HOLDS the blocking lock briefly
-- between grant and release, which the simulator's waiter never does. The grant then chains: each
-- release grants the next queued waiter. Measured drain latency, server-side:
--
--     depth  16 ->   0 ms      depth 64 ->  31 ms      depth 256 -> 157 ms   (~0.6 ms per waiter)
--
-- Doing the block and the release in ONE server-side call (below) rather than two client statements
-- is worth 4x: 640 ms -> 157 ms at depth 256. A pg_advisory_lock_shared broadcast was measured as an
-- alternative and REJECTED - its grants are not simultaneous either (93 ms spread at depth 256) and
-- it leaves the lock unavailable to exclusive acquirers for 203 ms, a larger transient-hold artifact
-- than the chain it would replace.
--
-- ON THE SIMULATOR'S WAKE FAN-OUT. The simulator wakes up to maxWakeupsPerRelease (default 4)
-- waiters per release; PostgreSQL wakes ONE and the grant chains. Setting the simulator's cap high
-- for this arm is justified as "the cap does not bind, so it is not a live difference" - measured,
-- 4 vs 1000 changed throughput by <0.1% at every load on this cell, because each completion releases
-- k=25 locks and each release may wake 4, so ~100 wakeups per completion is already far more than
-- demand. It is NOT justified as "both arms then broadcast": they do not. PostgreSQL was measured
-- waking serially in BOTH modes - chained on exclusive, 93 ms spread on shared - so a high cap makes
-- the simulator's fan-out effectively unbounded while PostgreSQL's stays serial. That is the
-- mismatch inverted, not removed; it is tolerable only because the cap is inert at these loads.
--
-- The residual confound is depth-dependent, so it lands on the same axis (queue depth ~ k) as the
-- effect under test - but it is bounded at ~0.6 ms x queue depth, i.e. <=157 ms against waits of
-- ~3.4 s, under 5%, while the effect is 38-109%. Signal exceeds confound by roughly 8-20x, so a
-- positive result is interpretable provided the bound is quoted per configuration (queue depth is
-- recoverable as W - n from data already collected, so no extra instrumentation is needed).
CREATE OR REPLACE FUNCTION try_acquire_all_queued(
    ids BIGINT[],
    max_attempts INT DEFAULT 1000000
) RETURNS INT AS $$
DECLARE
    lock_id BIGINT;
    blocker BIGINT;
    attempt INT := 0;
BEGIN
    LOOP
        attempt := attempt + 1;
        blocker := NULL;
        FOREACH lock_id IN ARRAY ids LOOP          -- ascending: caller sorts
            IF NOT pg_try_advisory_lock(lock_id) THEN
                blocker := lock_id;                -- lowest-id lock we cannot have
                EXIT;
            END IF;
        END LOOP;
        IF blocker IS NULL THEN
            RETURN attempt;
        END IF;
        PERFORM pg_advisory_unlock_all();          -- preclaim: never hold while waiting
        IF attempt >= max_attempts THEN
            RETURN -1;
        END IF;
        PERFORM pg_advisory_lock(blocker);         -- FIFO queue in the lock manager
        PERFORM pg_advisory_unlock(blocker);       -- same call: no client round trip in the chain
    END LOOP;
END;
$$ LANGUAGE plpgsql;


-- ---------------------------------------------------------------------------
-- V1: deferred constraint triggers at fan-out 1
-- ---------------------------------------------------------------------------
-- Two tables so the CPR crosses a "model" boundary the way the simulator's identity
-- correspondence does: same instance id, different property. model_a is the parent's
-- write target; model_b is what the deferred trigger propagates into.
--
-- IMPORTANT (V1 implementation departure): the PARENT does not acquire model_a rows with
-- SELECT ... FOR UPDATE. It preclaims k advisory ids exactly as V0's mode=update does, then
-- UPDATEs model_a. FOR UPDATE is incremental acquisition - the protocol mismatch already
-- measured and rejected on V0's for_update arm (-5.1% -> +37.4% signed residual) - and using
-- it here would compare a dynamic-acquisition parent against an all-or-nothing preclaim
-- simulator, at the rung whose entire purpose is the coupling point.
DROP TABLE IF EXISTS model_b;
DROP TABLE IF EXISTS model_a;

CREATE TABLE model_a (
    id    INTEGER PRIMARY KEY,
    v     INTEGER NOT NULL DEFAULT 0,
    -- semanticOverlap f: the fraction of the property pool that EMITS CPRs. Set on a random
    -- f-fraction at --setup time from the same seed the simulator's semanticOverlapSeed uses,
    -- so both sides realize a comparable graph. Making the trigger conditional on a
    -- precomputed flag keeps ONE trigger definition across the whole f sweep.
    emits BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE model_b (
    id INTEGER PRIMARY KEY,
    v  INTEGER NOT NULL DEFAULT 0
);

-- The bookkeeper analogue. Fires at COMMIT with the parent still holding its advisory locks,
-- which is the same coupling point as the simulator creating CUs at parent completion while
-- the parent sits in the waiting room holding locks.
--
-- SORTED ACQUISITION (V1 DEPARTURE, 2026-09-01): the SELECT ... ORDER BY id FOR UPDATE before
-- the UPDATE is load-bearing, not decoration. Trigger-firing order is not globally sorted
-- across concurrent transactions, so without it model_b deadlocks - and the simulator is
-- deadlock-free BY CONSTRUCTION (all-or-nothing preclaim never holds-and-waits). A nonzero
-- deadlock rate against a structural zero is an uncontrolled arm asymmetry contaminating
-- exactly the quantities V1 compares. Sorting makes the arms more alike, since ordered
-- all-or-nothing acquisition is what the simulator does.
--
-- Scope this buys: "the deferred coupling point is validated UNDER SORTED ACQUISITION" -
-- narrower than "deferred triggers behave as the simulator predicts". deadlocks is still
-- reported, as an invariant expected to be ZERO: nonzero means this sort broke.
CREATE OR REPLACE FUNCTION cpr_a_to_b() RETURNS TRIGGER AS $$
BEGIN
    PERFORM 1 FROM model_b WHERE id = NEW.id ORDER BY id FOR UPDATE;
    UPDATE model_b SET v = v + 1 WHERE id = NEW.id;   -- identity correspondence, fan-out 1
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- FOR EACH ROW at fan-out 1 = exactly one propagated write per written source row, matching
-- SchemaCprFactory.createFrom's "one bookkeeper per link". WHEN (OLD.emits) applies f without
-- redefining the trigger per sweep point.
DROP TRIGGER IF EXISTS trg_cpr_a_to_b ON model_a;
CREATE CONSTRAINT TRIGGER trg_cpr_a_to_b
    AFTER UPDATE ON model_a
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.emits)
    EXECUTE FUNCTION cpr_a_to_b();
