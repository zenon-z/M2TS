"""V3's PostgreSQL ladder: N x W x f, one CSV row per cell.

TWO THINGS THE LOOP ORDER DOES, both from C30:

  RESET BETWEEN CELLS. Every cell rebuilds the schema, so each starts from an identical empty
  table. Without it the outbox grows by a cell's worth of rows each time and the drain's rate
  decays with table size -- 16 repeats of ONE quantity fell 55,124 -> 6,416 rows/s that way, and a
  ladder is 48 of them in a row.

  RANDOMISED CELL ORDER. A fixed ascending sweep turns residual drift into an apparent effect along
  the swept axis, pointing the same way every run. This ladder sweeps N, which is exactly what that
  shape would fake. The permutation seed is recorded in every row, so the order is recoverable.

WHAT EACH CELL RECORDS, beyond c0:

  rho is MEASURED AS DRAIN UTILISATION, never computed from an isolated S_drain -- that estimator
  was 21x optimistic because the drain's service is not load-independent, and it returned
  rho(N=1) = 0.39 for a cell whose queue sat at 88%. Each in-server drain worker reports its own
  busy and idle microseconds, and rho = busy / (busy + idle) aggregated over the N workers. That
  assumes NOTHING about service time, load-dependence, or the host: a worker is either completing a
  dequeue or failing to find one. `service_under_load_us` = busy / drained falls out of the same
  counters, and is the figure to compare against the 24 us isolated S_drain.

  Both latency totals: `visible` (commit_ts -> processed_at), which is what the simulator's clock
  corresponds to and what the partition compares, and `wall` (enqueued_at -> processed_at), which
  is what an application observes. At W=4 those differ by 4.7x and ALL of the difference is the
  producer's own commit latency.

  The drain's wakeup discipline and ordering, per cell, because both are choices with measured
  consequences: poll adds up to 500us of latency the simulator has no counterpart for, and FIFO
  ordering costs parallelism (0.34x at N=16 against 3.12x unordered).
"""
import argparse
import itertools
import random
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_DSN = "host=127.0.0.1 port=15432 dbname=tgs user=tgs password=tgs"

HEADER = ("n,w,f,k_pg,seed_index,producer_tps,attempts,rejections,c0_pg,"
          "rho_measured,service_under_load_us,drained_rows,"
          "pending_mean,pending_frac_k,pending_growth,"
          "lat_total_visible_us,lat_total_wall_us,commit_gap_us,"
          "lat_processing_us,lat_queue_us,lat_blocking_us,"
          "drain_wakeup,drain_ordering,lat_comparable,target_tps,shuffle_seed,cell_index,gate_pass\n")


def parse(out):
    """Pull the numbers out of v3_producer.py's report. Returns {} if it refused."""
    got = {}
    for line in out.splitlines():
        line = line.strip()
        for key, tag in (("producer_tps", "producer transactions"),
                         ("c0_pg", "c0_pg"),
                         ("lat_total_visible_us", "lat_total_visible_us"),
                         ("lat_total_wall_us", "lat_total_wall_us"),
                         ("commit_gap_us", "commit gap"),
                         ("lat_processing_us", "processing "),
                         ("lat_queue_us", "queue "),
                         ("lat_blocking_us", "blocking "),
                         ("pending_mean", "pending set (mean)"),
                         ("pending_growth", "pending growth"),
                         ("attempts_rejections", "attempts / rejections"),
                         ("rho_measured", "rho_measured"),
                         ("service_under_load_us", "service under load"),
                         ("drained_rows", "drained rows")):
            if line.startswith(tag):
                got[key] = line
    return got


