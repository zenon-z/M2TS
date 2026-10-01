#!/usr/bin/env bash
# Build ONE self-contained jar to scp to the campaign host, with its build provenance stamped
# inside it.
#
# WHY THE STAMP. CONVENTIONS C1 wants the numbers tied to a committed plan. On a host that has the
# repository that is a `git rev-parse`; on a host that received a jar over scp and has no git, no
# checkout and possibly no network, there is nothing to ask. Asking git on the ANALYSIS machine
# afterwards is not a substitute - that machine's HEAD is a fact about the analysis machine, not
# about the jar that produced the rows. So the record is baked into the artifact that runs.
#
# The jar is named with the short SHA, so two jars on the same host cannot be confused and the
# filename alone answers "which build produced this data".
#
# Usage:  scripts/campaign/build_campaign_jar.sh [--allow-dirty]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ALLOW_DIRTY=0
[ "${1:-}" = "--allow-dirty" ] && ALLOW_DIRTY=1

# ---------------------------------------------------------------- clean tree
DIRTY_COUNT=$(git status --porcelain 2>/dev/null | wc -l)
if [ "$DIRTY_COUNT" -ne 0 ]; then
  if [ "$ALLOW_DIRTY" -eq 0 ]; then
    echo "REFUSING: $DIRTY_COUNT modified/untracked file(s). A jar built from a dirty tree" >&2
    echo "produces numbers that cannot be tied to any commit, and the stamp inside it would" >&2
    echo "record a SHA that does not describe the code that ran - worse than no stamp." >&2
    git status --porcelain | head -15 | sed 's/^/  /' >&2
    echo >&2
    echo "Commit, or re-run with --allow-dirty for a throwaway build (it will be stamped" >&2
    echo "dirty=true and must not produce paper numbers)." >&2
    exit 1
  fi
  echo "WARNING: building from a DIRTY tree because --allow-dirty was passed."
  echo "         The stamp will say dirty=true. Do not use this jar for paper numbers."
fi

SHA=$(git rev-parse HEAD 2>/dev/null || echo unknown)
SHORT=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)
DIRTY=$([ "$DIRTY_COUNT" -eq 0 ] && echo false || echo true)

echo "=== building ==="
mvn -q -o package -DskipTests 2>/dev/null || mvn -q package -DskipTests || { echo "BUILD FAILED" >&2; exit 1; }

SRC_JAR=$(ls -1 target/ts-simulator-*.jar 2>/dev/null | grep -v original | head -1)
[ -z "$SRC_JAR" ] && { echo "no shaded jar in target/" >&2; exit 1; }

# ---------------------------------------------------------------- stamp
STAMP_DIR=$(mktemp -d)
cat > "$STAMP_DIR/campaign-provenance.properties" <<EOF
git.sha=$SHA
git.branch=$BRANCH
git.dirty=$DIRTY
built.utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
built.by=$(git config user.email 2>/dev/null || echo unknown)
built.jdk=$(java -version 2>&1 | head -1 | tr -d '"')
EOF

OUT="target/ts-simulator-campaign-${SHORT}$([ "$DIRTY" = true ] && echo '-DIRTY').jar"
cp "$SRC_JAR" "$OUT"
( cd "$STAMP_DIR" && jar uf "$ROOT/$OUT" campaign-provenance.properties ) \
  || { echo "could not stamp the jar" >&2; exit 1; }
rm -rf "$STAMP_DIR"

echo
echo "=== $OUT ==="
unzip -p "$OUT" campaign-provenance.properties | sed 's/^/  /'
echo
echo "  size: $(du -h "$OUT" | cut -f1)"
echo
echo "Verify it runs standalone BEFORE copying (no repo, no mvn, no git needed):"
echo "  java -cp $OUT de.tum.tssimulator.experiments.WorkloadSdrClosureCheck"
echo
echo "Then:"
echo "  scp $OUT you@server:~/"
echo "  scp scripts/campaign/w1w4_server.sh you@server:~/"
echo "  ssh you@server 'bash w1w4_server.sh $(basename "$OUT")'"
