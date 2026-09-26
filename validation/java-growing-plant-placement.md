# Java growing-plant placement

## Cause and change

`NmsClientInteraction` previously rejected every `GrowingPlantHeadBlock` placement
because the client's private random generator chooses an initial age that a
detached predictor cannot reproduce. This also suppressed the predictable block
placement and inventory decrement. The native client placed weeping/twisting
vines and consumed an item while Cult retained air and the original stack count.

Remove that early return and use the existing native placement path. Support,
head/body conversion, geometry, and inventory consumption remain native behavior.
No movement check or simulation uncertainty changes.

The smoketest snapshot comparison treats initial plant ages 0–24 as equivalent.
All other properties and the block type remain exact. Age 25 remains distinct
because it changes client shears behavior. Crop and berry-bush ages remain exact.
There is no additional age metadata in block predictions or compensated worlds.

## Vanilla source evidence

MCP-Reborn 26.2, commit `727d72ffc66bcdf1a8c16ee92b120db2eaa46e26`:

- `world/level/block/GrowingPlantBlock.java:35`: chooses head versus body from
  the neighboring block in the growth direction; support is checked at line 47.
- `world/level/block/GrowingPlantHeadBlock.java:40`: initial age is
  `random.nextInt(25)`, so 0–24. Random growth at line 50 runs on `ServerLevel`.
- `world/level/block/GrowingPlantBlock.java:69`: outline geometry is the fixed
  plant shape, independent of age. Vines have fixed collision shapes and their
  climbability comes from the block tag.
- `world/item/ShearsItem.java:66`: only non-max-age growing heads consume the
  shears action; it sets age 25 locally and damages the item.
- `world/item/BlockItem.java`: the existing native placement path applies item
  block-state components and decrements the held stack after successful placement.

## Validation

Seven focused unit tests pass with no skips:

- Supported and unsupported placement for both vines, with inventory consumption.
- Extending vines converts the previous head into a body.
- Every initial vine age has identical native outline/collision geometry and
  climbability.
- Snapshot comparison accepts initial ages while rejecting max-age differences,
  wrong block types, berry-property differences, crop-age differences, and invalid
  ages.

The original live `block-item-catalog-10` failure showed air/stack-count mismatches.
Removing the placement guard first produced correct blocks and counts, with only
private random-age differences left. The semantic comparison resolves that
unreproducible property while retaining assertions for placement and consumption.

Live validation uses Paper 26.2 build 111, native client 26.2, Java 25, and the
existing 40 ms +/- 10 ms latency in each direction. The harness is commit
`761bccd` (event-9 scenarios temporarily paused by user request).

Validated jar SHA256:
`3fc6af4a251ef9a09695e2ba27ed07501b492be6c59b0cda14c01181bcf90b0c`.

Results: the previously failing catalog batch passes 3/3 live executions. Each
execution proves 100 native block-item outcomes and 25 initial item-state checks;
all snapshots match, no flags occur, and maximum prediction offset is 0.0.
This is a scoped rerun, not a rerun of the entire smoketest catalog.

Artifacts under the harness `.real-validation/runs/`:

- `smoketest-cult-dev-20260926-162541/block-item-catalog-10-play`.
- `smoketest-cult-dev-20260926-162700/iteration-00001/block-item-catalog-10-play`.
- `smoketest-cult-dev-20260926-162700/iteration-00002/block-item-catalog-10-play`.

Commands:

```sh
./gradlew :common:test \
  --tests ac.cult.cultac.utils.blockplace.GrowingPlantPlacementTest \
  --tests ac.cult.cultac.manager.player.SmoketestBlockStateComparisonTest

JAVA_TOOL_OPTIONS=-Dsmoketest.workspace.manifest=/tmp/smoketest-growing-plant-placement.properties \
DISPLAY=:1 ./gradlew smoketestRun \
  -Psmoketest.suite=block-item-catalog:range:10-10 \
  -Psmoketest.repeatCount=2
```
