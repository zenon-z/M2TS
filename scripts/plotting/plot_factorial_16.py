#!/usr/bin/env python3
"""The 2^4 factorial: f, mu, alpha and theta, from the 16 cells x 5 seeds already measured.

WHY THETA BECOMES A FACTOR. The campaign ran the 2^3 (f, mu, alpha) design at theta=0.5 and again
at theta=0.8, and the existing analysis reports two separate tables. Those 16 cells ARE a 2^4
design, and analysing them as one answers a question the split tables cannot: how large is theta's
effect NEXT TO the others, and does theta change what the other factors do. Nothing new was run -
this is the same 80 rows, read with theta as a column instead of as a heading.

STYLE IS INHERITED from plot_lovelace_experiments.py, which inherits it from plot_workload_axes.py:
same colours, markers, font sizes, grid, save settings. Importing them rather than restating them
is deliberate - a second copy of the palette is a second thing to forget to change.

Usage:  plot_factorial_16.py <extracted-lovelace-out-dir>
"""
import collections
import io
import math
import statistics
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

sys.path.insert(0, str(Path(__file__).resolve().parent))
from plot_lovelace_experiments import (          # noqa: E402
    read_rows, style_axes, save, RC, OUT, C_LOW, C_HIGH, M_LOW, M_HIGH, F_LABEL, F_TICK,
    dataset)
# The panel geometry and type rules live in one place, shared with the theta-axis panels, which
# are authored the same way. See panel_style for why each of them measures instead of assuming.
from panel_style import (                           # noqa: E402
    text_width_in, set_log_ticks, marker_clearance, widen_for_markers, fit_label, canvas_slack,
    save_exact)

# Factor order is fixed here and everything else indexes into it: 0=f, 1=mu, 2=alpha, 3=theta.
SYM = ["$f$", r"$\mu$", r"$\alpha$", r"$\theta$"]
TEX = ["f", r"\mu", r"\alpha", r"\theta"]
PLAIN = ["f", "mu", "alpha", "theta"]


def load_cells():
    """(f, mu, alpha, theta) -> [throughput per seed], for all 16 cells.

    THE TWO THETA DIRECTORIES ARE THE TWO LEVELS. They were run as separate jobs - one job is one
    (cell, theta, seed) - so pooling them is a matter of reading theta off the directory rather
    than recomputing anything. The cell count and seed count are asserted below, because a design
    that is quietly missing a corner still produces a table, and the table looks fine.
    """
    cells = collections.defaultdict(list)
    for th in ("0.5", "0.8"):
        p = dataset(f"factorial_th{th}")
        if not p.exists():
            raise SystemExit(f"REFUSING: {p} is missing - the 2^4 design needs both theta levels")
        for r in read_rows(p):
            lab = r["label"]                          # f0.05_mu1.0_m5_a0.0500000
            f = float(lab.split("_")[0][1:])
            mu = float(lab.split("_")[1][2:])
            a = float(lab.split("_")[3][1:])
            cells[(f, mu, a, float(th))].append(float(r["throughput_tps"]))

    levels = [sorted({k[i] for k in cells}) for i in range(4)]
    for i, lv in enumerate(levels):
        if len(lv) != 2:
            raise SystemExit(f"REFUSING: factor {PLAIN[i]} has levels {lv}, expected exactly 2")
    if len(cells) != 16:
        raise SystemExit(f"REFUSING: {len(cells)} cells, expected 16 - the design is incomplete")
    counts = {len(v) for v in cells.values()}
    if counts != {5}:
        raise SystemExit(f"REFUSING: seed counts per cell are {sorted(counts)}, expected 5 each")
    print(f"    16 cells x 5 seeds = 80 runs; levels "
          + ", ".join(f"{PLAIN[i]} {levels[i]}" for i in range(4)))
    return cells, levels


