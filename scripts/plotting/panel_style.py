#!/usr/bin/env python3
"""Shared geometry and type helpers for panels authored at their PRINTED size.

WHY THIS MODULE EXISTS. The factorial panels are drawn at exactly the size they occupy in the
document - 1.6 x 1.0 in, 7 pt type - and placed with no LaTeX scaling, so their type size is what
it says it is. The theta-axis panels are being moved to the same treatment. Doing that by copying
the helpers would leave two copies of the rules that decide whether a tick label is clipped, and
the copies would drift the first time one figure's range changed; so the rules live here and both
scripts import them.

EVERY FUNCTION HERE MEASURES RATHER THAN ASSUMES, and each of them exists because an estimate
shipped a defect: a left margin estimated at 0.25 in clipped the tick label "1000" to ".000", a
right margin estimated at 0.15 in cut the end off "alpha=0.05", a y margin expressed as a
percentage of the data range left markers touching the frame at one figure height and not at
another, and "Throughput (tps)" was clipped by an axes box shorter than the label. Numbers that
depend on the font, the size and the axes box cannot be picked by hand and stay right.
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker


def text_width_in(strings, size):
    """Width in inches of the widest of `strings` at `size` points, by rendering them."""
    fig = plt.figure(figsize=(2, 2))
    r = fig.canvas.get_renderer()
    worst = 0.0
    for t in strings:
        art = fig.text(0.5, 0.5, t, fontsize=size)
        worst = max(worst, art.get_window_extent(r).width / fig.dpi)
        art.remove()
    plt.close(fig)
    return worst


def set_log_ticks(ax, lo, hi):
    """Two or three labelled ticks inside (lo, hi), preferring whole decades.

    A FITTED LOG RANGE DOES NOT COOPERATE WITH A LOCATOR. Across the ~1.5 decades these panels
    span, a decade locator gives one or two ticks and a subdivided one gives five; neither is the
    two or three a panel this size can hold. So the candidates are enumerated - decades first,
    then thirds of a decade if the decades alone are too few - and trimmed from the middle
    outwards, which keeps the extreme ticks that anchor the reader's sense of the range.
    """
    ticks = [10.0 ** k for k in range(-4, 8) if lo <= 10.0 ** k <= hi]
    if len(ticks) < 2:
        ticks = sorted(v for k in range(-4, 8) for v in (10.0 ** k, 3.0 * 10.0 ** k)
                       if lo <= v <= hi)
    while len(ticks) > 3:
        ticks.pop(len(ticks) // 2)
    ax.set_yticks(ticks)
    ax.set_yticklabels([f"{v:g}" for v in ticks])
    ax.yaxis.set_minor_locator(mticker.NullLocator())
    return len(ticks)


def marker_clearance(fig, ax):
    """Smallest gap in points between any drawn marker edge and the top or bottom spine."""
    fig.canvas.draw()
    y0, y1 = (ax.transData.transform((0, v))[1] for v in ax.get_ylim())
    worst = float("inf")
    for ln in ax.get_lines():
        if ln.get_marker() in ("", "None", None):
            continue
        half = ln.get_markersize() * fig.dpi / 72.0 / 2.0
        for _, py in ax.transData.transform(ln.get_xydata()):
            worst = min(worst, (py - half) - y0, y1 - (py + half))
    return worst * 72.0 / fig.dpi


def widen_for_markers(fig, axes, ylim, target_pt=2.5, step=1.05, max_iter=10):
    """Expand a shared y range until no marker in `axes` comes within target_pt of a spine.

    A y margin expressed as a percentage of the DATA range cannot know how close a marker lands
    to the frame, because a marker is a fixed number of POINTS tall while the range is in data
    units: the same 18% left 0.5 pt of clearance in one figure height and 0.9 pt in another, both
    of them touching. The range is widened a step at a time and the clearance re-measured.

    Every axes passed in widens together, because they share one axis; widening only the
    offending panel would silently break that sharing.
    """
    lo, hi = ylim
    for _ in range(max_iter):
        for ax in axes:
            ax.set_ylim(lo, hi)
        if min(marker_clearance(fig, ax) for ax in axes) >= target_pt:
            break
        lo, hi = lo / step, hi * step
    return lo, hi


def fit_label(fig, ax, candidates, size, axis="y"):
    """The longest of `candidates` that fits the CANVAS, slid off-centre if that is what it takes.

    THE CONSTRAINT IS THE FIGURE, NOT THE AXES. An earlier version required a rotated y label to be
    no taller than the axes box, and so shortened labels - or broke them across two lines - to
    solve a problem that moving them solves for free. The label sits OUTSIDE the axes; what caps it
    is that matplotlib centres it ON the axes, so an axes that is not itself centred on the canvas
    wastes the margin on its far side. On a 1.00 in panel whose axes spans 0.27-0.97 the label's
    centre is 0.62 and its ceiling is 2 x 0.38 = 0.76 in, while "Throughput ops/s" is 0.838 in and
    the canvas is a whole inch. Slid down, it fits with room to spare.

    THE SHIFT IS A FEEDBACK LOOP because set_label_coords' anchor semantics are not the label's
    centre, and label.get_position() reports a placeholder rather than the automatic coordinate
    matplotlib computed. Both were tried and both put the label somewhere else - once on top of the
    tick labels. Measuring the rendered box and correcting the residual needs no assumption about
    either: it converges in two passes and holds x where it already was while moving y.
    """
    setter = ax.set_ylabel if axis == "y" else ax.set_xlabel
    W, H = fig.get_size_inches()
    for text in candidates:
        setter(text, fontsize=size, labelpad=1.5)
        fig.canvas.draw()
        r = fig.canvas.get_renderer()
        art = ax.yaxis.label if axis == "y" else ax.xaxis.label
        box = art.get_window_extent(r)
        if (box.height if axis == "y" else box.width) / fig.dpi > (H if axis == "y" else W):
            continue                       # too long for the figure itself; try a shorter one
        # ONLY MOVE A LABEL THAT DOES NOT FIT WHERE MATPLOTLIB PUT IT. The first version of this
        # repositioned unconditionally, which centred every label on the CANVAS - and the axes is
        # not centred on the canvas horizontally, because the y label and tick labels take a strip
        # off the left. So every x label slid left of the plot it belonged to. Default placement
        # is correct whenever it fits; the slide exists only to rescue a label that would
        # otherwise hang off the edge.
        if (box.x0 >= -0.5 and box.y0 >= -0.5
                and box.x1 <= W * fig.dpi + 0.5 and box.y1 <= H * fig.dpi + 0.5):
            return text
        # SLIDING IS FOR THE Y AXIS ONLY. A rotated y label runs along the short dimension of a
        # panel that is not centred there, so moving it recovers room without changing what it
        # says, and nothing else sits where it lands. An x label is horizontal and centred under
        # its own plot: sliding it to the canvas centre moves it away from the axes it labels -
        # 0.2 in left, on the one figure whose x label is wider than its axes - and it reads as
        # misaligned, which it is. A too-wide x label wants a shorter word, not a new position.
        if axis == "x":
            continue
        ab = ax.get_window_extent()
        # Hold the coordinate matplotlib chose, move the other one to the canvas centre.
        tx = (box.x0 + box.x1) / 2.0
        ty = H * fig.dpi / 2.0
        fx, fy = (tx - ab.x0) / ab.width, (ty - ab.y0) / ab.height
        for _ in range(6):
            ax.yaxis.set_label_coords(fx, fy)
            fig.canvas.draw()
            box = art.get_window_extent(r)
            ex = tx - (box.x0 + box.x1) / 2.0
            ey = ty - (box.y0 + box.y1) / 2.0
            if abs(ex) < 0.5 and abs(ey) < 0.5:
                break
            fx += ex / ab.width
            fy += ey / ab.height
        if (box.x0 >= -0.5 and box.y0 >= -0.5
                and box.x1 <= W * fig.dpi + 0.5 and box.y1 <= H * fig.dpi + 0.5):
            return text
    return candidates[-1]


def canvas_slack(fig):
    """How far the drawn content falls short of the canvas, in inches, worst side.

    NOT A FITTER - A CHECK. Adjusting margins here would handle axes and tick labels but not a
    FIGURE-level label, which sits at a fixed figure coordinate: pulling the axes leftwards to
    close a gap walks the tick labels into it. The caller lays out; this reports what was left
    over, so "the bounding box is tight" is measured rather than intended.
    """
    fig.canvas.draw()
    W, H = fig.get_size_inches()
    tb = fig.get_tightbbox(fig.canvas.get_renderer())
    return max(tb.x0, tb.y0, W - tb.x1, H - tb.y1)


def save_exact(fig, stem, check_slack=True, hint=None):
    """Save at exactly the authored figsize - no bbox_inches, so LaTeX rescales nothing.

    A tight bbox CROPS, so the saved width becomes whatever the labels happened to need, and
    including that at a fixed width SCALES it back up: 7 pt type set on a 5.5-inch canvas destined
    for a 7-inch slot prints at 8.9 pt, which is exactly what authoring at the final size is meant
    to prevent. The canvas is the final size and the layout fills it, so there is nothing to crop.

    `stem` is a full path prefix; ".pdf" and ".png" are appended.
    """
    slack = canvas_slack(fig)
    if check_slack and slack > 0.03:
        print(f"    WARNING: {stem} leaves {slack:.3f} in of unused margin - not tight")
    W, H = fig.get_size_inches()
    fig.savefig(f"{stem}.pdf")
    # 120 dpi is roughly 100% zoom on a normal screen, so the PNG can be judged for type size
    # against a reference figure rather than inspected magnified.
    fig.savefig(f"{stem}.png", dpi=120)
    plt.close(fig)
    name = str(stem).rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
    print(f"    wrote {name}.pdf  {W:.2f} x {H:.2f} in, slack {slack:.3f} in"
          + (f"  -> {hint}" if hint else ""))
    return slack
