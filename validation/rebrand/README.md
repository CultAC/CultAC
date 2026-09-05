# CultAC rebrand validation

Baseline: `9f9899174` (2026-09-04). The complete baseline smoke run finished before
any tracked file was edited; all 1,136 tracked file hashes were checked at that boundary.
The initial attempt selected a stale dev jar and was discarded. The captured baseline
uses the freshly built jar identified in `jar-sha256.json`.

## Scope

Owned implementation packages, classes, build conventions, artifacts, commands,
permissions, placeholders, localized configuration, messages, and documentation use
CultAC/Cult naming. `/cult` is primary; `/cultac`, `/grim`, and `/grimac` are aliases.
The generated Bukkit descriptor is named `CultAC` and provides `GrimAC`.

The published `ac.grim.grimac.api` and `ac.grim.grimac.internal` dependencies retain
their original binary identity, public methods, and logical storage IDs. Historical
migration schema names, copyright attribution, real upstream documentation links,
and existing upstream update/paste services remain intact. Default physical database
names and configuration branding use CultAC. See the root README for migration notes.

No anticheat behavior was repaired or relaxed. Runtime Java source matches the
mechanical rebrand apart from the requested command aliases. The parity diagnostic
tools also recognize historical Grim class names and retain the old baseline jar
filename; their decision rules and simulation behavior were not changed.

## Evidence

- `runtime-source-audit.json`: 945 runtime/test Java files checked against the
  mechanical rename; only 19 command files have the requested alias additions.
- `test-comparison.json`: all 366 common test outcomes and all 104 replay test
  outcomes are identical before/after. Each suite has the same one failed replay
  test and six skipped tests.
- `harness-test-comparison.json`: all 155 harness test outcomes are identical,
  including the three existing failures.
- `api-binary-comparison.json`: every API/internal class is retained (553 in the
  development jar, 524 in the production jar). Their bytes are identical after
  normalizing the shaded dependency package prefix.
- `api-before.log.gz` and `api-after.log.gz`: the exact same independently compiled
  `GrimApiProbe.jar` loaded successfully and received typed and legacy Bukkit reload
  events on Paper 26.2. It was compiled against GrimAPI 1.6.0.9, declares
  `depend: [GrimAC]`, and contains no CultAC classes. Its source and standalone build
  are in `../grimapi-compat/`. Both `/cult reload` and `/grim reload` were exercised;
  `/cultac` and `/grimac` help and legacy history syntax were checked as well.
- `jar-sha256.json`: exact jar identities used for validation.
- `before-smoke-summary.txt` and `before-smoke.log.gz`: baseline smoke capture.

Final after-smoke capture: deduplication **PASS**, combat/reach **PASS** in both
bundled and unbundled modes, with no violations in either combat segment. The
wrapper exits 1 because the same three harness tests fail. Baseline deduplication
passed; baseline bundled combat recorded five Simulation flags. No simulation fix
was made; the live executions have timing-dependent outcomes.

`after-smoke-summary.txt` and `after-smoke.log.gz` contain the final artifact run.
The two `*-combat-summary.txt` files preserve the individual combat outcomes.
The final run took 552 seconds; the baseline took 546 seconds.

`./gradlew build :common:offlineBedrockReplayTest --continue` completed with only the
same two failing test tasks. Production and development jar builds succeeded.
The 13 parity-tool unit tests passed. Existing failures were left unchanged.

## Reproducing the smoke comparison

The external MCP/Paper harness had pre-existing local changes. Its captured source
was copied, leaving the original workspace untouched. `harness-brand-only.patch.gz`
contains the name substitutions required for CultAC log markers, configuration
paths, plugin channels, JVM properties, and reflection targets. Apply it from the
root of a copy of that harness with `gzip -dc PATH | patch -p0`. Preserve its cached
Paper/MCP toolchains and `build/libs/smoketest-packet-control-plugin.jar` fixture.

`harness-source-audit.json` verifies all 130 harness Java files have identical
nonliteral code, apart from renamed package/import declarations in safety fixtures.
No assertion, numeric bound, scenario, or validation branch was removed or weakened.

Run the repository wrapper with `--smoketest-root PATH --artifact-root PATH` against
the adapted harness. The final capture uses the built `CultAC-dev.jar` without
rebuilding it during validation. Raw run artifacts are also retained locally under
`/home/hunter/Downloads/cultac-rebrand-validation/`.

## Version control

All package/file moves and the probe/evidence are included in the rebrand change. Git pairs all 961 moved
files with `git diff --cached --find-renames=20%`; its default similarity threshold
shows tiny files with completely renamed imports as additions/deletions. The user's
pre-existing untracked `logs/` directory was left untouched.
