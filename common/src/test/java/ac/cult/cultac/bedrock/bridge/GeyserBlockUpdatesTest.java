package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.geysermc.geyser.registry.type.GeyserBedrockBlock;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.BeforeClass;
import org.junit.Test;

public class GeyserBlockUpdatesTest {
    private static final BlockPos POSITION = new BlockPos(2, 64, 3);
    private static final int SAND =
            DataTables.defaults().registry().block("minecraft:sand").defaultState();
    private static final int CHEST =
            DataTables.defaults().registry().block("minecraft:chest").defaultState();
    private static final int STAIRS =
            DataTables.defaults().registry().block("minecraft:oak_stairs").defaultState();

    @BeforeClass
    public static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void asynchronousQueueLeavesTranslationsToTheJavaListenerAndCorrectionsWaitForReceipt() throws Exception {
        Harness h = new Harness();
        h.serverSent(POSITION, SAND);
        var session = mock(GeyserSession.class);
        List<BedrockPacket> queue = new ArrayList<>();
        doAnswer(call -> {
                    queue.add(call.getArgument(0));
                    return null;
                })
                .when(session)
                .sendUpstreamPacket(any());
        session.sendUpstreamPacket(block(0, 17)); // Normal translation remains on the Java listener.
        session.sendUpstreamPacket(block(1, 99));
        GeyserBlockAckTranslator.bracket(session, () -> {
            session.sendUpstreamPacket(block(0, 99));
            session.sendUpstreamPacket(block(1, 99));
        });
        assertFalse(h.updates.active()); // Translation has finished, queue has not drained yet.
        for (BedrockPacket packet : queue) {
            if (packet instanceof GeyserBlockAckTranslator.Boundary marker) {
                if (marker.start) {
                    // The translation before the bracket repeats what the server sent: nothing to prove.
                    h.finish();
                    assertTrue(h.receipts.isEmpty());
                    h.updates.begin();
                } else h.end();
            } else h.capture(packet);
        }
        assertFalse(h.updates.active());
        assertFalse(h.updates.pending());
        assertEquals(1, h.receipts.size());
        verify(h.player.compensatedWorld, never()).handleBedrockClientBlockUpdate(any(), anyInt(), anyBoolean());
        h.receipts.removeFirst().accept(h.player);
        // The correction leaves air where the server last sent sand: a block only this client has.
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(POSITION, BlockIds.AIR.defaultState(), false);
        // Geyser sending sand again gives the client the server's block back, with the server's exact state.
        h.capture(block(0, 17));
        assertTrue(h.updates.pending());
        h.finish();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(POSITION, SAND, true);
    }

    @Test
    public void virtualContainerAboveTheHeadIsAClientOnlyBlockUntilGeyserRestoresTheServerBlock() throws Exception {
        // Geyser places the holder of a container the server opens at the block of the eye position plus one:
        // (20102, 123, 20080) for feet at y 120.75, the block a 1.8 tall player jumping from y 120 rises into.
        Harness h = new Harness();
        h.player.x = 20102.5;
        h.player.y = 120.7532;
        h.player.z = 20080.2626;
        var frame = new BedrockCoordinateFrame(20064, 20080, 9);
        var holder = new BlockPos(20102, 123, 20080);
        h.updates.capture(block(Vector3i.from(38, 123, 0), 0, 54), frame, h.tables);
        assertTrue(h.updates.pending());
        h.finish();
        assertFalse(h.updates.pending());
        assertEquals(1, h.receipts.size());
        verify(h.player.compensatedWorld, never()).handleBedrockClientBlockUpdate(any(), anyInt(), anyBoolean());
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(holder, CHEST, false);

        // The holder sent again changes nothing the client holds.
        h.updates.capture(block(Vector3i.from(38, 123, 0), 0, 54), frame, h.tables);
        h.finish();
        assertTrue(h.receipts.isEmpty());

        // Closing the container sends the real block on layer 0 only.
        h.updates.capture(block(Vector3i.from(38, 123, 0), 0, 99), frame, h.tables);
        h.finish();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(holder, BlockIds.AIR.defaultState(), true);

        // From then on the translation of air is the Java listener's again.
        h.updates.capture(block(Vector3i.from(38, 123, 0), 0, 99), frame, h.tables);
        h.finish();
        assertTrue(h.receipts.isEmpty());
    }

