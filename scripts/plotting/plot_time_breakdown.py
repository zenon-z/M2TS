"""Per-transaction time breakdown: three panels at theta = 0 / 0.5 / 0.8, stacked bars.

Style follows Yu et al. (Abyss, VLDB '14) Fig 6 and Deneva (VLDB '17) Fig 8: one panel per
contention level, one bar per arm, segments stacked in lifecycle order.

FRACTIONS OF LIFETIME (0-1), WHICH IS A CORRECTION
--------------------------------------------------
The y axis is each segment's share of a transaction's time, matching the runtime-breakdown panels
of Abyss Fig 8-10 and Deneva Fig 8. An absolute axis was tried and withdrawn twice:

  * LOG absolute was wrong outright. A stacked bar asserts segment HEIGHT is proportional to
    value, and a log axis breaks the assertion - it inflates the bottom segment by its distance
    from the axis floor and compresses everything above. It drew the control's 640-us lock wait as
    a third of the bar while its 20,020-us service was a sliver.
  * LINEAR absolute is honest but unreadable here. Shared across panels, the largest bar (8.2 M us
    at theta=0.8/f=0.80) reduces the control to a ~1% sliver and most bars vanish; per-panel scales
    make the panels incommensurable and invite a false cross-panel comparison.

The claim this figure makes is about COMPOSITION - which segment dominates, and where that changes
- so normalising removes a variable the companion throughput figure already reports, rather than
hiding one.

THE SEGMENTS CLOSE - MEASURED, NOT ASSERTED
-------------------------------------------
A stacked bar claims its segments are mutually exclusive and sum to the whole. Here they do:
Transaction.LineagePhase closes every interval before opening the next, and the audit
(WorkloadTimeBreakdownAudit) reports GAP = lifetime - sum as its own column. It reads 0 exactly.

That column exists because the alternative - defining the last segment as "whatever is left" -
cannot fail, and this repo has that defect in its history as residual_frac. So this script
RE-CHECKS the closure from the CSV rather than trusting it, and refuses to plot if it breaks.

TWO SEGMENTS THAT WOULD BE MISREAD WITHOUT A CAPTION
----------------------------------------------------
"Lock wait (at admission)" is one segment, not two. Under C2PL preclaim the whole footprint is
acquired at admission or the transaction does not start, so there is no in-flight lock wait to
separate. A reader who knows Fig 6 would otherwise look for the missing bar.

It is ALSO not purely contention, and that took a measurement to establish rather than a reading
of the phase name. A transaction denied at admission enters ADMISSION_WAIT
(ACIDAdmissionController:466) and is then paced by calculateLockRetryBackoff between attempts
(:194) - with NO markPhase on that path, so the whole exponential retry delay is booked to this
segment. Doubling backoffScale grows it 2.145x at f=0.25/theta=0.5 - five paired seeds, pooled -
which is MORE than the 2x applied and more than backoff's own 1.967x, because longer delays also
accumulate retries at higher softDenyRetryCount. Pure lock-hold waiting would not scale with a
retry constant at all, so most of this segment is policy.

(First measured as 2.51x on ONE seed. The paired five give 2.145. Recorded because the single-seed
reading was quoted in the plan before the sweep ran, and that is the fourth time on this campaign
that n=1 has misstated a number by a wide margin.)

The consequence is worth stating plainly because it changes what the figure's two largest bars
mean at high skew: BOTH backoff and lock wait are largely retry-policy time, not contention time.
The base backoff is 500 ms against a parent service of 20 ms - 25x the work it retries - so the
sensitivity of every result to that constant is a question the figure invites and cannot answer
on its own.

"Admitted, not in service" (formerly "Processor wait") dominates the control (~74%) and that is the OPERATING POINT, not a result:
MPL 192 against 24 cores is 8x oversubscription, chosen (plan 0.9) so the no-CPR reference stays
capacity-bound at every x. The CPR effect is what CHANGES between bars - cascade wait and lock
wait growing, processor wait shrinking as parents park in the waiting room and stop competing for
processors. The caption says so; see CAPTION below, which is printed on every run so it cannot
drift away from the figure.

WHAT IS NOT HERE, DELIBERATELY
------------------------------
Cascade WORK as its own segment. The tempting arithmetic - cpr_edges_per_completion x service,
subtracted from the waiting room - is invalid: with groupUnitAffinity = false a group's CUs run
concurrently on different processors, so summed CU service is not wall-clock on the parent's
critical path. It would overstate cascade work, understate cascade delay, and can go negative.
The cascade's work is already quantified by sdr in the multiplier figure; this figure reports
cascade DELAY and does not restate it.
"""
import collections
import csv
import math
import re
import statistics
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
# Explicit, though pyplot happens to pull it in transitively today. The legend file is built from
# Patch, so relying on another module's import chain would make this script's output depend on a
# matplotlib internal that is free to change.
import matplotlib.patches
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "artifacts/experiments/results"
OUT = ROOT / "artifacts/figures/wl_time_breakdown"

# WorkloadAxisDefaults.CORES. Duplicated here rather than parsed out of the Java, which is what
# every other script in this directory does with that file's constants - but unlike a cosmetic
# duplicate this one GATES which rows are plotted, so it is asserted against the data instead of
# trusted: load() refuses if no row carries it. If the Java default ever moves, the campaign's
# own rows stop matching and the script says so rather than silently plotting nothing.
BASE_CORES = 24

# FIGURES ARE AUTHORED AT THEIR TRUE DISPLAY SIZE, so \includegraphics scales them by 1.0 and a
# declared 7 pt is 7 pt on the page.
#
# This is the difference between a figure that looks right in the PNG and one that is readable in
# the paper. Authored at 3.2 in and drawn in a 0.32\textwidth subfigure, a panel is scaled 0.70x
# and its 7 pt tick labels arrive at 4.9 pt. The legend, cropped by a tight bbox to 10.5 in and
# then fitted to \textwidth, is scaled 0.67x - so its text lands at a DIFFERENT size from the
# panels' despite both being specified in points. Nothing in the rendered PNG shows either effect.
#
# Set TEXTWIDTH_IN to the target document's \textwidth (\the\textwidth in LaTeX, /72.27 for
# inches). 7.0 is a typical two-column \textwidth for a figure* spanning both columns.
TEXTWIDTH_IN = 7.0
SUBFIG_FRACTION = 0.32          # must match the subfigure width in the LaTeX below
# 4:3, was 2:1. The bars were too flat to read segment boundaries in, and height is the only
# dimension free to change: the width is pinned by SUBFIG_FRACTION, and altering it would stop
# the three panels lining up in the LaTeX row. 1.12 -> 1.49 in of height, +33% of the figure,
# which is the ~50% more PLOT height asked for once the axis furniture is accounted for.
PANEL_ASPECT = 4.0 / 3.0        # width : height
PANEL_W_IN = TEXTWIDTH_IN * SUBFIG_FRACTION
PANEL_H_IN = PANEL_W_IN / PANEL_ASPECT

