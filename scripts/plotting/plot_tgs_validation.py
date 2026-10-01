"""TGS static-locking validation - matplotlib figures (paper-ready, PDF+PNG).

Mirrors the artifact at tgs_panelb.html exactly - same data files, same binning, same framing
(no pre-registered pass/fail criterion on Panel A; the parity plot is the argument, not a summary
statistic - see the session discussion this reflects). Produces:

  panel_a_parity.{pdf,png}      - measured vs exact hypergeometric P(blocked|H), 3 cells, y=x,
                                   BOTH arms overlaid (simulator filled, PostgreSQL open) so a
                                   reader can see which system deviates where rather than being
                                   handed one number per arm. Falls back to simulator-only if the
                                   Postgres Stage B pass has not been collected.
  panel_a_residuals.{pdf,png}   - diagnostic: residual (measured-exact) vs p_exact, checks for
                                   systematic bias
  panel_b_k25.{pdf,png}         - n vs load, D=5000 k=25, simulator vs Postgres
  panel_b_k10.{pdf,png}         - n vs load, D=5000 k=10, simulator vs Postgres
  panel_b_saturation.{pdf,png}  - n vs load, D=250 k=5, simulator only (explicit saturation)
  collapse_contrast.{pdf,png}   - advisory vs FOR UPDATE: throughput and latency vs W

Run from the repo root: python scripts/plotting/plot_tgs_validation.py
"""
from __future__ import annotations

import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import matplotlib.lines as mlines

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts/analysis"))
# Single source of truth for the prediction and the interval - see analysis_helpers'
# hypergeometric_block_probability() docstring. The whole P1 claim is that two independently
# instrumented arms both land on this formula, so a second local copy of it would make a divergence
# indistinguishable from a real disagreement.
from analysis_helpers import hypergeometric_block_probability, wilson  # noqa: E402

SIM_SUMMARY = REPO_ROOT / "artifacts/experiments/results/tgs_panelb_sim.csv"
# Binned form, not the raw attempt rows - see bin_sim_first_attempts.py. Lossless for every
# statistic drawn here, and 47 kB against ~1 GB.
SIM_BINS = REPO_ROOT / "artifacts/experiments/results/tgs_panelb_sim_bins.csv"
PG_RAW = REPO_ROOT / "scripts/postgres_anchor/artifacts/experiments/results/postgres_anchor_raw.csv"
PG_PARITY = (REPO_ROOT
             / "scripts/postgres_anchor/artifacts/experiments/results"
             / "postgres_p1_attempts_stageb.csv")
PG_G0 = (REPO_ROOT
         / "scripts/postgres_anchor/artifacts/experiments/results"
         / "postgres_p1_attempts_stageg0.csv")
# Fixed before the band was inspected - see p1_parity_gates.py for the same constants.
G0_RELIABILITY_THRESHOLD = 0.90
G0_POWER_MAX_CI_HALFWIDTH = 0.15
OUT_DIR = REPO_ROOT / "artifacts/experiments/results/figures"

# M1's rule ("bins with <30 attempts are not plotted"). The previous default here was 15, which
# contradicted it; standardised on 30 so the figure and p1_parity_gates.py score the same bins.
MIN_BIN_N = 30

# Cell palette - deliberately disjoint from the sim/postgres colors used in panel B, so panel A
# (simulator-only) can never be misread as containing Postgres data. Distinct marker shapes too,
# for colorblind readers and black-and-white print.
CELLS = [
    {"d": 5000, "k": 25, "color": "#6a5fc4", "marker": "o", "label": "D=5000, k=25 (D/k²=8)"},
    {"d": 5000, "k": 10, "color": "#b3457a", "marker": "^", "label": "D=5000, k=10 (D/k²=50)"},
    {"d": 250, "k": 5, "color": "#7a8a2e", "marker": "s", "label": "D=250, k=5 (D/k²=10)"},
]
SIM_COLOR = "#1f8fa3"
PG_COLOR = "#b9791f"
FU_COLOR = "#b8483f"

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.size": 10,
    "axes.edgecolor": "#333333",
    "axes.linewidth": 0.8,
    "axes.grid": True,
    "grid.color": "#dddddd",
    "grid.linewidth": 0.6,
    "figure.facecolor": "white",
    "savefig.facecolor": "white",
    "legend.frameon": False,
})


