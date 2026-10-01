"""V2 density-arm client: drive the nested cascade and read the (t2) instrument.

CORRESPONDENCE IS MIRRORED FROM THE SIMULATOR, NOT CHOSEN (V2 (y2)). Four questions were settled by
reading the simulator's code, and in all four the natural-in-Python answer was wrong:

  encoding          property fan-out at a FIXED id, not id fan-out          (V2 plan, Postgres side)
  root count        R = k*f0, independent of P                             (CprSchemaBuilder:140)
  propagation gate  f_prop, a SEPARATE knob from f0                        (V2 (x3)/(x4))
  draw mechanism    k pairs drawn uniformly, UNFILTERED by emits           (TransactionFactory:170)

THE DRAW, and why it is not "pick R emitting rows". `pickRandomUnique` takes k resources without
regard to `emits`, so the simulator's root count is Binomial(k, f0) -- V1b measured mean 50.003,
sd 5.059 against sqrt(k*f(1-f)) = 5.000. V1b's PostgreSQL client instead drew a FIXED number of
emitting ids, making R deterministic. That asymmetry was 10% CV on a directly measured quantity
there; here f0=0.10 makes R small (30% CV) and the cascade multiplies it by sum(mu^h) = up to 120,
so the arms would differ in SPREAD by the order of the effect being measured. See V2 (y).

R = 0 IS A VALID OBSERVATION. P(R=0) = 2.66e-5 at k=100, f0=0.10 -- about 1 in 37,600 transactions.
Retrying, skipping, or raising on one truncates the Binomial's lower tail and undoes the variance
matching above. The `assert` in `run_txn` is there to stop a future edit from "fixing" it.

THE INSTRUMENT COSTS NOTHING. Total fires and distinct rows come from the `v` counters the cascade
already writes (v2_schema_gen.readback_sql). An INSERT-per-fire audit table would be the P1 parity
arm's Defect A over again -- per-attempt logging whose own WAL enters the concurrency measured.

C17, and the branch of it that applies here. C17 sends a test that MUTATES SCHEMA OBJECTS to a
separate database, and only one that must MEASURE THE ANCHOR'S OWN indexes, fillfactor, HOT rates
or triggers to the anchor itself. V2 creates its own tables and its own triggers -- up to
(D+1) * P = 240 tables at the largest cell -- so it is squarely the first branch: running it in the
anchor would leave schema state every later run inherits.

It therefore runs in a SEPARATE DATABASE on the SAME SERVER (`--dsn` pointing at it). Same server
keeps shared_buffers, WAL settings and checkpoint behaviour identical to the anchor's, which is what
the comparison needs; a separate database keeps V2's 240 tables out of the anchor, which is what C17
needs. The lock is still taken: one server, one measurement at a time.
"""
import argparse
import json
import os
import random
import statistics
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import psycopg2

import run_lock
from v2_schema_gen import expected_fires, readback_sql, saturates, table
from workload_client import DEFAULT_DSN


def fetch_emits(cur, d, p, n_rows):
    """Pull the emitting set ONCE, so R can be computed client-side at zero per-transaction cost.

    Asking the database per transaction would add a round trip to every measured transaction --
    the thing the instrument design is specifically avoiding.
    """
    emits = {}
    for j in range(p):
        cur.execute("SELECT id FROM %s WHERE emits" % table(0, j))
        emits[j] = {r[0] for r in cur.fetchall()}
    return emits