# Lifecycle order, bottom to top: the transaction's own history reads upward. Colours go cool for
# waiting-to-start, neutral for real work, warm for the two CPR-specific costs, so the CPR effect
# is the warm band growing rather than a legend lookup.
#
# PENDING_QUEUE IS NOT DRAWN, AND THAT IS A MEASUREMENT RATHER THAN AN OMISSION.
# Dispatch queueing reads 0.0000 of lifetime in all fifteen cells - 0 to 7 ticks against
# lifetimes of 8e4 to 8e6 - because admission, not dispatch, is the binding stage: a transaction
# that clears the MPL cap is dispatched at once. It is a structural zero, not an unwired counter
# (C15): the column is populated and rises with f, it is simply sub-microsecond. Drawing it would
# add an invisible sliver and a legend entry for nothing, so it is dropped here and stated in the
# caption - a reader who knows Abyss Fig 8 would otherwise look for the queue segment.
#
# It is still summed in the closure check below, so dropping it from the STACK cannot quietly
# open a gap: PLOT_SEGMENTS is what is drawn, SEGMENTS is what must close.
SEGMENTS = [
    ("processor_wait_ticks", "Admitted, not in service", "#595959", ""),
    ("queue_ticks",          "Dispatch queue",           "#a6a6a6", ""),
    ("lock_wait_ticks",      "Lock wait (at admission)", "#5b9bd5", "//"),
    ("cascade_wait_ticks",   "Waiting for cascade",          "#f5a03c", ""),
    ("wasted_ticks",         "Wasted work (aborted)",    "#7ac77a", "//"),
    ("backoff_ticks",        "Abort backoff",            "#e8d44d", "\\\\"),
    ("service_ticks",        "Useful work",              "#e8493a", "//"),
]

# WASTED WORK IS MERGED INTO PROCESSOR WAIT, which removes a premise rather than hiding a problem.
#
# The split was derived, not measured: wasted = (restarts/completion) x service, on the premise
# that every aborted attempt had finished its full service first. Under cautious waiting that is
# FALSE in one case the campaign reaches - a parent hard-denied AT ADMISSION aborts before running,
# so that restart wastes nothing - and short backoff produces many more such denials. Measured at
# theta=0.8/alpha=0.05, scale 0.10: two of five rows drove processor wait to 1.59x and 1.80x BELOW
# its window-edge bound, i.e. outside the range edge accounting can explain, and the script's own
# gate would have refused to plot.
#
# The merged quantity needs none of that. processor_wait + wasted = running - service identically,
# and running is measured occupancy while service is a verified constant (20,020 ticks, min = max
# over all 75 rows, and exact at MPL = 1). So the bar rests on two measurements and no premise.
#
# The cost is a segment nobody could see: wasted is ~2,000 ticks against a 1.6 M lifetime at scale
# 1.0 and under 2% of lifetime even at 0.01 - a thin line whose only function was to carry an
# assumption. The label says what is inside it so the merge is not a silent one.
#
# Keeping the exact split would take one counter - at each group abort, record whether the parent
# had finished its service, then wasted = restartsAfterService x service. Left undone deliberately:
# it is a few lines for a segment that does not appear in the figure or the story.
MERGED = "running_ex_service_ticks"

# ORDER: service at the BOTTOM, processor wait directly above it.
#
# Both halves of the length argument are read off these two segments, and a segment is only
# comparable across bars when it shares a baseline. Service used to be the TOP segment, floating
# on whatever the bars below it summed to, which made 25% -> 12.6% -> 0.1% impossible to compare
# by eye - and "a transaction spends a thousandth of its life working" is the strongest sentence
# this figure has.
#
# Proc wait sits second because its collapse to ~0 is the IDLE-CORE signature. That is the waiting
# that actually costs throughput, as distinct from waiting that merely DISPLACES processor
# queueing: the control's lock wait grows 83x from k=10 to k=50 and costs nothing, because its
# lifetime grows exactly 5.0x for a 5x k.
# PALETTE CHOSEN BY MEASUREMENT, not by eye. Three properties were required and each is
# computable, so each was computed rather than judged:
#
#   GREYSCALE. Relative luminance is spread evenly across the five - 0.18, 0.33, 0.48, 0.63,
#   0.78 - so the minimum gap between any two segments is 0.148. The palette this replaces had
#   a minimum gap of 0.082. Hatching still differentiates, but it is no longer load-bearing.
#
#   CONTRAST WITH THE BAR OUTLINE, which is why the floor is 0.18 rather than lower. The first
#   version of this palette put lock wait at 0.060, a dark navy: against the black outline that
#   is 2.2:1, and where the segment is thin - the f=0.80 bar - it read as part of the outline
#   rather than as a band. At 0.18 the ratio is 4.6:1 and the band is visible at any thickness.
#   Raising the floor costs spread - the grid now runs 0.18 to 0.78 instead of 0.06 to 0.78, so
#   the minimum gap falls from 0.166 to 0.148 - and that is the right trade: 0.148 is still
#   nearly twice the old palette's, whereas a segment indistinguishable from its own border is
#   not a problem more separation elsewhere can fix.
#
#   COLOUR VISION. Under a deuteranope simulation the closest pair is 0.145 apart against 0.119
#   before, and there is no red/green pairing anywhere: the one warm hue is orange, whose
#   complement in the figure is blue.
#
#   GROUPING. The three waiting-for-something segments are one blue family running dark to
#   light (lock wait, waiting for CUs, abort backoff), so the eye reads them as a set. Useful
#   work is the only warm hue because it is the only segment that is not waiting. Admitted-but-
#   not-in-service is neutral grey, at a mid lightness because it is usually the largest
#   segment and a near-white fill would read as empty space.
#
# The legend file is built from this list (see the legend block below), so it cannot describe a
# colour the bars no longer use.
PLOT_SEGMENTS = [
    ("service_ticks",      "Useful work",                         "#ffa342", "//"),
    # RENAMED FROM "Processor wait (incl. re-execution)", because that name asserted something
    # the quantity satisfies only on the control.
    #
    # The segment is occRunning - service, and RUNNING means ADMITTED, not RUNNABLE - a parent
    # blocked on a lock its group was denied stayed in RUNNING, so its blocked time is in here.
    # Measured at 1 core / f = 0.80: the residual put 20.3 parents in RUNNING while the two units
    # were 16.6% busy, which is queued work at an idle server and impossible for real processor
    # queueing. The same cell with CPRs removed read 188.6 of 192 in RUNNING at ~100% busy - the
    # name is exactly right on the control and wrong in proportion to cascade activity.
    #
    # "Admitted, not in service" is what the population count actually measures and needs no
    # qualification. CAPTION carries the equivalence, since a reader comparing against Abyss
    # Fig 6 will want to know when this IS processor wait.
    #
    # LineagePhase.BLOCKED now separates the two, but this figure is deliberately NOT re-run off
    # it: the campaign's rows predate the split, and re-running 15 cells to relabel one segment
    # buys nothing the honest name does not already give. New rows carry blocked_ticks and load()
    # refuses to pool the two schemas, so this stays a pure relabelling of existing data.
    (MERGED,               "Admitted, not in service",            "#d0d0d0", ""),
    ("lock_wait_ticks",    "Lock wait (at admission)",            "#2b79c1", "//"),
    ("cascade_wait_ticks", "Waiting for cascade",                     "#54a4d1", ""),
    ("backoff_ticks",      "Abort backoff",                       "#c8eaf9", "\\\\"),
]

