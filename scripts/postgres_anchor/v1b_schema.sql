-- V1b Phases 1-2: the server-side comparator and its measurement window.
--
-- Runs V1's transaction loop INSIDE the database so no round trip lies in the measured interval.
-- The protocol under test is unchanged -- all-or-nothing advisory preclaim, deferred constraint
-- trigger firing at commit with locks still held. What changes is the driver and the operating
-- point, both declared in the plan.
--
-- OPERATING POINT: hold = 0, k = 100. V0 calibrated at hold = 95.269 ms client-side; here both arms
-- are pure service, which is closer to the simulator (whose critical section has never been anything
-- else) but is a DIFFERENT operating point than the base rung was fitted at. Stated wherever V1b is
-- cited; not interchangeable with V0's numbers.
--
-- ============================ THE MEASUREMENT WINDOW ============================
--
-- SELF-GATING AT TRANSACTION GRANULARITY, not whole-batch gating. A batch that straddles the
-- boundary would have to be counted wholly or dropped wholly, and at k=100 a batch is long enough
-- that the granularity would matter. Transaction granularity is also where every other arm has its
-- window, so the comparison to V0/V1 stays clean.
--
-- ONE INTERVAL, PASSED IN, NEVER RE-DERIVED. p_win_start and p_win_end are computed ONCE by the
-- client, from a single `clock_timestamp()` query, and handed identically to every worker, to the
-- lock sampler and to the fire sampler. Inside here there is exactly ONE test -- `in_window` -- and
-- every accumulator reads that one boolean. There is deliberately no `elapsed > warmup` in one
-- place and `elapsed < duration` in another: that shape is what produced all three window defects
-- recorded in docs/LOCKING_PROTOCOL_CONFIG_INCIDENT.md, most recently the fire sampler whose
-- half-open interval quietly absorbed the drain.
--
-- ONE CLOCK. The lock sampler runs client-side but timestamps its polls with the SERVER's
-- clock_timestamp() (it is issuing a query anyway), so client and server are not two origins to be
-- reconciled -- there is only the server's. Reconciling two clocks is how the fire sampler's time
-- origin drifted from the workers' the first time; the fix there was a shared barrier, and the
-- fix here is to not have a second clock at all.

-- DROPPED and recreated, not CREATE IF NOT EXISTS. An earlier shape of this table survived a
-- schema change and IF NOT EXISTS silently kept it, so the procedure wrote one set of columns while
-- the analysis read another and the run died on a missing column -- the lucky version of that
-- failure. Nothing durable lives here: every run DELETEs its own (tag, rep) rows, writes them, and
-- reads them back in the same process, so the table is scratch and dropping it costs nothing.
DROP TABLE IF EXISTS v1b_batch_result;
CREATE TABLE v1b_batch_result (
    tag          TEXT,
    rep          INT,
    worker       INT,
    k            INT,
    d            INT,
    seed         FLOAT,
    attempted    INT,          -- transactions run, including warmup and drain
    counted      INT,          -- transactions whose COMMIT fell inside the window
    lat_sum_us   FLOAT,
    lat_min_us   FLOAT,
    lat_max_us   FLOAT,
    -- The aggregation rule pre-registers R_pg = median/median, and Little's identity needs the
    -- MEAN. Both are recorded: they answer different questions and neither substitutes.
    lat_p50_us   FLOAT,
    lat_p25_us   FLOAT,
    lat_p75_us   FLOAT,
    lat_p95_us   FLOAT,
    lat_truncated BOOL,
    -- Paired mode: each arm's median overall and by batch HALF, so monotone within-batch
    -- drift (bloat) is visible as a half-to-half shift rather than assumed to cancel.
    counted_treat INT,
    counted_base  INT,
    p50_treat_us  FLOAT,
    p50_base_us   FLOAT,
    p50_treat_h1  FLOAT,
    p50_treat_h2  FLOAT,
    p50_base_h1   FLOAT,
    p50_base_h2   FLOAT,
    win_start    TIMESTAMPTZ,  -- echoed back so the analysis can assert every row shares one
    win_end      TIMESTAMPTZ,
    retries_total BIGINT,
    -- Per-arm retry counts. The null control failed at W=64 (R=2.76 with both arms firing
    -- zero times and drawing from the same 80,000 rows), and these separate the two candidate
    -- mechanisms: treatment RETRYING more (a contention asymmetry) versus treatment being
    -- slower per attempt (anything else).
    retries_treat BIGINT,
    retries_base  BIGINT,
    -- Per-arm latency SUMS, so R_pg can be formed from MEANS as well as medians.
    -- The simulator arm publishes throughput and lineage_wait percentiles but NO
    -- transaction-latency percentile, so at MPL=1 R_sim = 1/throughput is a ratio of means
    -- while R_pg is a ratio of medians. Comparing the two is an estimator mismatch, not a
    -- measurement, and combined-arm latency here is right-skewed by 11-16% -- the same order
    -- as the W=1 effects themselves. lat_sum_us cannot serve: it is the total over BOTH arms,
    -- so the per-arm mean is unrecoverable from it by any later analysis.
    lat_sum_treat FLOAT,
    lat_sum_base  FLOAT
);

