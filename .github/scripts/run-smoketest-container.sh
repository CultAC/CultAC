#!/usr/bin/env bash
set -euo pipefail
suite="${1:-}"
case "$suite" in java|bedrock) ;; *) echo 'Expected java or bedrock' >&2; exit 2 ;; esac

sandbox="$(mktemp -d "$RUNNER_TEMP/cultac-smoketest.XXXXXXXX")"
echo "path=$sandbox" >> "$GITHUB_OUTPUT"
mkdir -p "$sandbox/workspace/ci/.github" "$sandbox/home/.gradle"
cp -a --reflink=auto "$GITHUB_WORKSPACE/ci/.github/scripts" "$sandbox/workspace/ci/.github/"
mkdir -p "$sandbox/workspace/ci/scripts/smoketests"
cp "$GITHUB_WORKSPACE/ci/scripts/smoketests/bedrock-replay-runtime.json" \
  "$sandbox/workspace/ci/scripts/smoketests/"
cp -a --reflink=auto "$GITHUB_WORKSPACE/cultac" "$sandbox/workspace/"
if [[ "$suite" = java ]]; then
  cp -a --reflink=auto "$GITHUB_WORKSPACE/mcp-client-smoketest" "$sandbox/workspace/"
else
  cp -a --reflink=auto "$GITHUB_WORKSPACE/replay" "$GITHUB_WORKSPACE/scenarios" "$sandbox/workspace/"
fi
for directory in caches wrapper; do
  if [[ -d "$HOME/.gradle/$directory" ]]; then
    # Real copies, never hard links or writable mounts of the shared host cache.
    cp -a --reflink=auto "$HOME/.gradle/$directory" "$sandbox/home/.gradle/"
  fi
done

# Keep original absolute paths for the prepared MCP library list. Only disposable
# copies are mounted. No runner directories, Docker socket, tokens, or secret env.
# Disable workflow commands so malicious build/plugin stdout cannot alter the job.
marker="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
echo "::stop-commands::$marker"
trap 'echo "::$marker::"' EXIT
docker run --rm --init --read-only \
  --user "$(id -u):$(id -g)" \
  --cap-drop=ALL --security-opt=no-new-privileges:true \
  --pids-limit=1024 --memory=6g --memory-swap=6g --cpus=2 \
  --tmpfs /tmp:rw,nosuid,size=1g \
  --mount "type=bind,src=$sandbox/workspace,dst=$GITHUB_WORKSPACE" \
  --mount "type=bind,src=$sandbox/home,dst=$HOME" \
  --workdir "$GITHUB_WORKSPACE" \
  --env "HOME=$HOME" --env "GRADLE_USER_HOME=$HOME/.gradle" \
  --env "JAVA_TOOL_OPTIONS=-Duser.home=$HOME" \
  --env "GITHUB_WORKSPACE=$GITHUB_WORKSPACE" \
  --env 'GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx2g -Dorg.gradle.workers.max=2' \
  --env REAL_VALIDATION_SERVER_JVM_XMS=512m \
  --env REAL_VALIDATION_SERVER_JVM_HEAP=1536m \
  cultac-smoketest bash ci/.github/scripts/run-selected-smoketests.sh "$suite"
