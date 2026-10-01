"""V2 density-arm sweep driver: resumable, append-only, one cell at a time.

RESUMABILITY IS A DESIGN REQUIREMENT, NOT A CONVENIENCE. A driver that can re-run ONE cell in
isolation is what the (u3) exclusion rule needs: cells failing the achieved-vs-analytic gate have to
be inspected and possibly re-run individually, not as part of a fresh sweep. Losing a 3-hour run to
a transient fault is the lesser reason.

THREE RULES, each learned the hard way (see V2 (z12)):

1. ROWS ARE WRITTEN AS EACH CELL COMPLETES, never batched at the end -- R1's own rule, "raw rows are
   written per seed as they complete, so a cell's data is never lost to a later abort". It also
   makes resumption need no state file: the CSV *is* the state, so the two cannot drift apart.

2. "ALREADY DONE" MEANS ROW EXISTS **AND** GATE PASSED. A row that exists but failed its (t2) gate
   is re-run, because otherwise a re-run after fixing the cause would skip exactly the cells the fix
   was for. Three states, not two: absent / present-and-failed / present-and-passed.

3. NEVER OVERWRITE. Every row carries a `run_id`, and re-runs APPEND. Today produced several cases
   where the interesting thing was the difference between two runs of the same cell -- the n=1 vs
   n=5 size-arm legs, and the before/after of the trigger-firing zeroing. Overwriting destroys
   exactly that.

SEED POLICY (V2 (z7)): the topology seed is FIXED WITHIN a rep and VARIED ACROSS reps. Rep r uses
`seed_base + r` for the schema (link graph AND emitting sets) and for the transaction draw, so all
cells in a rep share one graph realization -- cells stay paired and comparable -- while the reps give
the density claim independent graphs. A cell's density is then a mean over reps with BETWEEN-REP
spread, which is the quantity that says whether a result is a property of the topology or of one
draw of it.

ORDER INDEPENDENCE: each cell's DDL begins by dropping EVERY `m<h>_p<j>` table, not just the ones it
is about to create. Without that a cell with smaller D or P leaves its predecessor's tables behind --
measured: a D=1/P=2 cell needing 4 tables left a database holding 192 tables and 142 triggers -- and
the database state would depend on run order, which is precisely what rule 1 above exists to avoid.
"""
import argparse
import csv
import io
import json
import os
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import psycopg2

import run_lock
from v2_schema_gen import generate, saturates
from workload_client import DEFAULT_DSN

DEPTHS = (1, 2, 3, 4)
FANOUTS = (2, 3)
P_MULTIPLIERS = (1, 4, 16)          # P in {mu, 4mu, 16mu} -- the density axis, V2 (u2)

# W = 1 ONLY. The load axis is BUILT and verified (z16) -- deadlock-free under sorted acquisition,
# gate_ratio and dup_fraction identical at every W -- but DEFERRED by (z23): its throughput and
# latency columns are not steady-state measurements until (z19)'s warmup and (z21)'s settable
# SIMULATION_SECONDS exist. Re-enable by restoring (1, 4, 16, 64); nothing else needs to change.
LOADS = (1,)

# CANARY (V2 (z22)): a fixed cell re-measured through the sweep so container-state drift (z20) is
# OBSERVED rather than assumed absent. Chosen for three properties -- cheap (~26 s), sensitive (the
# cell where the 2.3x warm/cold spread was measured), and FIXED including its seed, which the
# per-rep policy (z7) otherwise varies, so any movement is attributable to container state alone.
#
# W AMENDED FROM (z22)'s 64 TO 1. (z22) picked W=64 because that is where the spread was observed,
# but (z23) then settled the grid at W=1. A canary must guard the regime the grid RUNS in: at W=64
# it could show drift that never reaches a W=1 cell, or miss drift that does. Sensitivity is worth
# less than representativeness here.
CANARY = dict(depth=3, fanout=3, properties=48, load=1, seed=999_001)
CANARY_EVERY = 24                   # once per rep's worth of cells; fixed BEFORE any data (z22)

KEY = ("rep", "depth", "fanout", "properties", "load")


def cells():
    for d in DEPTHS:
        for mu in FANOUTS:
            for mult in P_MULTIPLIERS:
                for w in LOADS:
                    yield d, mu, mu * mult, w


def done_keys(path):
    """Keys that are DONE = present AND gate-passed. Present-but-failed is deliberately not done."""
    done = set()
    if not os.path.exists(path):
        return done
    with open(path, newline="") as fh:
        for r in csv.DictReader(fh):
            if r.get("kind", "cell") != "cell":
                continue          # canary rows are not grid cells (z22)
            if str(r.get("gate_pass", "")).lower() == "true":
                done.add(tuple(str(r[k]) for k in KEY))
    return done