-- Drop EVERY overload by name before creating. CREATE OR REPLACE matches on the full argument
-- signature, so adding a parameter -- even one with a DEFAULT -- creates a SECOND procedure
-- rather than replacing the first. Both then exist, and a call that omits the new argument
-- matches both and fails with "is not unique". That is what happened when p_swap was added: the
-- schema applied cleanly, every existing caller kept working until the first one that relied on
-- a default, and the failure surfaced inside a worker thread mid-run rather than at apply time.
DO $drop$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT oid::regprocedure AS sig FROM pg_proc
              WHERE proname = 'v1b_run_batch'
                AND pronamespace = 'public'::regnamespace
    LOOP
        EXECUTE 'DROP PROCEDURE ' || r.sig;
    END LOOP;
END
$drop$;

CREATE OR REPLACE PROCEDURE v1b_run_batch(
    p_tag       TEXT,
    p_rep       INT,
    p_worker    INT,
    p_k         INT,
    p_d         INT,
    p_pool      INT,
    p_seed      FLOAT,
    p_win_start TIMESTAMPTZ,
    p_win_end   TIMESTAMPTZ,
    p_max_txns  INT DEFAULT 1000000,
    -- PAIRED: alternate a treatment id set (uniform over D, fires ~ Bin(k,f)) with a
    -- baseline id set (non-emitting rows only, fires = 0) at TRANSACTION granularity, so
    -- the two arms are microseconds apart and share one table state. Per-cell pairing was
    -- falsified by the pilot: a rep is minutes long and the machine moves within it.
    p_paired    BOOLEAN DEFAULT FALSE,
    -- Swaps which PARITY carries which arm. Nothing in the measurement should depend on it:
    -- it is the removal test for the null control's W=64 failure. If R survives the swap the
    -- effect is positional (odd vs even transactions); if R inverts, it follows the arm.
    p_swap      BOOLEAN DEFAULT FALSE,
    -- Diagnostic: build the BASELINE pool with the treatment's own construction (rejection
    -- sampling over 1..p_d) instead of drawing from the non-emitting set. Legitimate ONLY at
    -- f=0, where every row is non-emitting and the two constructions therefore sample the same
    -- population -- which makes it the removal test for the null control's failure. If R goes
    -- to 1, the bias is in the baseline pool's construction; if it stays, the construction is
    -- innocent and the bias is in the alternation itself.
    p_base_uniform BOOLEAN DEFAULT FALSE
) AS $$
DECLARE
    v_flat    BIGINT[] := '{}';
    v_ids     BIGINT[];
    i INT; j INT;
    t0 TIMESTAMPTZ; t2 TIMESTAMPTZ;
    s_lat FLOAT := 0;
    mn FLOAT := 1e18;
    mx FLOAT := 0;
    attempted INT := 0;
    counted INT := 0;
    v_retries BIGINT := 0;
    v_att INT;
    in_window BOOLEAN;
    v_lat FLOAT;
    lat_arr FLOAT[];
    lat_cap INT := 100000;   -- 800 KB/worker; a 10 s window at ~0.6 ms holds ~16 k
    v_base  BIGINT[] := '{}';
    v_ne    BIGINT[];            -- every non-emitting id, scanned ONCE for the baseline pool
    v_nn    INT;   -- the baseline pool, non-emitting rows only
    lat_t FLOAT[]; lat_b FLOAT[];
    cnt_t INT := 0; cnt_b INT := 0;
    ret_t BIGINT := 0; ret_b BIGINT := 0;
    slat_t FLOAT := 0; slat_b FLOAT := 0;
    att_t BIGINT := 0; att_b BIGINT := 0;
    is_treat BOOLEAN;
