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
network=(--network=none)
egress=""
cleanup() {
  if [[ -n "$egress" ]]; then
    echo "Egress proxy log:"
    docker logs "$egress" 2>&1 | tail -n 200 || true
    docker rm -f "$egress" > /dev/null 2>&1 || true
    docker network rm "$egress" > /dev/null 2>&1 || true
  fi
  echo "::$marker::"
}
trap cleanup EXIT
if [[ "$suite" = bedrock ]]; then
  # The pinned Geyser fetches Bedrock auth discovery when it starts and shuts down without it.
  # The Bedrock sandbox joins an internal network whose only route out is an allowlisting proxy.
  egress="cultac-egress-${sandbox##*.}"
  docker network create --internal "$egress" > /dev/null
  docker run -d --init --read-only --name "$egress" \
    --user "$(id -u):$(id -g)" \
    --cap-drop=ALL --security-opt=no-new-privileges:true \
    --pids-limit=64 --memory=256m --cpus=0.5 \
    --mount "type=bind,src=$GITHUB_WORKSPACE/ci/.github/scripts/egress-proxy.py,dst=/egress-proxy.py,readonly" \
    cultac-smoketest python3 -I /egress-proxy.py 3128 > /dev/null
  docker network connect "$egress" "$egress"
  network=(--network "$egress" --env "CULTAC_EGRESS_PROXY=$egress:3128")
fi
# Docker mounts tmpfs noexec unless asked; the JVMs load native libraries (SQLite, JLine) from /tmp.
docker run --rm --init --read-only "${network[@]}" \
  --user "$(id -u):$(id -g)" \
  --cap-drop=ALL --security-opt=no-new-privileges:true \
  --pids-limit=1024 --memory=6g --memory-swap=6g --cpus=2 \
  --tmpfs /tmp:rw,exec,nosuid,size=1g \
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
