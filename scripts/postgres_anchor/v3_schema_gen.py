"""Generate V3's outbox schema, DERIVE K_pg, and refuse to proceed if a gate fails.

WHAT THIS BUILDS

    v3_parent   N_parent rows. A fraction `f` of them carry a link to a target row; the rest
                carry NULL. This is the simulator's `semanticOverlap`: "fraction of properties
                with at least one outgoing link".
    v3_target   one row per parent id, so a linked parent always has a target to point at.
    v3_outbox   the queue. A UNIQUE PARTIAL INDEX on (target_id) WHERE processed_at IS NULL is
                the dedup: at most one PENDING row per target. That index IS the simulator's
                dirty set, and an index rejection IS a fan-out collapse.

K_pg IS DERIVED, NEVER DECLARED -- and it is NOT the target table's row count.

    On the simulator side K is the reachable dirty-key space, `inDegreeNonzero * instancePoolSize`:
    derived, and f-dependent. The Postgres analogue is the number of DISTINCT target rows a producer
    can reach, i.e. targets with an inbound link -- not every row in `v3_target`. Here the two differ
    by exactly 1/f: `v3_target` holds 80,000 rows while K_pg is 8,000 at f=0.10.

    Declaring `K_pg = target table row count` would therefore have been wrong by 10x, and gate 1
    would have compared c0 at two different K's -- the failure the shared-K rule exists to prevent,
    since c0 scales as 1/K. So this script COUNTS the distinct linkable targets and prints what it
    counted.

WHY THE DEFAULT SIZING IS 80,000 PARENT ROWS

    The simulator's K was MEASURED at both f, not assumed:

        f = 0.10 -> K =  8,000   (inDegreeNonzero 1 x instancePool 8,000)
        f = 0.20 -> K = 16,000   (inDegreeNonzero 2 x instancePool 8,000)

    An injective linked-parent -> target map gives K_pg = f * N_parent, so ONE table sizing of
    80,000 reproduces both, with the same f-dependence. That correspondence is checked by
    --assert-k rather than trusted.

F IS ON A 1/P GRID, THE SAME GRID THE SIMULATOR HAS

    `CprSchemaBuilder` computes `round(totalProperties * f)`, so the simulator's f axis is a multiple
    of 1/totalProperties and f = 0.25 silently became 0.30 at P = 10. This generator applies the same
    representability test, so the two arms cannot disagree about which f they ran.

INJECTIVITY, AND WHY IT IS ASSERTED

    Each linked parent maps to its OWN target, so the f*k targets in one producer draw are always
    distinct and a producer's batch cannot collide with itself. That reproduces the simulator's W=1
    result -- 4.87 keys seen, exactly 0 collapses -- and it is what lets the producer count
    rejections without a self-collision correction. Asserted here, not assumed, because the whole
    self-collision exclusion rests on it.
"""
import argparse
import sys

try:
    import psycopg2
except ImportError:  # pragma: no cover - environment guard
    print("psycopg2 is required: pip install -r requirements.txt", file=sys.stderr)
    raise

DEFAULT_DSN = "host=127.0.0.1 port=15432 dbname=tgs user=tgs password=tgs"

DDL = """
DROP TABLE IF EXISTS v3_outbox CASCADE;
DROP TABLE IF EXISTS v3_parent CASCADE;
DROP TABLE IF EXISTS v3_target CASCADE;

CREATE TABLE v3_target (
    id INTEGER PRIMARY KEY
);

CREATE TABLE v3_parent (
    id        INTEGER PRIMARY KEY,
    target_id INTEGER NULL REFERENCES v3_target(id)
);

CREATE TABLE v3_outbox (
    id           BIGSERIAL PRIMARY KEY,
    target_id    INTEGER     NOT NULL,
    enqueued_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processed_at TIMESTAMPTZ NULL,
    -- The PRODUCER's commit time, captured by the drain from the OLD row version.
    -- It cannot be read after the fact: once the drain UPDATEs the row, xmin is the DRAIN's xid,
    -- so pg_xact_commit_timestamp(xmin) then returns the drain's own commit and the measured
    -- "visibility latency" comes out slightly NEGATIVE -- clock_timestamp() inside a transaction
    -- minus that same transaction's commit. That negative sign is what exposed the mistake.
    visible_at   TIMESTAMPTZ NULL
);

-- THE DEDUP. One PENDING row per target; a second insert for a target already pending is
-- rejected by this index. This index is the simulator's dirty set.
CREATE UNIQUE INDEX v3_outbox_pending ON v3_outbox (target_id) WHERE processed_at IS NULL;

-- The drain reads oldest-first among pending rows.
CREATE INDEX v3_outbox_drain ON v3_outbox (enqueued_at) WHERE processed_at IS NULL;
"""