def variation_shares(cells, levels):
    """Jain's allocation of variation for a 2^4 r design, on log and on raw throughput.

    LOG IS THE ANSWER AND RAW IS THE CONTROL. The response spans 2370 to about 10 tps, and on raw
    values whichever factor has the largest absolute swing absorbs most of the sum of squares
    mechanically. These effects are multiplicative - a cascade multiplies work - so log is the
    scale on which "share of variation" answers the question. The raw column is printed anyway,
    because where the two disagree that disagreement is a finding, and dropping it would be a
    choice made silently.

    Generalised over the factor count rather than written out for four: the 2^3 version this
    replaces hard-coded range(3) and a /8, and those constants are exactly what one forgets when
    a factor is added.
    """
    k = len(levels)
    n = 2 ** k
    r = min(len(v) for v in cells.values())
    masks = [tuple(bool(m & (1 << i)) for i in range(k)) for m in range(1, n)]
    out = {}
    for tlabel, tf in (("log", math.log), ("raw", lambda x: x)):
        ybar = {}
        for key, v in cells.items():
            sgn = tuple(1 if key[i] == levels[i][1] else -1 for i in range(k))
            ybar[sgn] = statistics.mean([tf(x) for x in v])
        ss = {}
        for mask in masks:
            s = 0.0
            for sgn, y in ybar.items():
                coef = 1.0
                for i in range(k):
                    if mask[i]:
                        coef *= sgn[i]
                s += coef * y
            q = s / n
            ss[mask] = n * r * q ** 2
        sse = 0.0
        for v in cells.values():
            vals = [tf(x) for x in v]
            m = statistics.mean(vals)
            sse += sum((x - m) ** 2 for x in vals)
        sst = sum(ss.values()) + sse
        out[tlabel] = ({mk: 100 * ss[mk] / sst for mk in masks}, 100 * sse / sst, r)
    return out, masks


def tex_name(mask):
    return r"\!\cdot\!".join(TEX[i] for i in range(len(mask)) if mask[i])


def plain_name(mask):
    return " x ".join(PLAIN[i] for i in range(len(mask)) if mask[i])


def report_shares(shares, masks):
    """Print the allocation, and write the LaTeX table.

    ORDERED BY THE LOG SHARE, and the raw share is carried alongside rather than sorted
    separately, so a reader comparing the columns is comparing the same row.
    """
    log_share, log_res, r = shares["log"]
    raw_share, raw_res, _ = shares["raw"]
    order = sorted(masks, key=lambda mk: -log_share[mk])
    print(f"    allocation of variation, 2^4, r={r} seeds/cell")
    for mk in order:
        if log_share[mk] < 0.05 and raw_share[mk] < 0.05:
            continue
        print(f"      {plain_name(mk):<26} log {log_share[mk]:5.1f}%   raw {raw_share[mk]:5.1f}%")
    print(f"      {'residual (seed)':<26} log {log_res:5.1f}%   raw {raw_res:5.1f}%")

    L = [r"% Generated by scripts/plotting/plot_factorial_16.py - do not edit by hand.",
         r"\begin{tabular}{lrr}",
         r"\toprule",
         r"Effect & log scale & raw scale \\",
         r"\midrule"]
    for mk in order:
        L.append(rf"${tex_name(mk)}$ & {log_share[mk]:.1f}\% & {raw_share[mk]:.1f}\% \\")
    L += [r"\midrule",
          rf"Residual (seed) & {log_res:.1f}\% & {raw_res:.1f}\% \\",
          r"\bottomrule",
          r"\end{tabular}"]
    p = OUT.parent / "wl_factorial16_shares.tex"
    p.parent.mkdir(parents=True, exist_ok=True)
    io.open(p, "w", encoding="utf-8").write("\n".join(L) + "\n")
    print(f"    wrote {p}")


def cell_group(cells, conds):
    """Every throughput value from the cells matching conds: {factor index: level}."""
    vals = []
    for key, v in cells.items():
        if all(key[i] == lv for i, lv in conds.items()):
            vals.extend(v)
    return vals