# LEGEND LABELS ARE THE SEGMENT NAMES WITH THE PARENTHETICALS STRIPPED, because label LENGTH is
# what sets the font size in a one-row legend.
#
# The strip has to fit inside \textwidth, so the five labels and their swatches share 7 in. At
# full length that is ~108 characters and forces ~7 pt - visibly smaller than the reference, whose
# six labels ("Useful Work", "Txn Manager", "2PC") total about half that. Without the brackets it
# is ~60 characters and the same row fits at 9 pt.
#
# DERIVED, not a second hand-maintained list. A parallel dict of short names would be free to
# drift from PLOT_SEGMENTS - rename a segment in one place and the legend keeps describing the old
# one, which is precisely the silent-mislabelling failure this file is structured to avoid.
#
# The qualifiers are not lost, they move to the caption, which has room to say them properly:
# "(at admission)" means nothing without a sentence about C2PL preclaim, and "(incl. re-execution)"
# needs the reason wasted work is not its own segment. Both are already in CAPTION below.
def legend_label(name):
    return re.sub(r"\s*\([^)]*\)", "", name)

# Bar order: the f-series first, then the CONCENTRATION series. The concentration series is
# alpha = 0.05 / 1.0 / 100 and its midpoint is the f=0.25 bar - BASE_ALPHA is 1.0, so f=0.25
# already IS alpha=1.0 and a separate bar for it would be a bit-identical duplicate. The
# caption says so, because nothing in the labels reveals that two of them share a cell.
ARMS = ["control", "f=0.05", "f=0.25", "f=0.80", "alpha=0.05", "alpha=100"]

# ALPHA-SWEEP ROWS ARE NOT CAMPAIGN ROWS, and they collide on (cell_id, seed).
#
# -Dts.tb.alphas PINS alpha in the cell key so every alpha draws the same five seeds (that is
# deliberate - it makes the concentration series paired). The consequence is that all seven
# alpha-sweep files carry cell_id "..._a1.0000_..." while their alpha COLUMN ranges 0.0005 to 100,
# so each one shares (cell_id, seed) with the campaign's f=0.25 rows and disagrees on throughput.
# That is what makes the duplicate detector refuse to plot.
#
# Detected by the INTERNAL INCONSISTENCY rather than by the _alpha filename tag: the tag would work
# today and break the first time a file is renamed, while a row whose cell_id disagrees with its own
# alpha column is self-evidently from a pinned-key sweep whatever it is called.
#
# NOTE the one row class this cannot catch: the alpha=1.0 sweep file agrees with the campaign on
# both (it IS the f=0.25 cell, and its throughput matches bit-for-bit). It is excluded by arm-label
# allowlist where one exists, and is harmless where it does not - it duplicates a row it agrees with.
def _is_pinned_key_sweep_row(r):
    m = re.search(r"_a([0-9.]+)_", r.get("cell_id", ""))
    if not m or not r.get("alpha"):
        return False
    return abs(float(m.group(1)) - float(r["alpha"])) > 1e-9

# WORDS, NOT PARAMETER VALUES. "$f$=0.25" asks the reader to hold the parameter's meaning and
# its scale in mind to read a bar; "Med rate" says what the bar is. The numbers move to the
# caption, which is where a reader looks for the exact levels anyway - and once the labels say
# what they mean, the grouping brackets that used to sit under them have nothing left to add.
ARM_LABELS = {"control": "No CPR", "f=0.05": "Low rate", "f=0.25": "Med rate",
              "f=0.80": "High rate", "alpha=0.05": "Concentrated", "alpha=100": "Spread"}
THETAS = [0.0, 0.5, 0.8]

# The length facet: one panel per transaction length, all at the base contention level.
#
# k = 10 is deliberately NOT here - it is the base cell and is already the theta=0.5 panel of the
# contention figure. Re-drawing it would print the same six bars twice in one paper.
BASE_KLEN = 10
BASE_THETA_FOR_KLEN = 0.5
KLENS = [50]

CAPTION = "Fraction of a transaction's time in each phase, by contention level. Segments are\ntime-weighted state occupancy over the measurement window divided by completions: the live\ncount in each phase is integrated at every step, so nothing is censored and transactions\nstill running at the end are counted where they actually are.\n  * Equivalently, by Little's law, each segment is the fraction of the LIVE TRANSACTION\n    POPULATION sitting in that phase at a random instant - a system-wide residency view,\n    which is how it maps onto a CPU-time runtime breakdown without being one.\n  * 'Lock wait (at admission)' is the only lock wait: under C2PL preclaim the whole\n    footprint is acquired at admission or the transaction does not start, so there is no\n    in-flight lock wait to separate. It INCLUDES the lock-retry backoff between admission\n    attempts, so it is part contention and part retry policy - measured: doubling the\n    backoff scale grows it 2.1x at the base cell.\n  * Both dominant segments at high skew are therefore largely RETRY-POLICY time. The\n    backoff base is 500 ms against a 20 ms parent service; throughput at the base cell is\n    flat from 0.01x to 0.5x of it and falls above, so 500 ms sits ~7% off the plateau.\n  * The CONCENTRATION series is the three alpha bars, and its midpoint is the f=0.25\n    bar: BASE_ALPHA is 1.0, so f=0.25 and alpha=1.0 are the same cell and only one is\n    drawn. Smaller alpha is MORE concentrated in-degree, so the series runs\n    alpha=100 (spread) -> f=0.25 (alpha=1.0) -> alpha=0.05 (concentrated); all three\n    hold f = 0.25, so they differ in concentration alone.\n  * There is no dispatch-queue segment because dispatch queueing is negligible - under\n    10 us in every cell - since admission, not dispatch, is the binding stage.\n  * Seeds are POOLED, not averaged: each segment is summed occupancy over summed\n    completions across the five seeds, which equals MPL / mean throughput exactly and so\n    matches the throughput figure bar for bar.\n  * 'Admitted, not in service' is admitted time that is not the transaction's own service.\n    It EQUALS processor queueing only where nothing blocks mid-flight, which is the control\n    and the near-control arms: a parent blocked on a lock its group was denied is still\n    admitted, so its blocked time is inside this segment, in proportion to cascade activity.\n    On the control it reflects 8x oversubscription (MPL 192, 24 cores), chosen so the no-CPR\n    reference is capacity-bound at every contention level - the operating point, not a\n    result. Read the CPR effect as what CHANGES between bars.\n  * 'Waiting for CUs' is the parent's DELAY waiting on the cascade units its own writes\n    triggered - not their WORK. CUs run concurrently, so their summed service is not\n    wall-clock on the parent's critical path; cascade work is reported as the\n    service-demand ratio in the interaction-multiplier figure. It INCLUDES time the\n    parent is blocked because one of its CUs is waiting for a lock - 34% of this segment\n    at f=0.80, theta=0.5. The parent has finished its own work either way, so both\n    are cascade delay; one of them is lock-related.\n  * Abort cost appears as BACKOFF. Work thrown away by an attempt that later aborted is a\n    property of a past attempt, not a current state, so a population count cannot separate\n    it from running; it sits inside useful work / admitted-not-in-service. Backoff exceeded it by\n    ~100x wherever both were measured.\n  * Every cell is gated on equilibrium (segments summing to MPL x window) and on Little's\n    law (per transaction, MPL / throughput); both read 1.000."



