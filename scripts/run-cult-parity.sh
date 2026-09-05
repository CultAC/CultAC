#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
audited_baseline="73a835d04f9bfbc76dc1d41c24ea73f3aec2280c"
baseline_ref="$audited_baseline"
checks=""
artifact_root=""

usage() {
    echo "usage: $0 --baseline-ref REF --checks all --artifact-root PATH" >&2
}

while (($# > 0)); do
    case "$1" in
        --baseline-ref)
            (($# >= 2)) || { usage; exit 2; }
            baseline_ref="$2"
            shift 2
            ;;
        --checks)
            (($# >= 2)) || { usage; exit 2; }
            checks="$2"
            shift 2
            ;;
        --artifact-root)
            (($# >= 2)) || { usage; exit 2; }
            artifact_root="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "unknown argument: $1" >&2
            usage
            exit 2
            ;;
    esac
done

if [[ "$checks" != "all" ]]; then
    echo "cultParity requires --checks all for final execution" >&2
    exit 2
fi
if [[ -z "$artifact_root" ]]; then
    echo "--artifact-root is required" >&2
    exit 2
fi
if [[ "$baseline_ref" != "$audited_baseline" ]]; then
    echo "cultParity baseline is locked to audited commit $audited_baseline" >&2
    exit 2
fi

if ! resolved_baseline="$(git -C "$repo_root" rev-parse --verify "${baseline_ref}^{commit}" 2>/dev/null)"; then
    echo "audited cultParity baseline commit is unavailable: $baseline_ref" >&2
    exit 2
fi
if [[ "$resolved_baseline" != "$audited_baseline" ]]; then
    echo "cultParity baseline resolved to $resolved_baseline, expected $audited_baseline" >&2
    exit 2
fi

artifact_root="$(realpath -m -- "$artifact_root")"
case "$artifact_root" in
    "$repo_root")
        echo "--artifact-root must not be the repository root" >&2
        exit 2
        ;;
    "$repo_root"/*)
        artifact_relative="${artifact_root#"$repo_root"/}"
        if ! git -C "$repo_root" check-ignore -q -- "$artifact_relative"; then
            echo "--artifact-root inside the repository must be git-ignored: $artifact_root" >&2
            exit 2
        fi
        ;;
esac
mkdir -p "$artifact_root/build-logs" "$artifact_root/jars" "$artifact_root/inventory"

current_commit="$(git -C "$repo_root" rev-parse HEAD)"
baseline_parent="$(mktemp -d "${TMPDIR:-/tmp}/cult-parity-worktree.XXXXXX")"
baseline_worktree="$baseline_parent/source"

cleanup_baseline_worktree() {
    if [[ -d "$baseline_worktree" ]]; then
        git -C "$repo_root" worktree remove --force "$baseline_worktree" \
            >/dev/null 2>&1 || true
    fi
    if [[ -d "$baseline_parent" ]]; then
        rmdir -- "$baseline_parent" >/dev/null 2>&1 || true
    fi
}
trap cleanup_baseline_worktree EXIT

git -C "$repo_root" worktree add --detach "$baseline_worktree" "$baseline_ref" \
    >"$artifact_root/build-logs/worktree.log" 2>&1
baseline_commit="$(git -C "$baseline_worktree" rev-parse HEAD)"
if [[ "$baseline_commit" != "$audited_baseline" ]]; then
    echo "detached baseline worktree is $baseline_commit, expected $audited_baseline" >&2
    exit 1
fi

git -C "$repo_root" status --porcelain=v1 >"$artifact_root/current-git-status.txt"
git -C "$repo_root" diff --no-ext-diff | sha256sum | awk '{print $1}' \
    >"$artifact_root/current-unstaged-diff.sha256"
git -C "$repo_root" diff --cached --no-ext-diff | sha256sum | awk '{print $1}' \
    >"$artifact_root/current-staged-diff.sha256"
untracked_manifest="$artifact_root/current-untracked-files.manifest"
(
    cd "$repo_root"
    while IFS= read -r -d '' untracked_path; do
        printf '%s\0' "$untracked_path"
        git hash-object --no-filters -- "$untracked_path" | tr '\n' '\0'
    done < <(git ls-files --others --exclude-standard -z | LC_ALL=C sort -z)
) >"$untracked_manifest"
sha256sum "$untracked_manifest" | awk '{print $1}' \
    >"$artifact_root/current-untracked-files.sha256"

(
    cd "$baseline_worktree"
    ./gradlew :bukkit:shadowJar --no-daemon --console=plain
) >"$artifact_root/build-logs/baseline-build.log" 2>&1

baseline_jar_candidates=()
while IFS= read -r jar; do
    baseline_jar_candidates+=("$jar")
done < <(find "$baseline_worktree/bukkit/build/libs" -maxdepth 1 -type f -name 'grimac-bukkit-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort)
if ((${#baseline_jar_candidates[@]} != 1)); then
    echo "expected exactly one baseline runtime jar, found ${#baseline_jar_candidates[@]}" >&2
    printf '%s\n' "${baseline_jar_candidates[@]}" >&2
    exit 1
fi
cp -- "${baseline_jar_candidates[0]}" "$artifact_root/jars/baseline.jar"

(
    cd "$repo_root"
    ./gradlew :bukkit:devShadowJar --rerun-tasks --no-daemon --console=plain
) >"$artifact_root/build-logs/current-build.log" 2>&1

current_jar_candidates=()
while IFS= read -r jar; do
    current_jar_candidates+=("$jar")
done < <(find "$repo_root/build/libs" -maxdepth 1 -type f -name '*-dev.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort)
if ((${#current_jar_candidates[@]} != 1)); then
    echo "expected exactly one current dev runtime jar, found ${#current_jar_candidates[@]}" >&2
    printf '%s\n' "${current_jar_candidates[@]}" >&2
    exit 1
fi
cp -- "${current_jar_candidates[0]}" "$artifact_root/jars/current.jar"

(
    cd "$repo_root"
    ./gradlew :parity:cultParityAgentJar --no-daemon --console=plain
) >"$artifact_root/build-logs/agent-build.log" 2>&1
agent="$repo_root/parity/build/libs/cult-parity-agent.jar"
test -f "$agent"

run_inventory_probe() {
    local side="$1"
    local jar="$2"
    local port="$3"
    local probe_root="$artifact_root/inventory-probes/$side"
    mkdir -p "$probe_root"
    CULT_PARITY_MODE=live \
    CULT_PARITY_SIDE="$side" \
    CULT_PARITY_CHECK=cult.parity.inventory \
    CULT_PARITY_PATH=inventory \
    CULT_PARITY_PROFILE=zero-latency \
    CULT_PARITY_SEED=9001 \
    CULT_PARITY_JAR="$jar" \
    CULT_PARITY_ARTIFACT_DIR="$probe_root" \
    CULT_PARITY_ACTION_TAPE=spawn-stationary \
    CULT_PARITY_VALIDITY_GATE='scenario-passed;scenario-valid' \
    CULT_PARITY_EXPECTED_OUTCOME=inventory \
    CULT_PARITY_NETWORK_SCHEDULE='[{"direction":"inbound","baseMillis":0,"jitterMillis":0},{"direction":"outbound","baseMillis":0,"jitterMillis":0}]' \
    CULT_PARITY_PORT="$port" \
    CULT_PARITY_AGENT="$agent" \
    CULT_PARITY_DISABLE_SAFETY_AGENT=true \
    "$repo_root/scripts/run-cult-parity-live.sh" \
        >"$probe_root/runner.log" 2>&1
    jq -e '.valid == true' "$probe_root/result.json" >/dev/null
    test -s "$probe_root/inventory.jsonl"
    cp -- "$probe_root/inventory.jsonl" "$artifact_root/inventory/$side.jsonl"
    cp -- "$probe_root/validity-evidence.json" "$artifact_root/inventory/$side-validity-evidence.json"
}

run_inventory_probe baseline "$artifact_root/jars/baseline.jar" 28101
run_inventory_probe current "$artifact_root/jars/current.jar" 28102

(
    cd "$repo_root"
    ./gradlew :parity:cultParity --no-daemon --console=plain \
        -PcultParity.checks="$checks" \
        -PcultParity.baselineRef="$baseline_ref" \
        -PcultParity.baselineCommit="$baseline_commit" \
        -PcultParity.currentCommit="$current_commit" \
        -PcultParity.artifactRoot="$artifact_root" \
        -PcultParity.baselineJar="$artifact_root/jars/baseline.jar" \
        -PcultParity.currentJar="$artifact_root/jars/current.jar" \
        -PcultParity.baselineSource="$baseline_worktree/common/src/main/java" \
        -PcultParity.currentSource="$repo_root/common/src/main/java" \
        -PcultParity.baselineInventory="$artifact_root/inventory/baseline.jsonl" \
        -PcultParity.currentInventory="$artifact_root/inventory/current.jsonl" \
        -PcultParity.scenarioCatalog="$repo_root/parity/scenarios/shared-check-scenarios.json" \
        -PcultParity.mappingFile="$repo_root/parity/config/check-equivalence-mappings.tsv" \
        -PcultParity.classificationFile="$repo_root/parity/config/check-classifications.tsv" \
        -PcultParity.repositoryRoot="$repo_root" \
        -PcultParity.liveCommand="$repo_root/scripts/run-cult-parity-live.sh"
) >"$artifact_root/build-logs/parity-run.log" 2>&1
