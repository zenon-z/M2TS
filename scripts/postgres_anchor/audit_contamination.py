"""Regenerate the contamination record for the pilot extension (PREREG Amendment 5j/5k).

WHY A SCRIPT AND NOT A ONE-OFF QUERY. The first audit ran while the extension was still measuring,
so it saw 71 of the eventual cells and its "reps clean" list was a SNAPSHOT that goes stale the
moment the run continues. Re-running this after the extension finishes makes the record cover the
whole run. It is deterministic given the bracket, so it can be re-run at any time and produce the
same flags.

WHAT HAPPENED. While the 25-rep extension held the lock, the verify-by-removal test for Amendment
5i(a) issued four CREATE OR REPLACE FUNCTION statements against the same database without taking
the lock. PL/pgSQL picks up a replaced definition on its next call, so briefly the extension's
fires ran a different trigger body.

THE BRACKET, and the one assumption it rests on. The DDL ran inside the turn that ENDED with the
Amendment 5i commit, and after the turn that ended with the 5h commit. No finer timestamp exists,
so the turn boundary is the bracket. The assumption to check is that no DDL ran AFTER the 5i
commit -- verified: everything after it was a git amend and SELECT-only audit queries.

NOTE ON HASHES. The 5i commit was amended to fix a co-author trailer, so the hash quoted in
Amendment 5j (e3bb3d6) no longer exists; it is now 5c0a82b. The bracket end is that commit's
AUTHOR date, which the amend preserved and which is when the original commit was made.

NOTE ON TIME BASE. Commit timestamps are +02:00; win_start/win_end are timestamptz rendered at
+00:00. 21:59:15+02:00 is 19:59:15Z and 22:06:57+02:00 is 20:06:57Z. Comparisons below use aware
datetimes, so no manual conversion is involved.
"""
import argparse
import csv
import datetime
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2

from workload_client import DEFAULT_DSN

# Bracket: 5h commit -> 5i commit (author date). Deliberately WIDER than the true exposure, which
# was one test script lasting seconds.
BRACKET_LO = datetime.datetime.fromisoformat("2026-09-11T21:59:15+02:00")
BRACKET_HI = datetime.datetime.fromisoformat("2026-09-11T22:06:57+02:00")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "artifacts", "experiments", "results", "ptrial2_contaminated_cells.csv")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag-prefix", default="ptrial2")
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--sweep-csv", default=None,
                    help="the extension's kept CSV, for the trips-inside-vs-outside count")
    ap.add_argument("--superseded-csv", default=None)
    args = ap.parse_args()

    conn = psycopg2.connect(args.dsn)
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute("""SELECT tag, rep, min(win_start), max(win_end)
                       FROM v1b_batch_result WHERE tag LIKE %s
                       GROUP BY tag, rep ORDER BY min(win_start)""",
                    (args.tag_prefix + "%",))
        rows = cur.fetchall()
    conn.close()

    flagged = [(t, r, a, b) for t, r, a, b in rows if b > BRACKET_LO and a < BRACKET_HI]
    bad_reps = sorted({r for _, r, _, _ in flagged})
    all_reps = sorted({r for _, r, _, _ in rows})

    # REFUSE TO OVERWRITE A NON-EMPTY RECORD WITH AN EMPTY ONE.
    #
    # The compose file mounts the data directory on tmpfs, so recreating the container discards
    # v1b_batch_result entirely -- which happened on 2026-09-13 when the anchor was restarted to
    # exercise the advisory-identity assertion. This script rebuilds its CSV from that table, so
    # a re-run against a fresh database would silently replace the 125-row contamination record
    # with a 0-row file. The record is registered evidence for the campaign's void rule
    # (PREREG Amendment 5j), and the git working tree would show only a modification.
    #
    # The query returning nothing is not evidence that nothing was contaminated; it is evidence
    # that the table was wiped. Those must not produce the same output.
    # The trigger is FEWER ROWS THAN THE EXISTING RECORD, not zero rows. A zero-only guard leaves
    # the same failure mode open one step up: a future pilot writing 40 ptrial2-tagged cells
    # returns non-zero, passes a zero check, and overwrites the 125-row record with 40. The tag
    # makes that improbable, not impossible, and the consequence is identical.
    #
    # Not solved by writing a new path per run, which is how the campaign CSVs work: this file is
    # a single canonical artifact named in PREREG Amendment 5j, and per-run paths would orphan
    # that reference.
    if os.path.exists(args.out):
        with open(args.out, newline="") as fh:
            existing = max(sum(1 for _ in csv.reader(fh)) - 1, 0)
        if existing > len(rows):
            print("REFUSING TO OVERWRITE: the query returned %d cell(s) for tag prefix %r, but"
                  % (len(rows), args.tag_prefix))
            print("  %s already holds %d." % (args.out, existing))
            print("  v1b_batch_result lives on tmpfs, so a container restart empties it -- a")
            print("  SHORTFALL means the table was wiped or partly repopulated, not that fewer")
            print("  cells were contaminated. The existing record is left untouched.")
            print("  Pass --out to a different path for a deliberately fresh audit.")
            return 1
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["tag", "rep", "win_start", "win_end", "overlaps_ddl_bracket"])
        for t, r, a, b in rows:
            w.writerow([t, r, a.isoformat(), b.isoformat(), b > BRACKET_LO and a < BRACKET_HI])

    print("bracket  : %s  ->  %s  (%.1f min)"
          % (BRACKET_LO, BRACKET_HI, (BRACKET_HI - BRACKET_LO).total_seconds() / 60))
    print("cells    : %d recorded, %d flagged" % (len(rows), len(flagged)))
    print("reps     : %d total; flagged %s" % (len(all_reps), bad_reps))
    print("clean    : %s" % sorted(set(all_reps) - set(bad_reps)))
    print("wrote    : %s" % args.out)

    # The question that decides whether the exclusion matters AT ALL: did any rep in the bracket
    # actually trip? Reps are flagged by window overlap, not by showing an effect, so if no trip
    # occurred inside the bracket the two p figures coincide and this reduces to disclosure.
    if args.sweep_csv:
        trips = set()
        for path in (args.sweep_csv, args.superseded_csv):
            if not path or not os.path.exists(path):
                continue
            for r in csv.DictReader(open(path)):
                if "estimator disagreement" in (r.get("gate_failures") or ""):
                    trips.add(int(float(r["rep"])))
        inside = sorted(trips & set(bad_reps))
        outside = sorted(trips - set(bad_reps))
        print()
        print("trips inside the bracket  : %s" % (inside or "none"))
        print("trips outside the bracket : %s" % (outside or "none"))
        if not inside:
            print("  No rep inside the bracket tripped, so p over all reps and p excluding")
            print("  reps %s are IDENTICAL. The contamination reduces to disclosure." % bad_reps)
        else:
            print("  Trips occurred inside the bracket. p MUST be reported both ways, and the")
            print("  more conservative upper bound governs the rule choice (Amendment 5j(c)).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