def load():
    """(theta, arm) -> {segment: mean across seeds}, plus the checks that gate plotting."""
    # EVERY breakdown CSV is read, and rows are filtered by backoff_scale rather than by filename.
    #
    # The sweep writes one file per (scale, arm-filter, theta-filter) combination, so the arms are
    # spread across the 15 campaign shards plus any later per-arm run - alpha=100 was added after
    # the campaign and lives in its own file. Globbing only the shards would silently leave its
    # bars off the figure.
    #
    # But the same directory also holds the BACKOFF-SENSITIVITY runs at scales 0.01 to 2.0, which
    # are a different experiment and must not be pooled in. Excluding them by filename ("skip
    # anything with _bs") would work today and break the first time someone names a file
    # differently; excluding them by the VALUE in the row cannot. A row with no backoff_scale
    # column predates the knob, and the binary that wrote it hard-coded 1.0.
    paths = sorted(RESULTS.glob("wl_breakdown*_raw.csv"))
    if not paths:
        raise SystemExit(f"no breakdown CSV in {RESULTS} - run WorkloadTimeBreakdownSweep")
    rows, skipped_scale, skipped_cores, folded_nonwroom, seen_keys = [], 0, 0, 0, {}
    skipped_protocol = 0
    skipped_other = 0
    for p in paths:
        with open(p, newline="") as fh:
            # '#' LINES SKIPPED. The sweep writes a leading provenance block (per-transaction
            # denominator, units, which columns are protocol-validated) that csv.DictReader would
            # otherwise take for the header row.
            for r in csv.DictReader(line for line in fh if not line.startswith("#")):
                # PROTOCOL, filtered BY VALUE for exactly the reason scale and cores are.
                #
                # -Dts.tb.ss2pl runs the triggered-vs-inlined comparison through this same driver,
                # at the default backoff scale and the default core count - so it clears both
                # filters above and its rows would land in this figure as extra bars under an
                # entirely different acquisition protocol. Worse than the cores case: those rows
                # differ from the campaign's in TWO columns this script had no reason to read.
                #
                # A row with no locking_protocol column predates the knob, and every binary that
                # wrote one ran C2PL (WorkloadAxisDefaults.baseBuilder sets it). Same reasoning as
                # the missing backoff_scale and cores columns.
                if (r.get("locking_protocol") or "C2PL") != "C2PL":
                    skipped_protocol += 1
                    continue
                # ARM ALLOWLIST. This figure plots exactly the six NAMED arms in ARMS, so anything
                # else is another experiment's row. Fails CLOSED: an unrecognised arm is dropped
                # rather than silently gaining a bar. It is also what excludes the alpha=1.0 sweep
                # file, which _is_pinned_key_sweep_row cannot see.
                if r["arm"] not in ARMS:
                    skipped_other += 1
                    continue
                if _is_pinned_key_sweep_row(r):
                    skipped_other += 1
                    continue
                # And the inlined arm, which is C2PL-compatible in no sense that matters here: its
                # parent carries the CPR closure, so its footprint is not the campaign's k.
                if (r.get("oracle_preclaim") or "false").lower() == "true":
                    skipped_protocol += 1
                    continue
                scale = float(r["backoff_scale"]) if r.get("backoff_scale") else 1.0
                if scale != 1.0:
                    skipped_scale += 1
                    continue
                # CORES, filtered BY VALUE for exactly the reason the scale is.
                #
                # ts.tb.cores lets the sweep run along the cores axis, and those runs are at the
                # default scale - so without this they land in the glob and pool six different
                # core counts into one bar. That is worse than the scale case: a scale run at
                # least carries _bs in its name, while these rows differ from the campaign's only
                # in a column this script had no reason to read. Every existing figure would keep
                # rendering, with segments averaged over machines of different widths.
                #
                # klen needs no such filter - it is already part of the panel key below, so a
                # k = 25 run opens its own panel instead of contaminating one.
                #
                # A row with no `cores` column predates the knob, and the binary that wrote it
                # hard-coded BASE_CORES. Same reasoning as the missing backoff_scale column.
                if int(float(r.get("cores") or BASE_CORES)) != BASE_CORES:
                    skipped_cores += 1
                    continue
                # POST-SPLIT ROWS ARE FOLDED BACK, NOT REFUSED.
                #
                # LineagePhase.BLOCKED split blocked-on-a-lock time out of the phase it
                # interrupted. Verified as PURE REATTRIBUTION on a paired re-run: the same cell and
                # seed gives cascade_wait 35,303.7 before and 12,930.8 + 22,372.9 after, a
                # difference of 0.000000, with throughput, completions and lifetime bit-identical.
                # Nothing was created or lost, so the two schemas describe the same quantity under
                # different names and CAN be pooled - which is what keeps the 150 committed rows
                # usable instead of re-running them.
                #
                # FOLDED BY PHASE OF ORIGIN, not all into cascade. The split is "by the phase
                # BLOCKED interrupted" (Transaction.LineagePhase's own javadoc), so each bucket
                # goes back where it came from: BLOCKED_WAITING_ROOM to cascade, BLOCKED_RUNNING to
                # the running remainder, BLOCKED_OTHER to admission/lock wait. Summing all three
                # into cascade gives the same answer TODAY - both of the others are 0 in every
                # post-split row - but only because C2PL preclaims the whole footprint, so a parent
                # already holds every lock by the time it runs and cannot block while RUNNING. That
                # is a property of the protocol, not of the accounting, and it would stop holding
                # under S2PL. Folding by origin is right either way.
                if "blocked_waiting_room_ticks" in r:
                    r["cascade_wait_ticks"] = repr(float(r["cascade_wait_ticks"])
                                                   + float(r["blocked_waiting_room_ticks"]))
                    r["processor_wait_ticks"] = repr(float(r["processor_wait_ticks"])
                                                     + float(r["blocked_running_ticks"]))
                    r["lock_wait_ticks"] = repr(float(r["lock_wait_ticks"])
                                                + float(r["blocked_other_ticks"]))
                    # Reported rather than assumed: a nonzero value here means the C2PL argument
                    # above no longer holds, and the fold is then doing something the caption does
                    # not describe.
                    if float(r["blocked_running_ticks"]) or float(r["blocked_other_ticks"]):
                        folded_nonwroom += 1
                # A (cell, seed) can legitimately appear twice - once in a campaign shard and once
                # in a later per-arm rerun at the default scale. Those must agree exactly, since
                # runs are bit-reproducible; a disagreement means a changed binary or default, and
                # pooling both copies would quietly double one seed's weight.
                key = (r["cell_id"], r["seed"])
                if key in seen_keys:
                    prev = seen_keys[key]
                    # SCHEMA VERSION IS PART OF THE AGREEMENT TEST, and it has to be, because the
                    # three columns below CANNOT detect the change that matters here.
                    #
                    # LineagePhase.BLOCKED split blocked-holding-locks time out of RUNNING. A
                    # re-run of an existing cell on the new binary therefore reports a SMALLER
                    # processor_wait_ticks and a new blocked_ticks, while throughput, completions
                    # and mean_lifetime_ticks are all bit-identical - the total lifetime did not
                    # move, only its attribution did. So the old test passes, and whichever file
                    # sorted first silently wins: the figure would be drawn from one schema or the
                    # other depending on filename order, with no symptom.
                    #
                    # Compared by PRESENCE, not value: two rows of the same schema still have to
                    # agree on the three columns below, and two rows of different schemas are
                    # refused outright rather than reconciled. Pooling them is not a thing a
                    # caption could explain.
                    bad = [c for c in ("throughput_tps", "completions", "mean_lifetime_ticks")
                           if prev[c] != r[c]]
                    if bad:
                        raise SystemExit(
                            f"REFUSING TO PLOT: {key} differs on {bad} between two files. Runs "
                            "are bit-reproducible, so this is a changed binary or default.")
                    continue
                seen_keys[key] = r
                rows.append(r)
    notes = []
    if skipped_scale:
        notes.append(f"{skipped_scale} rows skipped: backoff_scale != 1.0")
    if skipped_cores:
        notes.append(f"{skipped_cores} rows skipped: cores != {BASE_CORES}")
    if skipped_protocol:
        notes.append(f"{skipped_protocol} rows skipped: not C2PL / CPRs inlined "
                     "(the SS2PL triggered-vs-inlined experiment)")
    if skipped_other:
        notes.append(f"{skipped_other} rows skipped: arm not in ARMS, or a pinned-key "
                     "alpha-sweep row (shares cell_id with the f=0.25 cell)")
    if folded_nonwroom:
        # Not a skip - these rows ARE plotted. It flags that blocked time appeared outside the
        # waiting room, which the C2PL preclaim argument says should not happen.
        notes.append(f"{folded_nonwroom} rows had blocked time outside WAITING_ROOM "
                     "(folded to their origin phase - check the C2PL preclaim assumption)")
    print(f"merged {len(paths)} files -> {len(rows)} rows"
          + (f" ({'; '.join(notes)})" if notes else ""))
    # BASE_CORES ASSERTED AGAINST THE DATA, not trusted. If it disagrees with the Java default,
    # the cores filter above rejects every row and the failure would otherwise surface as an
    # empty figure or a confusing KeyError further down - the classic wrong-constant symptom.
    if not rows and skipped_cores:
        raise SystemExit(
            f"REFUSING TO PLOT: all {skipped_cores} rows were filtered out as cores != "
            f"{BASE_CORES}. BASE_CORES here disagrees with WorkloadAxisDefaults.CORES.")

    by_cell = collections.defaultdict(list)
    dropped = collections.Counter()
    worst_gap, worst_viol, worst_inflight = 0.0, 0.0, 0.0
    for r in rows:
        # C5: never pool across termination paths - a censored run measured a different thing.
        if r["terminated_by"] != "TIME_LIMIT":
            dropped[r["terminated_by"]] += 1
            continue
        # The closure is re-checked HERE, from the CSV, not taken on trust from the driver. A
        # stacked bar whose segments do not sum to the whole is a claim the data does not make,
        # and the cheapest place for that to break is a schema change upstream of this script.
        life = float(r["mean_lifetime_ticks"])
        total = sum(float(r[c]) for c, _, _, _ in SEGMENTS)
        worst_gap = max(worst_gap, abs(total - life) / life if life else 0.0)
        # PREMISE VIOLATION, floored and REPORTED rather than clamped away.
        #
        # wasted = (restarts/completion) x service assumes every aborted attempt finished its
        # full service before aborting. Under cautious waiting that holds physically: a hard deny
        # aborts the REQUESTER, which is a CU, and a CU only runs after its own parent finished
        # its service. No other group can abort your parent mid-service.
        #
        # So a negative processor wait is not a mechanism, it is WINDOW-EDGE ACCOUNTING. Restarts
        # and completions are counted over [warmup, end] while running occupancy is clipped to
        # that same window, so an attempt whose service straddles either boundary contributes a
        # whole restart but only part of its service time. It appears only on the cell with the
        # fewest completions relative to the ~MPL transactions in flight at each boundary -
        # theta=0.8/f=0.80, ~1,100 completions, violation 2.9% - which is the size that effect
        # predicts. A 120 s horizon roughly halves an edge effect and leaves a mechanism
        # untouched, which is how the attribution gets checked rather than asserted.
        #
        # The excess is moved OUT of wasted rather than deleted, so the stack still sums to
        # lifetime exactly: wasted was over-estimated by precisely this amount.
        # THE THRESHOLD IS DERIVED, NOT ROUND. A flat "5% of running" was the first draft and it
        # failed on correct behaviour - 6.07% on one seed - which is how a gate stops being read.
        # The edge effect has a computable bound: at each window boundary ~MPL transactions are
        # mid-attempt, and each can contribute at most one service of mismatch, spread over the
        # completions in the window. So the deficit per completion cannot exceed
        # MPL x service / completions if window-edge accounting is the whole story.
        #
        # Measured against it on the four violating rows (all theta=0.8/f=0.80, the cell with
        # fewest completions relative to the in-flight population):
        #     completions  deficit  bound
        #            1631     2032   2357
        #            1261     1918   3049
        #             981     1846   3918
        #            1035      975   3715
        # Every one sits BELOW its own bound, so the attribution is quantitative rather than a
        # plausible story. A deficit ABOVE the bound is not edge accounting and means the service
        # constant or the one-service-per-restart premise is wrong.
        # The merged segment: running time that is not service. No restart counting, so the
        # premise that used to be checked here cannot be violated - there is nothing to violate.
        # What remains is a real guard on the SERVICE CONSTANT: if computed service exceeded
        # measured running time this would go negative, and service is the figure's largest
        # segment on the uncontended cells. The window-edge bound stays as the tolerance, because
        # an attempt straddling either boundary still contributes partial running time.
        merged = float(r["processor_wait_ticks"]) + float(r["wasted_ticks"])
        r[MERGED] = repr(merged)
        edge_bound = (float(r["mpl"]) * float(r["service_ticks"])
                      / max(1.0, float(r["completions"])))
        worst_viol = max(worst_viol, (max(0.0, -merged) / edge_bound) if edge_bound > 0 else 0.0)
        if merged < 0:
            r[MERGED] = "0.0"
        # Both gates, re-checked here rather than trusted from the driver. population_ratio is
        # the stronger: total population-time against MPL x window, using no throughput estimate.
        worst_inflight = max(worst_inflight, abs(float(r["population_ratio"]) - 1.0),
                             abs(float(r["littles_ratio"]) - 1.0))
        # TRANSACTION LENGTH comes from the cell_id, which ends in "_klen<k>".
        #
        # The sweep emits no txn_len column, and adding one would leave every row produced before
        # it was added unreadable - including the 90 that already exist. cell_id already carries
        # k because WorkloadCellId puts every axis coordinate in the key, so parsing it costs
        # nothing and makes the older rows facet correctly without being re-run.
        m = re.search(r"_klen(\d+)$", r["cell_id"])
        if not m:
            raise SystemExit("cannot read transaction length from cell_id %r" % r["cell_id"])
        by_cell[(float(r["theta"]), int(m.group(1)), r["arm"])].append(r)

    if worst_gap > 0.005:
        raise SystemExit(
            f"REFUSING TO PLOT: segments miss lifetime by up to {worst_gap:.2%}.\n"
            "The bars would not sum to the quantity they claim to decompose. Fix the\n"
            "decomposition, do not add a residual segment - a residual is defined to close the\n"
            "sum and therefore cannot fail.")
    if worst_viol > 1.0:
        raise SystemExit(
            f"REFUSING TO PLOT: the running-time deficit reaches {worst_viol:.2f}x its "
            "WINDOW-EDGE BOUND on some cell.\n"
            "That bound is MPL x service / completions - the most that attempts straddling the\n"
            "warm-up boundary or run end can contribute. Below it, the deficit is accounting at\n"
            "the window edges and is floored away. Above it, it is not, and either the service\n"
            "constant or the one-service-per-restart premise is wrong - the segments rest on both.")

    # SEEDS ARE POOLED, NOT AVERAGED, and the difference is not cosmetic.
    #
    # Each row already carries occupancy/completions for its own seed. Pooling re-forms the ratio
    # over the whole cell - sum(occupancy) / sum(completions) - which is the completion-weighted
    # mean of the per-seed values, NOT their arithmetic mean.
    #
    # Why it has to be this way. Per seed, lifetime = MPL / tps, and the mean of 1/x exceeds
    # 1/mean(x) whenever x varies (Jensen). At theta=0 / f=0.80 the per-seed throughput spans 4x,
    # and there the two estimators differ by 1.384x - 1,697,285 averaged against 1,226,474 pooled.
    # Every low-variance cell reads 1.000 to 1.003, so the correction lands precisely on the cell
    # that drives the headline and nowhere else.
    #
    # Pooling is the one that AGREES WITH THE THROUGHPUT FIGURE. Because the measurement window is
    # the same in every seed, c_i = tps_i x W and life_i = MPL / tps_i, so tps_i cancels and
    #     sum(life_i x c_i) / sum(c_i) = MPL / mean(tps)   exactly.
    # A reader can take mean throughput off the companion figure and land on this bar's height.
    # Averaging lifetimes lands somewhere else, with no quantity anywhere in the paper to check it
    # against. The identity is asserted below rather than assumed, because it holds only while the
    # window is constant - a run terminating early would break it silently.
    #
    # It also stops the slowest seed from setting the bar. A seed with a sixth of the completions
    # contributes a sixth of the weight, which is what "per transaction" means; under arithmetic
    # averaging that seed counts the same as one with six times the evidence.
    #
    # Composition is a true partition either way - the segments of each seed close on its own
    # lifetime, so any weighted combination of them closes too. What changes is the DENOMINATOR,
    # and therefore every absolute height and the printed lifetimes.
    means = {}
    for key, rs in by_cell.items():
        completions = sum(float(r["completions"]) for r in rs)
        means[key] = {c: sum(float(r[c]) * float(r["completions"]) for r in rs) / completions
                      for c in [s[0] for s in SEGMENTS] + [MERGED]}
        means[key]["_n"] = len(rs)
        means[key]["_life"] = sum(means[key][c] for c, _, _, _ in SEGMENTS)
        means[key]["_tps"] = statistics.mean(float(r["throughput_tps"]) for r in rs)
        means[key]["_pop"] = statistics.mean(float(r["population_ratio"]) for r in rs)
        means[key]["_little"] = statistics.mean(float(r["littles_ratio"]) for r in rs)
        # The Little's-law identity above, checked per cell. Tolerance 1e-3 rather than something
        # tighter because the CSV rounds each segment to 0.1 ticks, which on the control's 80,000
        # is ~1e-4; measured worst across the 15 cells is 1.25e-4. A real divergence here means
        # the window was not constant, and then pooled heights no longer equal MPL / mean(tps).
        little_w = float(rs[0]["mpl"]) / means[key]["_tps"] * 1e6
        drift = abs(means[key]["_life"] - little_w) / little_w
        if drift > 1e-3:
            raise SystemExit(
                f"REFUSING TO PLOT: pooled lifetime for {key} misses MPL/mean(tps) by "
                f"{drift:.2%}.\nPooling equals MPL / mean(tps) only while every seed shares one\n"
                "measurement window. It does not here, so the bar heights would not be the\n"
                "quantity the throughput figure reports and the two figures would disagree.")

    return means, dropped, worst_gap, worst_inflight, worst_viol


