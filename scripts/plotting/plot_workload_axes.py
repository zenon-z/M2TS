"""W1-W4: four workload-axis figures from one panel function, called four times.

Each figure is the shared 4-panel CPR template against one workload axis:

  A  CPR fraction f                  0.05 / 0.25 / 0.80
  B  fan-out mu                      1 / 2 / 4
  C  cap m                           2 / 3 / 5   (mu held at 2, so the MEAN out-degree is
                                                  fixed and only its VARIANCE moves)
  D  in-degree concentration alpha   0.05 / 1.0 / 100

plus the no-CPR control replotted in every panel.

TWO Y-QUANTITIES, AND WHY NEITHER IS "THROUGHPUT RELATIVE TO THE CONTROL"
-------------------------------------------------------------------------
An earlier draft plotted capacity share - throughput divided by the parent transaction's
analytic capacity - which makes the control sit at 1.0 and every CPR arm a fraction of
it. That is NOT a fair comparison and it is not used here: the arms do different amounts
of work. At f = 0.80 a group issues ~100 resource references against the control's 10,
so "16x lower share" mostly reports that the arm did more work, not that it did it
worse. Dividing by a denominator that ignores the extra work builds the answer into the
axis.

  THROUGHPUT  absolute completed groups per second. Fair because it claims no ratio - it
              is simply what the system delivered. Log y, because the range is ~140x.

  MULTIPLIER  (1 / capacity_share) / sdr, where sdr is the realised service-demand ratio
              1 + cpr_edges_per_completion / k. This DIVIDES THE EXTRA WORK OUT: 1.0
              means CPRs cost exactly the work they add, above 1.0 is interaction cost on
              top of it. The control returns exactly 1.0000 on all four axes, which is
              the statistic validating itself - no cascades, no interaction.

THE JOIN HAS A TRAP IN IT
-------------------------
A raw row carries the panel/label of its config's FIRST appearance only, because the
driver runs each config once under a representative label. The base cell appears as
A/mid, B/mid, C/high and D/alpha=1.0, so all of its raw rows say "A/mid" - and grouping
raw rows by their own panel/label leaves Panels B, C and D with a HOLE where the middle
line should be. The first draft did exactly that: it rendered cleanly with 80 points
instead of 104, and the missing third only showed on looking at the picture. So
(panel, label) -> config_id comes from the sidecar, which is what the fan-out is for.

BANDS
-----
Lines are the mean over 5 seeds; bands are the MIN-MAX range over those seeds. Not a
confidence interval - see series() for why that was withdrawn.

Where the band is wide - the theta transition, and f = 0.80 everywhere - that is the random
CPR graph draw, not instrument noise: a denser draw raises the cascade size and the loss
together, and cpr_edges_fired_per_completion measures it directly (at f = 0.80 one draw in
forty fired 41 edges per completion against a floor of 59 across the rest). It is a property
of the system and is left visible rather than smoothed.

CAPTION CLAUSE, required on every one of the four figures:
  "Lines show the mean over 5 seeds; shaded bands show the min-max range. Each x-point
   draws an independent CPR graph per seed; at f = 0.80 the closure percolates and its
   size varies several-fold across draws, which is what the wide bands on that line are."

EXTRA CLAUSE ON THE UPDATE-INTENSITY FIGURE (the readonly axis):
  "x is the NOMINAL update-transaction fraction; under contention the admitted mix
   shifts further toward updates."

That second sentence is not decoration. read_only_txn_frac_realized is the ADMITTED mix
and counts restarts, so realised = nominal / (1 + aborts_per_completion): update
transactions abort and read-only ones do not, so the admitted population carries more
updates than the generated one, by exactly the abort rate. Plotting against the realised
value would make the x axis a function of the y axis's own mechanism - the cells losing
most throughput would slide furthest right - which is why the axis is nominal and the
caption says so. See supersedes row 28.
"""
import collections
import csv
import statistics
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
# The geometry and type rules are shared with the factorial panels, which are authored the same
# way - at printed size, placed without LaTeX scaling. See panel_style for why each one measures.
from panel_style import (                           # noqa: E402
    text_width_in, set_log_ticks, widen_for_markers, save_exact, fit_label)

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "artifacts/experiments/results"
OUT = ROOT / "artifacts/figures/wl"

C_CTL = "#000000"
C_LOW, C_MID, C_HIGH = "#1f77b4", "#2ca02c", "#d62728"
M_CTL, M_LOW, M_MID, M_HIGH = "o", "s", "^", "v"
ROLES = ("low", "mid", "high")

# PANEL FONT SIZES, explicit rather than inherited from rcParams - and these are the ORIGINAL
# sizes, kept deliberately.
#
# A panel is authored at 2.75 x 2.5 in, the size it had inside the old 11 x 2.5 in combined
# figure, and LaTeX scales it to a 0.245\textwidth slot by ~0.62 - the same factor the combined
# figure got at \linewidth. So the panels print exactly as they did before, at ~5.6 pt effective.
#
# Authoring at TRUE display size (1.71 in, ~7 pt) was tried and looked wrong: correct for a figure
# read on its own, but visibly heavier than the figure it replaces. Matching an appearance that
# already exists beats being theoretically right about point sizes.
#
# The LEGEND is the deliberate exception - its own file at the full \textwidth, so its 9 pt really
# is 9 pt instead of the ~5.4 pt it reached inside the old figure.
F_TICK, F_LABEL = 8.0, 9.0

# Set while rendering the standalone panels, cleared for the combined review figure - which is
# 11 in wide and has room for the label. A list so the closure can mutate it.
STANDALONE = [False]