def _bin_to_points(bins, min_n: int) -> dict[tuple[int, int], list[dict]]:
    by_cell: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for (D, k, H), (blocked, n) in bins.items():
        if n < min_n:
            continue
        p, lo, hi = wilson(blocked, n)
        by_cell[(D, k)].append({
            "H": H, "n": n, "p_measured": p,
            "p_exact": hypergeometric_block_probability(D, k, H), "ci_lo": lo, "ci_hi": hi,
        })
    for pts in by_cell.values():
        pts.sort(key=lambda r: r["H"])
    return by_cell


def load_sim_parity_points(min_n: int = MIN_BIN_N) -> dict[tuple[int, int], list[dict]]:
    """(D,k) -> list of {H, n, p_measured, p_exact, ci_lo, ci_hi} - one row per qualifying bin.

    Simulator side: FIRST admission attempts only, enforced upstream by
    SimulationMetrics.recordAdmissionAttempt()'s `attemptIndex == 0` gate. Reads the binned form
    (blocked/total per H), which carries the same information as the raw rows for this plot."""
    bins: dict[tuple[int, int, int], list[int]] = defaultdict(lambda: [0, 0])
    with open(SIM_BINS) as f:
        for row in csv.DictReader(f):
            key = (int(row["D"]), int(row["k"]), int(row["held_lock_units"]))
            bins[key][0] += int(row["blocked"])
            bins[key][1] += int(row["total"])
    return _bin_to_points(bins, min_n)


def load_pg_reliability():
    """(D,k) -> {W: ratio}. Delegates to the analysis module so the figure and the gates cannot
    drift apart on how reliability is computed."""
    sys.path.insert(0, str(REPO_ROOT / "scripts/analysis"))
    from p1_parity_gates import g0_reliability_by_w  # noqa: E402
    return {cell: {w: r for w, (r, hw) in d.items() if hw <= G0_POWER_MAX_CI_HALFWIDTH}
            for cell, d in g0_reliability_by_w(PG_G0).items()}


def load_pg_parity_points(min_n: int = MIN_BIN_N, w_filter=None) -> dict[tuple[int, int], list[dict]]:
    """Same shape, Postgres side, from the Stage B pass. Every row there is a first attempt by
    construction (one attempt per fresh uniform k-subset), which is what makes it comparable to the
    simulator's `attemptIndex == 0` rows - see collect_p1_data.py. Returns {} if the file is absent,
    so the figure still builds simulator-only."""
    if not PG_PARITY.exists():
        return {}
    bins: dict[tuple[int, int, int], list[int]] = defaultdict(lambda: [0, 0])
    with open(PG_PARITY) as f:
        for row in csv.DictReader(f):
            d, k = int(row["D"]), int(row["k"])
            if w_filter is not None and int(row["W"]) not in w_filter.get((d, k), set()):
                continue
            key = (d, k, int(row["h_before"]))
            bins[key][1] += 1
            if row["success"].lower() != "true":
                bins[key][0] += 1
    return _bin_to_points(bins, min_n)


