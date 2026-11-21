#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cultac_root="$(cd "$script_dir/.." && pwd)"
harness="${SMOKETEST_REPO:-$cultac_root/../mcp-client-smoketest}"
if [[ ! -x "$harness/gradlew" && -z "${SMOKETEST_REPO:-}" ]]; then
  harness="$cultac_root/../CultAC-old/mcp-client-smoketest"
fi
[[ -x "$harness/gradlew" ]] || { echo "Missing real-client harness: $harness" >&2; exit 1; }
patch="$script_dir/interaction-visibility-smoketest.patch"
if git -C "$harness" apply --check "$patch" 2>/dev/null; then
  git -C "$harness" apply "$patch"
elif ! git -C "$harness" apply --reverse --check "$patch" 2>/dev/null; then
  echo "The harness has conflicting visibility test changes; review $patch against $harness" >&2
  exit 1
fi
manifest="${SMOKETEST_WORKSPACE_MANIFEST:-$harness/.real-validation/smoketest-workspace.properties}"
artifacts="${SMOKETEST_ARTIFACT_ROOT:-$cultac_root/validation/interaction-visibility/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$artifacts"
artifacts="$(cd "$artifacts" && pwd)"

python3 - "$manifest" "$script_dir/verify-smoketest-client.py" "$artifacts/client-identity.json" <<'PY'
import importlib.util
import json
import sys
from pathlib import Path
manifest, verifier, output = sys.argv[1:]
properties = dict(line.split('=', 1) for line in Path(manifest).read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
spec = importlib.util.spec_from_file_location('client_identity', verifier)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
identity = module.verify(Path(properties['smoketest.mcp.checkoutDir']), properties['smoketest.paper.version'])
Path(output).write_text(json.dumps(identity, indent=2) + '\n')
print(f"Verified official {identity['version']['id']} client: {identity['verified_classes']} unchanged classes")
PY

python3 - "$manifest" "$artifacts/workspace.properties" "$cultac_root" "${SMOKETEST_PLUGIN_JAR:-}" <<'PY'
import hashlib
import io
import sys
import zipfile
from pathlib import Path
source, target, root, requested = sys.argv[1:]
candidates = list((Path(root) / 'bukkit/build/libs').glob('cultac-bukkit-*.jar'))
if not requested and not candidates:
    raise SystemExit('Build :bukkit:shadowJar first, or set SMOKETEST_PLUGIN_JAR')
jar = Path(requested).resolve() if requested else max(candidates, key=lambda p: p.stat().st_mtime)
if not jar.is_file():
    raise SystemExit(f'Missing plugin: {jar}')
content = jar.read_bytes()
with zipfile.ZipFile(io.BytesIO(content)) as archive:
    if archive.testzip() is not None:
        raise SystemExit('Plugin archive failed its CRC check; rebuild before validation')
    for check in ('PlaceVisibility', 'BreakVisibility', 'AttackPlayerVisibility', 'AttackEntityVisibility', 'InteractEntityVisibility', 'InteractPlayerVisibility'):
        if not any(name.endswith(f'/{check}.class') for name in archive.namelist()):
            raise SystemExit(f'Plugin does not contain {check}; rebuild before validation')
# The shared workspace may be rebuilding this same filename. The server must
# receive immutable bytes, including when another task starts a Gradle build.
jar = Path(target).parent / 'plugin.jar'
jar.write_bytes(content)
lines = [line for line in Path(source).read_text().splitlines()
         if not line.startswith(('smoketest.grim.devJar=', 'smoketest.cultac.repoRoot=', 'smoketest.cult.sha256='))]
lines += [f'smoketest.grim.devJar={jar}', f'smoketest.cultac.repoRoot={root}',
          f'smoketest.cult.sha256={hashlib.sha256(content).hexdigest()}']
Path(target).write_text('\n'.join(lines) + '\n')
print(f'Pinned anticheat: {jar}')
PY

export REAL_VALIDATION_SERVER_JVM_XMS="${REAL_VALIDATION_SERVER_JVM_XMS:-512m}"
export REAL_VALIDATION_SERVER_JVM_HEAP="${REAL_VALIDATION_SERVER_JVM_HEAP:-1536m}"
defaults=(spawn-stationary block-break-instant-short-grass block-break-abort-stone
  block-break-waterlogged-slab block-break-switch-target-aborts-first
  block-place-replace-short-grass block-place-replace-snow-layer
  block-place-stairs-rapid-turn block-place-powered-trapdoor)
for blocker in stone glass; do
  defaults+=("visibility-blocked-attack-corner-$blocker"
    "visibility-blocked-interact-cow-corner-$blocker"
    "visibility-native-attack-corner-opening-$blocker")
  for action in use start_destroy_block stop_destroy_block attack; do
    defaults+=("visibility-blocked-$action-$blocker")
  done
  defaults+=("visibility-blocked-place-$blocker")
done
for face in south east west down; do defaults+=("visibility-impossible-face-$face"); done
for material in stone glass oak_slab oak_stairs oak_fence chest; do
  defaults+=("visibility-native-use-$material")
done
for material in stone oak_slab chest; do defaults+=("visibility-native-crouch-use-$material"); done
defaults+=(visibility-native-attack-clear visibility-native-attack-slab-opening
  visibility-native-attack-with-iron_sword visibility-native-attack-with-iron_spear
  visibility-native-custom-attack-range visibility-native-crouch-attack visibility-native-turn-use)
for entity in cow oak_boat minecart; do
  for blocker in stone glass; do defaults+=("visibility-blocked-interact-$entity-$blocker"); done
  for variant in clear slab-opening crouch; do defaults+=("visibility-native-interact-$entity-$variant"); done
done
defaults+=(visibility-blocked-interact-range visibility-native-interact-custom-range)
default_selectors="$(IFS=,; echo "${defaults[*]}")"
selectors="${SMOKETEST_SCENARIOS:-$default_selectors}"
"$harness/gradlew" -p "$harness" smoketestRun -x smoketestCombatReach \
  --no-daemon --console=plain --max-workers=2 \
  -I "$script_dir/smoketest-workspace.init.gradle" \
  -Psmoketest.workspaceManifest="$artifacts/workspace.properties" \
  -Psmoketest.artifactRoot="$artifacts" \
  -Psmoketest.scenarios="$selectors" \
  -Psmoketest.basePort="${SMOKETEST_BASE_PORT:-25980}" \
  -Psmoketest.jvmHeap="${SMOKETEST_JVM_HEAP:-3g}" "$@"

# MustFlag alone can pass because another check flagged the injected packet.
# Require this check's public API event, and prove Cult loaded for positive runs.
python3 - "$artifacts" <<'PY'
import re
import sys
from pathlib import Path
root = Path(sys.argv[1])
transcripts = sorted(root.rglob('*.transcript.txt'))
if not transcripts:
    raise SystemExit('No real-client transcripts were written')
failures = []
verified = 0
connection_retries = 0
visibility_checks = ('PlaceVisibility', 'BreakVisibility', 'AttackPlayerVisibility', 'AttackEntityVisibility', 'InteractEntityVisibility', 'InteractPlayerVisibility')
for transcript in transcripts:
    content = transcript.read_text()
    header = dict(line.split('=', 1) for line in content.split('--- ticks ---', 1)[0].splitlines() if '=' in line)
    name = header['scenario']
    directory = transcript.parent
    analysis = (directory / 'cult-log-analysis.txt').read_text()
    # The existing harness retries connections that never reach PLAY. Preserve
    # that decision, while checking that this check did not flag those attempts.
    if header['phase'] != 'PLAY':
        connection_retries += 1
        if any(check in analysis for check in visibility_checks):
            failures.append(f'{name}: visibility flag during a failed connection attempt')
        continue
    verified += 1
    if any(header.get(key) != 'true' for key in ('passed', 'scenarioValid', 'cultValid')):
        failures.append(f'{name}: PLAY validation did not pass')
    server = (directory / 'server-console.log').read_text()
    if 'Enabling CultAC' not in server:
        failures.append(f'{name}: CultAC did not load')
    if f"Cult smoketest snapshot passed player=CodexClient tick=0 scenario={header['fixture']}" not in server:
        failures.append(f'{name}: native inventory/world snapshot was not verified')
    if name.startswith(('visibility-blocked-', 'visibility-impossible-face-')):
        expected_check = (
            'InteractEntityVisibility' if name.startswith('visibility-blocked-interact-') else
            'AttackEntityVisibility' if name.startswith('visibility-blocked-attack-') else
            'BreakVisibility' if name.startswith(('visibility-blocked-start_destroy_block-', 'visibility-blocked-stop_destroy_block-')) else
            'PlaceVisibility'
        )
        if f'check={expected_check} ' not in analysis:
            failures.append(f'{name}: missing {expected_check} public flag event')
    if name.startswith(('visibility-native-use-', 'visibility-native-crouch-use-', 'visibility-native-turn-use')):
        if not re.search(r'ServerboundUseItemOnPacket\[.*?pos=\(0,64,2\)', content):
            failures.append(f'{name}: no native packet actually claimed the target block')
    if name.startswith(('visibility-native-interact-', 'visibility-blocked-interact-')):
        if 'ServerboundInteractPacket[' not in content:
            failures.append(f'{name}: no native entity interaction packet')
    if name in {f'visibility-native-interact-{entity}-{variant}'
                for entity in ('oak_boat', 'minecart') for variant in ('clear', 'slab-opening')}:
        # ServerPlayer#startRiding sends exactly one teleport to the vehicle and
        # SetPassengers. Require those packets; allow no additional corrections.
        teleports = re.findall(r'ClientboundPlayerPositionPacket\[id=\d+ position=Vec3\[x=([^,]+), y=([^,]+), z=([^\]]+)\]', content)
        if len(teleports) != 1 or any(abs(float(v) - expected) > 1e-5
                                    for v, expected in zip((teleports[0][0], teleports[0][2]), (.5, 1.5))):
            failures.append(f'{name}: expected exactly the vanilla mounting teleport')
        if 'ClientboundSetPassengersPacket[' not in content:
            failures.append(f'{name}: no vanilla passenger attachment packet')
    if name.endswith('attack-with-iron_spear') and 'action=STAB' not in content:
        failures.append(f'{name}: no native STAB packet')
if failures:
    raise SystemExit('\n'.join(failures))
if not verified:
    raise SystemExit('No successful PLAY results were written')
print(f'Verified native snapshots and specific visibility evidence in {verified} real-client runs; {connection_retries} pre-PLAY retries')
PY
