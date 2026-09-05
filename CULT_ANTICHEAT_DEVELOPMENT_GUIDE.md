# Cult Anticheat Development Guide

This guide describes how to write Cult checks and compensation code in a way that matches vanilla movement, vanilla protocol timing, and latency. Cult should not be a collection of exemptions. Cult should be a latency-compensated reverse simulation of what the vanilla client could have done.

## Philosophy

Cult's job is to reconstruct the client's legal movement envelope.

For every player movement packet, Cult should answer:

1. What vanilla client tick produced this packet?
2. What server state had the client definitely received by that tick?
3. What server state might still be pending because of latency?
4. What inputs, entity collisions, fluids, blocks, vehicles, status effects, item states, and server corrections could affect movement?
5. Does the reported movement fit inside the resulting vanilla movement envelope?

If the answer is unclear because the client might not have received state yet, model that uncertainty. Do not exempt the player because they have latency. Do not weaken the final analyzer because earlier compensation is incomplete.

## Reverse The Game, Do Not Approximate It

Cult movement code should mirror the vanilla client pipeline.

Use MCP-Reborn as the source of truth:

```text
/home/hunter/Downloads/MCP-Reborn/src/main/java/net/minecraft/
```

Read source before writing assumptions. Important sources:

- `net.minecraft.client.Minecraft`
- `net.minecraft.client.player.LocalPlayer`
- `net.minecraft.client.player.ClientInput`
- `net.minecraft.client.player.KeyboardInput`
- `net.minecraft.client.multiplayer.ClientPacketListener`
- `net.minecraft.client.multiplayer.MultiPlayerGameMode`
- `net.minecraft.client.multiplayer.ClientLevel`
- `net.minecraft.world.entity.Entity`
- `net.minecraft.world.entity.LivingEntity`
- `net.minecraft.network.Connection`
- `net.minecraft.network.protocol.BundlerInfo`
- `net.minecraft.network.protocol.game.ServerboundClientTickEndPacket`
- `net.minecraft.network.protocol.common.ClientboundPingPacket`
- `net.minecraft.network.protocol.common.ServerboundPongPacket`
- `net.minecraft.network.protocol.game.ClientboundBundlePacket`
- `net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket`

Do not use decompiled bytecode when these sources are available.

## Vanilla Client Tick Boundaries

Modern vanilla has a real tick-end packet.

`Minecraft.runTick()` processes queued packets and scheduled tasks before ticking, then runs up to `Math.min(10, elapsedTicks)` calls to `Minecraft.tick()`, then applies accumulated mouse movement after the tick catch-up loop.

`Minecraft.tick()` sends `ServerboundClientTickEndPacket.INSTANCE` at the end of the client tick when a play connection exists and the game is not paused. This matters because modern vanilla can have a client tick without a movement packet. `LocalPlayer.tick()` only sends movement after `connection.hasClientLoaded()`, and `LocalPlayer.sendPosition()` only sends movement when position, rotation, on-ground, or horizontal-collision state changed enough to require it. The client tick still happened.

Use `ServerboundClientTickEndPacket` as the exact client tick delimiter.

It should drive:

- idle client ticks
- packet ordering inside a client tick
- post-movement/action checks
- timer accounting
- end-of-client-tick state transitions
- "known movement" reset logic for ticks without movement
- frozen tick-step accounting

Do not infer ticks only from movement packets. Movement packets are evidence that a tick produced movement; tick-end is evidence that the tick ended.

## Movement Packet Semantics

Movement packets are not all client ticks.

Valid tick-producing packets include:

- `ServerboundMovePlayerPacket` when the local player sends normal movement
- `ServerboundMoveVehiclePacket` when the client controls a vehicle
- vehicle input packets when mounted and no movement packet has been received for that client tick
- `ServerboundClientTickEndPacket` when no movement packet was received in that client tick

Treat the tick-end packet as the delimiter that clears per-client-tick state. Packets after tick-end belong to the next client tick even if no Cult transaction has been acknowledged yet.

For post checks, this means a vanilla action sequence can be:

```text
movement or input packets
use / attack / interact / dig packets from handleKeybinds or game mode
ServerboundClientTickEndPacket
```