# Filled by main()'s first pass over every (axis, quantity); see render().
MARGINS = [0.16, 0.20]
LATEX_OUT = []

# Path prefix as the DOCUMENT sees it, which is not where this script writes. The figures land
# in artifacts/figures/ and are copied into the paper tree; emitting the document's prefix means
# the generated block pastes in without a find-and-replace that is easy to do to three of four
# figures.
FIG_PREFIX = "images/EAnB"

# The t-table and t95() that stood here are DELETED, not left unused. This figure no longer
# draws a confidence interval (see series()), and a dead t95() sitting in the file is an
# invitation to put one back without re-reading why it was withdrawn. The plan's separation
# tests still use t at the point's own n - supersedes row 21 - and that is the right place
# for it: a decision about whether an effect is real, not a shaded band on a plot.

# PLOTTED X, WHICH IS NOT ALWAYS THE KNOB THAT WAS SET.
#
# The readonly axis sweeps readOnlyTransactionFraction, so a rising x means LESS CPR work and the
# curves rise left to right - the inverse of the other three figures, where rising x means more
# work and the curves fall. Four panels read in two opposite directions is a reading error waiting
# to happen, and it is one a reader makes silently.
#
# Plotted as its complement, the UPDATE fraction: ro in {0, 0.25, 0.5, 0.75, 0.9} becomes
# {1.0, 0.75, 0.5, 0.25, 0.1}, and the curves fall as update share - and therefore CPR work -
# rises, like every other figure.
#
# A DISPLAY TRANSFORM ONLY. readOnlyTransactionFraction stays untouched in the raw rows and the
# sidecar, because it is the knob the driver actually set and the value config_hash covers.
# Rewriting the stored column to match the picture would make the data describe the figure rather
# than the run - and it is the stored value a later reader re-derives a cell from.
#
# The transform is applied where x is READ, so the sort that builds each series operates on
# plotted values; transforming at draw time instead would order the points by the knob and connect
# them in the wrong sequence.
X_TRANSFORM = {"readonly": lambda x: 1.0 - x}

AXES = [("theta", r"Contention level ($\theta$)"),
        ("readonly", "Update transaction fraction"),
        ("cores", "Number of cores"),
        ("txnlen", "Transaction length $k$")]

PANELS = [
    ("CPR fraction $f$", "f", [("low", "0.05", C_LOW, M_LOW), ("mid", "0.25", C_MID, M_MID),
                               ("high", "0.80", C_HIGH, M_HIGH)]),
    (r"Fan-out $\mu$", r"\mu", [("low", "1", C_LOW, M_LOW), ("mid", "2", C_MID, M_MID),
                                ("high", "4", C_HIGH, M_HIGH)]),
    ("Cap $m$", "m", [("low", "2", C_LOW, M_LOW), ("mid", "3", C_MID, M_MID),
                      ("high", "5", C_HIGH, M_HIGH)]),
    # ORDERED BY CONCENTRATION, NOT BY alpha - deliberately descending in alpha.
    #
    # Price attachment draws targets with P ~ inDegree + alpha, so SMALL alpha lets
    # rich-get-richer dominate and produces the MOST concentrated in-degree. Listing
    # alpha ascending paints the harmful end blue here while it is red in panels (a)
    # and (b), and a reader scanning colours across four panels reads the concentration
    # result backwards. The panel sweeps CONCENTRATION; alpha is the knob that produces
    # it inversely, so the roles follow concentration and the subcaption states the
    # direction for anyone reading the numbers rather than the colours.
    (r"Concentration $\alpha$", r"\alpha",
     [("alpha=100.0", "100", C_LOW, M_LOW), ("alpha=1.0", "1.0", C_MID, M_MID),
      ("alpha=0.05", "0.05", C_HIGH, M_HIGH)]),
]

# Subcaption suffix where the swept quantity needs its direction stated. Second line, so
# it cannot grow into the neighbouring panel's subcaption.
NOTES = {3: "\n" + r"(smaller $\alpha$ = more concentrated)"}


def load(axis):
    """(panel,label,x) -> list of (throughput_tps, multiplier) across seeds."""
    # Identity for every axis but readonly; see X_TRANSFORM.
    xf = X_TRANSFORM.get(axis, lambda x: x)
    cap, klen, appears = {}, {}, collections.defaultdict(set)
    for c in csv.DictReader(open(RESULTS / f"wl_{axis}_cells.csv")):
        cap[c["config_id"]] = float(c["reference_capacity_tps"])
        klen[c["config_id"]] = float(c["txn_len"])
        appears[c["config_id"]].add((c["panel"], c["label"]))

    dropped = collections.Counter()
    pts = collections.defaultdict(list)
    for r in csv.DictReader(open(RESULTS / f"wl_{axis}_raw.csv")):
        # C5: never pool across termination paths - a censored run measured a different
        # thing, and averaging it in produces a point that describes neither.
        if r["terminated_by"] != "TIME_LIMIT":
            dropped[r["terminated_by"]] += 1
            continue
        cid = r["config_id"]
        tps = float(r["throughput_tps"])
        share = tps / cap[cid]
        sdr = 1.0 + float(r["cpr_edges_fired_per_completion"]) / klen[cid]
        mult = (1.0 / share) / sdr if share > 0 else float("nan")
        # THIRD QUANTITY: tuple accesses per second, tps x k.
        #
        # Transactions/s is not a valid unit on the TRANSACTION-LENGTH axis: a shorter
        # transaction completes faster by construction, so tps falls with k for reasons that have
        # nothing to do with CPRs. The control makes this unmistakable - it reads 4800, 2400, 960
        # and 480 tps at k = 5, 10, 25, 50, a 10x decline that is pure unit artefact.
        #
        # In accesses the same control is FLAT at exactly 24,000/s at every k, which is
        # 24 cores / 1000 us per access - so the no-CPR reference is capacity-bound in ACCESSES,
        # not in transactions, and accesses are the invariant unit. Yu et al. make the same
        # change for the same reason on their variable-length YCSB experiment.
        #
        # PARENT accesses only, not parent + CU. Counting cascade references would credit a
        # high-f arm for the extra work the cascade creates, which is the same error the module
        # docstring rejects for capacity share: a denominator that ignores added work builds the
        # answer into the axis. What the workload asked for is k tuples; the cascade is overhead.
        #
        # k is taken from the sidecar rather than assumed: it is the configured length, and the
        # control's exact 24,000 confirms the engine performs exactly k references per
        # transaction, so configured and realised agree here (C6).
        for key in appears[cid]:
            pts[(key[0], key[1], xf(float(r["x"])))].append((tps, mult, tps * klen[cid]))
    return pts, dropped


