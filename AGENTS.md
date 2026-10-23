# Codex Instructions

This tree is the GrimCult fork: a GPLv3 Grim 2.0-modern base hosting the ported
non-PacketEvents networking stack, the Java 3.0 simulation engine, and the Bedrock
simulation engine. The engine and anticheat rules below are the copyright holder's
own rules, carried over verbatim from the source repository; only the repository
layout and the build/test commands are adapted to this tree.

## Repository layout and build/test commands

- `common/` — platform-agnostic code: the raw-NMS networking stack (`network/`),
  packet event listeners (`events/`), the Java 3.0 simulation engine
  (`checks/impl/prediction/`, engine `utils/`), the Bedrock simulation engine
  (`bedrock/`), checks, managers, and the platform SPI (`platform/api/`).
- `bukkit/` — the Bukkit/Paper platform module. Bukkit/Paper-only; the Fabric
  modules were removed in the port. The distributable jar is built here.
- `placement-api/` — JDK-only inputs and results shared with the isolated runtime.
- `placement-runtime/` — the isolated original vanilla Java 26.3 action and geometry runtime.
- Full build: `./gradlew build` (output at `bukkit/build/libs/`)
- Unit tests: `./gradlew :common:test`
- Offline Bedrock replay tests: `./gradlew :common:offlineBedrockReplayTest`
- Live smoketests (real Paper server + real client): `scripts/run-local-all-smoketests.sh`

## Rules applicable to both the Java and Bedrock simulation engine

- For broader Grim compensation, packet timing, transaction, bundle, threading, and check-writing guidance, consult `CULT_ANTICHEAT_DEVELOPMENT_GUIDE.md`. If it conflicts with the Bedrock-specific uncertainty limits in this file, `AGENTS.md` wins.
- Assume cheaters will read Grim source code and look for bypasses, so exemptions and adding new lenience are not acceptable solutions.
- Temporary diagnostics may be added while investigating, but they must be removed before final validation or handoff.
- Applying changes to the check level is almost certainly wrong. The post prediction checks should be stupidly simple. No giving uncertainties due to being near certain blocks or otherwise working around simulation engine bugs. Instead, almost all changes should be made to the simulation engine itself, not post prediction checks.
- Engine-first fixes do not limit investigation to Simulation or movement offsets. For every effective flag, trace the check's own vanilla invariant, matching client methods, packet/tick order and compensated inputs. Input legality, sprint/hunger/abilities, pose/overlap, timing and impulse-consumption checks can fail independently of geometric prediction. A source-proven defect in a check's own decision must be corrected there; do not mask upstream simulation or compensation bugs with check lenience. Unknown deployed artifact provenance limits production attribution, not research of available local or historical code.
- Model client packet processing at tick boundaries: packets are not processed in the middle of a client tick, so do not add half-tick uncertainty for packet arrival. Use tick-end and transaction ordering to prove when the client has processed state. Both Java and Bedrock clients guarantee packets in order.

When validating anticheat fixes, continue running the relevant smoke tests after each source-proven fix until the requested test target passes without false flags.

## Bedrock simulation engine changes

The Bedrock engine is a machine-written port of the Java simulation engine for Bedrock clients. It is required to be architected at the same high level as the Java engine:
- All Bedrock small-scenario fixes must validate to <= 0.001 block offset unless a tighter source-proven bound is required.
- Java engine architecture must be followed. The Bedrock engine should follow the Java engine's high-level runner architecture. The shared runner uncertainty handlers `PistonShulkerPush`, `CollisionModifier`, `StepTransform`, `Fireworks`, and `InsideBlock` are explicitly allowed for Bedrock, provided they operate on compensated state and exact Bedrock simulation/collision candidates. Other Java uncertainty classes must not be applied to Bedrock unless explicitly requested. The Java simulation engine is your source of truth for how to structure a simulation engine because it is carefully architected, while the Bedrock engine was machine-written.
- Stepping must be architecturally integrated with the Java runner, but the Bedrock engine must supply its own semantics.
- Collisions must create the same CollideAxisData and run NO FURTHER COLLISIONS within the engine other than for stepping, or Java-equivalent collision checking such as with sneaking. For example, running a collision downwards as the post position the player is at is not allowed.
- Do not use authored/provided client inputs as truth. The engine should derive possible movement first, then evaluate whether required input is legal at the post-prediction stage, similar to Java Grim.
- Sprinting legality belongs at the same level as input legality. The engine may model server-observable sprint state, but must not add sprint lenience to compensate for uncertain input.
- Packet delta represents future velocity. Do not move sprinting or movement multipliers into simulation if they belong to final check-side input allowance.
- Do not touch the evaluator unless the bug is proven to be there.
- Keep one system for each mechanic. Do not add duplicate sprint multipliers, duplicate evaluator lenience, or parallel ad hoc movement paths.
- When implementing movement logic within the Bedrock simulation engine, you MUST match the vanilla Bedrock client/server movement behavior as verified by official protocol documentation, packet/tick ordering, and replay validation. Do not run systems out of order unless it would violate other rules written in this document.
  * For Bedrock anticheat logic changes, cite the vanilla behavior being matched and the replay evidence that proves it.
  * Determine the order of components from packet ordering, tick boundaries, and replay observations. Please reorder components and fix logic to match vanilla behavior.