A packet after `ServerboundClientTickEndPacket` is not "post" for the previous tick. It is part of the next tick or an out-of-tick vanilla action path that must be judged by the correct vanilla source.

## Ping/Pong Transactions

Modern vanilla common protocol has `ClientboundPingPacket` and `ServerboundPongPacket`.

MCP-Reborn behavior:

- `ClientboundPingPacket` carries an integer id.
- `ClientCommonPacketListenerImpl.handlePing()` schedules handling on the client packet processor and sends `ServerboundPongPacket` with the same id.
- `ServerboundPongPacket` is handled by `ServerCommonPacketListenerImpl.handlePong()`.
- This is separate from keepalive. Keepalive has its own packet pair and can be delayed by client conditions such as frozen polling. Ping/pong is the better Cult transaction primitive.

Cult currently uses `ClientboundPingPacket` as the transaction packet and only accepts `ServerboundPongPacket` ids it sent. Keep this property:

- only Cult-generated ping ids should advance Cult transaction state
- third-party or server-generated pings must not corrupt Cult's transaction order
- transactions should be monotonic and attached to the packet boundary they are proving
- a pong proves the client received and processed every prior clientbound packet that was delivered before that ping in the same ordered stream

Do not treat ping as a latency exemption. A pong is a causality proof. Use it to move pending server state from "might not be known by the client" to "known by the client."

## Keepalive Is Not A Transaction Replacement

Keepalive is useful for timeout and behavior checks, but it is not as precise as ping/pong for simulation compensation.

Use keepalive for:

- disconnect timeout behavior
- detecting clients that delay keepalive differently from transaction pong
- coarse latency diagnostics

Do not use keepalive as the primary movement compensation barrier when ping/pong is available. It does not map as cleanly to a deliberately placed Cult transaction boundary.

## Bundle Packets And Delimiters

Modern play protocol supports clientbound bundles.

MCP-Reborn behavior:

- `ClientboundBundlePacket` contains sub-packets.
- `ClientboundBundleDelimiterPacket` is not handled by `ClientGamePacketListener`; its `handle()` throws because it is a pipeline marker.
- `BundlerInfo.unbundlePacket()` emits:
  - delimiter
  - every sub-packet
  - delimiter
- `PacketBundlePacker` turns delimiter-delimited packet streams into a bundle.
- `PacketBundleUnpacker` expands a bundle into delimiter-delimited packets.
- `Connection.setupInboundProtocol()` installs a bundler after the decoder when the protocol has bundle support.
- `Connection.setupOutboundProtocol()` installs an unbundler after the encoder.
- The play clientbound protocol registers `ClientboundBundlePacket` with `ClientboundBundleDelimiterPacket`.
- `ClientPacketListener.handleBundlePacket()` still handles sub-packets sequentially if a bundle object reaches the listener.

For Cult, a bundle is a causality group.

Packets inside one bundle should be treated as arriving in the same ordered clientbound group. A transaction placed after the bundle proves the client received the whole bundle, not just one sub-packet. A transaction placed inside the logical bundle boundary is wrong unless it matches the actual wire/pipeline order.

Future Cult bundle support should track:

- bundle start delimiter
- bundle end delimiter
- sub-packet order
- whether a transaction was sent before, inside, or after the bundle group
- pending state created by sub-packets until the confirming pong

Do not ignore bundle delimiters if Cult's Netty hook sees them. Do not flatten bundles in a way that loses the "these packets arrived together before the next packet" property.

## Latency Model

Latency is not a boolean exempt state.

Latency means the server and Cult know about a packet before the client necessarily knows about it. Cult must preserve both timelines:

- server timeline: what the server sent and when
- client-confirmed timeline: what the client has proven it processed via pong/transaction
- client tick timeline: what tick the client says ended via `ServerboundClientTickEndPacket`

Clientbound state should generally flow through these states:

1. Observed by Cult on outbound packet send.
2. Recorded as pending at the current transaction id.
3. Included in uncertainty if it could affect current movement before confirmation.
4. Promoted to confirmed when the matching pong proves receipt.
5. Removed or expired only at the same boundary vanilla would observe.

Examples of latency-sensitive state:

- block changes
- fluids
- entity spawns, despawns, movement, metadata, equipment, attributes, passengers
- teleports and rotations
- velocities and explosions
- open/close inventory state
- held item and equipment
- potion effects
- vehicle mount/dismount
- world border and game state changes
- server ticking state and frozen tick steps

The correct fix for latency is usually more precise pending-state modeling, not a check exemption.

## What Is Not Allowed

Do not add hacks such as:

- "if ping > X, exempt this check"
- "if jitter exists, ignore speed/angle/antikb"
- "if startup traffic exists, skip simulation"
- "if entities are close, suppress movement checks"
- "if bundle packet exists, ignore sub-packets"
- "if the client has not loaded, force a movement state"
- "if a test false positives, make the bot avoid that action"

Do not patch terminal analyzers to hide upstream uncertainty. If the issue is pending entity movement, fix entity compensation or entity-push uncertainty. If the issue is pending block state, fix block compensation. If the issue is tick identification, fix tick-end handling.

Do not read live Bukkit state from the Netty thread.

Packet listeners run in the network pipeline context. Bukkit state is not a reliable source of what the client knew on the packet being processed, and many Bukkit APIs are not safe to call from Netty. If a check needs Bukkit-derived state:

- snapshot it on the correct server thread
- copy it into Cult-owned data structures
- synchronize that snapshot into the Netty timeline using transactions
- read the Cult-owned compensated state from packet/check code

Allowed packet-thread data sources:

- packet contents
- Cult-owned compensated world/entity/inventory state
- transaction-confirmed snapshots
- immutable data captured from safe server-thread tasks
- NMS packet data already present on the event

Disallowed packet-thread data sources:

- live Bukkit world block reads
- live Bukkit entity state reads
- live Bukkit inventory reads
- live Bukkit player flags as proof of client state
- plugin APIs that may touch main-thread-only state

## Threading Rules

Cult's packet-time state should live on the player's Netty/event-loop timeline.

Use `CultPlayer.runSafely()` or `ChannelHelper.runInEventLoop()` when a mutation must occur on the player's Netty event loop. Use transaction-scheduled tasks when client receipt matters. Use Bukkit/Folia scheduling only to capture server-thread state, then copy it back into Cult-owned state safely.

Do not mix these timelines:

- Bukkit tick thread
- Folia region thread
- Netty event loop
- async scheduler

A field used by checks should have a clear owner timeline. If it is synchronized between timelines, the code should say which transaction or tick boundary makes it valid.

## Writing Movement Checks

Movement checks should consume simulation results, not invent their own movement model.

Preferred structure:

1. Packet listeners update compensated state.
2. Tick-end/tick-start logic identifies the client tick.
3. Simulation builds possible vanilla starting velocities.
4. World/entity/vehicle/item uncertainty widens the valid set where needed.
5. Prediction compares the reported movement to the valid set.
6. Terminal checks report only after upstream compensation is correct.

When adding a check, document:

- what vanilla method or packet behavior it mirrors
- what packet establishes the state
- how latency changes what the client could know
- what transaction confirms the state
- what uncertainty is required before confirmation
- what scenario proves the behavior

## Entity Compensation

Entity state must be packet-driven and latency-compensated.

For entities, track:

- spawn id, UUID, type, dimensions, scale
- server position and rotations
- client interpolation windows
- pending relative moves and teleports
- metadata affecting pose, riptide, sneaking, invisibility, health, death, etc.
- equipment and attributes
- passenger and vehicle relationships
- despawn boundaries

Player entities are living entities and can be pushable. If movement false positives appear when players overlap, first verify:

- the remote player exists in Cult's entity map
- the remote player exists in the client's local world
- pending server positions are represented until confirmed
- collision boxes use the correct dimensions and scale
- the uncertainty pipeline sees the collision before final movement analysis
- client-side push behavior from `LivingEntity`/`Entity` is modeled as uncertainty when exact ordering is not known

Do not solve entity-push false positives by weakening horizontal analysis.

## Block And Fluid Compensation

Block and fluid state must reflect what the client could have known.

For block changes:

- record pending clientbound block changes at send time
- attach them to the current transaction
- use pending state to produce unknown/expanded movement possibilities
- promote to confirmed when pong proves receipt
- handle prediction and rollback for vanilla client block placement and breaking