def series(pts, panel, label, idx):
    """Mean across seeds, with a MIN-MAX band rather than a confidence interval.

    A 95% CI answers "where might the true mean lie given these five draws", and on the cell
    where that question is hardest it answers loudly: at theta = 0 / f = 0.80 the runs are
    81, 94, 105, 106 and 346 tps, and the t-interval on that spread reaches roughly 8 to 280.
    Nothing in the experiment ever ran at 8 tps. On a log axis its lower edge reads as a
    near-collapse that did not happen, and a reader will take a shaded band around a line as
    "where the runs landed" no matter what the caption says - so the figure would be making a
    claim its own data contradicts.

    Min-max says exactly that: the range the five runs occupied. It needs no statistical
    gloss, it cannot be read as something it is not, and it is the common convention in
    systems papers. It also renders the real structure of the hard cell honestly - the band
    runs 81 to 346 and the single fast run is visibly the top of it, rather than being
    laundered into a symmetric interval around a mean it alone pulled up.

    The cost, stated: min-max is not an inferential quantity and it WIDENS with n, so it must
    not be read as precision, and two points are only comparable at equal n. Every point here
    is n = 5 by construction, which is what makes it safe; the seed count is in the sidecar's
    seeds_used column if that ever stops being true.
    """
    xs = sorted({x for (p, l, x) in pts if p == panel and l == label})
    m, lo, hi = [], [], []
    for x in xs:
        v = [row[idx] for row in pts[(panel, label, x)]]
        m.append(statistics.mean(v)); lo.append(min(v)); hi.append(max(v))
    return xs, m, lo, hi


def panel_row(axs, pts, x_label, idx, y_label, logy, ctl_line, only=None):
    """One figure body: four panels. Called once per axis per y-quantity.

    ONE FIGURE-LEVEL LEGEND, with the concrete values in each panel's subcaption.

    Per-panel legends occluded data: in the cores figure the f = 0.80 points at 1-2 cores
    sat underneath the box, and the bottom-left of theta and readonly was at risk. Moving
    them to the upper right does not help either, because the control line runs across
    the top of every panel.

    A shared legend normally cannot work here, since each panel sweeps a different
    quantity and listing all twelve series would leave the reader matching entries to
    panels. It works because the three lines are always low / mid / high in the same
    colour and marker, so the legend names the ROLE and the subcaption names the VALUES.
    """
    # `only` draws a single named panel into a single axes - used by the standalone renders,
    # so they go through the SAME drawing code as the combined review figure and cannot drift.
    panels = PANELS if only is None else [PANELS[only]]
    for n, (ax, (quantity, sym, lines)) in enumerate(zip(axs, panels)):
        if only is not None:
            n = only
        handles = {}
        cxs, cm, clo, chi = series(pts, "control", "no CPR", idx)
        if cxs:
            h, = ax.plot(cxs, cm, ls="--", color=C_CTL, lw=1.1, marker=M_CTL, ms=4.5,
                         mfc="white", mew=1.1, zorder=5)
            handles["No CPR (control)"] = h
        for (label, shown, colour, marker), role in zip(lines, ROLES):
            xs, mm, lo, hi = series(pts, quantity_key(n), label, idx)
            if not xs:
                print(f"    WARNING: panel {n} / {label} has no rows")
                continue
            ax.fill_between(xs, lo, hi, color=colour, alpha=0.15, lw=0, zorder=2)
            h, = ax.plot(xs, mm, ls="-", color=colour, lw=1.1, marker=marker, ms=4.5,
                         mfc="white", mew=1.1, zorder=4)
            handles[role] = h
        if logy:
            ax.set_yscale("log")
        elif ctl_line:
            ax.axhline(1.0, color="#999999", lw=0.8, ls=":", zorder=1)
        ax.set_xlabel(x_label, fontsize=F_LABEL)
        # NO Y-AXIS LABEL on the panels. At four across \textwidth a panel is 1.7 in wide, and
        # "Throughput (tps)" plus its tick labels claimed 44% of that - leaving a 0.92 in plot
        # box, narrower than the label beside it. The quantity is named once in the LaTeX
        # caption instead. Tick labels stay, so values are still readable off every panel.
        ax.set_ylabel(y_label, fontsize=F_LABEL)
        ax.tick_params(labelsize=F_TICK)
        ax.grid(True, which="major", ls=":", lw=0.6, color="#bbbbbb", alpha=0.9)
        ax.set_axisbelow(True)
        for s in ax.spines.values():
            s.set_linewidth(0.8)
        # NO SUBCAPTION. The "(a) CPR fraction f = 0.05 / 0.25 / 0.80" line moved to the LaTeX
        # subfigure caption, so the panel values and the (a)/(b)/(c)/(d) labels are set in the
        # document rather than baked into the PDF. Kept in SUBCAPTIONS below so the LaTeX can be
        # generated from the same source the panels are drawn from - retyping them into the
        # document by hand is how a panel ends up captioned with another panel's values.
        if n:                       # shared y: only the leftmost panel is labelled
            ax.set_ylabel("")
    return handles