def apply_schema(dsn, sql):
    conn = psycopg2.connect(dsn)
    try:
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute(sql)
    finally:
        conn.close()


def measure(dsn, d, mu, p, rows, k, f0, f_prop, txns, probes, seed, load):
    """Run the client as a SEPARATE PROCESS.

    Process isolation per cell is deliberate: a fresh connection and interpreter for every cell is
    what makes a single cell re-runnable in isolation and identical to its in-sweep run. Parsing one
    JSON line is also a value read back from the child, not an exit code -- C24.
    """
    out = subprocess.run(
        [sys.executable, os.path.join(HERE, "v2_client.py"), "--dsn", dsn,
         "--depth", str(d), "--fanout", str(mu), "--properties", str(p), "--rows", str(rows),
         "--k", str(k), "--f0", str(f0), "--f-prop", str(f_prop), "--txns", str(txns),
         "--probes", str(probes), "--seed", str(seed),
         "--workers", str(load), "--json"],
        capture_output=True, text=True,
        # Tell the child that the lock it will find is OURS, so it proceeds instead of aborting.
        # Same convention run_java.py uses for the JVM guard. Without it the driver deadlocks
        # against itself: parent holds the lock, child asks for it, child exits 1.
        env=dict(os.environ, TSSIM_RUN_LOCK_PID=str(os.getpid())))
    line = next((l for l in reversed(out.stdout.splitlines()) if l.startswith("{")), None)
    if line is None:
        raise RuntimeError("client produced no JSON for D=%d mu=%d P=%d seed=%d\nstderr:\n%s"
                           % (d, mu, p, seed, out.stderr[-2000:]))
    return json.loads(line)


