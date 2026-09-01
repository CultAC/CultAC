#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
live_root="${GRIM_PARITY_LIVE_ROOT:-"$repo_root/../CultAC/mcp-client-smoketest"}"
artifact_dir="${GRIM_PARITY_ARTIFACT_DIR:?GRIM_PARITY_ARTIFACT_DIR is required}"
agent="${GRIM_PARITY_AGENT:-"$repo_root/parity/build/libs/grim-parity-agent.jar"}"

# Paper changes its working directory to a per-trial server directory.  The
# agent and the live runner must therefore receive the same absolute artifact
# path; a relative path would leave the agent's JSONL beside the temporary
# server instead of beside result.json.
artifact_dir="$(realpath -m -- "$artifact_dir")"

if [[ ! -x "$live_root/gradlew" ]]; then
    echo "MCP/Paper live workspace is missing: $live_root" >&2
    exit 2
fi
if [[ ! -f "$agent" ]]; then
    echo "parity agent jar is missing: $agent" >&2
    exit 2
fi

export GRIM_PARITY_AGENT="$agent"
export GRIM_PARITY_AGENT_ARGUMENTS="inventory=$artifact_dir/inventory.jsonl;trace=$artifact_dir/semantic-trace.jsonl;methodTrace=full"
export GRIM_PARITY_OPTIONAL_STUBS="${GRIM_PARITY_OPTIONAL_STUBS:-true}"
export GRIM_PARITY_DISABLE_SAFETY_AGENT="${GRIM_PARITY_DISABLE_SAFETY_AGENT:-true}"

heap="${REAL_VALIDATION_SERVER_JVM_HEAP:-4g}"
cd "$live_root"
exec ./gradlew grimParityLive --no-daemon --console=plain "-Psmoketest.jvmHeap=$heap"