# The panel descriptions, for pasting into the LaTeX subfigure captions. Derived from PANELS so
# they cannot disagree with what is drawn.
def subcaption(n):
    quantity, _sym, lines = PANELS[n]
    vals = " / ".join(s for _, s, _, _ in lines)
    return f"{quantity} = {vals}{NOTES.get(n, '').replace(chr(10), ' ')}"


def shared_ylim(pts, idx, logy):
    """One y range across all four panels of a figure.

    WITHOUT THIS THE SEPARATE FILES ARE NOT COMPARABLE. The combined figure passed sharey=True,
    so one limit governed all four panels; four independent figures share nothing and matplotlib
    autoscales each to its own data. Panel (c) spans a much narrower range than panel (a), so it
    would render with its curves filling the axes and look like the same collapse - four panels
    that each look right and together say something false.

    Computed over every series INCLUDING the control and the min-max band edges, so no part of a
    drawn curve can fall outside the limit.
    """
    lo, hi = float("inf"), float("-inf")
    for (panel, label, _x), rows in pts.items():
        for row in rows:
            v = row[idx]
            if v is None or v != v or (logy and v <= 0):
                continue
            lo, hi = min(lo, v), max(hi, v)
    if lo > hi:
        return None
    if logy:
        return lo / 1.6, hi * 1.6
    pad = (hi - lo) * 0.08 or 0.1
    return lo - pad, hi + pad


def quantity_key(n):
    return ["A", "B", "C", "D"][n]


# FOUR SEPARATE PANELS, authored at their true display size so \includegraphics scales by 1.0 and
# a declared point size is that size on the page. See the same note in plot_time_breakdown.py: a
# figure authored larger than its slot has its fonts silently shrunk by the scale factor, and
# nothing in the rendered PNG shows it.
TEXTWIDTH_IN = 7.0
COLUMNWIDTH_IN = 3.33               # one column of a two-column page
AXIS_SUBFIG_FRACTION = 0.245        # must match the subfigure width in the LaTeX
# Authored at the size each panel had INSIDE the old 11 x 2.5 in combined figure, not at its
# 1.71 in slot. LaTeX then scales it by 0.62 - the same factor the combined figure got when it
# was placed at \linewidth - so the panels print exactly as they did before, at ~5.6 pt
# effective. Authoring at true display size would be the right call for a figure read on its
# own; here the goal is to match an appearance that already existed.
#
# The LEGEND is the deliberate exception: its own file at true \textwidth, so its 9 pt is 9 pt
# rather than the ~5.4 pt it reached inside the old figure.
# THE TARGET IS THE PLOT BOX ASPECT, NOT THE FIGURE ASPECT - they are not the same thing and
# confusing them is what made the first attempt look square.
#
# In the old combined figure each panel sat in a 2.75 x 2.5 in cell, but tight_layout plus
# subplots_adjust(bottom=0.34) - the strip reserved for the (a)/(b)/(c)/(d) subcaption -
# left a PLOT BOX of roughly 2.2 x 1.3 in, about 1.7:1. Reproducing the 2.75 x 2.5 figure
# without that reserved strip gives a 1.16:1 plot box: the same figure ratio, a visibly
# different plot.
#
# So the height is derived from the wanted plot-box ratio and the fitted margins, rather
# than set directly. One fixed-point iteration is enough - the margins barely move with H.
PLOT_BOX_ASPECT = 1.7              # plot width : plot height, matching the previous figure
AXIS_PANEL_W = 2.75
AXIS_PANEL_H = 1.72                # provisional; recomputed in main() once margins are known


def fit_axis_margins(draw, left=0.16, bottom=0.20, pad_px=3.0, rounds=7):
    """Grow margins until no tick label or axis label falls outside the figure.

    Measured rather than hardcoded, for the reason the breakdown script records: these files are
    saved WITHOUT a tight bbox so all four plot boxes stay identical, which means nothing expands
    to rescue a label that runs off the edge. A hardcoded margin is correct only for the labels
    that existed when it was picked - and the y tick labels here differ between the log throughput
    panels and the linear multiplier ones.
    """
    for _ in range(rounds):
        fig, ax = plt.subplots(figsize=(AXIS_PANEL_W, AXIS_PANEL_H))
        fig.subplots_adjust(left=left, right=0.975, top=0.97, bottom=bottom)
        draw(ax, True)
        fig.canvas.draw()
        r = fig.canvas.get_renderer()
        w_px, h_px = fig.get_size_inches() * fig.dpi
        # ax.get_tightbbox, NOT a loop over get_xticklabels().
        #
        # get_xticklabels() returns every tick Locator produced, INCLUDING ones outside the view
        # limits, and an off-screen tick's label carries a bbox far outside the figure. Measuring
        # those asked the margin to make room for labels that are never drawn: theta demanded
        # 20 px for a "-0.25" tick on an axis running 0.0 to 0.8, and cores 37 px for "-10" on an
        # axis running 1 to 24. That inflated the left margin to 44% of the panel and shrank the
        # plot box to 0.92 in - a layout driven entirely by invisible artists.
        #
        # get_tightbbox accounts for what is actually rendered, so it cannot be led by a phantom.
        bb = ax.get_tightbbox(r)
        over_l = max(0.0, -bb.x0)
        over_b = max(0.0, -bb.y0)
        plt.close(fig)
        if over_l <= 0 and over_b <= 0:
            return left, bottom
        left += (over_l + pad_px) / w_px
        bottom += (over_b + pad_px) / h_px
    raise SystemExit("REFUSING TO PLOT: axis panel labels overflow after %d rounds" % rounds)


