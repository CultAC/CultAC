# Java inventory proof bundles

## Cause and change

The original inventory handler queued its compensated update against a transaction,
then wrote the ping in a later `tasksAfterSend` callback. The client could process
the inventory update, emit a smoketest snapshot, and only process the following
ping on its next tick. The snapshot bridge consequently compared different
processed states.

For Java clients supporting bundles, `CompensatedInventory` now returns the proof
ping immediately after the inventory update in `PacketSendEvent.packetsAfterSend`.
`ChannelPacketHandler` already wraps multiple replacement packets in a bundle and
rebuilds an enclosing vanilla bundle while preserving child order. Its flattening
of children is an internal representation step, not removal of the wire boundary.
The initial diagnosis that the full pipeline discarded ordinary bundles was
incorrect.

The compensated update still waits for the transaction acknowledgement. Send
tracking still runs after forwarding, with the existing idempotent tracking guard.
No additional inventory/slot packets are generated. Consumption event 9, use
state, simulation checks, and smoketest assertions are unchanged. Legacy Java and
Bedrock keep their existing deferred-proof transport.

## Vanilla source evidence

MCP-Reborn 26.2, commit `727d72ffc66bcdf1a8c16ee92b120db2eaa46e26`:

- `network/PacketBundlePacker.java`: buffers packets through the closing delimiter
  and emits one bundle.
- `client/multiplayer/ClientPacketListener.java:2451`: queues the whole bundle to
  the client thread, then handles its children in order in the same call.
- `client/multiplayer/ClientCommonPacketListenerImpl.java:153`: handles the ping on
  that thread and sends its pong.
- `network/protocol/BundlerInfo.java:20`: encodes a high-level bundle using an
  opening delimiter, its children, and a closing delimiter.

This establishes that no client tick can separate an inventory update from its
proof in the same bundle. It does not establish inventory equality during an
unmodeled consumption event before its authoritative inventory update.

## Regression tests

`InventoryProofBundleTest` exercises the actual inventory handler and channel
packet handler using an embedded Netty channel:

1. A standalone slot update and its proof are one outgoing bundle. The compensated
   slot remains unchanged until the acknowledged callback runs.
2. Multiple inventory updates and unrelated packets retain their order in an
   existing bundle; all inventory proofs are inside it.
3. Legacy Java and Bedrock retain the original separate deferred proof.

The two modern bundle assertions fail against the original inventory handler;
the transport fallback assertion passes. Restoring the fix makes all three pass.
The eight existing channel packet-handler tests also pass: 11/11, no skips.

## Live validation

Harness commit: `e365455a709f9ea13b801319c59deb01affa148c`.
Cult source base: `f4f343c865044c881d5ff9069e55dd61bd46e363`, with this isolated fix.
Validated jar SHA256:
`f1b1844deadc0afe68b1cc60f3fe6057cb9c74b7fab134dea3c6729c774c872d`.
Paper 26.2 build 111, native client 26.2, Java 25.

The two flaky scenarios run ten times each with the existing 40 ms +/- 10 ms
latency in each direction. The complete inventory suite runs against the same
jar. Results: 20/20 targeted executions passed (10/10 each). The complete inventory suite passed 39/44; only the five pre-existing event-9 completion cases failed, with native-valid scenarios and Cult inventory mismatches.

Artifacts:

- Targeted repetitions: `smoketest-cult-dev-20260926-161246`.
- Full inventory suite: `smoketest-cult-dev-20260926-161359`.

These runs precede the user-requested temporary pause of those five consumption scenarios. Disabled cases are not counted as passes. The other 39 active inventory cases all passed.

The crafting wire transcript at iteration 1, tick 5 confirms actual grouping:

```text
BundleDelimiter
ContainerSetSlot
Ping
BundleDelimiter
BundleDelimiter
ContainerSetContent
Ping
BundleDelimiter
```

The harness probe records decoded wire children before vanilla assembles the
bundle; these delimiters therefore verify the actual protocol grouping, not only
the high-level object used by the unit tests.

Commands (the manifest points to the validated jar and pinned toolchain):

```sh
./gradlew :common:test \
  --tests ac.cult.cultac.packet.InventoryProofBundleTest \
  --tests ac.cult.cultac.packet.ChannelPacketHandlerTest

JAVA_TOOL_OPTIONS=-Dsmoketest.workspace.manifest=/tmp/smoketest-inventory-bundles.properties \
DISPLAY=:1 ./gradlew smoketestRun \
  -Psmoketest.scenarios=inventory-crafting-2x2-result,inventory-brewing-stand-inputs \
  -Psmoketest.repeatCount=10

JAVA_TOOL_OPTIONS=-Dsmoketest.workspace.manifest=/tmp/smoketest-inventory-bundles.properties \
DISPLAY=:1 ./gradlew smoketestRun -Psmoketest.suite=inventory
```