def gmean(vals):
    """Geometric mean - the mean OF THE LOGS, exponentiated.

    THIS IS NOT A DETAIL. The table allocates variation on log throughput, so the interaction it
    quantifies is an interaction between LOG effects; two lines are parallel there when the effect
    of one factor is a constant RATIO across the other. Marginalising with an arithmetic mean and
    then drawing it on a log axis would answer a different question from the one the table answers,
    and the two could disagree about whether a pair of lines is parallel - the figure appearing to
    contradict the table, with neither wrong. So the points are geometric means and the axis is
    log, which keeps the picture and the table on one scale.
    """
    return math.exp(statistics.mean([math.log(v) for v in vals]))


def seed_band(cells, conds):
    """The marginal mean's range under SEED variation alone, on the log scale.

    THE PREVIOUS WHISKER WAS THE WRONG QUANTITY AND IT SHOWED. Each point is a mean over the two
    marginalised factors as well as the seeds, so a min-max of everything pooled into it spanned
    the levels of those factors - on the panels that marginalise over f, the 59.5% factor, that is
    nearly the whole axis. A reader takes a whisker as "how uncertain is this point", and this one
    answered "how far apart are the things I averaged", which is a different question and one the
    panel is deliberately NOT asking.

    WHAT IS DRAWN INSTEAD: the same marginal mean, recomputed with every contributing cell sitting
    at its slowest seed, and again with every cell at its fastest. It is the honest seed-driven
    range of the mean - the mean's own spread, not its ingredients' - and being a min-max of
    measured runs it keeps the campaign's rule against confidence intervals, which on 5 seeds
    reach to values nothing ever ran at.
    """
    per_cell = []
    for key, v in cells.items():
        if all(key[i] == lv for i, lv in conds.items()):
            logs = [math.log(x) for x in v]
            per_cell.append((min(logs), statistics.mean(logs), max(logs)))
    n = len(per_cell)
    return (math.exp(sum(c[0] for c in per_cell) / n),
            math.exp(sum(c[1] for c in per_cell) / n),
            math.exp(sum(c[2] for c in per_cell) / n))


