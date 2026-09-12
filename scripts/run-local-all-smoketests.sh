#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cultac_root="$(cd "$script_dir/.." && pwd)"
default_smoketest_root="$cultac_root/../mcp-client-smoketest"
smoketest_root="${SMOKETEST_REPO:-$default_smoketest_root}"

time_budget_minutes="${SMOKETEST_TIME_BUDGET_MINUTES:-60}"
base_port="${SMOKETEST_BASE_PORT:-25580}"
mcp_ref="${SMOKETEST_MCP_REF:-26.2}"
timestamp="$(date +%Y%m%d-%H%M%S)"
artifact_root="${SMOKETEST_ARTIFACT_ROOT:-$smoketest_root/.real-validation/runs/local-all-smoketests-$timestamp}"
setup_client="${SMOKETEST_SETUP_CLIENT:-auto}"
use_xvfb="${SMOKETEST_USE_XVFB:-auto}"
skip_cultac_build="${SMOKETEST_SKIP_CULTAC_BUILD:-false}"
plugin_jar="${SMOKETEST_PLUGIN_JAR:-}"
stop_gradle_daemons="${SMOKETEST_STOP_GRADLE_DAEMONS:-true}"
max_workers="${SMOKETEST_MAX_WORKERS:-2}"
gradle_heap="${SMOKETEST_GRADLE_HEAP:-1024m}"
cultac_gradle_heap="${SMOKETEST_CULTAC_GRADLE_HEAP:-2g}"
mcp_tool_heap="${SMOKETEST_MCP_TOOL_HEAP:-4g}"
smoketest_jvm_heap="${SMOKETEST_JVM_HEAP:-3g}"
server_jvm_xms="${REAL_VALIDATION_SERVER_JVM_XMS:-512m}"
server_jvm_heap="${REAL_VALIDATION_SERVER_JVM_HEAP:-1536m}"

usage() {
  cat <<'EOF'
Usage: scripts/run-local-all-smoketests.sh [options]

Runs the local all-smoketests workflow without GitHub Actions minutes.

Options:
  --smoketest-root PATH       Path to the standalone mcp-client-smoketest repo.
                              Default: ../mcp-client-smoketest
  --artifact-root PATH        Root directory for all validation artifacts.
  --base-port PORT            First local Paper server port. Default: 25580
  --time-budget-minutes N     Overall wall-clock budget. Default: 60
  --mcp-ref REF               MCP-Reborn ref for setup. Default: 26.2
  --setup-client MODE         auto, always, or never. Default: auto
                              auto reuses an existing prepared MCP-Reborn client.
  --max-workers N             Gradle worker cap. Default: 2
  --gradle-heap SIZE          Smoketest Gradle heap. Default: 1024m
  --cultac-gradle-heap SIZE   CultAC Gradle heap for devShadowJar. Default: 2g
  --mcp-tool-heap SIZE        MCP-Reborn setup/decompiler heap. Default: 4g
  --smoketest-jvm-heap SIZE   JavaExec heap for each smoketest phase. Default: 3g
  --server-jvm-heap SIZE      Paper server heap for real validation. Default: 1536m
  --keep-gradle-daemons       Do not stop existing Gradle daemons before running.
  --skip-cultac-build         Use an existing CultAC *-dev.jar.
  --plugin-jar PATH           Validate this exact jar, skipping the plugin build.
  --no-xvfb                   Do not wrap real-client phases in xvfb-run.
  --help                      Show this message.

Environment overrides match the long option names:
  SMOKETEST_REPO, SMOKETEST_ARTIFACT_ROOT, SMOKETEST_BASE_PORT,
  SMOKETEST_TIME_BUDGET_MINUTES, SMOKETEST_MCP_REF,
  SMOKETEST_SETUP_CLIENT, SMOKETEST_MAX_WORKERS, SMOKETEST_GRADLE_HEAP,
  SMOKETEST_CULTAC_GRADLE_HEAP, SMOKETEST_MCP_TOOL_HEAP,
  SMOKETEST_JVM_HEAP, REAL_VALIDATION_SERVER_JVM_XMS,
  REAL_VALIDATION_SERVER_JVM_HEAP, SMOKETEST_SKIP_CULTAC_BUILD,
  SMOKETEST_STOP_GRADLE_DAEMONS, SMOKETEST_PLUGIN_JAR,
  SMOKETEST_USE_XVFB.
EOF
}