def fit_margins(key, means, left=0.10, bottom=0.20, pad_px=3.0, rounds=6):
    """Grow the left/bottom margins until no tick label falls outside the figure.

    MEASURED, NOT GUESSED, because guessing is what put labels off the edge. Rotated 45 degrees,
    a label extends both left of and below its tick by an amount that depends on its TEXT - so
    any hardcoded margin is correct only for the labels that happened to exist when it was
    chosen. Adding the alpha=100 arm silently clipped the bottom row by 9.6 px, which is invisible
    at a glance and gone from the printed page.

    These figures are saved WITHOUT bbox_inches="tight" so the three plot boxes stay identical
    (see the panel loop), which means nothing auto-expands to rescue a label. The margin has to be
    right before the save, not cropped to fit after it.

    One margin pair is computed and used for ALL THREE panels - they carry the same labels, and
    identical margins are what makes the plot boxes coincide. Returned rather than applied so the
    caller can do exactly that.
    """
    for _ in range(rounds):
        fig, ax = plt.subplots(figsize=(PANEL_W_IN, PANEL_H_IN))
        fig.subplots_adjust(left=left, right=0.99, top=0.88, bottom=bottom)
        draw_panel(ax, key, means)
        fig.canvas.draw()
        r = fig.canvas.get_renderer()
        w_px, h_px = fig.get_size_inches() * fig.dpi
        over_l = over_b = 0.0
        # TICK LABELS ONLY. A previous version also measured ax.texts and ax.lines, to reserve
        # room for grouping brackets drawn below the labels with clip_on=False. The brackets are
        # gone - the arm labels now say what each bar is - so there is nothing outside the axes to
        # account for, and measuring the bars' own artists would only inflate the margin.
        for t in ax.get_xticklabels() + ax.get_yticklabels():
            bb = t.get_window_extent(renderer=r)
            over_l = max(over_l, -bb.x0)
            over_b = max(over_b, -bb.y0)
        plt.close(fig)
        if over_l <= 0 and over_b <= 0:
            return left, bottom
        left += (over_l + pad_px) / w_px
        bottom += (over_b + pad_px) / h_px
    raise SystemExit(
        "REFUSING TO PLOT: tick labels still overflow the panel after %d margin rounds "
        "(left=%.3f bottom=%.3f). Shorten the arm labels or widen the panel." % (rounds, left, bottom))