- No huge monolith classes or huge state logic.

## Allowed Bedrock simulation uncertainties
- Bedrock next-tick state uncertainty is limited to source-proven end-of-tick candidates: the three ladder/climbable carry candidates and the single fluid-hop carry candidate. The explicitly allowed shared runner handlers above may transform or validate current-tick movement, but must not create additional Bedrock next-tick state alternatives. No other Bedrock uncertainty is permitted unless explicitly requested.
- Preserve explicitly allowed next-tick alternatives and carry them through normal end-of-tick processing. Do not collapse them early, and do not add new next-tick alternatives beyond the allowed ladder/climbable and fluid-hop candidates.
- Multiple next-tick alternatives may be evaluated, but the state space must stay bounded; pick the least-flagging valid candidate without exploding simulation state.
- Do not add generic horizontal input radius widening, arbitrary vertical projection, broad step ranges/exemptions, collision-nearby lenience, or world-proximity lenience.
- Unless explicitly told to implement uncertainty, assume that the goal is deterministic and can be accurately represented in the engine.

## Java simulation engine changes

The Java engine is the original engine and is manually written with intentional design. It is the source of truth for how to write a simulation engine.

For every anticheat change:
- You must investigate source code of the Java Edition client, typically MCP-Reborn, before making changes.
- Java clients use doubles for movement and collisions so > 1e-5 offsets typically indicates something is wrong.
- No significant architectural changes to the Java simulation engine are expected. Never adapt Bedrock engine changes into the Java engine.
- Treat any packet sent to the client by the server or a plugin as valid protocol and valid client-visible state; fix Grim's model of that state instead of rejecting the sequence as a harness issue.
- Sprinting is two client states written independently: the sprint flag (entity shared flag 3) and the `minecraft:sprinting` movement speed modifier. Self entity data overwrites only the flag and attribute updates overwrite only the modifier; `LivingEntity#setSprinting` writes both. `ClientSprintState` tracks them from the bundled metadata and attribute proofs and reconciles them each client tick.
  * The client's last START/STOP_SPRINTING is the flag during travel, because `LocalPlayer#aiStep` updates it before `travel` and `sendPosition` sends it before the movement. Use it as exact simulation state (jump boost, fluid gravity, air speed). Ground speed is legal at sprint speed only with the tracked modifier.
  * Attack slowdowns (`Player#causeExtraKnockback`'s 0.6 X/Z damping) are classified per attack as none, possible or certain from `ClientAttackStrength`, `ClientCombatAttributes` and the sprint flag. The engine keeps candidates for 0..possible dampings; the `KeepSprint` check owns the offset of the best candidate carrying every certain damping, the way AntiKB owns ignored velocity. Do not enforce the certain count inside the engine or flag it as Simulation.
  * Players must claim sprinting to move at sprint speed. Do not reintroduce an unconditional sprint allowance or unknown sprint state. The only allowed exceptions are source-proven: a riptide spin (`LivingEntity#checkAutoSpinAttack` can reset sprint after travel), and an attack whose vanilla conditions allow `Player#causeExtraKnockback` to reset sprint before `aiStep` silently re-sprints (a target that can be hurt client-side, and client-side knockback or a sprinting attack that `ClientAttackStrength`/`ClientCombatAttributes` cannot rule out above 0.9 strength).


## Entity tracking

- Only the local player's own riding tree is positioned and ticked: the tree under the root of its riding chain, plus the tree of the vehicle the server last put it on. This is Grim's model and an owner decision, not an optimisation to revisit.
- Riders on any other entity (name tags, model parts on other players) are untracked: never positioned or ticked, and re-anchored at their current seat when they dismount or lose their vehicle.
- Every reader of another entity's position or boxes (checks, collision, block placement, visibility, debug) skips entities for which `CompensatedEntities#isUntrackedRider` is true. An event-driven reader that needs such a rider's position uses `CompensatedEntities#currentMovementBox`.
- Entity pushes are the one per-tick exception: a living untracked rider pushes the Java player from its seat (`CompensatedEntities#untrackedRiderPushBox`, widened over its root's possible positions) once `CompensatedEntities#untrackedRiderMayReach`, which allocates nothing, finds it can reach.
- `RiderAllocationTest` guards it: cosmetic riders on other players allocate nothing per passenger sweep or client tick, and far living riders nothing in push collection.

## Rules for using smoketests to validate logic

- You are writing a general purpose movement engine, not overfitting the engine to fit the replays and tested behavior.
  * Issues flagged in the replay mean you should look at client sources, NOT guess or handwave the issue found.
  * Understand the client sources FIRST, and THEN make the anticheat changes. Looking at the client sources after the smoketest indicates an issue is a required step.
- You should never modify the smoketest or validation logic unless you are absolutely confident there is a bug in the smoketest or validation logic.
- Do not weaken the smoketest. Do not weaken the anticheat. All smoketest scenarios are designed to be passable in a properly written engine and anticheat.
