#!/usr/bin/env bash
# Builds only public PR inputs; no private fixtures, cached secrets or host tokens.
set -euo pipefail
sandbox="$(mktemp -d "$RUNNER_TEMP/cultac-pr-build.XXXXXXXX")"
mkdir -p "$sandbox/workspace" "$sandbox/home/.gradle"
cp -a --reflink=auto "$GITHUB_WORKSPACE/cultac" "$sandbox/workspace/"
marker="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
echo "::stop-commands::$marker"
cleanup() {
  echo "::$marker::"
  rm -rf -- "$sandbox"
}
trap cleanup EXIT
# Network access is required here for Gradle dependencies; the container receives
# no private repos, repo tokens, host Gradle cache, Docker socket or runner home.
docker run --rm --init --read-only \
  --user "$(id -u):$(id -g)" \
  --cap-drop=ALL --security-opt=no-new-privileges:true \
  --pids-limit=512 --memory=6g --memory-swap=6g --cpus=2 \
  --tmpfs /tmp:rw,nosuid,size=1g \
  --mount "type=bind,src=$sandbox/workspace,dst=$GITHUB_WORKSPACE" \
  --mount "type=bind,src=$sandbox/home,dst=$HOME" \
  --workdir "$GITHUB_WORKSPACE" \
  --env "HOME=$HOME" --env "GRADLE_USER_HOME=$HOME/.gradle" \
  --env "JAVA_TOOL_OPTIONS=-Duser.home=$HOME" \
  --env 'GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx2g -Dorg.gradle.workers.max=2' \
  cultac-smoketest bash -c \
  'cultac/gradlew -p cultac :bukkit:devShadowJar :bukkit:shadowJar --no-daemon --console=plain --max-workers=2'