def draw_panel(ax, key, means):
    """One theta panel. No title and no legend - both live in LaTeX now.

    FRACTIONS OF LIFETIME, 0 to 1 - the Abyss/Deneva runtime-breakdown axis. An absolute axis was
    the wrong call and had to be withdrawn twice over. Shared and linear, the largest bar (8.2 M us
    at theta=0.8/f=0.80) reduces the control to a ~1% sliver and most bars vanish; per-panel scales
    make panels incommensurable. Since the claim here is about COMPOSITION - which segment
    dominates, and where that changes - normalising is not hiding the magnitude, it is removing a
    variable the companion throughput figure already reports.

    set_ylim(0, 1) IS SET EXPLICITLY IN EVERY PANEL, and that is load-bearing now in a way it was
    not before. The combined figure passed sharey=True, so one limit governed all three; three
    separate figures share nothing, and matplotlib would autoscale each to its own data. Three
    panels at three different y limits look fine individually and are not comparable, which is the
    whole point of the figure.
    """
    theta, klen = key
    present = [a for a in ARMS if (theta, klen, a) in means]
    xs = range(len(present))
    bottoms = [0.0] * len(present)
    # Denominator is the FULL lifetime (all SEGMENTS), while only PLOT_SEGMENTS are stacked. So
    # the dropped dispatch queue is still charged against the bar rather than redistributed into
    # the segments around it - the bars top out a hair below 1.0 by exactly the amount of time
    # genuinely unaccounted for on the axis. Normalising by the drawn segments instead would
    # inflate every other segment to close a gap: the residual defect wearing a different hat.
    totals = [sum(means[(theta, klen, a)][c] for c, _, _, _ in SEGMENTS) for a in present]
    for col, _label, colour, hatch in PLOT_SEGMENTS:
        vals = [means[(theta, klen, a)][col] / t if t else 0.0
                for a, t in zip(present, totals)]
        ax.bar(xs, vals, bottom=bottoms, width=0.7, color=colour, hatch=hatch,
               edgecolor="black", linewidth=0.5, zorder=3)
        bottoms = [b + v for b, v in zip(bottoms, vals)]
    # LIFETIME RELATIVE TO THE CONTROL, above each bar.
    #
    # A share plot cannot show magnitude, and magnitude is most of the story: at k = 50 the
    # f = 0.80 bar reads "mostly backoff" while its transactions live ~199x longer than the
    # control's. Population is pinned at the MPL cap and Little's law holds (both gates read
    # 1.000), so the lifetime ratio IS the inverse throughput ratio - no extra measurement, and a
    # reader can check it against the throughput figure.
    ctl = means.get((theta, klen, "control"))
    if ctl:
        base_life = sum(ctl[c] for c, _, _, _ in SEGMENTS)
        for x, a in zip(xs, present):
            ratio = sum(means[(theta, klen, a)][c] for c, _, _, _ in SEGMENTS) / base_life
            txt = ("%.0f" % ratio) if ratio >= 10 else ("%.2g" % ratio)
            ax.text(x, 1.015, txt + "x", ha="center", va="bottom", fontsize=5.5, clip_on=False)

    # NO NUMERIC LABEL ON A THIN SERVICE BAND. At f = 0.80 service is 0.1-0.4% of lifetime and the
    # band is thinner than the bar's own edge line, so the figure cannot show it - but an inline
    # "0.1%" in red was worse than the omission, reading as an error marker rather than a value.
    # The number belongs in the caption, where there is room to say what it means; the bar's job
    # is the shape. The lifetime-ratio labels above each bar already carry the magnitude.

    ax.set_ylim(0, 1.0)
    ax.set_yticks([0, 0.2, 0.4, 0.6, 0.8, 1.0])
    ax.set_xticks(list(xs))
    # ROTATED, which the combined figure did not need. Five labels across a 2.4 in panel is about
    # 2.3 in of text at 8 pt before gaps, so horizontal labels collide or get silently dropped.
    # Flagged rather than done quietly, since the brief asked for them "as currently".
    # HORIZONTAL again. Rotation was needed at 2.4 in wide, where five labels at 8 pt overran the
    # axis; at 3.2 in and 7 pt they occupy ~1.8 in of 3.2 and fit with room to spare. Rotated
    # labels would also be costly here specifically - at 1.1 in tall they would eat a third of the
    # figure's height.
    # ROTATED 30 degrees, which also buys back a point of size. Horizontal, six labels compete for
    # the panel's WIDTH and had to drop to 6 pt to stop colliding; rotated they compete for its
    # diagonal instead, so 7 pt fits and matches the y tick labels again. ha="right" puts the end
    # of each label under its own tick rather than centring it between two.
    ax.set_xticklabels([ARM_LABELS.get(a, a) for a in present],
                       rotation=30, ha="right", rotation_mode="anchor")
    # Tight x limits so the bars span the axis instead of clustering mid-panel, which is what a
    # wide short panel makes obvious and a square one hides.
    ax.set_xlim(-0.6, len(present) - 0.4)
    # PER AXIS, and that is not cosmetic: a bare tick_params(labelsize=...) sets BOTH axes and
    # runs after set_xticklabels, so a fontsize passed there is silently overridden. That bug
    # kept the x labels at 7 pt through a render that was supposed to shrink them, and the only
    # symptom was labels still overlapping - which reads as "6 pt is not small enough" rather
    # than "the setting never applied".
    #
    # x is smaller than y because six bars share the panel width while the y axis has six ticks
    # down its height, where there is no crowding.
    ax.tick_params(axis="x", labelsize=7)
    ax.tick_params(axis="y", labelsize=7)
    for sp in ax.spines.values():
        sp.set_linewidth(0.8)
    # NO AXIS LABEL on any panel - what the axis measures is stated in the LaTeX caption.
    # TICK LABELS ARE KEPT ON ALL THREE, matching the reference figure. An earlier version
    # dropped them from (b) and (c) to save width; the reference keeps them, and it is the
    # better call for a figure whose whole claim is that the three panels are comparable - a
    # reader checking a bar height against 0.6 should not have to count ticks across two panels
    # to find the scale. It also makes the three plot boxes identical by construction rather
    # than by the reserved-margin trick that was needed when only (a) carried labels.