def num(line, after=":"):
    """First number after `after`, THOUSANDS SEPARATORS STRIPPED FIRST.

    Without the strip the scan stops at the first comma: "1,560.5 tps" parses as 1 and
    "105,877" as 105. The smoke test passed only because its numbers were under 1,000 - at W=64
    the tps is four digits and every row would have been silently wrong by ~1000x.
    """
    if line is None:
        return float("nan")
    tail = line.split(after, 1)[1] if after in line else line
    tail = tail.replace(",", "")
    buf = ""
    for ch in tail:
        if ch.isdigit() or ch in ".-+":
            buf += ch
        elif buf:
            break
    try:
        return float(buf)
    except ValueError:
        return float("nan")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dsn", default=DEFAULT_DSN)
    ap.add_argument("--ns", default="1,2,3,4,8,12,16,24")
    ap.add_argument("--ws", default="4,16,64")
    ap.add_argument("--fs", default="0.10,0.20")
    ap.add_argument("--reps", type=int, default=1)
    ap.add_argument("--duration-s", type=float, default=20.0)
    ap.add_argument("--parent-rows", type=int, default=80_000)
    ap.add_argument("--drain-wakeup", default="spin", choices=["poll", "spin", "notify"])
    ap.add_argument("--drain-ordering", default="fifo", choices=["fifo", "unordered"])
    ap.add_argument("--target-tps", type=float, default=0.0)
    ap.add_argument("--s-drain-us", type=float, default=24.0)
    ap.add_argument("--shuffle", type=int, default=20260917)
    ap.add_argument("--out", default="artifacts/experiments/results/v3_pg_ladder.csv")
    args = ap.parse_args()

    ns = [int(x) for x in args.ns.split(",")]
    ws = [int(x) for x in args.ws.split(",")]
    fs = [float(x) for x in args.fs.split(",")]

    cells = [(n, w, f, r) for n, w, f, r
             in itertools.product(ns, ws, fs, range(args.reps))]
    if args.shuffle:
        random.Random(args.shuffle).shuffle(cells)
    print(f"{len(cells)} cells, order {'randomised' if args.shuffle else 'FIXED'} "
          f"(seed {args.shuffle}); RESET between every cell")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    t_start = time.time()
    with out_path.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(HEADER)
        for idx, (n, w, f, rep) in enumerate(cells):
            k_pg = int(round(f * args.parent_rows))
            # RESET: rebuilding the schema truncates everything. Also re-derives and re-gates K_pg.
            gen = subprocess.run(
                [sys.executable, str(HERE / "v3_schema_gen.py"), "--dsn", args.dsn,
                 "--f", str(f), "--parent-rows", str(args.parent_rows),
                 "--w-max", str(max(ws)), "--assert-k", str(k_pg)],
                capture_output=True, text=True)
            if gen.returncode != 0:
                print(f"[{idx+1}/{len(cells)}] N={n} W={w} f={f}: SCHEMA GATE REFUSED")
                print("    " + (gen.stderr.strip().splitlines() or ["?"])[0])
                fh.write(f"{n},{w},{f},{k_pg},{rep}," + "nan," * 16
                         + f"{args.drain_wakeup},{args.drain_ordering},"
                         + f"{'true' if args.drain_ordering == 'fifo' else 'false'},"
                         + f"{args.target_tps},{args.shuffle},{idx},schema_refused\n")
                fh.flush()
                continue

            run = subprocess.run(
                [sys.executable, str(HERE / "v3_producer.py"), "--dsn", args.dsn,
                 "-w", str(w), "--k-pg", str(k_pg), "--duration-s", str(args.duration_s),
                 "--warmup-frac", "0.6", "--drain-workers", "0", "--server-drain", str(n),
                 "--s-drain-us", str(args.s_drain_us), "--drain-wakeup", args.drain_wakeup,
                 "--drain-ordering", args.drain_ordering,
                 "--target-tps", str(args.target_tps),
                 "--parent-rows", str(args.parent_rows)],
                capture_output=True, text=True)
            g = parse(run.stdout)
            gate = "true" if run.returncode == 0 else "gate_refused"
            tps = num(g.get("producer_tps"), "(")
            c0 = num(g.get("c0_pg"))
            pend = num(g.get("pending_mean"))
            ar = g.get("attempts_rejections")
            attempts = rejections = float("nan")
            if ar and ":" in ar:
                parts = ar.split(":", 1)[1].replace(",", "").split("/")
                if len(parts) == 2:
                    try:
                        attempts, rejections = float(parts[0]), float(parts[1])
                    except ValueError:
                        pass
            row = [n, w, f, k_pg, rep, tps,
                   attempts, rejections, c0,
                   num(g.get("rho_measured")), num(g.get("service_under_load_us")),
                   num(g.get("drained_rows")),
                   pend, pend / k_pg if k_pg else float("nan"),
                   num(g.get("pending_growth")),
                   num(g.get("lat_total_visible_us")), num(g.get("lat_total_wall_us")),
                   num(g.get("commit_gap_us")), num(g.get("lat_processing_us")),
                   num(g.get("lat_queue_us")), num(g.get("lat_blocking_us"))]
            fh.write(",".join(f"{x}" for x in row)
                     + f",{args.drain_wakeup},{args.drain_ordering},"
                     + f"{'true' if args.drain_ordering == 'fifo' else 'false'},"
                     + f"{args.target_tps},{args.shuffle},{idx},{gate}\n")
            fh.flush()
            print(f"[{idx+1}/{len(cells)}] N={n:>2} W={w:>2} f={f} : c0={c0:.5f} "
                  f"pending={pend:,.1f} ({100*pend/k_pg if k_pg else float('nan'):.2f}% of K) "
                  f"tps={tps:,.0f} gate={gate}")
    print(f"\nwrote {out_path}  ({time.time()-t_start:.0f}s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