def load_panel_b_overlay() -> dict[str, list[dict]]:
    sim: dict[tuple[int, int, int], float] = {}
    with open(SIM_SUMMARY) as f:
        for row in csv.DictReader(f):
            sim[(int(row["D"]), int(row["k"]), int(row["mpl"]))] = float(row["n_mean"])

    pg_agg: dict[tuple[int, int, int], list[float]] = defaultdict(list)
    # Second, independent estimate of the same quantity. n = N * (fraction of a transaction's cycle
    # spent holding locks), and that fraction is hold_ms / mean_latency_ms. The two differ in
    # exactly one respect: the pg_locks poller samples TRANSIENT PARTIAL HOLDS (try_acquire_all_retry
    # grabs a prefix before finding a conflict, then releases), the timing route does not. The
    # simulator has no such state at all - SS2PLLockManager.tryAcquireAllLocks() tests canGrant for
    # every lock before mutating anything, so a failed acquisition leaves the lock table untouched.
    # The asymmetry is therefore real, and it is signed: transients inflate the poller estimate,
    # making PostgreSQL look CLOSER to the ideal line the panel is read against.
    pg_timing_agg: dict[tuple[int, int, int], list[float]] = defaultdict(list)
    with open(PG_RAW) as f:
        for row in csv.DictReader(f):
            if row["mode"] != "advisory":
                continue
            key = (int(row["D"]), int(row["k"]), int(row["W"]))
            pg_agg[key].append(float(row["n_mean"]))
            lat = float(row["mean_latency_ms"])
            if lat > 0:
                pg_timing_agg[key].append(key[2] * float(row["hold_ms"]) / lat)
    pg = {key: sum(vals) / len(vals) for key, vals in pg_agg.items()}
    pg_timing = {key: sum(vals) / len(vals) for key, vals in pg_timing_agg.items() if vals}

    out: dict[str, list[dict]] = {}
    missing: list[str] = []
    for D, k, label in [(5000, 25, "k25"), (5000, 10, "k10"), (5000, 50, "k50")]:
        loads = sorted({mpl for (d, kk, mpl) in sim if d == D and kk == k}
                        & {w for (d, kk, w) in pg if d == D and kk == k})
        out[label] = [{"load": l, "n_sim": sim[(D, k, l)], "n_pg": pg[(D, k, l)],
                       "n_pg_timing": pg_timing.get((D, k, l))} for l in loads]
        if not out[label]:
            missing.append(f"D={D} k={k}")
    if missing:
        # Fail loudly. An absent cell would otherwise render as a blank sub-panel that looks like a
        # real (empty) result - the k=50 cell went missing exactly this way when a cellFilter run
        # excluded it from tgs_panelb_sim.csv.
        raise SystemExit(
            "Panel B is missing simulator data for: " + ", ".join(missing)
            + "\n  tgs_panelb_sim.csv has no rows for that cell (a -Dts.tgsPanelB.cellFilter run"
              " may have excluded it)."
              "\n  Re-run TgsPanelBSimCheck for the missing cell, or edit"
              " load_panel_b_overlay's cell list deliberately.")

    out["d250k5"] = sorted(
        [{"load": mpl, "n_sim": n} for (D, k, mpl), n in sim.items() if D == 250 and k == 5],
        key=lambda r: r["load"],
    )
    return out


def load_collapse_contrast() -> list[dict]:
    fu_tp: dict[int, list[float]] = defaultdict(list)
    fu_lat: dict[int, list[float]] = defaultdict(list)
    adv_tp: dict[int, list[float]] = defaultdict(list)
    adv_lat: dict[int, list[float]] = defaultdict(list)
    with open(PG_RAW) as f:
        for row in csv.DictReader(f):
            if int(row["D"]) != 5000 or int(row["k"]) != 25:
                continue
            w = int(row["W"])
            if row["mode"] == "for_update":
                fu_tp[w].append(float(row["throughput_tps"]))
                fu_lat[w].append(float(row["mean_latency_ms"]))
            elif row["mode"] == "advisory":
                adv_tp[w].append(float(row["throughput_tps"]))
                adv_lat[w].append(float(row["mean_latency_ms"]))

    rows = []
    for w in sorted(fu_tp):
        rows.append({
            "w": w,
            "tp_fu": sum(fu_tp[w]) / len(fu_tp[w]),
            "lat_fu": sum(fu_lat[w]) / len(fu_lat[w]),
            "tp_adv": sum(adv_tp[w]) / len(adv_tp[w]) if w in adv_tp else None,
            "lat_adv": sum(adv_lat[w]) / len(adv_lat[w]) if w in adv_lat else None,
        })
    return rows


def savefig(fig, name: str) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT_DIR / f"{name}.pdf", bbox_inches="tight")
    fig.savefig(OUT_DIR / f"{name}.png", bbox_inches="tight", dpi=200)
    plt.close(fig)
    print(f"  wrote {name}.pdf / {name}.png")


# Arms are distinguished by marker FILL, cells by colour+shape. Fill survives greyscale printing
# and colourblind viewing, so a reader can always see which system deviates where within a cell -
# which is the point of overlaying them rather than reporting one number per arm.
ARM_STYLE = {
    "sim": {"label": "simulator (filled)", "filled": True},
    "pg": {"label": "PostgreSQL (open)", "filled": False},
}


def _asymmetric_yerr(pts) -> list[list[float]]:
    """Wilson intervals are NOT centred on p_hat, so the two arms differ - and at p_hat = 1.0 the
    upper arm is zero up to floating-point rounding, which can come out as -1e-16 and makes
    matplotlib raise 'yerr must not contain negative values'. Real high-H bins are saturated at
    p_hat = 1.0, so this is on the main path, not an edge case. Clamp at zero."""
    return [
        [max(0.0, p["p_measured"] - p["ci_lo"]) for p in pts],
        [max(0.0, p["ci_hi"] - p["p_measured"]) for p in pts],
    ]