def run_one(dsn, d, mu, p, space, k, f0, f_prop, txns, probes, seed, load):
    """Apply a cell's schema and measure it. Returns (row, ddl_seconds).

    n_rows is DERIVED, never passed in: `space // p` holds the level-0 draw space constant across
    cells so it cannot co-vary with P (z18) -- at fixed n_rows instead, VIF(density | fires, space)
    is 7.10, above (u4)'s stop condition of 5.
    """
    rows = max(500, space // p)
    t0 = time.perf_counter()
    apply_schema(dsn, generate(d, mu, p, rows, f0, f_prop, seed))
    ddl_s = time.perf_counter() - t0
    row = measure(dsn, d, mu, p, rows, k, f0, f_prop, txns, probes, seed, load)
    row["draw_space"] = p * rows
    return row, ddl_s


def append_row(path, row, fields_holder):
    """Write ONE row as the cell completes. Never batched, never overwritten (z12 rules 1 and 3)."""
    new = not os.path.exists(path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if fields_holder[0] is None:
        fields_holder[0] = sorted(row)
    with open(path, "a", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=fields_holder[0], extrasaction="ignore")
        if new:
            wr.writeheader()
        wr.writerow(row)


def run_canary(a, run_id, index, fields_holder):
    """Re-measure the fixed canary cell so container drift is OBSERVED, not assumed absent (z22).

    Uses CANARY's own seed, NOT `seed_base + rep`: the point is that every parameter is identical at
    every firing, so the only thing that can move the number is the machine underneath it. A canary
    that varied with rep would confound drift with topology realization -- which is exactly what
    (z7) makes the grid cells do on purpose, and exactly what this must not do.
    """
    row, ddl_s = run_one(a.dsn, CANARY["depth"], CANARY["fanout"], CANARY["properties"],
                         a.space, a.k, a.f0, a.f_prop, a.txns, a.probes,
                         CANARY["seed"], CANARY["load"])
    row.update({"run_id": run_id, "rep": 0, "load": CANARY["load"], "ddl_s": round(ddl_s, 3),
                "arm": "postgres", "kind": "canary", "canary_index": index})
    append_row(a.out, row, fields_holder)
    return row


def sweep(a, run_id, todo):
    """Run the cells, firing the canary every CANARY_EVERY of them. Returns the canary readings."""
    fields = [None]
    canaries = []
    for n, (rep, d, mu, p, w) in enumerate(todo):
        # CANARY FIRST, so the sweep opens with a baseline rather than a mid-run reading and every
        # interval is bracketed by two firings (z22).
        if n % CANARY_EVERY == 0:
            c = run_canary(a, run_id, len(canaries), fields)
            drift = ""
            if canaries:
                drift = "  drift vs first %+.1f%%" % (
                    100.0 * (c["throughput_txn_s"] / canaries[0]["throughput_txn_s"] - 1.0))
            canaries.append(c)
            print("  CANARY #%d  tput %.2f/s  dup %.4f  gate %.4f%s"
                  % (len(canaries) - 1, c["throughput_txn_s"], c["dup_fraction"],
                     c["gate_ratio"], drift))

        row, ddl_s = run_one(a.dsn, d, mu, p, a.space, a.k, a.f0, a.f_prop,
                             a.txns, a.probes, a.seed_base + rep, w)
        row.update({"run_id": run_id, "rep": rep, "load": w, "ddl_s": round(ddl_s, 3),
                    "arm": "postgres", "kind": "cell"})
        append_row(a.out, row, fields)
        print("  rep%d D=%d mu=%d P=%-3d  ddl %4.1fs  gate %.4f %s  dup %.4f"
              % (rep, d, mu, p, ddl_s, row["gate_ratio"],
                 "PASS" if row["gate_pass"] else "**FAIL**", row["dup_fraction"]))
    return canaries


def report_canaries(canaries):
    """Report drift rather than averaging it away."""
    if len(canaries) < 2:
        return
    t = [c["throughput_txn_s"] for c in canaries]
    print("")
    print("  CANARY SUMMARY  %d firings  min %.2f  max %.2f  max/min %.2fx"
          % (len(t), min(t), max(t), max(t) / min(t)))
    print("  Every canary parameter including its seed is identical at every firing (z22), so this")
    print("  movement is CONTAINER STATE and nothing else. A large ratio means cell order and")
    print("  container warmth are confounded across this sweep -- report it, do not average it.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dsn", default=DEFAULT_DSN.replace("dbname=tgs", "dbname=v2_cascade"))
    ap.add_argument("--out", default=os.path.join(HERE, "artifacts", "experiments", "results",
                                                  "v2_postgres_raw.csv"))
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--seed-base", type=int, default=12345)
    ap.add_argument("--space", type=int, default=240_000,
                    help="P * n_rows, held CONSTANT across cells so the level-0 draw space does not "
                         "co-vary with P. n_rows is derived per cell as space // P. See V2 (z18): "
                         "with n_rows fixed instead, VIF(density | fires, space) = 7.10, above the "
                         "(u4) stop condition of 5.")
    ap.add_argument("--k", type=int, default=100)
    # f = 1.0 on BOTH sides is the only value the two arms can both express: the simulator's
    # emitting fraction is one global number (CprSchemaBuilder selects from a globally shuffled
    # eligible pool), so f0 != f_prop is unrepresentable there, and f0 = f_prop = 0.10 is the
    # inert-depth case (x3) rejected. semanticOverlap = D/(D+1) is its mirror. See V2 (z24).
    ap.add_argument("--f0", type=float, default=1.0)
    ap.add_argument("--f-prop", dest="f_prop", type=float, default=1.0)
    ap.add_argument("--txns", type=int, default=100)
    # 20, not 100. (z5) raised it because R was Binomial(k, f0) and a shared seed made a low
    # probe segment a grid-wide offset. At f0 = 1.0 every drawn row is a root, so R = k exactly
    # with zero variance -- measured 100.0 at every probe count -- and the only remaining probe
    # variance is which rows the draws hit. Measured deviation from the 100-probe value: 1.9% at
    # 20 probes, against a dup_fraction range of 0 to ~0.9 across the grid. See V2 (z25).
    ap.add_argument("--probes", type=int, default=20)
    ap.add_argument("--dry-run", action="store_true", help="list what would run, measure nothing")
    a = ap.parse_args()


    run_id = uuid.uuid4().hex[:12]
    done = done_keys(a.out)
    todo = [(rep, d, mu, p, w)
            for rep in range(1, a.reps + 1)
            for (d, mu, p, w) in cells()
            if (str(rep), str(d), str(mu), str(p), str(w)) not in done]

    print("V2 DENSITY SWEEP  run_id=%s" % run_id)
    print("  grid      %d depths x %d fanouts x %d P-levels x %d loads = %d cells/rep"
          % (len(DEPTHS), len(FANOUTS), len(P_MULTIPLIERS), len(LOADS), len(list(cells()))))
    print("  reps      %d   seed = %d + rep  (topology fixed within a rep, varied across -- (z7))"
          % (a.reps, a.seed_base))
    print("  already done (row exists AND gate passed): %d" % len(done))
    print("  to run    %d" % len(todo))
    sat = sum(1 for (_, d, mu, p, _) in todo if saturates(d, mu, p))
    print("  saturated %d of those are pigeonhole-SATURATED (mu^D > P): measured and marked," % sat)
    print("            not skipped -- (x2) excludes them from the DENSITY READ, not the run.")

    if a.dry_run:
        for t in todo[:8]:
            print("   would run rep=%d D=%d mu=%d P=%d W=%d" % t)
        print("   ... %d more" % max(0, len(todo) - 8))
        return 0

    report_canaries(sweep(a, run_id, todo))
    return 0


if __name__ == "__main__":
    with run_lock.held():
        sys.exit(main())
