"""Generate the V2 nested-cascade chain schema for (depth D, fan-out mu, properties P).

WHAT THIS BUILDS, and why the shape is what it is.

The simulator's cascade keeps the SAME instance id and walks to `mu` TARGET PROPERTIES at the next
hop (V2 plan, "Postgres side"). The faithful PostgreSQL encoding is therefore table-per-property:
level `h` is `P` tables `m{h}_p{j}`, and a fire writes `NEW.id` into `mu` of them -- NOT `mu`
different ids in one table. Getting this backwards would model a different cascade.

WHY P IS AN AXIS AND NOT `mu`. V2 (u1)/(u2): pinning `P = mu` makes every hop saturate its level
(mu^h writes onto mu rows), so collision density becomes a near-deterministic function of fire
count -- corr(log F, dup) = +0.93, VIF ~7 -- and neither a matched-pair nor a regression analysis
can identify the density effect. Out-degree determinism does NOT require that pin: it comes from
`avgCprsPerResource = maxCprsPerResource = mu` collapsing the binomial. So P is free, and it is
exactly the density knob. At P in {mu, 4mu, 16mu} the collinearity falls to +0.67, VIF ~1.8.

THE INSTRUMENT IS FREE. V2 (t2) needs total fires, distinct rows touched, and duplicate work, per
arm. The `v` counter already in each table gives all three with NO per-fire bookkeeping:

    total fires     = sum(v)            over all level >= 1 tables
    distinct rows   = count(*) where v > 0
    duplicate work  = total - distinct

An `INSERT`-per-fire audit table would have been the obvious instrument and is the wrong one: it is
the P1 parity arm's Defect A exactly -- per-attempt logging whose own WAL enters the concurrency
being measured. `v` is incremented by the fire that was happening anyway.

ACHIEVED, NEVER INTENDED. Everything above is read back from the database after the run. The
analytic prediction in `expected_fires()` exists to be CHECKED against it (the (t2) gate), never to
stand in for it -- the `cus_per_group` rule that the k/f conflation established.
"""
import argparse
import random

MAX_IDENT = 63  # PostgreSQL identifier limit; names below stay far inside it.

DROP_ALL_SQL = """DO $$ DECLARE r RECORD; BEGIN
  FOR r IN SELECT tablename FROM pg_tables
           WHERE schemaname = 'public' AND tablename ~ '^m[0-9]+_p[0-9]+$'
  LOOP EXECUTE 'DROP TABLE IF EXISTS ' || quote_ident(r.tablename) || ' CASCADE'; END LOOP;
END $$;"""
"""Drop EVERY cascade table, not just the ones the next cell is about to create.

WHY THIS IS NOT COVERED BY THE GENERATED `DROP TABLE IF EXISTS`. That drop names exactly the tables
the cell will create -- levels 0..D, properties 0..P-1. A cell with a SMALLER D or P than its
predecessor therefore leaves the extras in place. Measured: after a D=3/P=48 cell (192 tables), a
D=1/P=2 cell needing 4 tables left the database holding 192 tables and 142 triggers.

The accumulation is bounded (at most max over cells of (D+1)*P), and no stale trigger can fire,
since the surviving tables are ones the new cell never updates. The reason to drop them anyway is
REPRODUCIBILITY: with leftovers the database state depends on RUN ORDER, so re-running one cell in
isolation is not guaranteed to reproduce what it did mid-sweep -- which is precisely the property a
resumable driver exists to provide, and the property the (u3) exclusion rule needs when a failed
cell has to be re-examined on its own.

Pattern-scoped to `m<digits>_p<digits>` so it cannot touch anything else in the database.
"""


def table(h, j):
    return "m%d_p%d" % (h, j)


def targets_for(h, j, mu, p, seed):
    """The `mu` target properties at level h+1 for source property j at level h.

    Seeded and deterministic, so the same (D, mu, P, seed) always yields the same graph and a cell
    can be re-run byte-identically. Sampling WITHOUT replacement mirrors the simulator's link
    builder, which picks `mu` distinct target properties; with replacement would manufacture
    self-collisions that are an artefact of the generator rather than of the topology under test.
    """
    rng = random.Random((seed * 1_000_003) ^ (h * 7919) ^ j)
    return sorted(rng.sample(range(p), mu))