BEGIN
    -- POOL IS A PRECONDITION, NOT A TUNING KNOB. Two claims rest on its size, both with
    -- arithmetic behind them, and both fail silently if a caller lowers it:
    --
    --   COVERAGE. p_pool * p_k row-slots are reused for the whole cell. At pool=64, k=100,
    --   D=80,000 that is 6,400 of 80,000 = 8% of the table, which is what makes within-rep id
    --   reuse immaterial. At pool=8 it is 800 rows, 1%, and the same 800 rows are hit ~1300
    --   times each -- the reuse argument stops holding and nothing in the output looks wrong.
    --
    --   FIRE-COUNT VARIANCE. The realised per-transaction fire distribution is the empirical
    --   one of p_pool hypergeometric draws, so its sd carries ~1/sqrt(2*(pool-1)) relative
    --   error: 9% at 64, 27% at 8. Criterion 1's tolerance was derived from the 64 case.
    --
    -- Enforced here rather than documented, because a documented precondition is one a caller
    -- can pass a number past.
    IF p_paired AND (p_pool % 2) = 0 THEN
        RAISE EXCEPTION 'v1b: paired mode needs an ODD p_pool; % is even, which binds each '
                        'id set permanently to one arm and destroys the pairing.', p_pool;
    END IF;
    IF p_pool < 64 THEN
        RAISE EXCEPTION 'v1b: p_pool = % is below the minimum of 64. Coverage and fire-count '
                        'variance both depend on it -- see the derivation above. Change the '
                        'constant in this file if a rung genuinely needs a different value.',
                        p_pool;
    END IF;
    PERFORM setseed(p_seed);
    lat_arr := array_fill(0.0::FLOAT, ARRAY[lat_cap]);

    -- PRE-SAMPLED, outside the timed loop. `ORDER BY random() LIMIT k` over generate_series sorts
    -- p_d rows; measured at ~0.76 ms/txn if left inside, which alone would swamp the ~0.35 ms
    -- transaction it is meant to be part of. A single fixed array would remove the sampling cost
    -- AND the workload's variation, so a POOL is cycled instead.
    IF p_paired THEN
        v_ne := ARRAY(SELECT id FROM model_a WHERE NOT emits);
        v_nn := coalesce(array_length(v_ne, 1), 0);
        -- The baseline pool must be able to supply p_pool DISJOINT-ish k-subsets; far more
        -- basic than that, it must have more than k rows to draw from at all. At f -> 1 the
        -- non-emitting set shrinks, and the failure would otherwise appear as duplicate ids
        -- rather than as a stated precondition.
        IF v_nn < 10 * p_k THEN
            RAISE EXCEPTION 'v1b: only % non-emitting rows for a baseline pool of k=% -- '
                            'f is too high for paired mode at this D', v_nn, p_k;
        END IF;
    END IF;

    -- floor(), not a bare cast. PostgreSQL's ::INT / ::BIGINT ROUNDS rather than truncating,
    -- so (random() * N)::INT + 1 spans 1..N+1 and emits an out-of-range value with probability
    -- 0.5/N per draw. At D=80,000 over 3*k*pool = 19,500 draws that is an 11.5% chance per pool
    -- build of one id that matches NO model_a row -- a transaction silently touching k-1 rows.
    -- The treatment pool's distinctness check cannot see this (a nonexistent id is still
    -- distinct); it surfaced only because the same form indexes an ARRAY on the baseline side,
    -- where the out-of-range index yields NULL and trips the check. floor() spans 1..N exactly.
    FOR j IN 1 .. p_pool LOOP
        -- Draw from 3k CANDIDATES rather than sorting all of D. The old form,
        -- `ORDER BY random() LIMIT k` over generate_series(1, D), sorts D rows per set:
        -- 2.19 s to build the pool at D=80,000 against a 2.0 s warmup, so the build
        -- overran the warmup, sat inside the measurement window, and dragged Little's
        -- identity to 1.54 against W=2 because a worker still building is not in a
        -- transaction. Measured, not theorised -- the smoke run caught it.
        --
        -- 3k draws over D >> k collide rarely (expected duplicates ~ (3k)^2/2D = 0.6 at
        -- D=80,000), so k distinct almost always survive. "Almost always" is why the
        -- distinctness assert below is load-bearing rather than decorative: it is now the
        -- thing that catches a short draw instead of a property of the query's shape.
        v_ids := ARRAY(SELECT DISTINCT floor(random() * p_d)::BIGINT + 1
                       FROM generate_series(1, 3 * p_k) LIMIT p_k);
        -- Asserted, not assumed. generate_series + LIMIT cannot repeat a row, but that is a
        -- property of this query's shape rather than of the requirement, and the requirement is
        -- what V1's dedup-asymmetry argument rests on.
        IF coalesce(array_length(v_ids, 1), 0) <> p_k THEN
            RAISE EXCEPTION 'v1b: id set % has % ids, expected % (is p_k > p_d?)',
                            j, coalesce(array_length(v_ids, 1), 0), p_k;
        END IF;
        IF (SELECT count(DISTINCT u) FROM unnest(v_ids) u) <> p_k THEN
            RAISE EXCEPTION 'v1b: id set % contains duplicates', j;
        END IF;
        v_flat := v_flat || v_ids;

        IF p_paired THEN
            -- Baseline set: k ids from NON-emitting rows, so this transaction fires zero times
            -- by construction rather than by luck. Same precedent as measure_cu_point's
            -- pool partitioning. Asymmetry, stated: at f=0.5 this draws from 40,000 rows
            -- against the treatment's 80,000 -- both uniform k-subsets of spaces vastly larger
            -- than k, and non-emitting rows are a RANDOM subset, so per-row cost should match.
            --
            -- Drawn from v_ne, scanned ONCE above, rather than ORDER BY random() per set.
            -- The per-set form cost 0.777 s of a 2.0 s warmup at f=0.25 and is a full seq scan
            -- + sort of model_a EVERY set -- so at W=64 it is 64 backends scanning the table
            -- concurrently during warmup, contending with each other and with nothing to show
            -- for it. Measured: 0.777 s -> 0.022 s. Same rejection-sampling shape as the
            -- treatment pool, for the same reason: the pool build must finish inside warmup
            -- or it eats the window it was meant to precede.
            -- q.ix is qualified: an unqualified alias collides with this procedure's own
            -- loop variable i, and PL/pgSQL resolves the ambiguity by raising rather than
            -- silently picking one.
            IF p_base_uniform THEN
                v_ids := ARRAY(SELECT DISTINCT floor(random() * p_d)::BIGINT + 1
                               FROM generate_series(1, 3 * p_k) LIMIT p_k);
            ELSE
                v_ids := ARRAY(SELECT v_ne[q.ix] FROM (SELECT DISTINCT floor(random() * v_nn)::INT + 1 AS ix
                                                       FROM generate_series(1, 3 * p_k)
                                                       LIMIT p_k) q);
            END IF;
            IF coalesce(array_length(v_ids, 1), 0) <> p_k THEN
                RAISE EXCEPTION 'v1b: baseline set % short (% of %) -- too few non-emitting rows',
                                j, coalesce(array_length(v_ids, 1), 0), p_k;
            END IF;
            IF (SELECT count(DISTINCT u) FROM unnest(v_ids) u) <> p_k THEN
                RAISE EXCEPTION 'v1b: baseline set % contains duplicates', j;
            END IF;
            v_base := v_base || v_ids;
        END IF;
    END LOOP;
    lat_t := array_fill(0.0::FLOAT, ARRAY[lat_cap]);
    lat_b := array_fill(0.0::FLOAT, ARRAY[lat_cap]);

    -- The loop runs from NOW (warming) until the window closes. It is time-bounded, not
    -- count-bounded, so every worker stops on the same instant rather than after its own
    -- transaction budget -- which is what makes the throughput denominator shared.
    FOR i IN 1 .. p_max_txns LOOP
        EXIT WHEN clock_timestamp() > p_win_end;

        j := ((i - 1) % p_pool);
        -- p_pool is ODD, so id set j appears at positions j, j+pool, j+2*pool ... whose
        -- PARITIES ALTERNATE. With an even pool each set would be permanently bound to one
        -- arm and the two arms would never share id sets -- destroying the pairing that is
        -- the entire point of this mode.
        is_treat := (p_paired IS FALSE) OR ((i % 2) = (CASE WHEN p_swap THEN 0 ELSE 1 END));
        IF is_treat THEN
            v_ids := v_flat[j * p_k + 1 : (j + 1) * p_k];
        ELSE
            v_ids := v_base[j * p_k + 1 : (j + 1) * p_k];
        END IF;

        t0 := clock_timestamp();
        v_att := try_acquire_all_retry(v_ids);
        UPDATE model_a SET v = v + 1 WHERE id = ANY(v_ids);
        COMMIT;                      -- deferred constraint trigger fires HERE, locks still held
        t2 := clock_timestamp();
        attempted := attempted + 1;

        -- THE gate. Computed once, on the COMMIT instant, and read by every accumulator below.
        in_window := (t2 >= p_win_start AND t2 <= p_win_end);
        IF in_window THEN
            v_lat := EXTRACT(EPOCH FROM (t2 - t0)) * 1e6;
            s_lat := s_lat + v_lat;
            IF v_lat < mn THEN mn := v_lat; END IF;
            IF v_lat > mx THEN mx := v_lat; END IF;
            v_retries := v_retries + (v_att - 1);
            IF is_treat THEN
                ret_t := ret_t + (v_att - 1); att_t := att_t + 1;
            ELSE
                ret_b := ret_b + (v_att - 1); att_b := att_b + 1;
            END IF;
            counted := counted + 1;
            IF counted <= lat_cap THEN lat_arr[counted] := v_lat; END IF;
            IF p_paired THEN
                IF is_treat THEN
                    cnt_t := cnt_t + 1;
                    slat_t := slat_t + v_lat;
                    IF cnt_t <= lat_cap THEN lat_t[cnt_t] := v_lat; END IF;
                ELSE
                    cnt_b := cnt_b + 1;
                    slat_b := slat_b + v_lat;
                    IF cnt_b <= lat_cap THEN lat_b[cnt_b] := v_lat; END IF;
                END IF;
            END IF;
        END IF;
    END LOOP;

    INSERT INTO v1b_batch_result
    SELECT p_tag, p_rep, p_worker, p_k, p_d, p_seed, attempted, counted,
           s_lat, CASE WHEN counted = 0 THEN NULL ELSE mn END,
           CASE WHEN counted = 0 THEN NULL ELSE mx END,
           percentile_cont(0.5)  WITHIN GROUP (ORDER BY u),
           percentile_cont(0.25) WITHIN GROUP (ORDER BY u),
           percentile_cont(0.75) WITHIN GROUP (ORDER BY u),
           percentile_cont(0.95) WITHIN GROUP (ORDER BY u),
           counted > lat_cap,
           cnt_t, cnt_b,
           (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY u) FROM unnest(lat_t[1:cnt_t]) u),
           (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY u) FROM unnest(lat_b[1:cnt_b]) u),
           (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY u)
              FROM unnest(lat_t[1:GREATEST(cnt_t/2,1)]) u),
           (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY u)
              FROM unnest(lat_t[GREATEST(cnt_t/2,1):GREATEST(cnt_t,1)]) u),
           (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY u)
              FROM unnest(lat_b[1:GREATEST(cnt_b/2,1)]) u),
           (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY u)
              FROM unnest(lat_b[GREATEST(cnt_b/2,1):GREATEST(cnt_b,1)]) u),
           p_win_start, p_win_end, v_retries, ret_t, ret_b, slat_t, slat_b
      FROM unnest(lat_arr[1:LEAST(counted, lat_cap)]) u;