def representable_f(f, properties):
    """The simulator's f grid: round(P*f)/P. Returns the value the engine would ACTUALLY run."""
    return round(properties * f) / properties


def build(conn, parent_rows, f, properties):
    linked_per_period = round(properties * f)
    with conn.cursor() as cur:
        cur.execute(DDL)
        cur.execute(
            "INSERT INTO v3_target (id) SELECT generate_series(1, %s)", (parent_rows,))
        # Linked iff (id-1) mod P < round(P*f), and then target_id = id: INJECTIVE, so the f*k
        # targets in any one draw are distinct.
        cur.execute(
            """
            INSERT INTO v3_parent (id, target_id)
            SELECT g,
                   CASE WHEN (g - 1) %% %s < %s THEN g ELSE NULL END
              FROM generate_series(1, %s) AS g
            """,
            (properties, linked_per_period, parent_rows),
        )
        cur.execute("ANALYZE v3_parent")
        cur.execute("ANALYZE v3_target")
    conn.commit()


def derive_k_pg(conn):
    """K_pg = distinct targets reachable by a producer. NOT the target table's row count."""
    with conn.cursor() as cur:
        cur.execute("SELECT count(DISTINCT target_id) FROM v3_parent WHERE target_id IS NOT NULL")
        k_pg = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM v3_target")
        target_rows = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM v3_parent")
        parent_rows = cur.fetchone()[0]
        # Injectivity: no target is reachable from two different parents.
        cur.execute(
            """
            SELECT count(*) FROM (
                SELECT target_id FROM v3_parent WHERE target_id IS NOT NULL
                GROUP BY target_id HAVING count(*) > 1
            ) dup
            """
        )
        shared = cur.fetchone()[0]
    return k_pg, target_rows, parent_rows, shared


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--parent-rows", type=int, default=80_000,
                    help="parent table size; K_pg = f * this (default 80000)")
    ap.add_argument("--f", type=float, default=0.10, help="linked fraction (default 0.10)")
    ap.add_argument("--properties", type=int, default=10,
                    help="the f grid denominator, matching the simulator's totalProperties")
    ap.add_argument("--k", type=int, default=100, help="parent rows per producer transaction")
    ap.add_argument("--w-max", type=int, default=64, help="largest W the grid will run")
    ap.add_argument("--pigeonhole-limit", type=float, default=0.2)
    ap.add_argument("--assert-k", type=int, default=None,
                    help="fail unless the DERIVED K_pg equals this (the simulator's K for this f)")
    args = ap.parse_args()

    # --- gate 0: is this f representable at all? The simulator's C25 gate, same arithmetic. ---
    realised = representable_f(args.f, args.properties)
    if abs(realised - args.f) > 1e-9:
        print(f"REFUSED: f={args.f} is NOT REPRESENTABLE on a 1/{args.properties} grid -- "
              f"round({args.properties}*f) would run f={realised} instead. "
              f"The reachable axis is a multiple of {1.0/args.properties}.", file=sys.stderr)
        return 2

    conn = psycopg2.connect(args.dsn)
    try:
        build(conn, args.parent_rows, args.f, args.properties)
        k_pg, target_rows, parent_rows, shared = derive_k_pg(conn)
    finally:
        conn.close()

    offers_per_burst = args.w_max * args.f * args.k
    ratio = offers_per_burst / k_pg if k_pg else float("inf")

    print(f"parent rows            : {parent_rows:,}")
    print(f"target table rows      : {target_rows:,}   <- NOT K_pg")
    print(f"DERIVED K_pg           : {k_pg:,}   (distinct linkable targets, f={args.f})")
    print(f"offers per burst (W={args.w_max}): {offers_per_burst:,.0f}")
    print(f"W*f*k / K_pg           : {ratio:.4f}   (limit {args.pigeonhole_limit})")

    failed = False
    if shared:
        print(f"REFUSED: {shared} target(s) reachable from more than one parent -- the map is NOT "
              f"injective, so a producer's own batch could collide with itself and the "
              f"self-collision exclusion would be wrong.", file=sys.stderr)
        failed = True
    if not ratio < args.pigeonhole_limit:
        print(f"REFUSED: W*f*k/K_pg = {ratio:.4f} is at or above {args.pigeonhole_limit}. "
              f"Collisions would be FORCED by counting, so c0_pg would measure the key space "
              f"rather than the dedup. Raise --parent-rows.", file=sys.stderr)
        failed = True
    if args.assert_k is not None and k_pg != args.assert_k:
        print(f"REFUSED: derived K_pg={k_pg:,} != --assert-k={args.assert_k:,}. Gate 1 compares c0 "
              f"across arms and c0 scales as 1/K, so the two K must be EQUAL, not merely close.",
              file=sys.stderr)
        failed = True

    if failed:
        return 1
    print("\nOK -- schema built, K_pg derived, all gates pass.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
