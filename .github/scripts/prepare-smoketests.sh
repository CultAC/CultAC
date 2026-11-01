#!/usr/bin/env bash
# Host-only bootstrap. Do not check out, build, load, or run the tested CultAC here.
set -euo pipefail

case "${1:-}" in
  java)
    cd mcp-client-smoketest
    client=.real-validation/toolchains/MCP-Reborn/projects/mcp/build/mcp/stripClient/output.jar
    libraries=.real-validation/toolchains/MCP-Reborn/projects/mcp/build/mcp/listLibraries/libraries.txt
    natives=.real-validation/lwjgl-natives/linux-x64
    if [[ ! -f "$client" || ! -f "$libraries" || ! -d "$natives" ]] ||
        ! find "$natives" -name '*.so' -type f -print -quit | grep -q .; then
      JAVA_TOOL_OPTIONS=-Xmx4g ./gradlew setupSmoketestClient \
        --no-daemon --console=plain --stacktrace --max-workers=2 \
        -Psmoketest.mcp.ref=26.2
    fi
    ./gradlew setupSmoketestServer classes packetControlPluginJar \
      --no-daemon --console=plain --stacktrace --max-workers=2
    ;;
  bedrock)
    replay/gradlew -p replay assemble --no-daemon --console=plain --max-workers=2
    ;;
  *) echo 'Expected java or bedrock' >&2; exit 2 ;;
esac