def render(axis, x_label, pts, idx, y_label, logy, ctl_line, suffix):
    ylim = shared_ylim(pts, idx, logy)

    def draw_one(ax, show_ylabel, n=0):
        panel_row([ax], pts, x_label, idx, y_label, logy, ctl_line, only=n)
        if ylim:
            ax.set_ylim(*ylim)
        if not show_ylabel:
            ax.set_ylabel("")

    # One margin pair for all four, so their plot boxes coincide exactly. Measured on the panel
    # that CARRIES the y label, because that is the widest left margin any of them needs; (b)-(d)
    # leave the strip empty rather than drawing their boxes somewhere else.
    # ONE MARGIN FOR EVERY PANEL IN EVERY FIGURE, not one per figure.
    #
    # Fitting per figure gave left = 0.439 / 0.244 / 0.177 across the eight, so panels printed
    # side by side in the paper would have had visibly different plot boxes for no reason a
    # reader could infer. The widest requirement wins and everything else leaves the strip empty:
    # uniform by construction, at the cost of a little whitespace on the narrow ones.
    STANDALONE[0] = True
    fit_l, fit_b = MARGINS[0], MARGINS[1]

    stems = []
    for n in range(len(PANELS)):
        fig, ax = plt.subplots(figsize=(AXIS_PANEL_W, AXIS_PANEL_H))
        fig.subplots_adjust(left=fit_l, right=0.975, top=0.97, bottom=fit_b)
        draw_one(ax, n == 0, n)
        stem = f"{OUT}_{axis}_{suffix}_{chr(97 + n)}"
        fig.savefig(f"{stem}.pdf")
        fig.savefig(f"{stem}.png", dpi=300)
        plt.close(fig)
        stems.append(stem)
    STANDALONE[0] = False

    # Combined figure KEPT as a review copy - it shares panel_row with the four, so it cannot
    # disagree with them, and it is what anyone actually looks at while iterating.
    fig, axs = plt.subplots(1, 4, figsize=(11.0, 2.5), sharey=True)
    handles = panel_row(axs, pts, x_label, idx, y_label, logy, ctl_line)
    fig.legend(handles.values(), handles.keys(), loc="upper center", ncol=len(handles),
               frameon=True, fontsize=8.5, bbox_to_anchor=(0.5, 1.09),
               handlelength=2.2, columnspacing=1.8, borderpad=0.4)
    fig.tight_layout()
    fig.subplots_adjust(bottom=0.34)
    fig.savefig(f"{OUT}_{axis}_{suffix}.png", dpi=300, bbox_inches="tight", pad_inches=0.03)
    fig.savefig(f"{OUT}_{axis}_{suffix}.pdf", bbox_inches="tight", pad_inches=0.03)
    plt.close(fig)
    return stems


LATEX_CAPTIONS = {
    "theta": "Zipf access skew",
    "readonly": "update-transaction fraction",
    "cores": "core count",
    "txnlen": "transaction length",
}


# Figures whose panels are authored at printed size and must NOT be given a width by the
# document. Everything else here is still drawn at 2.75 in and scaled to its slot on purpose.
PRINT_SIZE_FIGURES = {("theta", "throughput"), ("readonly", "throughput"),
                      ("cores", "throughput"), ("txnlen", "operations")}


def emit_latex(axis, suffix, y_desc):
    """Print a ready-to-paste figure* block.

    GENERATED, not written by hand, so the subfigure captions cannot drift from the panels. The
    values in "(a) CPR fraction f = 0.05 / 0.25 / 0.80" come from PANELS - the same list the
    curves are drawn from - so a panel captioned with another panel's values is not possible.
    Retyping four captions into a document is exactly how that happens.
    """
    lines = [r"\begin{figure*}[t]", r"  \centering",
             r"  \includegraphics{%s/wl_axes_legend.pdf}" % FIG_PREFIX + r"\\[4pt]"]
    for n in range(len(PANELS)):
        sep = r"\hfill" if n < len(PANELS) - 1 else ""
        lines += [r"  \begin{subfigure}[b]{0.245\textwidth}",
                  # No width for the print-size panels: they are drawn at the size they
                  # occupy, so any width= would rescale their 7 pt type.
                  r"    \includegraphics%s{%s/wl_%s_%s_%s.pdf}"
                  % ("" if (axis, suffix) in PRINT_SIZE_FIGURES else r"[width=\linewidth]",FIG_PREFIX, axis, suffix, chr(97 + n)),
                  r"    \caption{%s}" % subcaption(n),
                  r"    \label{fig:%s-%s-%s}" % (axis, suffix, chr(97 + n)),
                  r"  \end{subfigure}" + sep]
    lines += [r"  \caption{%s versus %s. Lines show the mean over 5 seeds; shaded bands show the"
              % (y_desc, LATEX_CAPTIONS.get(axis, axis)),
              r"    min--max range. Each x-point draws an independent CPR graph per seed; at",
              r"    $f = 0.80$ the closure percolates and its size varies several-fold across",
              r"    draws, which is what the wide bands on that line are.}",
              r"  \label{fig:%s-%s}" % (axis, suffix), r"\end{figure*}"]
    return "\n".join(lines)