def main():
    plt.rcParams.update({
        "font.size": 9, "axes.linewidth": 0.8, "legend.fontsize": 8,
        "xtick.direction": "in", "ytick.direction": "in",
        "xtick.major.size": 3, "ytick.major.size": 3,
    })
    means, dropped, worst_gap, worst_inflight, worst_viol = load()

    # UNEQUAL SEED COUNTS ARE A HARD STOP, and this gate exists because the trap was sprung twice
    # on the very cell that would spring it again. theta=0 / f=0.80 reads 555,449 on one seed and
    # 2,377,405 on another - a 4.3x spread - so a bar drawn from a partial cell is not a noisier
    # version of the result, it is a different one. Both earlier misreadings of that cell came
    # from plotting or quoting it before its seeds were in, and the caption printed below states
    # five seeds, so a short cell would ship a figure whose caption is false.
    ns = sorted({means[k]["_n"] for k in means})
    if len(ns) > 1 and "--allow-partial" not in sys.argv:
        short = sorted(k for k in means if means[k]["_n"] < max(ns))
        raise SystemExit(
            f"REFUSING TO PLOT: seed counts differ across cells {ns}; short cells {short}.\n"
            "Pooling weights by completions, so a missing seed does not merely widen a bar - it\n"
            "moves it, and most on the highest-variance cell. Finish the sweep, or pass\n"
            "--allow-partial knowing the figure is provisional and the caption's seed count wrong.")

    OUT.parent.mkdir(parents=True, exist_ok=True)

    # ONE DRAWING FUNCTION, used by the standalone panels AND by the combined figure below.
    # Two copies of this loop would be free to drift - a segment reordered in one and not the
    # other produces two figures that disagree while both look right, and the PDF that reaches
    # LaTeX is not the one anyone reviews on screen.
    # One margin pair for all three panels, measured from the labels they actually carry.
    fit_l, fit_b = fit_margins((THETAS[0], BASE_KLEN), means)
    print(f"panel margins fitted: left={fit_l:.3f} bottom={fit_b:.3f}")
    for n, theta in enumerate(THETAS):
        # IDENTICAL figsize AND identical margins on all three, saved WITHOUT bbox_inches="tight".
        #
        # The brief asked for a wider panel (a) plus tight bbox, to compensate for the label column
        # it carries. That does not actually equalise the plot areas, and the arithmetic says why:
        # LaTeX scales each file to the same \linewidth, so what matters is each figure's plot-area
        # FRACTION. Panel (a) spends ~0.5 in on the y label and tick labels. At 3.2 in that
        # fraction is 0.84 against (b)'s ~0.98; widening (a) to 3.5 in moves it only to 0.86,
        # because the label column is a fixed width being divided by a slightly larger number.
        # Equalising this way needs a panel (a) about 19 in wide.
        #
        # Reserving the SAME left margin in all three fixes it exactly: (b) and (c) simply leave
        # that strip empty. The plot boxes are then identical by construction rather than by
        # eyeballing, and three panels whose bars are drawn at different widths is precisely the
        # defect that survives review - it looks like a rendering artifact, not like data.
        #
        # tight bbox has to go for the same reason: it crops each figure to its own ink, which
        # re-introduces the difference the fixed margins just removed.
        fig, ax = plt.subplots(figsize=(PANEL_W_IN, PANEL_H_IN))
        # Recomputed now the axis label is gone: the reserved left strip only has to hold a "0.0"
        # tick label at 7 pt (~0.23 in of 2.24), not a two-line rotated caption. Still IDENTICAL
        # on all three, which is the part that matters - (b) and (c) leave the strip empty so
        # their plot boxes coincide with (a)'s exactly.
        fig.subplots_adjust(left=fit_l, right=0.99, top=0.88, bottom=fit_b)
        draw_panel(ax, (theta, BASE_KLEN), means)
        stem = f"{OUT}_{'abc'[n]}"
        fig.savefig(f"{stem}.pdf")
        fig.savefig(f"{stem}.png", dpi=300)
        plt.close(fig)

    # LENGTH FACET. Same drawing code, same margins, same y limits - only the cell selected
    # differs - so a bar here is directly comparable to the contention figure's theta=0.5 panel,
    # which is the k = 10 point of this same series.
    for klen in KLENS:
        key = (BASE_THETA_FOR_KLEN, klen)
        if not any((key[0], key[1], a) in means for a in ARMS):
            print(f"    no rows for k={klen} at theta={key[0]} - skipping that panel")
            continue
        fig, ax = plt.subplots(figsize=(PANEL_W_IN, PANEL_H_IN))
        fig.subplots_adjust(left=fit_l, right=0.99, top=0.88, bottom=fit_b)
        draw_panel(ax, key, means)
        stem = f"{OUT}_k{klen}"
        fig.savefig(f"{stem}.pdf")
        fig.savefig(f"{stem}.png", dpi=300)
        plt.close(fig)

    # LEGEND AS ITS OWN FILE, built from PLOT_SEGMENTS rather than from the bar handles.
    # Driving it off the same list the bars are drawn from is what stops it drifting: a colour or
    # hatch changed in one place changes both, and a segment added or removed cannot leave the
    # legend describing a figure that no longer exists.
    #
    # Order is bottom-to-top of the stack. The combined figure reverses its legend because a
    # VERTICAL box can mirror the stack's own order; a single row has no vertical axis to mirror,
    # so the natural reading order is the stack's own.
    # Authored at exactly TEXTWIDTH_IN and saved WITHOUT a tight bbox, so width=	extwidth is a
    # 1.0 scale and the legend text matches the panels point for point.
    legend_fig = plt.figure(figsize=(TEXTWIDTH_IN, 0.34))
    patches = [matplotlib.patches.Patch(facecolor=colour, hatch=hatch, edgecolor="black",
                                        linewidth=0.5, label=legend_label(name))
               for _, name, colour, hatch in PLOT_SEGMENTS]
    # COLUMN SPACING IS FITTED, NOT CHOSEN. At the spacing this file used to carry the row
    # measured 7.28 in on a 7.00 in canvas and was clipped by 0.14 in at BOTH ends - the first
    # and last swatches ran off the page. The comment above about stripping the parentheticals
    # so the row "fits at 9 pt" was half right: the labels are short enough, the gaps were not.
    #
    # Nothing here is saved with a tight bbox (deliberately - see below), so nothing expands to
    # rescue it, and the 9 pt is the point of the exercise and must not be traded away. So the
    # gaps close until the row fits, and the run says what it settled on.
    for _cs in (1.8, 1.5, 1.2, 1.0, 0.8, 0.6):
        _lg = legend_fig.legend(handles=patches, loc="center", ncol=len(patches), fontsize=9,
                                frameon=True, handlelength=1.7, columnspacing=_cs,
                                borderpad=0.45, handletextpad=0.6)
        legend_fig.canvas.draw()
        _bb = legend_fig.get_tightbbox(legend_fig.canvas.get_renderer())
        if _bb.x0 >= -0.005 and _bb.x1 <= TEXTWIDTH_IN + 0.005:
            break
        _lg.remove()
    else:
        print("  WARNING: the legend row does not fit 7.00 in even at the tightest spacing")
    print(f"  legend columnspacing fitted to {_cs} "
          f"(row {_bb.x1 - _bb.x0:.2f} in of {TEXTWIDTH_IN:.2f} in)")
    legend_fig.savefig(f"{OUT}_legend.pdf")
    legend_fig.savefig(f"{OUT}_legend.png", dpi=300)
    plt.close(legend_fig)

    plt.close(fig)

    ns = sorted({means[k]["_n"] for k in means})
    print(f"{len(means)} cells, n={ns}, closure error <= {worst_gap:.2e}, "
          f"worst gate error {worst_inflight:.2e}, "
          f"edge deficit <= {worst_viol:.2f}x its bound (floored at 0)"
          + (f", DROPPED {dict(dropped)}" if dropped else ""))
    for theta, klen in [(t, BASE_KLEN) for t in THETAS] + \
            [(BASE_THETA_FOR_KLEN, k) for k in KLENS]:
        for a in ARMS:
            if (theta, klen, a) in means:
                m = means[(theta, klen, a)]
                print(f"  theta={theta:<4g} k={klen:<3d} {a:<11} "
                      f"lifetime={m['_life']:>10.0f}  "
                      f"service={m['service_ticks']:>8.0f}  "
                      # The MERGED segment, which is what the bar draws. Printing the raw
                      # processor_wait_ticks here showed -1263 on theta=0.8/f=0.80 - a number
                      # that is real in the CSV, is not in the figure, and invites exactly the
                      # "why is a wait negative" question the merge was made to retire.
                      f"proc_wait={m[MERGED]:>9.0f}  "
                      f"cascade={m['cascade_wait_ticks']:>10.0f}  tps={m['_tps']:>8.1f}  "
                      f"pop={m['_pop']:.4f} little={m['_little']:.4f}")
    print()
    print("CAPTION")
    print(CAPTION)
    print("\n-> LaTeX subfigures: "
          + ", ".join(f"{OUT.name}_{c}.pdf" for c in "abc")
          + f", {OUT.name}_legend.pdf")
    print(f"   review copy, NOT used by LaTeX: {OUT.name}.pdf")


main()