    @Test
    public void aBlockOnOneLayerKeepsTheOtherLayerTheClientHolds() throws Exception {
        Harness h = new Harness();
        h.serverSent(POSITION, BlockIds.WATER.defaultState());
        h.capture(block(0, 104));
        h.finish();
        h.receipts.removeFirst().accept(h.player);
        // Stairs replace the water on layer 0 and no water lies on layer 1.
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(POSITION, STAIRS, false);
        h.capture(block(1, 101));
        h.finish();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld)
                .handleBedrockClientBlockUpdate(POSITION, BlockProps.WATERLOGGED.with(STAIRS, true), false);
    }

    @Test
    public void aRunThatEndsOnTheServerBlockNeedsNoBoundaryAndUnknownBlocksAreSkipped() throws Exception {
        Harness h = new Harness();
        h.serverSent(POSITION, SAND);
        // Geyser refreshes some blocks by sending air first.
        h.capture(block(0, 99));
        h.capture(block(0, 17));
        h.capture(block(0, 4242)); // No server state: cannot be placed in the world.
        h.finish();
        assertTrue(h.receipts.isEmpty());
        // A column that is being replaced holds whatever the new chunk carries.
        h.serverSent(POSITION, CompensatedWorld.UNKNOWN_SERVER_STATE);
        h.capture(block(0, 54));
        h.finish();
        assertTrue(h.receipts.isEmpty());
    }

    @Test
    public void aReplacedColumnForgetsTheClientOnlyBlock() throws Exception {
        Harness h = new Harness();
        h.capture(block(0, 54));
        h.finish();
        h.receipts.clear();
        h.updates.forgetChunk(0, 0);
        // The new chunk carries the server's air, so the holder is a change again and air is not.
        h.capture(block(0, 99));
        h.finish();
        assertTrue(h.receipts.isEmpty());
        h.capture(block(0, 54));
        h.finish();
        assertEquals(1, h.receipts.size());
    }

    @Test
    public void distantClientOnlyBlocksUseTheExistingProof() throws Exception {
        Harness h = new Harness();
        h.player.x = 40;
        h.capture(block(0, 54));
        h.finish();
        assertTrue(h.receipts.isEmpty());
        verify(h.player).addBedrockTransactionTask(isNull(), any());
    }

    /**
     * A retracting sticky piston at x 67 of the session frame: Geyser places a moving block at x 68 with the data
     * of the slime block it carries, then the piston arm's data, and the arm's data again alone on later ticks.
     */
    @Test
    public void pistonBlockEntityDataTakesEffectWithItsRunAtWorldPositions() throws Exception {
        Harness h = new Harness();
        var registry = DataTables.defaults().registry();
        int slime = registry.block("minecraft:slime_block").defaultState();
        int moving = registry.block("minecraft:moving_piston").defaultState();
        int sticky = BlockProps.FACING.with(
                registry.block("minecraft:sticky_piston").defaultState(), Direction.EAST.get3DDataValue());
        var tables = new GeyserBlockStateMappings.Tables(Map.of(60, moving, 61, slime), h.tables.sentStates());
        NbtMap slimeTag = NbtMap.builder()
                .putString("name", "minecraft:slime")
                .putCompound("states", NbtMap.EMPTY)
                .build();
        java.util.function.Function<NbtMap, GeyserBedrockBlock> definitions =
                tag -> tag.equals(slimeTag) ? definition(61) : null;
        var frame = new BedrockCoordinateFrame(20000, 20040, 4);
        var pistonPosition = new BlockPos(20067, 121, 20020);
        var movingPosition = new BlockPos(20068, 121, 20020);
        java.util.function.ToIntFunction<BlockPos> world = position ->
                position.equals(pistonPosition) ? sticky : position.equals(movingPosition) ? moving : 0;
        h.player.bedrockState = new BedrockPlayerState(java.util.UUID.randomUUID());
        var pistons = h.player.bedrockState.pistonBlocks;
        h.player.x = 20070.3;
        h.player.y = 120;
        h.player.z = 20020.5;

        h.updates.capture(block(Vector3i.from(68, 121, -20), 0, 60), frame, tables);
        assertTrue(h.updates.capture(
                blockEntity(
                        68,
                        NbtMap.builder()
                                .putString("id", "MovingBlock")
                                .putBoolean("expanding", false)
                                .putCompound("movingBlock", slimeTag)
                                .putInt("pistonPosX", 67)
                                .putInt("pistonPosY", 121)
                                .putInt("pistonPosZ", -20)
                                .build()),
                frame,
                tables,
                definitions));
        assertTrue(h.updates.capture(blockEntity(67, arm(3, 1.0F, 1.0F)), frame, tables, definitions));
        h.finish();
        assertEquals(1, h.receipts.size());
        assertNull("nothing applies before the client has the run", pistons.carried(movingPosition, world));
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(movingPosition, moving, false);
        var carried = pistons.carried(movingPosition, world);
        assertEquals(slime, carried.state());
        assertEquals(new BlockPos(20069, 121, 20020), carried.last());
        assertEquals(new BlockPos(20069, 121, 20020), carried.current());

        // The arm's data alone is a run of its own.
        assertTrue(h.updates.capture(blockEntity(67, arm(3, 0.5F, 1.0F)), frame, tables, definitions));
        assertTrue(h.updates.pending());
        h.finish();
        assertFalse(h.updates.pending());
        h.receipts.removeFirst().accept(h.player);
        // It sets the state: the progress it carries does not replace the client's own.
        carried = pistons.carried(movingPosition, world);
        assertEquals(new BlockPos(20069, 121, 20020), carried.last());
        assertEquals(new BlockPos(20069, 121, 20020), carried.current());

        // Any other block entity only accompanies its block.
        assertFalse(h.updates.capture(
                blockEntity(68, NbtMap.builder().putString("id", "Chest").build()), frame, tables, definitions));
        assertFalse(h.updates.pending());

        // A moving block that names no piston, carrying a block the world cannot hold.
        assertTrue(h.updates.capture(
                blockEntity(
                        68,
                        NbtMap.builder()
                                .putString("id", "MovingBlock")
                                .putCompound("movingBlock", NbtMap.EMPTY)
                                .putInt("pistonPosX", 0)
                                .putInt("pistonPosY", -1)
                                .putInt("pistonPosZ", 0)
                                .build()),
                frame,
                tables,
                definitions));
        h.finish();
        h.receipts.removeFirst().accept(h.player);
        carried = pistons.carried(movingPosition, world);
        assertEquals(ac.cult.cultac.bedrock.player.BedrockPistonBlocks.NO_BLOCK, carried.state());
        assertEquals(movingPosition, carried.last());
        assertEquals(movingPosition, carried.current());

        // Distant data needs no boundary of its own.
        h.player.x = 20200;
        assertTrue(h.updates.capture(blockEntity(67, arm(0, 0.0F, 0.0F)), frame, tables, definitions));
        h.finish();
        assertTrue(h.receipts.isEmpty());
        verify(h.player).addBedrockTransactionTask(isNull(), any());
    }

    private static NbtMap arm(int state, float progress, float lastProgress) {
        return NbtMap.builder()
                .putString("id", "PistonArm")
                .putFloat("Progress", progress)
                .putFloat("LastProgress", lastProgress)
                .putByte("NewState", (byte) state)
                .putByte("State", (byte) state)
                .build();
    }

    private static BlockEntityDataPacket blockEntity(int x, NbtMap data) {
        var packet = new BlockEntityDataPacket();
        packet.setBlockPosition(Vector3i.from(x, 121, -20));
        packet.setData(data);
        return packet;
    }

    @Test
    public void scopeAlwaysEndsEvenWhenTranslationThrows() {
        var session = mock(GeyserSession.class);
        List<BedrockPacket> queue = new ArrayList<>();
        doAnswer(call -> {
                    queue.add(call.getArgument(0));
                    return null;
                })
                .when(session)
                .sendUpstreamPacket(any());
        assertThrows(
                IllegalStateException.class,
                () -> GeyserBlockAckTranslator.bracket(session, () -> {
                    throw new IllegalStateException("translation failed");
                }));
        assertEquals(2, queue.size());
        assertTrue(((GeyserBlockAckTranslator.Boundary) queue.getFirst()).start);
        assertFalse(((GeyserBlockAckTranslator.Boundary) queue.getLast()).start);
    }

    @Test
    public void layersAreCombinedForOneAckAndForgottenBeforeTheNext() throws Exception {
        Harness h = new Harness();
        h.updates.begin();
        h.capture(block(0, 104));
        h.capture(block(1, 101));
        h.end();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld)
                .handleBedrockClientBlockUpdate(POSITION, BlockProps.WATERLOGGED.with(STAIRS, true), false);
        h.updates.begin();
        h.capture(block(0, 101));
        h.capture(block(1, 99));
        h.end();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld)
                .handleBedrockClientBlockUpdate(POSITION, BlockIds.WATER.defaultState(), false);
        // A correction to the block the server sent applies although the tap saw nothing else there: the client's
        // own prediction may have replaced it.
        h.updates.begin();
        h.capture(block(0, 99));
        h.capture(block(1, 99));
        h.end();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(POSITION, BlockIds.AIR.defaultState(), true);
        h.updates.begin();
        h.capture(block(0, 99));
        h.capture(block(1, 99));
        h.end();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld, times(2))
                .handleBedrockClientBlockUpdate(POSITION, BlockIds.AIR.defaultState(), true);
    }

    @Test
    public void batchPositionsUseCapturedOriginAndSnapshotBeforePacketReuse() throws Exception {
        Harness h = new Harness();
        h.player.x = 1827;
        h.player.z = -3384;
        var packet = new UpdateSubChunkBlocksPacket();
        packet.setPosition(Vector3i.from(1824, 64, -432));
        var pos = Vector3i.from(1827, 65, -424);
        packet.getStandardBlocks()
                .add(new BlockChangeEntry(pos, definition(17), 3, -1, BlockChangeEntry.MessageType.NONE));
        packet.getExtraBlocks()
                .add(new BlockChangeEntry(pos, definition(99), 3, -1, BlockChangeEntry.MessageType.NONE));
        h.updates.begin();
        h.updates.capture(packet, new BedrockCoordinateFrame(0, -2960, 1), h.tables);
        h.end();
        packet.getStandardBlocks().clear();
        packet.getExtraBlocks().clear();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleBedrockClientBlockUpdate(new BlockPos(1827, 65, -3384), SAND, false);
    }

    @Test
    public void emptyAndNestedScopesDoNotGenerateExtraTransactions() throws Exception {
        Harness h = new Harness();
        h.updates.begin();
        h.end();
        assertTrue(h.receipts.isEmpty());
        h.updates.begin();
        h.updates.begin();
        h.capture(block(0, 17));
        h.capture(block(1, 99));
        h.end();
        assertTrue(h.receipts.isEmpty());
        h.end();
        assertEquals(1, h.receipts.size());
    }

    @Test
    public void bracketsTheClientPacketsGeyserMayAnswerWithARestore() {
        assertTrue(GeyserBlockAckTranslator.mayRestoreBlocks(new PlayerAuthInputPacket()));
        assertTrue(GeyserBlockAckTranslator.mayRestoreBlocks(use(InventoryTransactionType.ITEM_USE, 0)));
        assertFalse(GeyserBlockAckTranslator.mayRestoreBlocks(use(InventoryTransactionType.ITEM_USE, 1)));
        assertFalse(GeyserBlockAckTranslator.mayRestoreBlocks(use(InventoryTransactionType.ITEM_RELEASE, 0)));
        assertFalse(GeyserBlockAckTranslator.mayRestoreBlocks(new MovePlayerPacket()));
    }

    private static InventoryTransactionPacket use(InventoryTransactionType type, int action) {
        var packet = new InventoryTransactionPacket();
        packet.setTransactionType(type);
        packet.setActionType(action);
        return packet;
    }

    private static GeyserBedrockBlock definition(int id) {
        return new GeyserBedrockBlock(id, NbtMap.EMPTY);
    }

    private static UpdateBlockPacket block(int layer, int id) {
        return block(Vector3i.from(2, 64, 3), layer, id);
    }

    private static UpdateBlockPacket block(Vector3i position, int layer, int id) {
        var packet = new UpdateBlockPacket();
        packet.setBlockPosition(position);
        packet.setDataLayer(layer);
        packet.setDefinition(definition(id));
        return packet;
    }

    private static final class Harness {
        final CultPlayer player = mock(CultPlayer.class);
        final GeyserBlockUpdates updates = new GeyserBlockUpdates();
        final GeyserBlockStateMappings.Tables tables;
        final List<Consumer<CultPlayer>> receipts = new ArrayList<>();

        Harness() throws Exception {
            // Each of these blocks is its own translation, as for every state Geyser maps one to one.
            int[] sent = new int[DataTables.defaults().registry().stateCount()];
            for (int state = 0; state < sent.length; state++) sent[state] = state;
            tables = new GeyserBlockStateMappings.Tables(
                    Map.of(
                            17, SAND, 54, CHEST, 99, BlockIds.AIR.defaultState(), 101, BlockIds.WATER.defaultState(),
                            104, STAIRS),
                    sent);
            player.compensatedWorld = mock(CompensatedWorld.class);
            when(player.compensatedWorld.getServerSentBlockStateIdAt(any())).thenReturn(BlockIds.AIR.defaultState());
            player.y = 64;
            var received = CultPlayer.class.getField("lastTransactionReceived");
            received.setAccessible(true);
            received.set(player, new AtomicInteger());
        }

        void serverSent(BlockPos position, int state) {
            when(player.compensatedWorld.getServerSentBlockStateIdAt(position)).thenReturn(state);
        }

        void capture(BedrockPacket packet) {
            updates.capture(packet, BedrockCoordinateFrame.IDENTITY, tables);
        }

        void end() {
            updates.end(player, receipts::add);
        }

        void finish() {
            updates.finish(player, receipts::add);
        }
    }
}