# Authored at printed size, like the factorial panels: 1.6 x 1.0 in for a 2x2 grid in ONE column.
PRINT_W, PRINT_H = 1.60, 1.00
PRINT_TICK_FS = 7.0
PRINT_MS, PRINT_LW = 3.0, 0.8


# WHAT EACH AXIS NEEDS THAT THE OTHERS DO NOT. Ticks are listed rather than derived: a locator on
# an axis with five unevenly spaced levels puts ticks between them, and at 1.13 in of width only
# two or three labels fit, so which ones is a judgement about what the reader needs to anchor on -
# the ends plus a middle. Labels are given longest-first and the widest that FITS is used, because
# "Update transaction fraction" is 1.4 in of 7 pt type against a 1.13 in axes.
PRINT_AXES = {
    "theta": dict(
        idx=0, suffix="throughput", xticks=[0.0, 0.4, 0.8],
        xlab=(r"Contention $\theta$", r"Skew $\theta$", r"$\theta$"),
        ylab=("Throughput (tps)", "Throughput", "tps")),
    "readonly": dict(
        idx=0, suffix="throughput", xticks=[0.0, 0.5, 0.9],
        xlab=("Update transaction fraction", "Update fraction", "Update frac."),
        ylab=("Throughput (tps)", "Throughput", "tps")),
    "cores": dict(
        idx=0, suffix="throughput", xticks=[1, 8, 16, 24],
        xlab=("Number of cores", "Cores"),
        ylab=("Throughput (tps)", "Throughput", "tps")),
    "txnlen": dict(
        idx=2, suffix="operations", xticks=[5, 25, 50],
        xlab=("Transaction length $k$", "Txn length $k$", "$k$"),
        # "operations/s" - the unit alone, which is what this axis measures and the only thing
        # that distinguishes this figure from the tps ones. It is short enough to fit on one line
        # without widening the label strip, so the axes keeps its full 1.07 in: the earlier
        # "Throughput ops/s" needed a second line, and reserving that line cost 0.14 in of plot
        # width. A shorter label buys the width back rather than spending it on a word the
        # caption already supplies.
        ylab=("operations/s", "ops/s")),
}

# Authored at printed size, like the factorial panels: 1.6 x 1.0 in for a 2x2 grid in ONE column,
# which is what 0.49\linewidth gives in a 3.33 in column.
PRINT_W, PRINT_H = 1.60, 1.00
PRINT_TICK_FS = 7.0
PRINT_MS, PRINT_LW = 3.0, 0.8


