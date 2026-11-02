package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import org.junit.jupiter.api.Test;

/** MCP-Reborn 26.3 PistonBaseBlock#triggerEvent on a sticky retraction, as the client runs it. */
class StickyPistonRetractTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPos PISTON = new BlockPos(3, FLOOR_Y, 3);
    private static final BlockPos HEAD = PISTON.above();
    private static final BlockPos PUSHED = HEAD.above();
    private static final int STICKY =
            REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "up");
    private static final int SLIME = BlockIds.SLIME_BLOCK.defaultState();

    @Test
    void aBlockStillMovingTwoAheadIsFinalTickedInPlaceInsteadOfPulled() throws Exception {
        try (var f = new JavaTickFixture()) {
            extend(f);
            // PistonMovingBlockEntity keeps the client entity for five death ticks after its two passes.
            for (int tick = 0; tick < 4; tick++) f.tickEnd();
            blockEvent(f, 1);
            assertEquals(SLIME, f.player.compensatedWorld.getBlockStateIdAt(PUSHED));
            assertEquals(BlockIds.AIR.defaultState(), f.player.compensatedWorld.getBlockStateIdAt(HEAD));
        }
    }

    @Test
    void aSettledBlockTwoAheadIsPulled() throws Exception {
        try (var f = new JavaTickFixture()) {
            extend(f);
            for (int tick = 0; tick < 8; tick++) f.tickEnd();
            assertEquals(SLIME, f.player.compensatedWorld.getBlockStateIdAt(PUSHED));
            blockEvent(f, 1);
            assertEquals(BlockIds.AIR.defaultState(), f.player.compensatedWorld.getBlockStateIdAt(PUSHED));
            assertTrue(BlockIds.is(f.player.compensatedWorld.getBlockStateIdAt(HEAD), BlockIds.MOVING_PISTON));
        }
    }

    private static void extend(JavaTickFixture f) {
        f.player.compensatedWorld.updateBlock(PISTON, STICKY);
        f.player.compensatedWorld.updateBlock(HEAD, SLIME);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        blockEvent(f, 0);
    }

    private static void blockEvent(JavaTickFixture f, int action) {
        var packet = new ClientboundBlockEvent(PISTON, action, 1, REGISTRY.blockIndex(STICKY));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
