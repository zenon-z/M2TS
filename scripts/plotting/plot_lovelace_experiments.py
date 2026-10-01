#!/usr/bin/env python3
"""Figures for the three lovelace experiments: cores to 96, f*mu, and the 2^3 factorial.

STYLE IS INHERITED, NOT RE-CHOSEN. Colours, markers, font sizes, band style, legend placement,
grid and save settings are copied from plot_workload_axes.py so these figures sit beside the
existing axis figures without a visible seam - same C_LOW/C_MID/C_HIGH, same o/s/^/v markers, same
min-max band at alpha=0.15, same 8.0/9.0 pt tick/label sizes, same one-figure-level-legend rule.
Any deviation below is commented with its reason.

MIN-MAX BANDS, not confidence intervals, for the reason plot_workload_axes.py records at length:
a t-interval on a 5-seed spread of 81..346 tps reaches down to 8 tps, a value nothing ever ran at,
and a reader takes a shaded band as "where the runs landed". That argument applies with more force
here: the cores cells at 32-96 have CV 0.12-0.46, so the band is the point, not decoration.

THE cores == 24 FILTER IS DELIBERATELY NOT APPLIED IN THIS FILE. plot_workload_axes.py and
plot_time_breakdown.py filter it by VALUE to keep cores-axis runs out of figures drawn at the base
core count. Figure 1 here IS the cores axis, so that filter would delete its entire subject. It is
skipped here and nowhere else, and this comment is the record of that being a choice.

Usage:  plot_lovelace_experiments.py <extracted-lovelace-out-dir>
"""
import collections
import csv
import io
import math
import statistics
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.lines as mlines
import matplotlib.lines
import matplotlib.patches

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = ROOT / "artifacts/experiments/results"
OUT = ROOT / "artifacts/figures/wl"

# ---------------------------------------------------------------- inherited style
C_CTL = "#000000"
C_LOW, C_MID, C_HIGH = "#1f77b4", "#2ca02c", "#d62728"
M_CTL, M_LOW, M_MID, M_HIGH = "o", "s", "^", "v"
F_TICK, F_LABEL = 8.0, 9.0
TEXTWIDTH_IN = 7.0
PANEL_W, PANEL_H = 2.75, 2.5        # the size a panel is authored at in the axis figures

RC = {
    "font.size": 9, "axes.linewidth": 0.8, "lines.linewidth": 1.1,
    "legend.fontsize": 8.5, "xtick.direction": "in", "ytick.direction": "in",
}

# THE AXIS STOPS WHERE THE EXPERIMENT STOPS BEING A CAPACITY SWEEP.
#
# MPL is a fixed campaign constant (192), so as cores rise the offered load stays put while the
# machine grows. A transaction alternates between the IO and CPU pools, giving 2*cores service
# units, and the sweep only measures the CORE COUNT while there is enough backlog to keep those
# units fed.
#
# 1.5 IS THE SMALLEST RATIO MEASURED TO HOLD, NOT A DERIVED THRESHOLD. The axis has points at
# MPL/(2*cores) = 1.5 (64 cores), where the control is still exactly capacity-bound, and at 1.00
# (96 cores), where it is not. Nothing between 1.0 and 1.5 was run, so where the boundary
# actually lies is unknown; 1.5 is simply the last ratio with evidence. Writing it as a chosen
# threshold would claim a margin that was never measured.
#
# WHAT 96 ACTUALLY MEASURED, which is why it is dropped rather than caveated: at 96 the control
# falls 2% short of 100*cores (9,407 against 9,600), its unit utilisation drops from 0.9997 to
# 0.9797, and it becomes seed-dependent for the only time in the campaign - five distinct values
# where every other point is bit-identical. A/low and B/low, the two arms light enough to be
# capacity-bound as well, drop with it (0.9997 -> 0.9017 and 0.9993 -> 0.9654). The heavily
# loaded arms are contention-bound throughout and are unaffected. So one x-value would carry two
# different meanings - MPL-limited for some series, contention-limited for others - and a caption
# would have to say which applies per arm. The scalability result is already complete at 64.
MPL = 192
MIN_OVERSUBSCRIPTION = 1.5          # measured to hold; 1.0 measured to fail; between, untested
MAX_CORES = int(MPL / (2 * MIN_OVERSUBSCRIPTION))          # 64
CORES_TICKS = [c for c in [1, 2, 4, 8, 16, 24, 32, 48, 64, 96] if c <= MAX_CORES]
# MPL 192 / service 0.02002 s = 9590 tps, and capacity cores/(k x io) = 9600 at 96 cores. The two
# coincide there, which is why a flat control past 96 cores is arithmetic rather than a result.
CONTROL_CEILING = 9600.0