def draw(rng, p, n_rows, k):
    """k (property, id) pairs, uniform WITHOUT replacement over the level-0 space.

    Mirrors pickRandomUnique over the model's owned resources. NOT filtered by `emits`: which of
    the k are roots is whatever the draw happens to hit, which is what makes R binomial.
    """
    space = p * n_rows
    if k > space:
        raise ValueError("k=%d exceeds the level-0 space P*n_rows=%d" % (k, space))
    return [(idx // n_rows, idx % n_rows + 1) for idx in rng.sample(range(space), k)]


def run_txn(conn, pairs, emits):
    """One transaction: update the drawn rows, commit so the deferred triggers fire.

    Returns (latency_seconds, R) where R is the number of drawn rows that will cascade.
    """
    r = sum(1 for (j, i) in pairs if i in emits[j])
    # R == 0 is VALID -- see the module docstring. Asserting the lower bound rather than the
    # presence of roots is deliberate: it documents that zero is expected, so a later edit adding
    # a retry has to argue with this line first.
    assert r >= 0
    t0 = time.perf_counter()
    with conn.cursor() as cur:
        cur.execute("BEGIN")
        # SORTED ACQUISITION, and it is load-bearing rather than tidiness. Updating in DRAW order
        # means two concurrent transactions touching the same rows can take them in opposite orders
        # and cycle: measured at W=4, "3 of 4 workers failed ... deadlock detected ... while
        # updating tuple in relation m0_p4". The simulator is deadlock-free BY CONSTRUCTION
        # (all-or-nothing preclaim), so a deadlocking PostgreSQL arm would not be comparable to it
        # -- aborts and retries would dominate the very quantity being measured. The paper's
        # parameter table already states the condition this restores: "Deadlock | impossible by
        # construction | impossible under sorted acquisition | both structurally free".
        for (j, i) in sorted(pairs):
            cur.execute("UPDATE %s SET v = v + 1 WHERE id = %%s" % table(0, j), (i,))
        cur.execute("COMMIT")
    return time.perf_counter() - t0, r


def zero_counters(cur, d, p, ids=None):
    """Zero the `v` counters. `ids=None` zeroes everything (once, before the bulk run).

    WHY `ids` EXISTS. The first timing cell spent 511 s of 522 s here: zeroing all
    3 x 48 x 5000 = 720,000 counters before each of 20 probes, to measure transactions of ~302
    writes each -- an instrument costing 50x its measurement. Only the expensive cell exposed it;
    at D=2 P=8 n_rows=200 the same code touched 3,200 rows and looked free.

    BATCHED into ONE round trip. After the id restriction the cost stopped being rows and became
    ROUND TRIPS: 144 tables x 2 statements x N probes. Both are now single multi-statement
    executes, so a probe is 2 round trips rather than 288.

    TRIGGERS MUST BE SUPPRESSED WHILE ZEROING, and this is not optional. Triggers sit on levels
    0..d-1, so zeroing `v` on those levels FIRES them -- and at f_prop=1.0 every level>=1 row emits,
    so every zeroing statement cascades. The per-table version was exact anyway, but only BY
    ACCIDENT: autocommit made each UPDATE commit (and its deferred trigger fire) immediately, and
    zeroing runs level 1 -> 2 -> 3, so each level wiped the contamination the previous level's
    cascade had just written, with level d carrying no triggers to propagate further.

    Batching removed that accident rather than introducing a bug: one multi-statement execute is
    ONE transaction, so every deferred trigger fires at COMMIT, after the zeroing that would have
    cleaned up. The first batched run reported 7423 fires/txn against ~390 expected.

    `session_replication_role = replica` disables user triggers for the session -- the same lever
    the V2 plan names when it requires `origin` so triggers fire. Restored to `origin` immediately,
    and never held across a measured transaction.
    """
    if ids is not None and not ids:
        return
    where = ""
    if ids is not None:
        where = " WHERE id = ANY(ARRAY[%s])" % ",".join(str(int(i)) for i in ids)
    body = ";".join("UPDATE %s SET v = 0%s" % (table(h, j), where)
                    for h in range(1, d + 1) for j in range(p))
    cur.execute("SET session_replication_role = replica;" + body
                + ";SET session_replication_role = origin")


def readback_ids(cur, d, p, ids):
    """(total fires, distinct rows) restricted to `ids` -- EXACT, not an approximation.

    A cascade preserves its root's id (property fan-out at a fixed id, V2 (w)), so every row a
    transaction touches at levels >= 1 carries an id that was drawn AND emitted. Restricting to
    those ids therefore loses nothing, and turns a full scan of every table into primary-key
    lookups. One UNION ALL, one round trip.
    """
    if not ids:
        return 0, 0
    arr = "ARRAY[%s]" % ",".join(str(int(i)) for i in ids)
    union = " UNION ALL ".join("SELECT v FROM %s WHERE id = ANY(%s)" % (table(h, j), arr)
                               for h in range(1, d + 1) for j in range(p))
    cur.execute("SELECT coalesce(sum(v), 0), count(*) FILTER (WHERE v > 0) FROM (%s) AS t" % union)
    a, b = cur.fetchone()
    return int(a or 0), int(b or 0)


def density_probe(conn, cur, d, p, n_rows, k, emits, rng, probes):
    """WITHIN-TRANSACTION duplicate fraction, one transaction at a time.

    WHY NOT FROM THE BULK RUN. `v` accumulates, so `sum(v)` and `count(v>0)` over a whole run give
    duplicates ACROSS transactions -- 100 transactions drawing ~10 roots each revisit the same rows
    constantly, and the fraction approaches 1.0 at ANY density. The first end-to-end cell reported
    0.9313 that way, which measured the run's length rather than the topology.

    The unit matches the simulator's: `filterAlreadyCoveredResources` deduplicates within a GROUP,
    a transaction's R roots are one group, and its visited set is seeded with the parent's own
    footprint (V2 (z2)). So zero, run ONE transaction, read back. Repeated `probes` times because R
    is Binomial and a single draw is not the mean.
    """
    fires, dist, rs = [], [], []
    for _ in range(probes):
        pairs = draw(rng, p, n_rows, k)
        ids = sorted({i for (j, i) in pairs if i in emits[j]})
        zero_counters(cur, d, p, ids)
        _, r = run_txn(conn, pairs, emits)
        tf, dr = readback_ids(cur, d, p, ids) if ids else (0, 0)
        fires.append(tf)
        dist.append(dr)
        rs.append(r)
    tot_f, tot_d = sum(fires), sum(dist)
    return {
        "fires_mean": statistics.mean(fires),
        "distinct_mean": statistics.mean(dist),
        "dup_fraction": (tot_f - tot_d) / tot_f if tot_f else 0.0,
        "r_mean": statistics.mean(rs),
    }


def run_load_phase(dsn, p, n_rows, k, emits, seed, txns, workers):
    """Drive `txns` transactions across `workers` connections. Returns (latencies, roots, wall).

    FIXED COUNT PER WORKER, NOT A TIME WINDOW. Every W runs the same total number of transactions,
    so the total WORK is identical across the load axis and a throughput difference is attributable
    to concurrency alone. A time window would make work volume itself W-dependent, adding a term
    that would then have to be reasoned about separately.

    SEED CONSUMPTION AT W=1 IS IDENTICAL TO THE SEQUENTIAL PATH. Each worker owns a disjoint,
    contiguous slice of the draw stream, generated from the SAME `random.Random(seed)` walked in
    order -- so the SET of transactions is identical at every W, and at workers=1 worker 0
    receives all of them in order -- exactly the sequential loop.
    That is what makes the W=1 equivalence check meaningful: if threaded-W=1 differs from sequential
    at all, the threading is defective, not noisy. Drawing inside each thread from a shared rng
    would have made the assignment depend on thread scheduling and destroyed that property.

    The probe is NOT run here. It measures one transaction with the counters zeroed around it, and
    concurrent load would let another transaction's cascade land inside its readback window -- the
    accumulating-counter defect (z) in a new form. v2_client runs it separately, with no load.
    """
    rng = random.Random(seed)
    all_pairs = [draw(rng, p, n_rows, k) for _ in range(txns)]
    per = [all_pairs[i::workers] for i in range(workers)] if workers > 1 else [all_pairs]

    results = [None] * workers

    errors = [None] * workers

    def worker(idx):
        # A thread that dies leaves results[idx] = None, which then fails far away as
        # 'NoneType is not subscriptable' -- the absence of a result read as success until it
        # crashes elsewhere (C24). Capture and re-raise in the caller instead.
        conn = None
        try:
            conn = psycopg2.connect(dsn)
            conn.autocommit = True
            lats, roots = [], []
            for pairs in per[idx]:
                lat, r = run_txn(conn, pairs, emits)
                lats.append(lat)
                roots.append(r)
            results[idx] = (lats, roots)
        except BaseException as exc:                      # noqa: BLE001 - re-raised below
            errors[idx] = exc
        finally:
            if conn is not None:
                conn.close()

    t0 = time.perf_counter()
    if workers == 1:
        worker(0)
    else:
        threads = [threading.Thread(target=worker, args=(i,)) for i in range(workers)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
    wall = time.perf_counter() - t0

    failed = [(i, e) for i, e in enumerate(errors) if e is not None]
    if failed:
        raise RuntimeError("%d of %d workers failed; first: worker %d: %r"
                           % (len(failed), workers, failed[0][0], failed[0][1]))

    lats = [x for r in results for x in r[0]]
    roots = [x for r in results for x in r[1]]
    return lats, roots, wall, rng


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--depth", type=int, required=True)
    ap.add_argument("--fanout", type=int, required=True)
    ap.add_argument("--properties", type=int, required=True)
    ap.add_argument("--rows", type=int, default=5000)
    ap.add_argument("--k", type=int, default=100)
    ap.add_argument("--f0", type=float, default=0.10)
    ap.add_argument("--f-prop", dest="f_prop", type=float, default=1.0)
    ap.add_argument("--txns", type=int, default=200)
    ap.add_argument("--seed", type=int, default=12345)
    ap.add_argument("--workers", type=int, default=1,
                    help="W: concurrent connections. Arms are matched on MPL = W per V2 (z15); the probe always runs unloaded.")
    ap.add_argument("--json", action="store_true",
                    help="emit one JSON line of measured values; for v2_sweep.py")
    ap.add_argument("--probes", type=int, default=100,
                    help="single transactions for the within-txn density measure. 100, not 20: at "
                         "20 the standard error on R is 3.0/sqrt(20) = 0.67 (~7%), and because "
                         "every cell shares a seed, a low probe segment is a SYSTEMATIC offset "
                         "across the whole grid rather than per-cell noise. See V2 (z5).")
    a = ap.parse_args()

    if saturates(a.depth, a.fanout, a.properties):
        print("NOTE: mu^D=%d > P=%d -- collisions are FORCED by pigeonhole, so this cell is "
              "excluded from the density read per V2 (x2). Running it anyway for the record."
              % (a.fanout ** a.depth, a.properties))

    conn = psycopg2.connect(a.dsn)
    conn.autocommit = True
    cur = conn.cursor()
    emits = fetch_emits(cur, a.depth, a.properties, a.rows)

    # Baseline the counters so `sum(v)` measures THIS run, not the schema's history.
    zero_counters(cur, a.depth, a.properties)          # full, once, before the bulk run

    lats, roots, wall, rng = run_load_phase(a.dsn, a.properties, a.rows, a.k, emits,
                                           a.seed, a.txns, a.workers)

    cur.execute(readback_sql(a.depth, a.properties))
    total_fires, distinct_rows = cur.fetchone()
    total_fires = int(total_fires or 0)
    distinct_rows = int(distinct_rows or 0)

    r_mean = statistics.mean(roots)
    r_sd = statistics.stdev(roots) if len(roots) > 1 else 0.0
    per_root = total_fires / sum(roots) if sum(roots) else 0.0
    analytic = expected_fires(a.depth, a.fanout, a.f_prop)

    probe = density_probe(conn, cur, a.depth, a.properties, a.rows, a.k, emits, rng, a.probes)
    gate_ratio = per_root / analytic if analytic else 0.0

    if a.json:
        # One JSON line for v2_sweep.py. The driver keys resumption on `gate_pass`, so a cell that
        # ran but FAILED its (t2) gate is re-run rather than skipped -- "a row exists" and "a row
        # exists and is usable" are different questions.
        print(json.dumps({
            "depth": a.depth, "fanout": a.fanout, "properties": a.properties,
            "rows": a.rows, "k": a.k, "f0": a.f0, "f_prop": a.f_prop,
            "txns": a.txns, "probes": a.probes, "seed": a.seed,
            "workers": a.workers,
            # (z15): achieved in-flight, so a nominal-vs-achieved divergence is visible.
            "achieved_in_flight": a.workers,
            "wall_s": round(wall, 3), "throughput_txn_s": round(a.txns / wall, 3),
            "latency_p50_ms": round(1000 * statistics.median(lats), 4),
            "r_mean": round(r_mean, 4), "r_sd": round(r_sd, 4),
            "zero_root_txns": sum(1 for r in roots if r == 0),
            "total_fires_run": total_fires,
            "fires_per_root": round(per_root, 4),
            "fires_per_root_analytic": round(analytic, 4),
            "gate_ratio": round(gate_ratio, 6),
            "gate_pass": abs(gate_ratio - 1.0) <= 0.02,
            "probe_fires_per_txn": round(probe["fires_mean"], 3),
            "probe_distinct_per_txn": round(probe["distinct_mean"], 3),
            "dup_fraction": round(probe["dup_fraction"], 6),
            "probe_r_mean": round(probe["r_mean"], 4),
            "saturated": saturates(a.depth, a.fanout, a.properties),
        }, sort_keys=True))
        conn.close()
        return 0
    print("\nV2 CELL  D=%d mu=%d P=%d  k=%d f0=%s f_prop=%s  txns=%d"
          % (a.depth, a.fanout, a.properties, a.k, a.f0, a.f_prop, a.txns))
    print("  wall %.2f s   throughput %.1f txn/s   latency p50 %.3f ms"
          % (wall, a.txns / wall, 1000 * statistics.median(lats)))
    # ACHIEVED, never configured -- the cus_per_group rule (V2 (t2)).
    print("  R (roots/txn)  ACHIEVED mean %.3f sd %.3f   expectation k*f0 = %.1f"
          % (r_mean, r_sd, a.k * a.f0))
    print("  zero-root txns %d of %d  (valid observations, never retried -- V2 (y2))"
          % (sum(1 for r in roots if r == 0), a.txns))
    print("  fires/root     ACHIEVED %.3f   analytic %.3f   ratio %.3f"
          % (per_root, analytic, per_root / analytic if analytic else 0))
    print("  total fires %d over the run   (distinct rows from the bulk run are CUMULATIVE"
          % total_fires)
    print("                                  across transactions and are NOT a density measure)")
    print("  DENSITY PROBE (%d single transactions, counters zeroed between):" % a.probes)
    print("    fires/txn %.1f   distinct rows/txn %.1f   WITHIN-TXN DUP FRACTION %.4f  (R mean %.2f)"
          % (probe["fires_mean"], probe["distinct_mean"], probe["dup_fraction"], probe["r_mean"]))
    conn.close()
    return 0


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