def draw_interaction(ax, cells, levels, xi, li, extra=None, labels="legend", xlabel=True,
                     ms=4.0, lw=1.0, label_fs=7.0, tick_fs=7.0):
    """One interaction panel: x = factor xi, two lines = factor li, marginal over the rest.

    THE PANEL EXISTS TO SHOW THE RELATIVE ORIENTATION OF TWO LINES - parallel means no interaction
    on the log scale, converging or crossing means one - so everything not serving that reading is
    stripped. The x axis carries no label because the title names both factors and the first of
    them is the x one; the levels sit near the panel edges because the span between them is the
    only one that matters; and the series are labelled at the end of each line, because a legend
    box inside a 1.4-inch panel is a third of the panel.
    """
    extra = extra or {}
    xs = [0.0, 1.0]
    ends = []
    for n, (lv, colour, marker) in enumerate(zip(levels[li], (C_LOW, C_HIGH), (M_LOW, M_HIGH))):
        # A small horizontal dodge so the two whisker pairs do not overprint. Display only; both
        # lines are measured at the same two levels of factor xi.
        dx = (n - 0.5) * 0.035
        pts, los, his = [], [], []
        for xv in levels[xi]:
            conds = dict(extra)
            conds[xi] = xv
            conds[li] = lv
            lo, m, hi = seed_band(cells, conds)
            pts.append(m)
            los.append(lo)
            his.append(hi)
        for x, lo, hi in zip(xs, los, his):
            # VISIBLE, not decorative. At lw 0.6 and alpha 0.55 these read as absent - a reader
            # asked where the error bars were - and "absent" invites the conclusion that the runs
            # have no repeated seeds. They do: 5 per cell. Full opacity and a cap at each end, so
            # a band narrower than the marker still announces itself.
            ax.plot([x + dx, x + dx], [lo, hi], ls="-", lw=min(0.8, lw), color=colour,
                    alpha=0.9, zorder=3, solid_capstyle="butt")
            for yv in (lo, hi):
                ax.plot([x + dx - 0.03, x + dx + 0.03], [yv, yv], ls="-", lw=min(0.8, lw),
                        color=colour, alpha=0.9, zorder=3)
        ax.plot([x + dx for x in xs], pts, ls="-", color=colour, lw=lw, marker=marker, ms=ms,
                mfc="white", mew=min(1.0, lw), zorder=4, label=f"{SYM[li]} = {lv:g}")
        ends.append((pts[-1], 1.0 + dx, colour, f"{SYM[li]}={lv:g}", pts[0], 0.0 + dx))
    ax.set_yscale("log")
    # SMALL X MARGIN: the two levels sit near the edges, so the panel is almost all data. It can
    # be this tight because the direct labels are right-ALIGNED on the endpoint and grow leftwards
    # into the panel, so naming a line costs no horizontal room.
    ax.set_xlim(-0.12, 1.12)
    ax.set_xticks(xs)
    ax.set_xticklabels([f"{v:g}" for v in levels[xi]])
    # THE X AXIS IS LABELLED. It was dropped on the reasoning that the title names both factors
    # and the first of them is the x one - true, but that is a convention a reader has to be told,
    # and it lives in the caption rather than on the page. The label is one character; the space
    # it costs comes out of panel height, which the canvas absorbs, not out of clarity.
    style_axes(ax, SYM[xi] if xlabel else "", "")
    ax.tick_params(labelsize=tick_fs, pad=1.5)
    if xlabel:
        ax.xaxis.label.set_size(tick_fs)
        ax.xaxis.labelpad = 1.0

    if labels == "direct":
        # OUTSIDE THE DATA, AGAINST THE RIGHT SPINE. Three placements inside the panel were tried
        # and all of them failed at this size: right-aligned on the endpoint lays the text along
        # its own line; the higher line's left end puts it over the title; the corners the lines
        # leave empty are not empty once the panel is 0.7 in wide. There is no interior position
        # that works for every pair of lines, so the labels leave the data area entirely. The x
        # coordinate is in AXES fractions and clipping is off, so the text starts just past the
        # spine no matter what the data range is.
        ends.sort(key=lambda e: e[0])
        tr = ax.get_yaxis_transform()
        for k, (yv, xv, colour, text, y0, x0) in enumerate(ends):
            up = (k == len(ends) - 1)
            ax.annotate(text, (1.0, yv), xycoords=tr, textcoords="offset points",
                        xytext=(2.0, 2.5 if up else -2.5), ha="left",
                        va="bottom" if up else "top", fontsize=label_fs, color=colour,
                        zorder=5, annotation_clip=False)
    else:
        # IN THE EMPTY CORNER, chosen by matplotlib rather than fixed: which corner is empty is
        # decided by the lines, and the lines are what these panels vary. Frameless, so it sits in
        # the whitespace instead of cutting a hole in it.
        ax.legend(fontsize=label_fs, frameon=False, loc="best", handlelength=1.1,
                  handletextpad=0.3, borderpad=0.1, labelspacing=0.1, borderaxespad=0.2)


def fitted_ylim(cells, levels, specs, margin=1.18):
    """The range of everything the given panels draw, with a ~10% margin and nothing else.

    NO EMPTY DECADES. A log axis padded out to a round decade wastes a whole multiplicative band,
    and these panels are read for the ANGLE between two lines - an angle flattened by every bit of
    unused height above and below it.

    THE MARGIN IS 18%, NOT 10%, and the extra is not decoration. The requirement is that no marker
    TOUCH the frame, and a marker is 4 pt tall - about 6% of a 0.95-inch panel at each end, on top
    of the whisker it is centred on. 10% of the DATA range is not 10% of the drawn extent once the
    glyph is added, and at 10% the lowest markers clipped the axis. Checked by measurement, not by
    eye: main() asserts the gap in points.
    """
    edges = []
    for xi, li, extra in specs:
        for lv in levels[li]:
            for xv in levels[xi]:
                conds = dict(extra)
                conds[xi] = xv
                conds[li] = lv
                lo, _, hi = seed_band(cells, conds)
                edges += [lo, hi]
    return min(edges) / margin, max(edges) * margin


