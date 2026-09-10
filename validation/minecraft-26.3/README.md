# 26.2 → 26.3 preparation

Status on 2026-09-10: **partial RC1 port; not ready to merge or advertise as full
26.3 client/server support**. Mojang's latest release is 26.2 and latest snapshot
is 26.3-rc-1. The final 26.3 wire protocol has not been assigned in the published
artifacts. Paper's `dev/26.3` branch targets pre-3 and its published development
bundle metadata contains no 26.3 build.

## Inputs and method

Compared the official 26.2 and 26.3-rc-1 client jars using Vineflower 1.12.0,
and checked matching server artifacts and public method descriptors with JDK 25.
All four downloaded Mojang jars matched the SHA-1 values in Mojang's version
metadata. `source-audit.json` records their URLs, hashes, version.json contents,
decompiler hash, reviewed source areas, and added/removed game protocol classes.
Vanilla jars and decompiled sources are not committed.

Primary inputs:

- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [26.2 metadata](https://piston-meta.mojang.com/v1/packages/bd23a7ec14eb492bc20d8d06508b6ebb0a63c31a/26.2.json)
- [26.3 RC1 metadata](https://piston-meta.mojang.com/v1/packages/d3b1c1d9435b3544f3bfb2f77ebca2696a73dfc6/26.3-rc-1.json)
- [Paper development branch](https://github.com/PaperMC/Paper/blob/a4fd6405a7a668058bbbfe03c7ed45c4a0139086/gradle.properties)
- [Paper development bundle metadata](https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/maven-metadata.xml)

This is a targeted compatibility audit, not a claim that every changed vanilla
method has been ported. Cosmetic changes were separated from state and protocol
changes by reading the decompiled methods. Minecraft's synthetic enum-switch
decompilation emitted diagnostics; the packet-send sites were readable, and the
new packet's actual runtime class was checked independently.

## Implemented in this preparation

| Vanilla source evidence | Cult change |
| --- | --- |
| `version.json`: RC1 wire protocol `1073742160` (`1 << 30 | 336`), versus release 776 | Exact `V_26_3_RC_1` mapping. Version comparisons use chronological enum order. Other snapshots and unassigned release 777 remain unknown. |
| `Minecraft#startAttack`, `#continueAttack`; `ServerboundPunchPacket` | Route the handless main-hand PUNCH through the existing swing listeners. Keep legacy SWING optional by class name so its removal does not break handler reflection. Ordinary attack and break swing requirements remain active. |
| `MultiPlayerGameMode#piercingAttack`, `#dropItem` | STAB and DROP animate locally without a following swing in 26.3. STAB no longer creates an impossible swing obligation; DROP no longer authorizes an otherwise illegal punch while using an item. Older client behavior stays version-gated. |
| `ServerboundUseItemPacket`, `ServerboundUseItemOnPacket` become records | Read record or legacy accessors centrally, including sequence, hand, rotation, hit position, face and cursor. Route placement, no-slow state and negative-sequence checking through these readers. |
| Both move-vehicle packets wrap `PositionAndRotation` in `movingTo()` | Read the nested transform or the existing flat packet layout, preserving serverbound on-ground presence. |
| `ClientboundLevelChunkWithLightPacket` becomes a record; `ClientboundLevelChunkPacketData#forEachBlockEntityTag` replaces the consumer factory | Adapt the 26.x chunk reader's coordinates, payload and block-entity iteration. Keep section decoding and transaction scheduling intact. |
| `Player#getDestroySpeed` uses a float-cast exponential mining-fatigue scale | Apply the new 26.3 scale, including amplifiers 2+ and float underflow, while preserving Cult's existing older-client arithmetic. |
| `Entity#restituteMovementAfterCollisions` changes the impact test from `< gravity` to `<= gravity` | Suppress the exact-gravity rest bounce only for the new client semantics. |

Recognition of RC1 is an implementation boundary for these changes, not a support
declaration. The build, runtime target and published supported-version metadata
still name the available 26.2 baseline.

## Required follow-up before support is complete

| Area | Source change and remaining work |
| --- | --- |
| Entity replication and reach | `ClientboundMoveEntityPacket` replaces three short deltas with `VecDelta`, including multiple path steps; `ClientboundEntityPositionSyncPacket` replaces `PositionMoveRotation values` with `PositionPath position` and separate rotations. Port `PacketEntityReplication`, codec baselines and reach/movement interpolation together. Do not discard intermediate path points or interpret these bytes as the 26.2 layout. |
| Interpolation and tick order | `Entity#commonTick` now performs interpolation before base ticking. `LocalPlayer#sendChanges` moves from player ticking to after all entity and block-entity ticks in `Minecraft#tick`. Revalidate boat fluid sampling, passenger/jump ordering and packet proof boundaries. |
| Step collisions | `Entity#collide` expands the entity-collider query upward by `maxUpStep()`. Carry the correct set of compensated hard colliders through the existing collision/step runner and validate steps onto entities. |
| Elytra state | `Player#tryToStartFallFlying` rejects any liquid, including lava. Port the command's effect using the correct compensated tick state; merely relaxing an elytra check is not sufficient. |
| Client-visible blocks and prediction | Audit the new block-transformer interactions and held-item replacement in `MultiPlayerGameMode#performUseItemOn`. Verify registry/shape support for new blocks such as straw beds, cushions, paths and shelf mushrooms. `ClientPacketListener#handleAddTransientBlockPacket` only queues a transient render object in the level extractor; it does not change the collision world. |
| Sleeping | `LivingEntity#startSleeping` now validates an `AbstractBedBlock` and derives sleeping Y from its shape. `PacketSelfMetadataListener` currently hardcodes `bedY + 0.6875`, which is not sufficient for straw beds. |
| Fluids | Ridden floating uses `#entity_floatable`. Vanilla supplies only water, but client-visible tag changes need to be modeled rather than assumed. |
| Remaining NMS linkage | Audit record conversions across login, respawn, entity removal, chat and other decoded handlers; check removed/renamed NMS members and placement APIs against a real 26.3 Paper bundle. The adapters above cover only the listed families. |
| ViaVersion | Verify an actual translator that supports the final protocol. Test both newer-client/older-server and older-client/newer-server combinations; do not assume a 26.2 translator preserves punch, path and registry semantics. |

## Validation and release gates

The isolated test runner compiles the production `ClientVersion`,
`SwingPacketUtil` and `MiningFatigue` classes and their regression tests to Java 21
bytecode, then runs the same tests on both unmodified Mojang server runtimes under
JDK 25. It verifies download hashes and obtains the runtime libraries from the
official server bundles.

```sh
python3 scripts/validate-minecraft-26.3.py --java-home /path/to/jdk-25
```

Result: **8/8 passed on 26.2 and 8/8 passed on 26.3-rc-1**, with no skipped tests.
These cover version ordering/unknown protocols, actual legacy main/off-hand
packets versus the actual handless punch packet, unrelated packets, changed
fatigue levels and older-client results. They do not validate the full plugin,
handler integration, movement simulation or ViaVersion translation.

The normal Gradle compile attempt completed Paper 26.2 userdev setup but failed
at `:common:generateBedrockMovementCollisionOverrides` because the pinned
submodule is unavailable. This is a build blocker, not a passing full build.

The common production and test sources subsequently compiled, and all four new
test classes passed against Paper 26.2, with only the two unavailable Bedrock
resource-generation tasks excluded:

```sh
./gradlew :common:test \
  --tests '*ClientVersionTest' --tests '*SwingPacketUtilTest' \
  --tests '*MiningFatigueTest' --tests '*NmsPacketUtilUseAndVehicleTest' \
  -x :common:generateBedrockMovementCollisionOverrides \
  -x :common:prepareBedrockMovementCollisionOverrides
```

The compiled tests were also run with the Paper NMS jar removed from the test
classpath and each verified Mojang runtime substituted: **12/12 passed on 26.2
and 12/12 on 26.3-rc-1**, with no skipped tests. The four additional adapter tests
exercise actual item-use packets, negative block coordinates/cursors, the
serverbound vehicle wire codec including on-ground, and clientbound vehicle
round trips. These confirm the record and nested-transform readers at runtime.
They do not establish Paper 26.3 startup, chunk-handler integration or simulation
correctness. The standalone Python runner above covers the eight isolated tests;
the adapter tests additionally require the compiled common classes and their
non-NMS test dependencies.

Before marking the PR ready:

1. Obtain the final Mojang release and published Paper development bundle, repeat
   the RC-to-final diff, and use the observed final protocol number.
2. Finish the remaining client/server work above and update the Paper dependency,
   development runtime target, ViaVersion dependency if required, and published
   supported-version metadata together.
3. Restore the pinned `mappings-generator` commit
   `7b12b5ee25a9fe0028be0a9f947748344847f55b`, or deliberately update the submodule
   to a verified replacement. GitHub reports that the currently pinned commit
   does not exist in `CultAC/mappings-generator`; the required generated Bedrock
   collision catalog therefore cannot be reproduced from this checkout.
4. Pass `./gradlew build`, `:common:test`, the relevant replay tests and the live
   Paper/client smoketests. Include punch/attack/STAB/DROP ordering, mining fatigue
   III/IV/high amplifiers, exact-gravity bounces, lava elytra starts, new block
   shapes/sleeping, entity path interpolation, and translated clients. No smoke
   or replay acceptance criteria have been weakened in this preparation.