def render_print_panels(axis, pts):
    """Redraw one axis's four panels at printed size, overwriting the scaled versions.

    THESE PANELS LEAVE THE SCALED REGIME the rest of this script uses. Everything else here is
    authored at 2.75 x 2.5 in and scaled by LaTeX to about 0.62, which was a deliberate choice
    (see the F_TICK comment): it reproduces the appearance of the combined figure those panels
    replaced, when four sat across \textwidth. It does NOT hold for a 2x2 grid in a single
    column, where the slot is 1.6 in and scaling 2.75 in down to it would print 8 pt type at
    4.7 pt.

    ONE AXES RECTANGLE FOR ALL FOUR OF AN AXIS, computed once and applied identically. Only (a)
    and (c) carry a y label and only (c) and (d) an x label, but every panel reserves room for
    both: a panel that gave its label strip back to the data would have a wider plot box than its
    neighbours and the 2x2 grid would not line up.

    THE RECTANGLE IS NOT SHARED BETWEEN AXES, on purpose. Each figure is a separate 2x2 grid on
    the page and is read on its own, and their tick labels differ in width - forcing one rectangle
    across all four figures would size every one of them for the widest label in any of them.

    NO LEGEND INSIDE. At about 1.1 x 0.7 in of axes a four-entry legend is most of the panel; the
    series are named once in wl_axes_legend.pdf, which every figure in this family shares.
    """
    spec = PRINT_AXES[axis]
    idx = spec["idx"]
    # The y range from the DRAWN values only - every series' mean and both band edges - with no
    # decade padding, then widened until no marker touches a spine. shared_ylim() pads by 1.6x for
    # the scaled panels, which at this size is a visible empty strip top and bottom.
    lo, hi = float("inf"), float("-inf")
    xs_all = set()
    for (_panel, _label, x), rows in pts.items():
        for row in rows:
            v = row[idx]
            if v is None or v != v or v <= 0:
                continue
            lo, hi = min(lo, v), max(hi, v)
            xs_all.add(x)
    if lo > hi:
        print(f"    WARNING: {axis} has no positive rows at index {idx} - print panels skipped")
        return
    xpad = (max(xs_all) - min(xs_all)) * 0.06
    xlim = (min(xs_all) - xpad, max(xs_all) + xpad)

    def draw(ax, n):
        cxs, cm, _clo, _chi = series(pts, "control", "no CPR", idx)
        if cxs:
            ax.plot(cxs, cm, ls="--", color=C_CTL, lw=PRINT_LW, marker=M_CTL, ms=PRINT_MS,
                    mfc="white", mew=0.8, zorder=5)
        for (label, _shown, colour, marker), _role in zip(PANELS[n][2], ROLES):
            sx, mm, blo, bhi = series(pts, quantity_key(n), label, idx)
            if not sx:
                print(f"    WARNING: {axis} panel {n} / {label} has no rows")
                continue
            ax.fill_between(sx, blo, bhi, color=colour, alpha=0.15, lw=0, zorder=2)
            ax.plot(sx, mm, ls="-", color=colour, lw=PRINT_LW, marker=marker, ms=PRINT_MS,
                    mfc="white", mew=0.8, zorder=4)
        ax.set_yscale("log")
        ax.set_xlim(*xlim)
        ax.set_xticks([t for t in spec["xticks"] if xlim[0] <= t <= xlim[1]])
        ax.tick_params(labelsize=PRINT_TICK_FS, pad=1.5)
        ax.grid(True, which="major", ls=":", lw=0.6, color="#bbbbbb", alpha=0.9)
        ax.set_axisbelow(True)
        for sp in ax.spines.values():
            sp.set_linewidth(0.8)

    # PASS ONE: the tick labels are not knowable in advance - they depend on the widened range,
    # which depends on the geometry - so one throwaway panel is drawn to find out.
    fig = plt.figure(figsize=(PRINT_W, PRINT_H))
    ax = fig.add_axes((0.28, 0.24, 0.68, 0.72))
    draw(ax, 0)
    ylim = widen_for_markers(fig, [ax], (lo, hi))
    ax.set_ylim(*ylim)
    nticks = set_log_ticks(ax, *ylim)
    tick_strings = [t.get_text() for t in ax.get_yticklabels()]
    plt.close(fig)

    # 0.14 in PER LINE of the y label, not a flat 0.14. A rotated label is as WIDE as its line
    # count, and txnlen's fallback is two lines ("Throughput" / "ops/s"), which did not fit a
    # strip sized for one - the first line was clipped off the canvas. Derived from the
    # candidates so the strip and the label cannot disagree.
    _ylab_lines = max(c.count(chr(10)) + 1 for c in spec["ylab"])
    L = text_width_in(tick_strings, PRINT_TICK_FS) + 0.05 + 0.14 * _ylab_lines
    B = 0.13 + 0.14                                                # x tick labels + x label
    R, T = 0.04, 0.03
    RECT = (L / PRINT_W, B / PRINT_H, (PRINT_W - L - R) / PRINT_W, (PRINT_H - B - T) / PRINT_H)
    print(f"  {axis} print panels: {PRINT_W} x {PRINT_H} in; axes "
          f"{(PRINT_W - L - R):.2f} x {(PRINT_H - B - T):.2f} in; {nticks} y ticks; "
          f"y {ylim[0]:.0f}-{ylim[1]:.0f}")

    # 2x2 in one column: (a)(b) on top, (c)(d) below. Left column a, c; bottom row c, d.
    LEFT_COLUMN, BOTTOM_ROW = (0, 2), (2, 3)
    for n in range(len(PANELS)):
        fig = plt.figure(figsize=(PRINT_W, PRINT_H))
        ax = fig.add_axes(RECT)
        draw(ax, n)
        ax.set_ylim(*ylim)
        set_log_ticks(ax, *ylim)
        # MEASURED, NOT ASSUMED. "Throughput (tps)" is about 0.78 in of 7 pt type against an axes
        # box near 0.70 in, and "Update transaction fraction" is 1.4 in against 1.13 in - both run
        # off the canvas and are CLIPPED, which is how the first render of the theta panels came
        # out. fit_label falls back through shorter forms until one fits.
        if n in LEFT_COLUMN:
            used = fit_label(fig, ax, spec["ylab"], PRINT_TICK_FS)
            if used != spec["ylab"][0]:
                print(f"    note: {axis} ({chr(97 + n)}) y label -> {used!r}")
        if n in BOTTOM_ROW:
            used = fit_label(fig, ax, spec["xlab"], PRINT_TICK_FS, axis="x")
            if used != spec["xlab"][0]:
                print(f"    note: {axis} ({chr(97 + n)}) x label -> {used!r}")
        save_exact(fig, f"{OUT}_{axis}_{spec['suffix']}_{chr(97 + n)}", check_slack=False)