def _arm_legend_proxies(ax, arms) -> list:
    proxies = []
    for arm in arms:
        style = ARM_STYLE[arm]
        proxies.append(ax.plot([], [], linestyle="none", marker="o", markersize=5,
                               color="#444444",
                               markerfacecolor="#444444" if style["filled"] else "white",
                               markeredgecolor="#444444",
                               label=style["label"])[0])
    return proxies


def _loglog_axis(ax, ticks) -> None:
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.xaxis.set_major_formatter(mticker.ScalarFormatter())
    ax.xaxis.set_minor_formatter(mticker.NullFormatter())
    ax.yaxis.set_major_formatter(mticker.ScalarFormatter())
    ax.yaxis.set_minor_formatter(mticker.NullFormatter())
    ax.set_xticks(ticks)
    ax.set_yticks(ticks)


# Whether to draw the second PostgreSQL measurement (hold/latency) in panel_b_combined.
# False matches the figure in the paper; the timing data is still loaded and still checked
# elsewhere, it is simply not a fourth line here.
SHOW_PG_TIMING = False

# Shared with the other print-size figures; see panel_style for why each helper measures.
import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from panel_style import fit_label          # noqa: E402


def _savefig_exact(fig, name: str) -> None:
    """Save at exactly the authored figsize - no bbox_inches, so LaTeX rescales nothing.

    The shared savefig() crops with bbox_inches="tight", which is right for figures placed at
    whatever width they come out at. It is wrong for one authored AT its final size: the crop
    makes the saved width unpredictable, and width=1.0\\linewidth then scales it back to the
    column, taking every font with it. That is the defect this function exists to avoid - the
    figure was 12 in wide for a 3.4 in slot, so its 14 pt labels printed at about 4 pt.
    """
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT_DIR / f"{name}.pdf")
    fig.savefig(OUT_DIR / f"{name}.png", dpi=300)
    w, h = fig.get_size_inches()
    plt.close(fig)
    print(f"  wrote {name}.pdf / {name}.png  ({w:.2f} x {h:.2f} in, place at width=1.0\\linewidth)")


# ---------------------------------------------------------------- panel B as subfigures
#
# GEOMETRY IS FIXED HERE, NOT LEFT TO A LAYOUT ENGINE, because three separate files have to line
# up when LaTeX puts them side by side. The axes box is identical in all three; only the first
# carries the y label and y tick labels, and it is given a WIDER FILE to hold them rather than a
# narrower plot. That is the difference from the factorial panels, which reserve the label strip
# in every panel: there the files share one width, so the unlabelled ones carry empty space. Here
# the widths differ and the LaTeX includes them at different fractions, so nothing is wasted.
#
#   panel (a):  0.65 label strip + 0.83 axes + 0.04 pad = 1.52 in  -> 0.447\linewidth
#   panels (b), (c):  0.04 + 0.83 + 0.04                = 0.91 in  -> 0.268\linewidth
#   three files + two 0.03 gaps                          = 3.40 in  = \linewidth
#
# THE LABEL STRIP COSTS 0.65 OF THE 3.40. Dropping the in-figure y label and naming the quantity
# in the LaTeX caption instead would take the strip down to the tick labels alone (~0.27 in) and
# widen every panel from 0.83 to about 0.96 - 15% more plot for the same column. That is a
# content decision, not a layout one, so it is left as it is and recorded here.
#
# Change any of these and the three stop aligning, which is why they are named rather than spelt
# into the add_axes calls.
_SF_AX_W, _SF_AX_H = 0.83, 0.94            # the axes box, identical in all three
# 0.65 IS MEASURED, and the two guesses before it were both wrong: 0.42 clipped "Achieved" off
# the left edge entirely, and 0.50 still clipped it. The strip has to hold a TWO-LINE 9 pt label
# (~0.30 in) outside the "640" tick labels (~0.19 in) plus tick marks and pads. Sweeping the
# value and reading fig.get_tightbbox().x0 gives 0.63 as the threshold; 0.65 leaves 0.02 spare.
_SF_LEFT_LABELLED, _SF_LEFT_BARE = 0.65, 0.04
_SF_RIGHT, _SF_TOP = 0.04, 0.03
_SF_BOTTOM = 0.30                          # x tick labels + "Load N"


