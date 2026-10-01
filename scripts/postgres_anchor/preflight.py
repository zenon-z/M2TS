"""Abort a sweep unless the server is quiescent.

The lock sampler counts advisory locks SERVER-WIDE (no session filter), so a
concurrent client does not merely add load -- it corrupts the measured H
directly. Two overlapping sweeps on 2026-09-02 produced a spurious "H never
returns to zero" finding for exactly this reason. Run this before any sweep.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import psycopg2

from workload_client import DEFAULT_DSN as DSN  # single source of truth

conn = psycopg2.connect(DSN)
with conn.cursor() as cur:
    cur.execute("SELECT count(*) FROM pg_locks WHERE locktype='advisory'")
    locks = cur.fetchone()[0]
    cur.execute("SELECT count(*) FROM pg_stat_activity "
                "WHERE backend_type='client backend' AND pid <> pg_backend_pid()")
    backends = cur.fetchone()[0]
conn.close()

print("preflight: advisory_locks=%d other_client_backends=%d" % (locks, backends))
if locks or backends:
    sys.exit("ABORT: server is not quiescent -- another client is connected or holding "
             "advisory locks. H is a server-wide count, so this run would be corrupted.")
print("preflight OK")
