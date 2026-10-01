"""Verify the generated V2 topology against its own analytic prediction, with no database.

WHY THIS EXISTS BEFORE ANY POSTGRES RUN. V2 (t2) gates every cell on achieved fire count matching
`expected_fires()`. If the GENERATOR and the FORMULA disagree, that gate fails every cell and the
cause looks like a schema problem in PostgreSQL. Checking them against each other here separates
"the topology is wrong" from "the database did something unexpected" -- and it costs no container.

IT HAS ALREADY PAID FOR ITSELF. Written before any cell ran, it falsified two claims in V2 (u2):
the density figures there came from an OCCUPANCY APPROXIMATION rather than the real generated graph
(VIF 1.80 -> 2.57 once measured properly), and the first version of the second check below mixed
per-source writes with all-source distinct counts, which made duplicate fraction appear to RISE
with P. See V2 (v).
"""
import random
import sys

from v2_schema_gen import expected_fires, targets_for


def emits_set(h, j, n_rows, f, seed):
    """Byte-identical to the generator's own draw -- fixed size, so f is exact, not binomial."""
    rng = random.Random((seed * 31) ^ (h * 104_729) ^ (j * 15_485_863))
    return set(rng.sample(range(1, n_rows + 1), int(round(f * n_rows))))


def replay(d, mu, p, n_rows, f, seed, root_id, j0=None):
    """Replay a cascade. Returns (writes, distinct (level, property) rows written).

    `j0` starts from ONE level-0 property -- what a single updated row actually triggers, and the
    quantity (u2)'s density argument is about. `j0=None` starts from every emitting level-0
    property at once, which is a DIFFERENT quantity: it scales the number of concurrent cascades
    with P, so raising P does not lower density at all. Which one the experiment realises depends
    on how many level-0 rows a transaction touches. V2 (w) settles it from
    `CprSchemaBuilder:140`: R = k*f0, independent of P. Both branches here were wrong.
    """
    emits = {(h, j): emits_set(h, j, n_rows, f, seed)
             for h in range(d + 1) for j in range(p)}
    writes, touched = 0, set()
    frontier = ([(0, j) for j in range(p) if root_id in emits[(0, j)]] if j0 is None
                else [(0, j0)])
    for h in range(d):
        nxt = []
        for (_, j) in frontier:
            for t in targets_for(h, j, mu, p, seed):
                writes += 1
                touched.add((h + 1, t))
                if root_id in emits[(h + 1, t)]:
                    nxt.append((h + 1, t))
        frontier = nxt
    return writes, len(touched)


def main():
    print("=" * 74)
    print("V2 SCHEMA GENERATOR  --  topology vs analytic prediction")
    print("=" * 74)
    bad = 0

    # f = 1.0: every row emits, so propagation is unrestricted and one cascade must produce
    # EXACTLY mu + mu^2 + ... + mu^D writes. Any mismatch is a generator bug.
    print("\n  [1] f=1.0, single cascade -- exact agreement with expected_fires() required")
    print("   %3s %3s %4s   %9s %9s" % ("D", "mu", "P", "replayed", "analytic"))
    for d in (1, 2, 3, 4):
        for mu in (1, 2, 3):
            for p in (mu, 4 * mu):
                w, _ = replay(d, mu, p, 50, 1.0, 12345, root_id=7, j0=0)
                exp = expected_fires(d, mu, 1.0)
                ok = abs(w - exp) < 1e-9
                bad += 0 if ok else 1
                print("   %3d %3d %4d   %9d %9.0f  %s"
                      % (d, mu, p, w, exp, "OK" if ok else "*** MISMATCH ***"))

    # (u2) rests on: at fixed (D, mu), raising P lowers duplicate fraction WITHOUT changing the
    # write count. Measured on the real graph, per single cascade.
    print("\n  [2] (u2): P varies density at FIXED fire count (real graph, single cascade)")
    print("   %3s %3s %4s   %7s %9s %10s" % ("D", "mu", "P", "writes", "distinct", "dup_frac"))
    for d, mu in ((2, 3), (3, 2), (3, 3), (4, 3)):
        base, prev = None, None
        for p in (mu, 4 * mu, 16 * mu):
            w, dist = replay(d, mu, p, 50, 1.0, 12345, root_id=7, j0=0)
            frac = (w - dist) / w if w else 0.0
            print("   %3d %3d %4d   %7d %9d %10.3f" % (d, mu, p, w, dist, frac))
            if base is None:
                base = w
            elif w != base:
                print("        *** P CHANGED THE FIRE COUNT -- not a clean density knob ***")
                bad += 1
            if prev is not None and frac > prev + 1e-9:
                # WARNING, not a failure: a RECORDED property, not a regression. At small F the
                # seeded target draws collide by chance, so P's density effect is not monotone.
                # Failing here would make a permanently-red test out of a known limitation --
                # which trains people to ignore the suite. V2 (v1) carries it.
                print("        WARN dup_frac rose with P (small-F sampling noise; see V2 (v1))")
            prev = frac

    # The ambiguity (v) records: the SAME config under the all-sources reading. Not a failure --
    # a demonstration that the two readings disagree, so the spec gap is real and not academic.
    print("\n  [3] the unspecified quantity: all level-0 properties vs one")
    print("   %3s %3s %4s   %14s %14s" % ("D", "mu", "P", "one-cascade", "all-sources"))
    for p in (3, 12, 48):
        w1, d1 = replay(3, 3, p, 50, 1.0, 12345, root_id=7, j0=0)
        wa, da = replay(3, 3, p, 50, 1.0, 12345, root_id=7, j0=None)
        print("   %3d %3d %4d   %13.3f %14.3f"
              % (3, 3, p, (w1 - d1) / w1, (wa - da) / wa))
    print("      RESOLVED in V2 (w): neither reading -- R = k*f0 roots, fixed by k and f0, not by P.")

    print("\n  %s" % ("ALL CHECKS PASSED" if not bad else "%d CHECK(S) FAILED" % bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