def expected_fires(d, mu, f, f0=1.0):
    """Analytic fire count per emitting root -- the (t2) gate's reference for BOTH ARMS.

    ONE FUNCTION, ON PURPOSE. At f = 1.0 both arms run the same topology at the same emitting
    fraction (V2 (z24)), so the chain sum is the right reference for either. Computing it
    separately per arm would allow two independently-wrong references that happen to agree, or to
    differ for a reason that looks like an arm disagreement.

    GATES THE PostgreSQL ARM ONLY. PostgreSQL has no visited set, so it runs the UNPRUNED chain
    this function computes and matches it exactly (gate_ratio 1.0000, 24/24 cells). The simulator
    dedups: a dropped CU never executes, so it never spawns children, and dedup PRUNES THE TREE
    rather than only the leaf count -- V2 (z27). Its achieved fire count therefore has no closed
    form at fan-out > 1, and V2 (z40) records that the simulator arm has no analytic per-cell gate.
    DO NOT CONSTRUCT ONE FROM THIS FUNCTION.

    Units, because this has already caused one withdrawn conclusion (V2 (z44-CORRECTION)): this
    value is PER ROOT. The simulator's `cus_per_group` is per GROUP, which is k roots -- divide by
    k before any comparison. Normalised that way the arms are genuinely comparable: at D=2/mu=2 the
    simulator's `attempted` per root is 5.9966 against this function's 6 (0.06% off), and its
    `created` is 11.0% lower, equal to the measured dedup fraction. That agreement is a SHALLOW-TREE
    result and does not generalise -- at mu=3 (z27) measured `attempted` 5.3% low, because pruning
    compounds with depth.

    Level 1 always receives `mu` writes from an emitting root. Below that, propagation continues
    only through rows whose own `emits` is true, which is an f-fraction -- so level h contributes
    mu^h * f^(h-1) in expectation. This is the quantity the measured `sum(v)` is checked against;
    a cell that misses it is not measuring the cascade it names.
    """
    return sum((mu ** h) * (f ** (h - 1)) for h in range(1, d + 1))


def saturates(d, mu, p):
    """True when mu^D > P: the deepest level cannot hold its writes, so collisions are FORCED by
    pigeonhole rather than occurring by chance. Such a cell's duplicate fraction is arithmetic, not
    a measurement, and V2 (x2) excludes it from the density read."""
    return mu ** d > p


