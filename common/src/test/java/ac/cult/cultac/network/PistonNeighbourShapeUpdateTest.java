package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * MCP-Reborn PistonBaseBlock#triggerEvent and #moveBlocks run on the client's own Level when it handles the block
 * event, and PistonMovingBlockEntity settles in its Level#tickBlockEntities. Their Level#setBlock and
 * BlockStateBase#updateNeighbourShapes calls update the neighbors' shapes there, a server tick before ServerLevel#tick
 * broadcasts the same changes: a sticky piston pulling a fence away disconnects the fences beside it at once.
 */
class PistonNeighbourShapeUpdateTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final int SOUTH = 3;
    private static final BlockPos PISTON = new BlockPos(0, FLOOR_Y, -2);
    private static final BlockPos HEAD = new BlockPos(0, FLOOR_Y, -1);
    private static final BlockPos CENTER = new BlockPos(0, FLOOR_Y, 0);
    private static final BlockPos WEST = new BlockPos(-1, FLOOR_Y, 0);
    private static final BlockPos EAST = new BlockPos(1, FLOOR_Y, 0);
    private static final BlockPos BEHIND = new BlockPos(0, FLOOR_Y, 1);
    private static final int STICKY =
            REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "south");
    private static final int EXTENDED = REGISTRY.with(STICKY, "extended", "true");
    private static final int STICKY_HEAD = REGISTRY.with(
            REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "south"), "type", "sticky");
    private static final int FENCE = REGISTRY.block("minecraft:oak_fence").defaultState();

    /** The reported layout: a fence pushed into a gap between three fences, then pulled back out. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void pullingAFenceAwayDisconnectsTheFencesBesideItAsTheClientHandlesTheEvent(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            var world = f.player.compensatedWorld;
            world.updateBlock(new BlockPos(-2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            world.updateBlock(new BlockPos(2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            world.updateBlock(PISTON, EXTENDED);
            world.updateBlock(HEAD, STICKY_HEAD);
            world.updateBlock(CENTER, fence("north", "east", "south", "west"));
            world.updateBlock(WEST, fence("east", "west"));
            world.updateBlock(EAST, fence("east", "west"));
            world.updateBlock(BEHIND, fence("north"));
            settle(f);

            blockEvent(f, 1);
            assertEquals(BlockIds.AIR.defaultState(), world.getBlockStateIdAt(CENTER));
            assertTrue(BlockIds.is(world.getBlockStateIdAt(HEAD), BlockIds.MOVING_PISTON));
            assertEquals(fence("west"), world.getBlockStateIdAt(WEST));
            assertEquals(fence("east"), world.getBlockStateIdAt(EAST));
            assertEquals(FENCE, world.getBlockStateIdAt(BEHIND));

            // Five death ticks after its two passes, the pulled fence settles against the unextended piston.
            for (int tick = 0; tick < 8; tick++) f.tickEnd();
            assertEquals(STICKY, world.getBlockStateIdAt(PISTON));
            assertEquals(fence("north"), world.getBlockStateIdAt(HEAD));

            // Pushed back, it reconnects the three fences only as it settles: a MOVING_PISTON has no sturdy face.
            blockEvent(f, 0);
            assertTrue(BlockIds.is(world.getBlockStateIdAt(CENTER), BlockIds.MOVING_PISTON));
            assertEquals(fence("west"), world.getBlockStateIdAt(WEST));
            for (int tick = 0; tick < 8; tick++) f.tickEnd();
            assertEquals(fence("north", "east", "south", "west"), world.getBlockStateIdAt(CENTER));
            assertEquals(fence("east", "west"), world.getBlockStateIdAt(WEST));
            assertEquals(fence("east", "west"), world.getBlockStateIdAt(EAST));
            assertEquals(fence("north"), world.getBlockStateIdAt(BEHIND));
        }
    }

    /**
     * A player against the arm a pulled block's neighbor grew toward it walks through where that arm was in the tick
     * the client handles the event. The pulled block is an open fence gate, which the fences connect to but nothing
     * collides with, so neither the moving block nor its shove touches the player.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPlayerWalksThroughTheArmThePulledBlockLeftInTheEventsTick(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            var world = f.player.compensatedWorld;
            int gate = REGISTRY.with(
                    REGISTRY.with(REGISTRY.block("minecraft:oak_fence_gate").defaultState(), "facing", "south"),
                    "open",
                    "true");
            world.updateBlock(new BlockPos(2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            world.updateBlock(PISTON, EXTENDED);
            world.updateBlock(HEAD, STICKY_HEAD);
            world.updateBlock(CENTER, gate);
            world.updateBlock(EAST, fence("east", "west"));
            settle(f);

            // The east edge rests on the fence's west arm, which the gate holds out to x = 1.
            Vec3 position = new Vec3(0.7, FLOOR_Y, 0.3);
            f.place(position, new Vec3(0.1, -0.08D * 0.98F, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            blockEvent(f, 1);
            assertEquals(fence("east"), world.getBlockStateIdAt(EAST));

            var result = f.tick(position.add(0.1, 0, 0), true);
            assertEquals(0.0D, result.getOffset(), 1.0E-9D);
            assertFalse(result.hasEffectiveFlags(), () -> "flags at offset " + result.getOffset());
        }
    }

    private static int fence(String... connected) {
        int state = FENCE;
        for (String side : connected) state = REGISTRY.with(state, side, "true");
        return state;
    }

    private static void settle(JavaTickFixture f) {
        // Let the block-change timing uncertainty expire at client tick boundaries.
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void blockEvent(JavaTickFixture f, int action) {
        var packet = new ClientboundBlockEvent(PISTON, action, SOUTH, REGISTRY.blockIndex(STICKY));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