END;
$$ LANGUAGE plpgsql;


-- Fire counting stays MARGINAL -- sum(v) on model_b differenced across the window, never
-- conditioned on per-attempt state (L5). It reads at the window bounds and nowhere else, and it
-- waits for those bounds on the SERVER clock so its two readings sit on the same origin as the
-- workers' commit gate. pg_sleep of a negative interval returns immediately, so a bound already
-- passed degrades to "read now" rather than erroring -- and the returned timestamp records when
-- the read actually happened, so lateness is visible in the data instead of assumed away.
CREATE OR REPLACE FUNCTION v1b_read_model_b_at(p_when TIMESTAMPTZ)
RETURNS TABLE (read_at TIMESTAMPTZ, total BIGINT) AS $$
BEGIN
    PERFORM pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (p_when - clock_timestamp()))));
    RETURN QUERY SELECT clock_timestamp(), coalesce(sum(v), 0)::BIGINT FROM model_b;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------------------------
-- PIN THE TRIGGER BODY (PREREG Amendment 5h(e)).
--
-- Until now this file did NOT define cpr_a_to_b, and v1b_sweep applies only this file. The
-- trigger body -- which is precisely what the campaign measures the cost of -- was therefore
-- whatever the database happened to be left in by the last script to touch it. schema.sql still
-- carries the FOR UPDATE variant, so re-applying it would silently change the measured quantity
-- between campaigns, and plan_blob would not move because the pre-registration did not change.
--
-- The PLAIN body is pinned, matching what was installed for the plain2 and ptrial runs and
-- verified by reading pg_proc.prosrc rather than by assuming. The FOR UPDATE was removed after
-- an adversarial test produced zero deadlocks in 24 cells at P(share >= 2) = 0.96, because
-- try_acquire_all_retry's all-or-nothing non-blocking preclaim makes model_b wait-for cycles
-- structurally impossible.
--
-- This also fixes the WAL accounting registered in Amendment 5g(c): with FOR UPDATE each fire
-- emits an XLOG_HEAP_LOCK as well as an XLOG_HEAP_UPDATE, so a fire would cost two WAL records
-- against a parent row's one, and c_wal's null would be 2 rather than 1 for reasons having
-- nothing to do with cascade cost.
CREATE OR REPLACE FUNCTION cpr_a_to_b() RETURNS TRIGGER AS $$
BEGIN
    UPDATE model_b SET v = v + 1 WHERE id = NEW.id;   -- identity correspondence, fan-out 1
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------------------------
-- ASSERT THE ADVISORY KEY IS THE IDENTITY FUNCTION (PREREG V2 note, 2026-09-12).
--
-- Load-bearing, and it fails SILENTLY if broken. The claim that CPRs at fan-out 1 add no new
-- conflict opportunities runs like this: try_acquire_all_retry serialises transactions on the id
-- space, the cascade's targets are a SUBSET of the preclaimed ids (identity correspondence), so
-- two concurrent transactions never target the same model_b row. Advisory locks and row locks are
-- separate namespaces -- the preclaim does not "cover" the row lock, it keeps the transactions
-- apart in the first place.
--
-- That subset argument is exact ONLY while the advisory key is the raw id. Hash the ids into a
-- smaller key space and two transactions with DISJOINT id sets can collide on a lock, while two
-- with overlapping sets could in principle miss each other -- and every claim built on "they were
-- already kept apart" fails with nothing visibly going wrong. No error, no deadlock, just a
-- contention structure that no longer matches the one the analysis assumes.
--
-- So it is asserted rather than commented. Tested at both ends of the range, because a hash that
-- happened to be the identity on small values would otherwise pass.
--
-- EXERCISED 2026-09-13 against PostgreSQL 16.15, and VERIFIED BY REMOVAL rather than merely
-- observed to pass -- a check that cannot fail proves nothing (C15):
--   * applied to a clean anchor          -> assertion PASSED, probe locks leaked: 0
--   * try_acquire_all_retry replaced with a variant hashing ids into 1000 keys
--                                        -> assertion FIRED, and with the PROPERTY diagnosis,
--                                           not the check-is-broken one: pg_locks saw all 5
--                                           locks, so the introspection worked and the
--                                           mismatch was real.
-- The removal test mutates a schema object, so per C17 it ran in a SEPARATE database
-- (anchor_assert_test, created and dropped), never against the anchor.
-- The pg_locks encoding is separately confirmed from the PostgreSQL 16 docs, quoted inline
-- at the predicate below.
DO $advisory_identity$
DECLARE
    -- PROBE IDS ARE DEPRIVATISED FROM THE WORKLOAD'S RANGE. The earlier set (1, 42, 80000) was
    -- exactly what a concurrent campaign preclaims at D = 80,000, so this block could have
    -- blocked in try_acquire_all_retry's retry loop instead of asserting anything -- at the
    -- default million attempts and a 1 ms delay, a ~17 minute stall at schema apply. The identity
    -- property does not care what the values are, so they are moved above any plausible D
    -- (the largest configured anywhere in this project is 80,000).
    --
    -- 2^32+1 is kept deliberately: it is the only id here that exercises the HIGH half of the
    -- key, so a mapping that silently truncated to 32 bits would pass on all the others.
    v_ids   BIGINT[] := ARRAY[1000000001, 1000000042, 1000080000, 2147483646, 4294967297];
    v_id    BIGINT;
    v_att   INT;
    v_total INT;
    v_hits  INT;
