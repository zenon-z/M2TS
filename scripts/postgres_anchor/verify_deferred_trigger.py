"""Behavioural verification of V1's deferred constraint trigger.

Mirrors verify_probe_capture.py / verify_savepoint_release.py: verify, don't assume.
Must pass before any V1 sweep runs.

The DEFERRED firing point is V1's entire premise -- it is what makes the trigger the
analogue of a CU created at parent completion while the parent still holds locks. Reading
`tginitdeferred` off pg_trigger proves the DDL was accepted, not that firing is actually
deferred, so every assertion here is behavioural.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2
from workload_client import DEFAULT_DSN

N = 200
failures = []


def check(name, ok, detail=""):
    print("  %-58s %s%s" % (name, "ok" if ok else "FAIL", "  " + detail if detail else ""))
    if not ok:
        failures.append(name)


def seed(conn, emit_ids):
    with conn.cursor() as cur:
        cur.execute("TRUNCATE model_a, model_b")
        cur.execute("INSERT INTO model_a (id) SELECT generate_series(1, %s)", (N,))
        cur.execute("INSERT INTO model_b (id) SELECT generate_series(1, %s)", (N,))
        cur.execute("UPDATE model_a SET emits = true WHERE id = ANY(%s)", (emit_ids,))
    conn.commit()


def main():
    conn = psycopg2.connect(DEFAULT_DSN)
    conn.autocommit = False
    seed(conn, list(range(1, 51)))          # ids 1..50 emit, 51..200 do not

    # 1. DEFERRAL. The whole premise: model_b must be untouched while the parent's
    #    transaction is still open, and updated only once COMMIT runs.
    with conn.cursor() as cur:
        cur.execute("UPDATE model_a SET v = v + 1 WHERE id = ANY(%s)", ([1, 2, 3],))
        cur.execute("SELECT coalesce(sum(v), 0) FROM model_b")
        mid = cur.fetchone()[0]
    check("1a. model_b unchanged before COMMIT (fire is deferred)", mid == 0,
          "sum(v)=%d, expected 0" % mid)
    conn.commit()
    with conn.cursor() as cur:
        cur.execute("SELECT coalesce(sum(v), 0) FROM model_b")
        after = cur.fetchone()[0]
    check("1b. model_b updated by COMMIT", after == 3, "sum(v)=%d, expected 3" % after)

    # 2. WHEN (OLD.emits) gates firing -- this is how f is applied without redefining
    #    the trigger per sweep point, so a leak here would silently break the f axis.
    seed(conn, list(range(1, 51)))
    with conn.cursor() as cur:
        cur.execute("UPDATE model_a SET v = v + 1 WHERE id = ANY(%s)", ([100, 101, 102],))
    conn.commit()
    with conn.cursor() as cur:
        cur.execute("SELECT coalesce(sum(v), 0) FROM model_b")
        none_fired = cur.fetchone()[0]
    check("2. non-emitting rows fire nothing", none_fired == 0,
          "sum(v)=%d, expected 0" % none_fired)

    # 3. FAN-OUT 1, and it must hold on a MIXED batch: k sampled ids will always contain
    #    both emitting and non-emitting rows, so the count must equal the emitting subset
    #    exactly, not the batch size.
    seed(conn, list(range(1, 51)))
    ids = [10, 20, 30, 60, 70]              # 3 emit (<=50), 2 do not
    with conn.cursor() as cur:
        cur.execute("UPDATE model_a SET v = v + 1 WHERE id = ANY(%s)", (ids,))
    conn.commit()
    with conn.cursor() as cur:
        cur.execute("SELECT coalesce(sum(v), 0) FROM model_b")
        mixed = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM model_b WHERE v > 1")
        multi = cur.fetchone()[0]
    check("3a. mixed batch fires once per EMITTING row only", mixed == 3,
          "sum(v)=%d, expected 3" % mixed)
    check("3b. no target incremented twice (identity correspondence)", multi == 0,
          "%d rows with v>1" % multi)

    # 4. TARGET IDENTITY. Fan-out 1 means source id -> same target id; if this drifted the
    #    dedup-asymmetry trap would bite (PostgreSQL dedupes nothing, the simulator's
    #    filterAlreadyCoveredResources does).
    with conn.cursor() as cur:
        cur.execute("SELECT array_agg(id ORDER BY id) FROM model_b WHERE v > 0")
        hit = cur.fetchone()[0]
    check("4. fired targets are exactly the emitting sources", hit == [10, 20, 30],
          "got %s" % hit)
    conn.close()

    print()
    if failures:
        sys.exit("FAILED: %s" % ", ".join(failures))
    print("all deferred-trigger assertions passed")


main()