PANELS = [
    ("CPR fraction $f$", [("low", "0.05", C_LOW, M_LOW), ("mid", "0.25", C_MID, M_MID),
                          ("high", "0.80", C_HIGH, M_HIGH)], ("A", "A", "A")),
    (r"Fan-out $\mu$", [("low", "1", C_LOW, M_LOW), ("mid", "2", C_MID, M_MID),
                        ("high", "4", C_HIGH, M_HIGH)], ("B", "B", "B")),
    ("Cap $m$", [("low", "2", C_LOW, M_LOW), ("mid", "3", C_MID, M_MID),
                 ("high", "5", C_HIGH, M_HIGH)], ("C", "C", "C")),
    # Ordered by CONCENTRATION, descending in alpha - the axis figures' convention, so the harmful
    # end is red in all four panels. Reversing it here would make a reader scanning colours across
    # the row read the concentration result backwards.
    (r"Concentration $\alpha$", [("alpha=100.0", "100", C_LOW, M_LOW),
                                 ("alpha=1.0", "1.0", C_MID, M_MID),
                                 ("alpha=0.05", "0.05", C_HIGH, M_HIGH)], ("D", "D", "D")),
]
# THE BASE CELL APPEARS IN RAW ROWS ONLY AS A/mid, AT EVERY CORE COUNT.
#
# (f=0.25, mu=2, m=5, alpha=1.0) is one parameter set under four labels - A/mid, B/mid, C/high,
# D/alpha=1.0 - and runCell executes it once under its FIRST appearance, emitting sidecar rows for
# the others but a single raw row labelled A/mid. So B/mid, C/high and D/alpha=1.0 exist in no raw
# file, archived or new: without this fan-out three of the four panels silently lose their middle
# line. Keyed by (panel, role) because that is what series() is called with; an earlier version
# keyed it by the DISPLAYED value ("2", "5") and never matched, which is what the missing-row
# warnings caught.
#
# Safe because the rows are the same run: the 5 cores_xcheck jobs (C/high at 64 cores) came back
# bit-identical to A/mid at 64 on all 23 measured columns, which is the only thing that licenses
# copying one label's rows onto another.
BASE_ALIASES = {("B", "mid"): ("A", "mid"), ("C", "high"): ("A", "mid"),
                ("D", "alpha=1.0"): ("A", "mid")}
LABEL_BY_ROLE = {"low": "low", "mid": "mid", "high": "high"}
# Panel D names its series by alpha; they are still the low/mid/high roles for legend purposes.
ROLE_OF = {"low": "low", "mid": "mid", "high": "high",
           "alpha=100.0": "low", "alpha=1.0": "mid", "alpha=0.05": "high"}


# Datasets live in the archive as flat, named files. The campaign wrote them as one directory per
# run with an identical filename inside, which is why the readers below used to take a path to
# that tree - and why they could not run from a clone: the tree was never in the repo. This maps
# each logical dataset to the archived file so every figure reproduces from the repository alone.
DATASETS = {
    "cores":          "wl_cores_extension_raw.csv",
    "cores_xcheck":   "wl_cores_extension_xcheck_raw.csv",
    "fmu":            "wl_fmu_grid_raw.csv",
    "fmu_alpha":      "wl_fmu_alpha_grid_raw.csv",
    "fmu_fill":       "wl_theta_grid_merged_raw_full_grid.csv",
    "factorial_th0.5": "wl_factorial_2p4_th0.5_raw.csv",
    "factorial_th0.8": "wl_factorial_2p4_th0.8_raw.csv",
}


def dataset(name):
    """Path to an archived dataset. Missing files are reported by the caller, not raised here."""
    return ARCHIVE / DATASETS[name]


