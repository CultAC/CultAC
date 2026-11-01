#!/usr/bin/env bash
# Runs inside the disposable container. Never invoke this on the Actions host.
set -euo pipefail
root="$PWD"
case "${1:-}" in
  java)
    cultac/gradlew -p cultac :bukkit:devShadowJar --rerun-tasks --offline \
      --no-daemon --console=plain --stacktrace --max-workers=2
    cult_jar="$root/cultac/build/libs/CultAC-dev.jar"
    test -f "$cult_jar"
    cd mcp-client-smoketest
    ./gradlew --offline setupSmoketestServer writeSmoketestWorkspaceManifest \
      --no-daemon --console=plain --stacktrace --max-workers=2 \
      -Psmoketest.grim.devJar="$cult_jar" -Psmoketest.cultac.repoRoot="$root/cultac"

    run_root="$PWD/.real-validation/runs/github-ci"
    mkdir -p "$run_root"
    printf 'section\tseconds\tstatus\n' > "$run_root/section-times.tsv"
    status=0
    run_section() {
      local name="$1" minutes="$2" start rc=0
      shift 2
      start="$(date +%s)"
      timeout -k 30s "${minutes}m" xvfb-run -a -s '-screen 0 1280x720x24' \
        java "${java_common[@]}" "$@" || rc=$?
      printf '%s\t%d\t%d\n' "$name" "$(( $(date +%s) - start ))" "$rc" \
        >> "$run_root/section-times.tsv"
      if [[ "$rc" -ne 0 ]]; then status=1; fi
    }
    java_common=(
      -Xmx2g
      "-Dsmoketest.workspace.manifest=$PWD/.real-validation/smoketest-workspace.properties"
      "-Dsmoketest.grim.devJar=$cult_jar"
      "-Dsmoketest.cultac.repoRoot=$root/cultac"
      "-Dsmoketest.packetControlPluginJar=$PWD/build/libs/smoketest-packet-control-plugin.jar"
      "-Dorg.lwjgl.librarypath=$PWD/.real-validation/lwjgl-natives/linux-x64"
      -cp build/classes/java/main:build/resources/main
    )
    # Run both sections even if the first fails. No full movement/ci-full suite.
    run_section deduplication 20 \
      -Dcult.dedupVerification.port=25580 \
      ac.grim.movementvalidation.DeduplicationVerificationMain
    run_section combat-reach 8 \
      -Dcult.validation.reach.minSamples=24 \
      -Dcult.validation.reach.bundled.maxReachExcess=0.005 \
      -Dcult.validation.reach.unbundled.meanTrackingError=0.25 \
      ac.grim.movementvalidation.RealServerCombatReachValidationMain \
      "$run_root/combat-reach" 25620 --target cult-dev --bot-count 8 \
      --segments 1 --ticks-per-segment 1000 --seed 424242 --vehicles off --pistons off
    exit "$status"
    ;;
  bedrock)
    cultac/gradlew -p cultac :bukkit:shadowJar --offline --no-daemon --console=plain --max-workers=2
    mkdir -p artifacts
    git -C replay rev-parse HEAD > artifacts/replay-revision.txt
    git -C scenarios rev-parse HEAD > artifacts/scenarios-revision.txt
    git -C cultac rev-parse HEAD > artifacts/cult-revision.txt
    python3 - <<'PYRUN'
from pathlib import Path
import subprocess

current = Path('cultac/bukkit/build/libs/CultAC-paper.jar')
jars = [current] if current.is_file() else [
    p for p in current.parent.glob('cultac-bukkit-*.jar')
    if not any(s in p.name for s in ('-sources', '-javadoc', '-plain', '-dev'))
]
if len(jars) != 1:
    raise SystemExit(f'Expected one Cult distributable, got {jars}')
subprocess.run([
    'python3', 'replay/scripts/run-movement-regressions.py',
    '--scenarios', 'scenarios/scenarios', '--cultac-jar', str(jars[0]),
    '--server-dir', 'server', '--artifacts', 'artifacts', '--seed', '42',
    '--replay-runtime-lock', 'replay/.ci-runtime-offline.json',
], check=True)
PYRUN
    ;;
  *) echo 'Expected java or bedrock' >&2; exit 2 ;;
esac