def _panel_b_one(overlay, key, stem, labelled: bool, ylim) -> None:
    ticks = [1, 4, 16, 64, 256, 640]
    labels = {1, 16, 256}
    left = _SF_LEFT_LABELLED if labelled else _SF_LEFT_BARE
    W = left + _SF_AX_W + _SF_RIGHT
    H = _SF_BOTTOM + _SF_AX_H + _SF_TOP
    with plt.rc_context({'font.size': 8, 'axes.labelsize': 9, 'xtick.labelsize': 8,
                         'ytick.labelsize': 8, 'axes.linewidth': 0.7}):
        fig = plt.figure(figsize=(W, H))
        ax = fig.add_axes([left / W, _SF_BOTTOM / H, _SF_AX_W / W, _SF_AX_H / H])
        rows = overlay[key]
        loads = [r["load"] for r in rows]
        ax.plot(ticks, ticks, linestyle="--", color="#888888", linewidth=1.0, zorder=1)
        ax.plot(loads, [r["n_sim"] for r in rows], marker="o", markersize=3.0, color=SIM_COLOR,
                zorder=3, linewidth=1.0, markeredgewidth=0.0)
        ax.plot(loads, [r["n_pg"] for r in rows], marker="^", markersize=3.0, color=PG_COLOR,
                zorder=3, linewidth=1.0, markeredgewidth=0.0)
        if SHOW_PG_TIMING:
            _t = [(r["load"], r["n_pg_timing"]) for r in rows if r["n_pg_timing"] is not None]
            if _t:
                ax.plot([x for x, _ in _t], [y for _, y in _t], marker="v", markersize=2.6,
                        color=PG_COLOR, linestyle=":", linewidth=0.9, alpha=0.85, zorder=3,
                        markeredgewidth=0.0)
        _loglog_axis(ax, ticks)
        # EXPLICIT, because these are three SEPARATE figures with no shared axis between them.
        # sharey did that in the combined version; here each would autoscale to its own data and
        # the three would silently be drawn on different scales - the one error that makes a row
        # of subfigures actively misleading rather than merely untidy.
        ax.set_ylim(*ylim)
        ax.set_xticklabels([str(t) if t in labels else "" for t in ticks])
        ax.tick_params(labelsize=8, pad=1.5)
        ax.set_xlabel("Load $N$", labelpad=1.5)
        if labelled:
            ax.set_ylabel("Achieved" + chr(10) + "concurrency $n$", labelpad=1.5,
                          linespacing=1.2)
        else:
            # TICK LABELS OFF, not absent ticks: the gridlines stay at the same values, so the
            # three panels read against one scale even though only (a) prints the numbers.
            ax.tick_params(labelleft=False)
        for sp in ax.spines.values():
            sp.set_linewidth(0.7)
        _savefig_exact(fig, stem)


def plot_panel_b_subfigures(overlay: dict) -> None:
    """Three subfigure files plus a standalone legend, for LaTeX \\subcaption."""
    vals = [v for key in ("k10", "k25", "k50") for r in overlay[key]
            for v in (r["n_sim"], r["n_pg"]) if v is not None and v > 0]
    ylim = (min(vals + [1]) / 1.3, max(vals + [640]) * 1.3)
    print(f"  subfigure y range shared across the three panels: "
          f"{ylim[0]:.2f} to {ylim[1]:.0f}")
    for key, stem, labelled in (("k10", "panel_b_k10", True),
                                ("k25", "panel_b_k25", False),
                                ("k50", "panel_b_k50", False)):
        _panel_b_one(overlay, key, stem, labelled, ylim)

    # THE LEGEND IS ITS OWN FILE at the full column width, so it sits above the row of
    # subfigures instead of inside one of them - the same arrangement wl_axes_legend.pdf uses.
    with plt.rc_context({'font.size': 8, 'legend.fontsize': 8}):
        fig = plt.figure(figsize=(3.4, 0.22))
        handles = [
            mlines.Line2D([], [], linestyle="--", color="#888888", linewidth=1.0,
                          label="Ideal ($n{=}N$)"),
            mlines.Line2D([], [], marker="o", markersize=3.0, color=SIM_COLOR, linewidth=1.0,
                          markeredgewidth=0.0, label="Simulator"),
            mlines.Line2D([], [], marker="^", markersize=3.0, color=PG_COLOR, linewidth=1.0,
                          markeredgewidth=0.0, label="PostgreSQL"),
        ]
        fig.legend(handles=handles, loc="center", ncol=3, frameon=False, handlelength=1.8,
                   handletextpad=0.5, columnspacing=1.6)
        _savefig_exact(fig, "panel_b_legend")


def main() -> None:
    print("Loading data...")
    overlay = load_panel_b_overlay()
    print("Plotting...")
    plot_panel_b_subfigures(overlay)
    print(f"Figures written to {OUT_DIR}")


if __name__ == "__main__":
    main()