BEGIN
    -- Small max_attempts, and -1 means THE CHECK COULD NOT RUN rather than the property failing.
    -- With the probe ids outside the workload range the only plausible holder is a second copy of
    -- this same block, which run_lock exists to prevent -- so this branch firing is itself
    -- information.
    v_att := try_acquire_all_retry(v_ids, 100, 0.001);
    IF v_att = -1 THEN
        RAISE EXCEPTION
            'THE CHECK COULD NOT RUN, and this is not a property failure: 100 attempts to preclaim '
            'the probe ids all failed, so another session holds one. The probe ids sit above any '
            'workload D, so the likely cause is a concurrent schema apply -- which run_lock should '
            'have prevented. Re-run once the anchor is quiet.';
    END IF;

    -- Distinguish "the CHECK is broken" from "the PROPERTY is broken": they have opposite fixes,
    -- and this block runs at every schema apply, so a wrong introspection would block campaigns
    -- while reading as a hashing defect.
    SELECT count(*) INTO v_total
      FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid();
    IF v_total = 0 THEN
        RAISE EXCEPTION
            'THE CHECK IS BROKEN, not the property: % advisory locks were just taken but '
            'pg_locks shows none for this backend. Fix this assertion''s introspection before '
            'reading anything into it.', array_length(v_ids, 1);
    END IF;

    FOREACH v_id IN ARRAY v_ids LOOP
        -- Predicate taken verbatim from the PostgreSQL 16 docs for pg_locks: "A bigint key is
        -- displayed with its high-order half in the classid column, its low-order half in the
        -- objid column, and objsubid equal to 1. The original bigint value can be reassembled
        -- with the expression (classid::bigint << 32) | objid::bigint."
        --
        -- objsubid = 1 is REQUIRED, not decoration: the two-int form pg_advisory_lock(int, int)
        -- writes objsubid = 2 with the two keys in classid/objid, so without this filter a
        -- two-int lock taken elsewhere in the same backend could match and trip the assertion
        -- for a reason unrelated to the key mapping -- a false FAIL at every schema apply.
        --
        -- Using the documented reassembly rather than a classid = 0 special case makes the test
        -- correct at ANY key magnitude, which is what lets 2^32+1 above be a real test rather
        -- than an expected failure.
        SELECT count(*) INTO v_hits
          FROM pg_locks
         WHERE locktype = 'advisory'
           AND pid = pg_backend_pid()
           AND objsubid = 1
           AND ((classid::BIGINT << 32) | objid::BIGINT) = v_id;
        IF v_hits <> 1 THEN
            RAISE EXCEPTION
                'ADVISORY KEY IS NOT THE IDENTITY FUNCTION for id %: pg_locks shows % matching '
                'lock(s), out of % advisory locks held by this backend. The subset argument -- '
                'that CPRs at fan-out 1 add no NEW conflict opportunities, because the cascade''s '
                'targets are a subset of the preclaimed ids -- depends on this, and fails '
                'SILENTLY without it. Do not measure contention until it is restored or the '
                'argument is rewritten.', v_id, v_hits, v_total;
        END IF;
    END LOOP;
END
$advisory_identity$;

-- LOCK LIFETIME, so a future caller does not inherit a surprise. try_acquire_all_retry takes
-- pg_try_advisory_XACT_lock, so the five probe locks release when this DO block's transaction
-- commits. Every current caller applies this file with psycopg2 autocommit = True, where that is
-- immediate and nothing leaks into a campaign. A caller that wrapped the schema apply in an
-- EXPLICIT transaction would hold all five for its whole duration -- harmless at these ids, since
-- they sit above any workload D, but it would no longer be true that the apply leaves no locks
-- behind.