def save_tight(fig, stem):
    """Like save(), but with no outer padding at all."""
    OUT.parent.mkdir(parents=True, exist_ok=True)
    for ext in ("pdf", "png"):
        fig.savefig(f"{OUT}_{stem}.{ext}", bbox_inches="tight", pad_inches=0.0,
                    dpi=300 if ext == "png" else None)
    plt.close(fig)
    print(f"    wrote {OUT}_{stem}.pdf and .png")


def fig_panels(cells, levels):
    """Every panel as its own file at 1.6 x 1.0 in - the six pairwise AND the two three-way.

    THE THREE-WAY PANELS ARE EMITTED HERE, not by fig_threeway, because they are read alongside
    the pairwise ones and therefore have to be drawn by the same code. Kept in a separate
    function they would share a style by resemblance, and resemblance is what drifts: the first
    version of this figure had titles inside the graphic while the pairwise panels took theirs
    from LaTeX subcaptions, so the two sat side by side in the paper with different anatomy.

    ONE AXES RECTANGLE FOR ALL EIGHT FILES. Within a figure it is what makes a grid line up;
    across the two figures it is what lets a reader compare a three-way panel against a pairwise
    one without silently comparing different plot-box widths. The rectangle is derived from the
    widest tick label any of the eight produces, so no panel is sized for a label it does not
    have and none is too narrow for the one it does.

    Y IS SHARED WITHIN A GROUP, NOT ACROSS ALL EIGHT. The three groups - the f row, the mu row,
    and the three-way pair - each fit their own range. Forcing one range on all eight would push
    every panel to the span of the widest, which is the three-way pair at 18 to 2400 tps, and
    flatten the pairwise panels for no gain: nobody reads panel (a) against the mu=4 three-way
    panel on a common scale, they read each group internally.
    """
    W, H = 1.60, 1.00
    LABEL_FS, TICK_FS = 6.0, 7.0
    pair = [(xi, li, {}, STEM[(xi, li)]) for xi, li in PAIRS]
    # Groups are (specs, index-within-group that carries the y label). The pairwise files go into
    # a 3x2 grid whose left column is panels 1, 3 and 5, so two of the three rows of THIS list
    # carry a label; the three-way pair is one row of two, so only its first does.
    # THE LABEL SET IS A PROPERTY OF THE GRID, NOT OF THE GROUP. The six pairwise files are placed
    # 3 rows x 2 columns, so the left column is flat panels 1, 3 and 5 - even flat indices. The
    # y-range groups are the design's f row and mu row, which are rows of THREE, so deriving the
    # label set from a group's own indices labels p1, p3, p4 and p6: right for a 2x3 arrangement,
    # wrong for the 3x2 the paper uses, and wrong in a way that looks deliberate on the page.
    GROUPS = [(pair[0:3], 0), (pair[3:6], 3),
              ([(0, 2, {1: mv}, f"factorial16_tw{k + 1}_mu{mv:g}")
                for k, mv in enumerate(levels[1])], None)]

    # PASS ONE: per-group y range, and every tick label any of them produces. The labels are not
    # knowable in advance - they depend on the widened range, which depends on the geometry - so
    # one throwaway panel per group is drawn to find out.
    ylims, tick_strings = [], []
    for band, _offset in GROUPS:
        ylim = fitted_ylim(cells, levels, [(xi, li, ex) for xi, li, ex, _ in band])
        fig = plt.figure(figsize=(W, H))
        ax = fig.add_axes((0.28, 0.2, 0.68, 0.76))
        xi, li, extra, _ = band[0]
        draw_interaction(ax, cells, levels, xi, li, extra, labels="legend", xlabel=False,
                         ms=3.0, lw=0.8, label_fs=LABEL_FS, tick_fs=TICK_FS)
        ax.set_ylim(*ylim)
        ylim = widen_for_markers(fig, [ax], ylim)
        ax.set_ylim(*ylim)
        set_log_ticks(ax, *ylim)
        tick_strings += [t.get_text() for t in ax.get_yticklabels()]
        ylims.append(ylim)
        plt.close(fig)

    # 0.14 in for the rotated y label, 0.05 for the gaps around the tick labels.
    L = text_width_in(tick_strings, TICK_FS) + 0.05 + 0.14
    R, B, T = 0.04, 0.17, 0.03
    RECT = (L / W, B / H, (W - L - R) / W, (H - B - T) / H)
    print(f"    panels {W} x {H} in; axes {(W - L - R):.2f} x {(H - B - T):.2f} in; "
          f"left margin {L:.2f} in, shared by all {sum(len(g) for g, _ in GROUPS)} files")

    # PASS TWO: the real panels, on the geometry pass one measured.
    for (band, offset), ylim in zip(GROUPS, ylims):
        for n, (xi, li, extra, stem) in enumerate(band):
            # Pairwise: even flat index = left column of the 3x2 grid. Three-way: a single row of
            # two, so only its first panel.
            labelled = (n == 0) if offset is None else ((offset + n) % 2 == 0)
            fig = plt.figure(figsize=(W, H))
            ax = fig.add_axes(RECT)
            draw_interaction(ax, cells, levels, xi, li, extra, labels="legend", xlabel=False,
                             ms=3.0, lw=0.8, label_fs=LABEL_FS, tick_fs=TICK_FS)
            ax.set_ylim(*ylim)
            set_log_ticks(ax, *ylim)
            if labelled:
                used = fit_label(fig, ax, ("Throughput (tps)", "Throughput", "tps"), TICK_FS)
                if used != "Throughput (tps)":
                    print(f"    note: {stem} y label -> {used!r}")
            if [t for t in ax.get_yticklabels() if t.get_window_extent().x0 < -0.5]:
                print(f"    WARNING: {stem} clips a y tick label")
            save_exact(fig, f"{OUT}_{stem}", check_slack=False,
                       hint="include at its natural size (or width=0.49" + chr(92)
                            + "columnwidth)")
    # Subcaptions to use, so the panels can stay untitled:
    #   (a) f x mu   (b) f x alpha   (c) f x theta   (d) mu x alpha   (e) mu x theta
    #   (f) alpha x theta   then the three-way pair: (g) mu = 1   (h) mu = 4
    # The three-way pair is the f x alpha interaction at each level of mu, so it is panel (b)
    # split by mu - worth saying in the caption, since the two figures then read as one argument.


PAIRS = [(0, 1), (0, 2), (0, 3), (1, 2), (1, 3), (2, 3)]
# Built once, with a raw literal, so no caller can reintroduce the tab-for-times bug.
TITLE = {(xi, li): rf"{SYM[xi]} $\times$ {SYM[li]}" for xi, li in PAIRS}
# Filename stem per panel, for the standalone files.
STEM = {(xi, li): f"factorial16_p{n + 1}_{PLAIN[xi]}_{PLAIN[li]}"
        for n, (xi, li) in enumerate(PAIRS)}


def main():
    plt.rcParams.update(RC)

    print("1. loading the 2^4 design")
    cells, levels = load_cells()
    print("2. allocation of variation")
    shares, masks = variation_shares(cells, levels)
    report_shares(shares, masks)
    print("3. the six pairwise panels and the two three-way panels, one file each")
    fig_panels(cells, levels)


if __name__ == "__main__":
    main()