def render_legend():
    """One legend file shared by all eight figures - the four series are the same everywhere.

    Handles are constructed from the STYLE CONSTANTS rather than captured from a drawn panel, so
    the legend cannot describe a colour or marker the curves no longer use. Sized like the
    breakdown legend: authored at exactly TEXTWIDTH_IN and saved without a tight bbox, so
    width=\\textwidth is a 1.0 scale and 9 pt is 9 pt.
    """
    import matplotlib.lines as mlines
    entries = [("No CPR (control)", C_CTL, M_CTL, "--"),
               ("low", C_LOW, M_LOW, "-"),
               ("mid", C_MID, M_MID, "-"),
               ("high", C_HIGH, M_HIGH, "-")]
    handles = [mlines.Line2D([], [], color=c, ls=ls, marker=m, ms=5.5, lw=1.4,
                             mfc="white", mew=1.2, label=lab)
               for lab, c, m, ls in entries]
    # COLUMN WIDTH, 2 x 2, 7 pt - matched to the panels it now labels. The four panels moved to a
    # 2x2 grid in one column at 7 pt, and a legend authored at 7.0 in and placed beside them would
    # either be scaled down with them or tower over them at full width.
    #
    # THIS IS A SHARED FILE AND THE CHANGE REACHES EVERY FIGURE THAT INCLUDES IT. Any figure still
    # including it at width=\\textwidth now scales 3.33 in up to 7.0 - a factor of 2.1, printing
    # this 7 pt legend at about 15 pt. emit_latex() below therefore stops forcing a width on it;
    # a document that hardcodes one needs the same edit.
    # ONE ROW, SPREAD TO FILL THE COLUMN. At ncol=2 the four entries stacked into a block
    # narrower than the canvas, so the file carried a wide empty margin either side - and since
    # it is placed at width=\linewidth, that margin is printed. Four entries fit one row here,
    # and the column spacing is then opened UP until the row spans the canvas, which removes the
    # margin without cropping the file or changing how the document includes it.
    fig = plt.figure(figsize=(COLUMNWIDTH_IN, 0.30))
    # EVERY TRIAL IS REMOVED, including the one that ends the loop. Leaving the last trial
    # attached and then adding the chosen legend drew both, and the discarded one showed through
    # the final text as a ghost - two legends at different spacings printed over each other.
    _cs = 1.0
    for _try in (1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0):
        _lg = fig.legend(handles=handles, loc="center", ncol=len(handles), fontsize=7,
                         frameon=True, handlelength=1.8, columnspacing=_try,
                         borderpad=0.35, handletextpad=0.5)
        fig.canvas.draw()
        _w = _lg.get_window_extent().width / fig.dpi
        _lg.remove()
        if _w > COLUMNWIDTH_IN - 0.02:
            break
        _cs = _try
    _lg = fig.legend(handles=handles, loc="center", ncol=len(handles), fontsize=7, frameon=True,
                     handlelength=1.8, columnspacing=_cs, borderpad=0.35, handletextpad=0.5)
    fig.canvas.draw()
    print(f"  legend: one row of {len(handles)}, columnspacing {_cs} -> "
          f"{_lg.get_window_extent().width / fig.dpi:.2f} in of {COLUMNWIDTH_IN:.2f} in")
    fig.savefig(f"{OUT}_axes_legend.pdf")
    fig.savefig(f"{OUT}_axes_legend.png", dpi=300)
    plt.close(fig)


def main():
    plt.rcParams.update({
        "font.size": 9, "axes.linewidth": 0.8, "lines.linewidth": 1.1,
        "legend.fontsize": 8.5, "xtick.direction": "in", "ytick.direction": "in",
        "xtick.major.size": 3, "ytick.major.size": 3,
    })
    OUT.parent.mkdir(parents=True, exist_ok=True)
    render_legend()

    # FIRST PASS: find the margin every panel in every figure can live with, then render with it.
    # The log throughput panels need a wider left strip than the linear multiplier ones, and the
    # axes differ again; taking the max makes all 32 panels share one plot box.
    loaded = {}
    STANDALONE[0] = True
    for axis, x_label in AXES:
        if not (RESULTS / f"wl_{axis}_raw.csv").exists():
            continue
        loaded[axis] = load(axis)
        pts, _ = loaded[axis]
        for idx, y_label, logy, ctl in ((0, "Throughput (tps)", True, False),
                                        (1, "Interaction multiplier", False, True),
                                        (2, "Throughput (ops/s)", True, False)):
            ylim = shared_ylim(pts, idx, logy)

            def probe(ax, _sy, pts=pts, idx=idx, y_label=y_label, logy=logy, ctl=ctl, ylim=ylim):
                panel_row([ax], pts, x_label, idx, y_label, logy, ctl, only=0)
                if ylim:
                    ax.set_ylim(*ylim)
            l, b = fit_axis_margins(probe)
            MARGINS[0], MARGINS[1] = max(MARGINS[0], l), max(MARGINS[1], b)
    STANDALONE[0] = False
    # Derive the height that puts the PLOT BOX at PLOT_BOX_ASPECT, then re-fit once at that
    # height so the reported margins belong to the figure actually rendered.
    global AXIS_PANEL_H
    plot_w = AXIS_PANEL_W * (0.975 - MARGINS[0])
    AXIS_PANEL_H = (plot_w / PLOT_BOX_ASPECT) / (0.97 - MARGINS[1])
    pts0, _ = loaded[AXES[0][0]]
    ylim0 = shared_ylim(pts0, 0, True)

    def probe0(ax, _sy):
        panel_row([ax], pts0, AXES[0][1], 0, "Throughput (tps)", True, False, only=0)
        if ylim0:
            ax.set_ylim(*ylim0)
    MARGINS[0], MARGINS[1] = fit_axis_margins(probe0, left=MARGINS[0], bottom=MARGINS[1])
    plot_w = AXIS_PANEL_W * (0.975 - MARGINS[0])
    plot_h = AXIS_PANEL_H * (0.97 - MARGINS[1])
    print(f"panel {AXIS_PANEL_W:.2f} x {AXIS_PANEL_H:.2f} in, margins left={MARGINS[0]:.3f} "
          f"bottom={MARGINS[1]:.3f} -> PLOT BOX {plot_w:.2f} x {plot_h:.2f} in "
          f"= {plot_w/plot_h:.2f}:1")

    for axis, x_label in AXES:
        if not (RESULTS / f"wl_{axis}_raw.csv").exists():
            print(f"{axis:<9} SKIPPED - no wl_{axis}_raw.csv")
            continue
        pts, dropped = loaded[axis]
        # The transaction-length axis reports ACCESSES per second, not transactions per second.
        if axis == "txnlen":
            LATEX_OUT.append(emit_latex(axis, "operations", "Throughput in operations per second"))
        else:
            LATEX_OUT.append(emit_latex(axis, "throughput", "Throughput"))
        render_print_panels(axis, pts)
        note = f"n={sorted({len(v) for v in pts.values()})}"
        if dropped:
            note += f"  DROPPED {dict(dropped)}"
        print(f"{axis:<9} {len(pts):>3} points  {note}")
    for block in LATEX_OUT:
        print()
        print(block)


main()