def generate(d, mu, p, n_rows, f0, f_prop, seed):
    if mu > p:
        raise ValueError("fanout mu=%d exceeds properties per model P=%d: a source cannot have "
                         "more distinct targets than the level has properties" % (mu, p))
    out = ["-- V2 nested-cascade chain: D=%d mu=%d P=%d rows=%d f=%s seed=%d"
           % (d, mu, p, n_rows, "%s/%s" % (f0, f_prop), seed),
           "-- generated by v2_schema_gen.py; see V2 (t2)/(u2) for why P is an axis.",
           "SET session_replication_role = origin;  -- triggers MUST fire; V2 plan, Postgres side",
           DROP_ALL_SQL]

    for h in range(d + 1):
        for j in range(p):
            t = table(h, j)
            out.append("CREATE TABLE %s (id INTEGER PRIMARY KEY, v INTEGER NOT NULL DEFAULT 0, "
                       "emits BOOLEAN NOT NULL DEFAULT false);" % t)
            out.append("INSERT INTO %s (id) SELECT generate_series(1, %d);" % (t, n_rows))
            # autovacuum off for the same reason as V1b: nothing should reintroduce vacuum
            # activity between cells of a timed run.
            out.append("ALTER TABLE %s SET (autovacuum_enabled = off);" % t)

    # `emits` is drawn ONCE per table from a seeded sample of FIXED size, so f is realised exactly
    # rather than binomially -- the property PREREG Amendment 5o(b) verified for V1b's setup.
    for h in range(d + 1):
        # f0 gates ROOT COUNT (level 0 -> R = k*f0); f_prop gates PROPAGATION (levels >= 1).
        # One f for both makes the depth axis inert: at f=0.10 a cascade dies in the first hop
        # (D=1..4 moves total fires only 3.000 -> 4.251 at mu=3), so four depth levels produce
        # four near-identical cells. See V2 (x3).
        n_emit = int(round((f0 if h == 0 else f_prop) * n_rows))
        for j in range(p):
            if n_emit >= n_rows:
                # f = 1.0: every row emits. Listing all n_rows ids would put ~1.2M integers into
                # the DDL across 240 tables -- tens of MB of SQL to express "all of them".
                out.append("UPDATE %s SET emits = true;" % table(h, j))
                continue
            if n_emit <= 0:
                continue
            rng = random.Random((seed * 31) ^ (h * 104_729) ^ (j * 15_485_863))
            ids = sorted(rng.sample(range(1, n_rows + 1), n_emit))
            out.append("UPDATE %s SET emits = true WHERE id = ANY(ARRAY[%s]);"
                       % (table(h, j), ",".join(str(i) for i in ids)))

    for h in range(d):
        for j in range(p):
            src, fn = table(h, j), "cpr_hop_%d_%d" % (h, j)
            tgts = targets_for(h, j, mu, p, seed)
            body = "\n".join(
                "    UPDATE %s SET v = v + 1 WHERE id = NEW.id;" % table(h + 1, t) for t in tgts)
            out.append(
                "CREATE OR REPLACE FUNCTION %s() RETURNS TRIGGER AS $$\nBEGIN\n%s\n"
                "    RETURN NULL;\nEND; $$ LANGUAGE plpgsql;" % (fn, body))
            out.append(
                "CREATE CONSTRAINT TRIGGER trg_%d_%d AFTER UPDATE ON %s\n"
                "    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW\n"
                "    WHEN (OLD.emits) EXECUTE FUNCTION %s();" % (h, j, src, fn))
    return "\n".join(out)


def readback_sql(d, p):
    """The (t2) instrument. Reads the `v` counters the cascade already wrote -- no extra writes."""
    parts = ["SELECT sum(v)::BIGINT AS total_fires, "
             "count(*) FILTER (WHERE v > 0)::BIGINT AS distinct_rows FROM ("]
    parts.append(" UNION ALL ".join("SELECT v FROM %s" % table(h, j)
                                    for h in range(1, d + 1) for j in range(p)))
    parts.append(") AS all_levels;")
    return "\n".join(parts)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--depth", type=int, required=True)
    ap.add_argument("--fanout", type=int, required=True)
    ap.add_argument("--properties", type=int, required=True,
                    help="P, properties per model -- the DENSITY axis (V2 u2). Not tied to fanout.")
    ap.add_argument("--rows", type=int, default=5000)
    ap.add_argument("--f0", type=float, default=0.10,
                    help="emitting fraction at LEVEL 0 -- sets root count R = k*f0")
    ap.add_argument("--f-prop", dest="f_prop", type=float, default=1.0,
                    help="emitting fraction at levels >= 1 -- sets PROPAGATION. At 0.10 the depth axis is inert (V2 x3); 1.0 makes depth a real axis.")
    ap.add_argument("--seed", type=int, default=12345)
    ap.add_argument("--readback", action="store_true", help="emit the (t2) instrument query instead")
    a = ap.parse_args()
    if a.readback:
        print(readback_sql(a.depth, a.properties))
        return 0
    if saturates(a.depth, a.fanout, a.properties):
        import sys as _s
        print("-- WARNING: mu^D=%d > P=%d. Collisions are FORCED by pigeonhole, not chance, so "
              "this cell's duplicate fraction is arithmetic rather than a measurement. Excluded "
              "from the density read per V2 (x2)." % (a.fanout ** a.depth, a.properties),
              file=_s.stderr)
    print(generate(a.depth, a.fanout, a.properties, a.rows, a.f0, a.f_prop, a.seed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