For fluids:

- water and lava membership can change movement, riptide, swimming, bubble columns, and collision
- if a pending block/fluid change overlaps the player's movement extents, model the relevant state as unknown
- do not assume server-side Bukkit block state is client-known before confirmation

## Item, Inventory, And Use State

Item behavior affects movement.

Examples:

- shields, bows, food, and many use actions slow movement
- buckets predict client-side block changes differently from ordinary held-use slowdown
- riptide requires item use, water/rain context, charge timing, and auto-spin attack state
- elytra depends on client command timing and fall-flying state
- tool speed affects FastBreak

Use packet and transaction-confirmed inventory state. Do not read live Bukkit inventory from Netty to decide what the client had during a packet.

If inventory changes are pending, model uncertainty around item-dependent movement and interaction checks.

## Tick-End Implementation Direction

Modern Cult should make `ServerboundClientTickEndPacket` first-class.

Recommended packet-state fields:

- current client tick index
- whether this client tick contained movement
- whether this client tick contained vehicle movement
- whether this client tick contained vehicle input
- packets seen since the last tick-end
- last completed client tick transaction id
- last completed client tick receive timestamp
- pending tick-end tasks

Recommended behavior:

- increment or close client tick state on tick-end
- treat movement packets before tick-end as belonging to the same client tick
- treat an idle tick-end as a real tick with zero movement
- run post checks and per-tick cleanup at tick-end, not at arbitrary transaction boundaries
- use ping/pong only to confirm clientbound receipt, not to define the client's tick boundary

The client tick boundary and clientbound receipt boundary are different concepts. Cult should model both.

## Ping/Pong Implementation Direction

Cult should use ping/pong as a precise transaction layer.

Recommended transaction data:

- transaction id
- send nano time
- send server tick
- send clientbound bundle depth/group id
- pending state entries attached to the transaction
- pong receive nano time
- client tick index when pong arrived

Recommended behavior:

- send a transaction after latency-sensitive clientbound state
- send a transaction at end-of-server-tick only when useful for aging or timer/post checks
- never advance transaction state from pongs Cult did not send
- never assume a missing pong means the client did not tick
- never assume a tick-end means the client received later clientbound state

## Bundle Implementation Direction

Bundle support should make transaction placement exact.

Recommended model:

- when seeing a bundle object, iterate sub-packets in order while preserving one bundle group id
- when seeing delimiter packets, mark explicit bundle start/end
- do not send a Cult transaction in the middle of a bundle unless it is actually written there on the wire
- attach all pending state from the bundle to a single causal group
- after the bundle, a ping can confirm the whole group

This makes entity spawn + metadata + equipment + passenger packets behave as one client-visible update when the protocol grouped them as one update.

## Bukkit State Rule

Bukkit state is server truth, not client-known truth.

It is useful for:

- initial snapshots
- server-side sanity
- command/debug output
- scheduled main-thread captures
- detecting plugin-driven state changes that must become compensated packets

It is not valid as direct proof that the client knew a thing at the time of a Netty packet.

If a value matters to movement prediction, prefer the packet that told the client. If no packet exists, snapshot server state safely and attach the snapshot to a transaction-aware compensation path.

## Check Review Checklist

Before merging a Cult anticheat change, answer:

- Which vanilla source method or packet behavior is this modeling?
- Does this run on the correct thread?
- Does it avoid live Bukkit reads from Netty?
- Does it distinguish client tick-end from transaction confirmation?
- Does it handle idle client ticks?
- Does it handle ping/pong pongs that Cult did not send?
- Does it preserve bundle packet ordering and grouping?
- Does it model pending clientbound state as uncertainty?
- Does it avoid latency exemptions?
- Does it avoid weakening terminal analyzers for upstream compensation bugs?
- Is there an external validation scenario proving the valid vanilla behavior?
- Is there a cheat scenario proving invalid behavior still flags?

## Practical Rule

When in doubt, move the fix earlier in the pipeline.

The final movement analyzer should receive a correct set of possible vanilla outcomes. If that set is wrong, fix packet compensation, tick tracking, latency confirmation, world/entity state, or uncertainty first. Only change the final analyzer when the upstream model is already correct and the analyzer itself is mathematically wrong.