def read_rows(path):
    with io.open(path, encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def load_cores():
    """Archived 1-24 rows plus the new 32-96 rows, keyed (panel, label, cores) -> {seed: tps}."""
    pts = collections.defaultdict(dict)
    for p in sorted(ARCHIVE.glob("wl_cores*_raw.csv")):
        for r in read_rows(p):
            if r["terminated_by"] != "TIME_LIMIT":
                continue
            pts[(r["panel"], r["label"], int(float(r["x"])))][r["seed"]] = r
    merged = dataset("cores")
    for r in read_rows(merged):
        pts[(r["panel"], r["label"], int(float(r["x"])))][r["seed"]] = r
    return pts


def series(pts, panel, label, column="throughput_tps"):
    """Mean with a MIN-MAX band. Returns (xs, mean, lo, hi)."""
    key = BASE_ALIASES.get((panel, label))
    xs, m, lo, hi = [], [], [], []
    for c in CORES_TICKS:
        rows = pts.get((panel, label, c)) or {}
        if not rows and key:
            rows = pts.get((key[0], key[1], c)) or {}
        if not rows:
            continue
        v = [float(r[column]) for r in rows.values()]
        xs.append(c)
        m.append(statistics.mean(v))
        lo.append(min(v))
        hi.append(max(v))
    return xs, m, lo, hi


def style_axes(ax, xlabel, ylabel):
    ax.set_xlabel(xlabel, fontsize=F_LABEL)
    ax.set_ylabel(ylabel, fontsize=F_LABEL)
    ax.tick_params(labelsize=F_TICK)
    ax.grid(True, which="major", ls=":", lw=0.6, color="#bbbbbb", alpha=0.9)
    ax.set_axisbelow(True)
    for s in ax.spines.values():
        s.set_linewidth(0.8)


def save(fig, stem):
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(f"{OUT}_{stem}.pdf", bbox_inches="tight", pad_inches=0.03)
    fig.savefig(f"{OUT}_{stem}.png", dpi=300, bbox_inches="tight", pad_inches=0.03)
    plt.close(fig)
    print(f"    wrote {OUT}_{stem}.pdf and .png")


# ================================================================ 3. factorial
NAMES = {(1, 0, 0): "f", (0, 1, 0): r"\mu", (0, 0, 1): r"\alpha",
         (1, 1, 0): r"f\!\cdot\!\mu", (1, 0, 1): r"f\!\cdot\!\alpha",
         (0, 1, 1): r"\mu\!\cdot\!\alpha", (1, 1, 1): r"f\!\cdot\!\mu\!\cdot\!\alpha"}


def load_factorial(th):
    rows = read_rows(dataset(f"factorial_th{th}"))
    cells = collections.defaultdict(list)
    for r in rows:
        lab = r["label"]                              # f0.05_mu1.0_m5_a0.05
        f = float(lab.split("_")[0][1:])
        mu = float(lab.split("_")[1][2:])
        a = float(lab.split("_")[3][1:])
        cells[(f, mu, a)].append(float(r["throughput_tps"]))
    return cells


def variation_shares(cells):
    """Jain's allocation of variation for a 2^3 r design, on LOG throughput.

    LOG, not raw: the response spans 2370 to 10 tps, and on raw values the single largest factor
    absorbs most of the sum of squares mechanically because its absolute swing is largest. The
    effects here are multiplicative (a cascade multiplies work), so log is the scale on which
    "share of variation" answers the question asked. The raw-scale table is printed too, because
    the difference between them is itself informative and hiding it would be a choice.
    """
    fs = sorted({k[0] for k in cells})
    mus = sorted({k[1] for k in cells})
    als = sorted({k[2] for k in cells})
    r = min(len(v) for v in cells.values())
    out = {}
    for tlabel, tf in (("log", math.log), ("raw", lambda x: x)):
        ybar = {}
        for key, v in cells.items():
            sgn = (1 if key[0] == fs[1] else -1, 1 if key[1] == mus[1] else -1,
                   1 if key[2] == als[1] else -1)
            ybar[sgn] = statistics.mean([tf(x) for x in v])
        q = {}
        for mask in list(NAMES):
            s = 0.0
            for sgn, y in ybar.items():
                coef = 1.0
                for i in range(3):
                    if mask[i]:
                        coef *= sgn[i]
                s += coef * y
            q[mask] = s / 8.0
        ss = {mk: 8 * r * q[mk] ** 2 for mk in NAMES}
        sse = 0.0
        for v in cells.values():
            vals = [tf(x) for x in v]
            mu_ = statistics.mean(vals)
            sse += sum((x - mu_) ** 2 for x in vals)
        sst = sum(ss.values()) + sse
        out[tlabel] = ({mk: 100 * ss[mk] / sst for mk in NAMES}, 100 * sse / sst, r)
    return out


def factorial_table(shares):
    """LaTeX table: share of variation per effect, both thetas, log throughput."""
    order = sorted(NAMES, key=lambda mk: -shares["0.5"]["log"][0][mk])
    L = [r"% Generated by scripts/plotting/plot_lovelace_experiments.py - do not edit by hand.",
         r"\begin{tabular}{lrr}",
         r"\toprule",
         r"Effect & $\theta=0.5$ & $\theta=0.8$ \\",
         r"\midrule"]
    for mk in order:
        a = shares["0.5"]["log"][0][mk]
        b = shares["0.8"]["log"][0][mk]
        L.append(rf"${NAMES[mk]}$ & {a:.1f}\% & {b:.1f}\% \\")
    L += [r"\midrule",
          rf"Residual (seed) & {shares['0.5']['log'][1]:.1f}\% & {shares['0.8']['log'][1]:.1f}\% \\",
          r"\bottomrule",
          r"\end{tabular}"]
    p = OUT.parent / "wl_factorial_shares.tex"
    p.parent.mkdir(parents=True, exist_ok=True)
    io.open(p, "w", encoding="utf-8").write("\n".join(L) + "\n")
    print(f"    wrote {p}")


def alpha_points():
    """(f, mu) -> {alpha: [throughput per seed]}, pooled from the three runs that carry both alphas.

    All at theta=0.5, 24 cores, k=10, m=5: the f*mu grid cells and the 2^3 corners from this
    campaign, plus the base cell (0.25, 2) from the archived cores axis at its base core count -
    the only place that configuration was run at both alpha levels. The cores rows are filtered to
    x=24 so every point is the same machine width; a point from another core count would not be
    comparable with the rest.
    """
    pts = {}

    def collect(path):
        if not path.exists():
            print(f"    note: {path} absent - its points are not plotted")
            return
        acc = collections.defaultdict(list)
        for r in read_rows(path):
            lab = r["label"]
            f = float(lab.split("_")[0][1:])
            mu = float(lab.split("_")[1][2:])
            a = float(lab.split("_")[3][1:])
            acc[(f, mu, a)].append(float(r["throughput_tps"]))
        for (f, mu, a), v in acc.items():
            pts.setdefault((f, mu), {})[a] = v

    collect(dataset("fmu_alpha"))
    collect(dataset("factorial_th0.5"))
    # The fill-in grid (gen_jobs.sh fmu_fill): the 9 cells that complete f = 0.05..0.8 at
    # mu = 1..4. Absent until that run is merged in, and collect() says so rather than failing -
    # the figures are drawn from whatever is present, so a half-finished grid is still plottable.
    collect(dataset("fmu_fill"))
    # KEYED BY SEED, NOT APPENDED. The glob matches both the merged cores file and the per-shard
    # files it was merged from, so every row here appears at least twice. Today the duplicates are
    # bit-identical - same seed, same throughput to 0.1 tps, checked - so appending would not move
    # the mean; but it would silently double-count the moment one shard differed, and a band drawn
    # from 10 rows that are really 5 is a band nobody would think to question. Deduping by seed
    # makes the invariant "one value per seed" hold by construction rather than by luck.
    acc = collections.defaultdict(dict)
    for q in sorted(ARCHIVE.glob("wl_cores*_raw.csv")):
        for r in read_rows(q):
            if r["panel"] != "D" or int(float(r["x"])) != 24:
                continue
            if r["terminated_by"] != "TIME_LIMIT":
                continue
            a = float(r["label"].split("=")[1])
            if a not in (0.05, 100.0):
                continue
            seed, tps = r["seed"], float(r["throughput_tps"])
            if seed in acc[a] and abs(acc[a][seed] - tps) > 0.05:
                print(f"    WARNING: seed {seed} at alpha={a} differs between archive files: "
                      f"{acc[a][seed]} vs {tps} - the archive is not self-consistent")
            acc[a][seed] = tps
    for a, v in acc.items():
        pts.setdefault((0.25, 2.0), {})[a] = list(v.values())
    return {k: v for k, v in pts.items() if 0.05 in v and 100.0 in v}