fail() {
  echo "error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --smoketest-root)
      [[ $# -ge 2 ]] || fail "--smoketest-root requires a path"
      smoketest_root="$(cd "$2" && pwd)"
      shift 2
      ;;
    --artifact-root)
      [[ $# -ge 2 ]] || fail "--artifact-root requires a path"
      artifact_root="$2"
      shift 2
      ;;
    --base-port)
      [[ $# -ge 2 ]] || fail "--base-port requires a port"
      base_port="$2"
      shift 2
      ;;
    --time-budget-minutes)
      [[ $# -ge 2 ]] || fail "--time-budget-minutes requires a value"
      time_budget_minutes="$2"
      shift 2
      ;;
    --mcp-ref)
      [[ $# -ge 2 ]] || fail "--mcp-ref requires a ref"
      mcp_ref="$2"
      shift 2
      ;;
    --setup-client)
      [[ $# -ge 2 ]] || fail "--setup-client requires auto, always, or never"
      setup_client="$2"
      shift 2
      ;;
    --max-workers)
      [[ $# -ge 2 ]] || fail "--max-workers requires a value"
      max_workers="$2"
      shift 2
      ;;
    --gradle-heap)
      [[ $# -ge 2 ]] || fail "--gradle-heap requires a value"
      gradle_heap="$2"
      shift 2
      ;;
    --cultac-gradle-heap)
      [[ $# -ge 2 ]] || fail "--cultac-gradle-heap requires a value"
      cultac_gradle_heap="$2"
      shift 2
      ;;
    --mcp-tool-heap)
      [[ $# -ge 2 ]] || fail "--mcp-tool-heap requires a value"
      mcp_tool_heap="$2"
      shift 2
      ;;
    --smoketest-jvm-heap)
      [[ $# -ge 2 ]] || fail "--smoketest-jvm-heap requires a value"
      smoketest_jvm_heap="$2"
      shift 2
      ;;
    --server-jvm-heap)
      [[ $# -ge 2 ]] || fail "--server-jvm-heap requires a value"
      server_jvm_heap="$2"
      shift 2
      ;;
    --keep-gradle-daemons)
      stop_gradle_daemons="false"
      shift
      ;;
    --skip-cultac-build)
      skip_cultac_build="true"
      shift
      ;;
    --plugin-jar)
      [[ $# -ge 2 ]] || fail "--plugin-jar requires a path"
      plugin_jar="$(realpath "$2")"
      skip_cultac_build="true"
      shift 2
      ;;
    --no-xvfb)
      use_xvfb="false"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

if [[ -n "$plugin_jar" ]]; then
  plugin_jar="$(realpath "$plugin_jar")"
  skip_cultac_build="true"
fi

[[ -d "$smoketest_root" ]] || fail "missing mcp-client-smoketest repo: $smoketest_root"
smoketest_root="$(cd "$smoketest_root" && pwd)"
[[ -x "$cultac_root/gradlew" ]] || fail "missing CultAC Gradle wrapper: $cultac_root/gradlew"
[[ -x "$smoketest_root/gradlew" ]] || fail "missing smoketest Gradle wrapper: $smoketest_root/gradlew"

case "$base_port" in
  ''|*[!0-9]*) fail "base port must be numeric" ;;
esac
case "$time_budget_minutes" in
  ''|*[!0-9]*) fail "time budget must be a whole number of minutes" ;;
esac
case "$max_workers" in
  ''|*[!0-9]*) fail "max workers must be a whole number" ;;
esac
case "$setup_client" in
  auto|always|never) ;;
  *) fail "--setup-client must be auto, always, or never" ;;
esac
case "$stop_gradle_daemons" in
  true|false) ;;
  *) fail "SMOKETEST_STOP_GRADLE_DAEMONS must be true or false" ;;
esac

mkdir -p "$artifact_root"
log_file="$artifact_root/local-all-smoketests.log"
summary_file="$artifact_root/local-all-smoketests-summary.txt"
touch "$log_file"

exec > >(tee -a "$log_file") 2>&1

start_epoch="$(date +%s)"
deadline_epoch=$((start_epoch + (time_budget_minutes * 60)))
declare -a phase_summaries=()
overall_status=0
last_phase_status=0

remaining_seconds() {
  local now
  now="$(date +%s)"
  local remaining=$((deadline_epoch - now))
  if (( remaining < 1 )); then
    remaining=1
  fi
  printf '%s\n' "$remaining"
}

elapsed_seconds() {
  local now
  now="$(date +%s)"
  printf '%s\n' "$((now - start_epoch))"
}

find_cult_dev_jar() {
  find "$cultac_root/build/libs" -maxdepth 1 -name 'CultAC-dev.jar' -print -quit 2>/dev/null || true
}

use_xvfb_for_real_client() {
  if [[ "$use_xvfb" == "false" ]]; then
    return 1
  fi
  command -v xvfb-run >/dev/null 2>&1
}

run_phase() {
  local phase_name="$1"
  shift
  local phase_start phase_status phase_elapsed timeout_seconds
  phase_start="$(date +%s)"
  timeout_seconds="$(remaining_seconds)"

  echo
  echo "== $phase_name =="
  echo "timeout: ${timeout_seconds}s"
  set +e
  timeout -k 30s "${timeout_seconds}s" "$@"
  phase_status=$?
  set -e
  last_phase_status="$phase_status"
  phase_elapsed="$(( $(date +%s) - phase_start ))"

  if [[ "$phase_status" -eq 0 ]]; then
    phase_summaries+=("$phase_name: PASS (${phase_elapsed}s)")
  else
    phase_summaries+=("$phase_name: FAIL exit=$phase_status (${phase_elapsed}s)")
    overall_status=1
  fi
  return 0
}

run_real_client_gradle_phase() {
  local phase_name="$1"
  shift
  if use_xvfb_for_real_client; then
    run_phase "$phase_name" xvfb-run -a -s "-screen 0 1280x720x24" "$@"
  else
    run_phase "$phase_name" "$@"
  fi
}

mcp_client_jar="$smoketest_root/.real-validation/toolchains/MCP-Reborn/projects/mcp/build/mcp/stripClient/output.jar"
client_setup_needed="false"
if [[ "$setup_client" == "always" || ( "$setup_client" == "auto" && ! -f "$mcp_client_jar" ) ]]; then
  client_setup_needed="true"
fi
if [[ "$setup_client" == "never" && ! -f "$mcp_client_jar" ]]; then
  fail "--setup-client never was set but the MCP-Reborn client cache is missing: $mcp_client_jar"
fi
if [[ -f "$mcp_client_jar" && "$setup_client" != "always" ]]; then
  if ! python3 "$script_dir/verify-smoketest-client.py" \
      "$smoketest_root/.real-validation/toolchains/MCP-Reborn" "$mcp_ref" \
      > "$artifact_root/client-identity.json"; then
    [[ "$setup_client" != "never" ]] || fail "cached client identity does not match --mcp-ref $mcp_ref"
    client_setup_needed="true"
  fi
fi
client_setup_args=(setupSmoketestClient --no-daemon --console=plain --stacktrace -Psmoketest.mcp.ref="$mcp_ref")

gradle_env_cmd=(env "GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx${gradle_heap} -Dorg.gradle.workers.max=${max_workers}")
cultac_gradle_env_cmd=(env "GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx${cultac_gradle_heap} -Dorg.gradle.workers.max=${max_workers}")
mcp_setup_gradle_env_cmd=(
  env
  "GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx${gradle_heap} -Dorg.gradle.workers.max=${max_workers}"
  "JAVA_TOOL_OPTIONS=-Xmx${mcp_tool_heap}"
)
real_validation_env_cmd=(
  env
  "GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx${gradle_heap} -Dorg.gradle.workers.max=${max_workers}"
  "REAL_VALIDATION_SERVER_JVM_XMS=$server_jvm_xms"
  "REAL_VALIDATION_SERVER_JVM_HEAP=$server_jvm_heap"
)

echo "local all-smoketests workflow"
echo "cultac: $cultac_root"
echo "smoketest: $smoketest_root"
echo "artifact root: $artifact_root"
echo "base port: $base_port"
echo "time budget: ${time_budget_minutes}m"
echo "mcp ref: $mcp_ref"
echo "setup client mode: $setup_client"
echo "max workers: $max_workers"
echo "smoketest Gradle heap: $gradle_heap"
echo "CultAC Gradle heap: $cultac_gradle_heap"
echo "MCP setup tool heap: $mcp_tool_heap"
echo "smoketest JavaExec heap: $smoketest_jvm_heap"
echo "Paper server heap: Xms=$server_jvm_xms Xmx=$server_jvm_heap"
echo "stop stale Gradle daemons: $stop_gradle_daemons"
echo "xvfb: $use_xvfb"

if [[ "$stop_gradle_daemons" == "true" ]]; then
  echo
  echo "== stop stale Gradle daemons =="
  "${gradle_env_cmd[@]}" "$smoketest_root/gradlew" -p "$smoketest_root" --stop --max-workers="$max_workers" || true
  "${cultac_gradle_env_cmd[@]}" "$cultac_root/gradlew" -p "$cultac_root" --stop --max-workers="$max_workers" || true
  phase_summaries+=("stop stale Gradle daemons: BEST_EFFORT")
fi

if [[ "$client_setup_needed" == "true" ]]; then
  run_phase "setup MCP-Reborn client workspace" \
    "${mcp_setup_gradle_env_cmd[@]}" \
    "$smoketest_root/gradlew" -p "$smoketest_root" "${client_setup_args[@]}" --max-workers="$max_workers"
else
  echo
  echo "== setup MCP-Reborn client workspace =="
  echo "skipped; using cached client jar at $mcp_client_jar"
  phase_summaries+=("setup MCP-Reborn client workspace: SKIP")
fi

python3 "$script_dir/verify-smoketest-client.py" \
  "$smoketest_root/.real-validation/toolchains/MCP-Reborn" "$mcp_ref" \
  > "$artifact_root/client-identity.json" || fail "client setup did not produce the requested client"

if [[ "$skip_cultac_build" == "true" ]]; then
  cult_dev_jar="${plugin_jar:-$(find_cult_dev_jar)}"
  [[ -n "$cult_dev_jar" ]] || fail "--skip-cultac-build was set but no *-dev.jar exists under $cultac_root/build/libs"
  echo
  echo "== build CultAC dev jar =="
  echo "skipped; using $cult_dev_jar"
  phase_summaries+=("build CultAC dev jar: SKIP")
else
  run_phase "build CultAC dev jar" \
    "${cultac_gradle_env_cmd[@]}" \
    "$cultac_root/gradlew" -p "$cultac_root" devShadowJar --rerun-tasks --no-daemon --console=plain --stacktrace --max-workers="$max_workers"
  [[ "$last_phase_status" -eq 0 ]] || fail "CultAC build failed; refusing to validate a stale jar"
  cult_dev_jar="$(find_cult_dev_jar)"
fi

[[ -n "${cult_dev_jar:-}" ]] || fail "no CultAC dev jar found under $cultac_root/build/libs"
[[ -f "$cult_dev_jar" ]] || fail "plugin jar does not exist: $cult_dev_jar"
sha256sum "$cult_dev_jar" > "$artifact_root/plugin.sha256"

common_gradle_args=(
  --no-daemon
  --console=plain
  --stacktrace
  --max-workers="$max_workers"
  -Psmoketest.jvmHeap="$smoketest_jvm_heap"
  -Psmoketest.grim.devJar="$cult_dev_jar"
  -Psmoketest.cultac.repoRoot="$cultac_root"
)

run_phase "prepare smoketest server workspace" \
  "${gradle_env_cmd[@]}" \
  "$smoketest_root/gradlew" -p "$smoketest_root" setupSmoketestServer writeSmoketestWorkspaceManifest "${common_gradle_args[@]}"

run_phase "harness guardrails" \
  "${gradle_env_cmd[@]}" \
  "$smoketest_root/gradlew" -p "$smoketest_root" test verifyVanillaMovementSources verifyVanillaHarnessSources verifyNoHardcodedSmoketestAbsolutePaths "${common_gradle_args[@]}"

run_real_client_gradle_phase "deduplication verification" \
  "${real_validation_env_cmd[@]}" \
  "$smoketest_root/gradlew" -p "$smoketest_root" deduplicationVerification "${common_gradle_args[@]}" \
  -Psmoketest.artifactRoot="$artifact_root/deduplication"

run_real_client_gradle_phase "combat reach smoketest" \
  "${real_validation_env_cmd[@]}" \
  "$smoketest_root/gradlew" -p "$smoketest_root" smoketestCombatReach "${common_gradle_args[@]}" \
  -Psmoketest.basePort="$base_port" \
  -Psmoketest.artifactRoot="$artifact_root/combat-reach"

run_real_client_gradle_phase "full Java movement smoketest" \
  "${real_validation_env_cmd[@]}" \
  "$smoketest_root/gradlew" -p "$smoketest_root" smoketestRun -x smoketestCombatReach "${common_gradle_args[@]}" \
  -Psmoketest.suite=ci-full \
  -Psmoketest.basePort="$((base_port + 20))" \
  -Psmoketest.artifactRoot="$artifact_root/java-movement"

{
  echo "totalElapsedSeconds=$(elapsed_seconds)"
  echo "artifactRoot=$artifact_root"
  echo "cultDevJar=$cult_dev_jar"
  echo "maxWorkers=$max_workers"
  echo "smoketestJvmHeap=$smoketest_jvm_heap"
  echo "serverJvmHeap=$server_jvm_heap"
  echo "--- phases ---"
  printf '%s\n' "${phase_summaries[@]}"
} | tee "$summary_file"

echo
echo "summary: $summary_file"
exit "$overall_status"
