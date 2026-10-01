"""Reduce TgsPanelBSimCheck's raw first-attempt rows to per-(cell, mpl, seed, H) bin counts.

The raw file is one row per admission attempt: at the canonical cost basis that is ~13 M rows and
~950 MB for three cells, which is slow to analyse (a six-pass script over it timed out) and awkward
to keep around. Every statistic the P1 parity analysis computes - coverage, Wilson intervals, mean
signed residual, the residual-vs-H trend, mean H per load point - is a function of
`(blocked, total)` per H bin, so the binned form is lossless FOR THOSE PURPOSES and ~4 orders of
magnitude smaller.

What the bins discard: per-attempt ordering and timing (`virtual_time_ms`), `n_running`, and the
identity of individual attempts. Nothing in the current analysis uses them. If a future analysis
needs per-attempt structure - autocorrelation of outcomes, time-ordered transients, attempt-level
joins - regenerate the raw rows instead of trying to recover them from bins: the run is
deterministic given its seed.

`--verify` recomputes coverage / mean residual / worst miss from both the raw file and the bins and
refuses to report success unless they agree exactly. Run it before deleting any raw input.
"""
from __future__ import annotations

import argparse
import collections
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analysis_helpers import hypergeometric_block_probability as p_exact, wilson  # noqa: E402

MIN_BIN_N = 30
HEADER = "D,k,mpl,seed,held_lock_units,blocked,total\n"


def bin_raw(path: Path):
    """(D,k,mpl,seed,H) -> [blocked, total]. Single pass, manual split - csv.DictReader over 13 M
    rows is itself the bottleneck."""
    bins = collections.defaultdict(lambda: [0, 0])
    with open(path) as f:
        hdr = f.readline().rstrip("\n").split(",")
        iD, ik, im, isd, ih, io = (hdr.index(c) for c in
                                   ("D", "k", "mpl", "seed", "held_lock_units", "outcome"))
        for line in f:
            p = line.split(",")
            key = (p[iD], p[ik], p[im], p[isd], p[ih])
            bins[key][1] += 1
            if p[io].rstrip("\n") == "BLOCKED_AT_CHECK":
                bins[key][0] += 1
    return bins


def load_bins(path: Path):
    bins = collections.defaultdict(lambda: [0, 0])
    with open(path) as f:
        f.readline()
        for line in f:
            D, k, m, s, h, b, t = line.rstrip("\n").split(",")
            e = bins[(D, k, m, s, h)]
            e[0] += int(b)
            e[1] += int(t)
    return bins


def stats_from_bins(bins):
    """(D,k) -> (coverage%, nbins, attempts, mean residual, worst miss). Pools over mpl and seed,
    which is what the per-cell parity figures do."""
    per_cell = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0]))
    for (D, k, _m, _s, h), (b, t) in bins.items():
        e = per_cell[(int(D), int(k))][int(h)]
        e[0] += b
        e[1] += t
    out = {}
    for (D, k), hs in per_cell.items():
        q = [(h, v) for h, v in hs.items() if v[1] >= MIN_BIN_N]
        if not q:
            continue
        ins = sum(1 for h, (b, n) in q if wilson(b, n)[1] <= p_exact(D, k, h) <= wilson(b, n)[2])
        resid = sum(b / n - p_exact(D, k, h) for h, (b, n) in q) / len(q)
        worst = max(abs(p_exact(D, k, h) - (wilson(b, n)[1] + wilson(b, n)[2]) / 2)
                    / max((wilson(b, n)[2] - wilson(b, n)[1]) / 2, 1e-12) for h, (b, n) in q)
        out[(D, k)] = (100 * ins / len(q), len(q), sum(v[1] for v in hs.values()), resid, worst)
    return out


def write_bins(bins, path: Path, append: bool) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    exists = path.exists()
    with open(path, "a" if append else "w", newline="") as f:
        if not append or not exists:
            f.write(HEADER)
        for (D, k, m, s, h), (b, t) in sorted(bins.items(),
                                              key=lambda kv: tuple(int(x) for x in kv[0])):
            f.write(f"{D},{k},{m},{s},{h},{b},{t}\n")
    return len(bins)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", default="artifacts/experiments/results/tgs_panelb_sim_first_attempts.csv")
    ap.add_argument("--out", default="artifacts/experiments/results/tgs_panelb_sim_bins.csv")
    ap.add_argument("--append", action="store_true",
                    help="add to an existing bin file (for merging a later cell's run)")
    ap.add_argument("--verify", action="store_true",
                    help="recompute the parity statistics from raw and from bins and require an "
                         "exact match before reporting success")
    args = ap.parse_args()

    raw, out = Path(args.raw), Path(args.out)
    if not raw.exists():
        print(f"missing {raw}")
        return 1

    print(f"binning {raw} ({raw.stat().st_size / 1e6:.0f} MB) ...")
    bins = bin_raw(raw)
    n = write_bins(bins, out, args.append)
    print(f"wrote {n} bins to {out} ({out.stat().st_size / 1e3:.0f} kB)")

    if args.verify:
        a, b = stats_from_bins(bins), stats_from_bins(load_bins(out))
        # Compare against the bins just re-read from disk, so the round trip is what is checked.
        ok = True
        print(f"\n{'cell':<14}{'coverage':>10}{'bins':>7}{'attempts':>12}{'mean resid':>13}{'worst':>8}")
        for cell in sorted(a):
            for label, s in (("raw", a[cell]), ("file", b[cell])):
                print(f"{'D=%d k=%d %s' % (cell[0], cell[1], label):<14}"
                      f"{s[0]:>10.4f}{s[1]:>7}{s[2]:>12}{s[3]:>+13.6f}{s[4]:>8.3f}")
            if a[cell] != b[cell]:
                ok = False
                print(f"  MISMATCH for {cell}")
        print("\nverify: " + ("EXACT MATCH - safe to delete the raw input" if ok else "MISMATCH"))
        return 0 if ok else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
