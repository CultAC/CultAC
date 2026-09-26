# Smoketest reach and chunk lifecycle validation

The failing job checked out smoketest commit
`90b7ab41dd9895c9c2a55a303dacde44896f1fb4` and passed `--target grim-dev`.
That harness recognized `cult-dev`, and treated the old name as an empty custom
target with scenario-only evaluation. The workflow now uses `cult-dev` for
both core movement and combat reach.

The corresponding smoketest fix restores the legacy target aliases, rejects
unconfigured target names, uses the current console prediction-debug command,
and synchronizes world binding refresh with the vanilla client tick.
It was pushed first as smoketest commit
`f39c7a1be99ff3e640d52da29f82a599eb6b21cf`.

Minecraft 26.2 `ParticleEngine.setLevel` clears the particle groups that
`ParticleEngine.tick` iterates. Vanilla calls it on world changes through
`Minecraft.updateLevelInEngines`; the harness had also called it on every
readiness poll, concurrently with the combat ticker. The refresh now uses the
existing client context lock and preserves particle state in an unchanged world.

## Verified runs

Built CultAC a677278 with `./gradlew :bukkit:shadowJar` from a clean worktree.
Used MCP-Reborn 26.2, Paper 26.2 build 111, and Java 25.

- Full harness unit and source guardrails: 160 tests passed.
- Final target-resolution and particle concurrency regression tests: passed.
- Combat reach, 8 bots, seed 424242, 1000-tick cap, vehicles/pistons off:
  both bundled and unbundled passed, with 31 and 28 attack samples respectively.
  Maximum reach excess was zero in both modes; no anticheat flags were recorded.
  CI thresholds were used unchanged.
- Six focused reach scenarios: all passed with `scenarioValid=true` and
  `cultValid=true`.
- Full default 32-client chunk deduplication run: 1,153 snapshot validations,
  no block/inventory mismatches, no movement/reach flags, and all rewrite,
  reload, dimension-change, respawn, and disconnect phases passed. After the
  final disconnect: active players, section references, and deduplicated
  sections were all zero.

The full 360-scenario core suite is a separate validation target; these runs
verify combat reach, its focused scenarios, and chunk lifecycle behavior.
