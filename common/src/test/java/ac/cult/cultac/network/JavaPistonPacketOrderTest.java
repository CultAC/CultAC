package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.worldreader.BasePacketWorldReader;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import org.junit.jupiter.api.Test;

class JavaPistonPacketOrderTest {
    @Test
    @SuppressWarnings("unchecked")
    void aLaterBlockUpdateReplacesTheMoverCreatedByAnEarlierEventInTheSameBundle() throws Exception {
        try (var f = new JavaTickFixture()) {
            var pos = new BlockPos(3, JavaTickFixture.FLOOR_Y, 3);
            var registry = DataTables.defaults().registry();
            int piston = registry.with(BlockIds.STICKY_PISTON.defaultState(), "facing", "up");
            f.player.compensatedWorld.updateBlock(pos, piston);
            int proof = f.player.lastTransactionSent.incrementAndGet();
            var packet = new ClientboundBlockEvent(pos, 1, 1, registry.blockIndex(piston));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, true);
            event.receiptProof(proof);
            var reader = new BasePacketWorldReader();
            reader.handleBlockEvent(f.player, event, packet);
            var type = (PacketType<WorldPackets.BlockUpdate>) ClientPacketCodecs.connectionCatalog().stream()
                    .filter(t -> t.recordClass() == WorldPackets.BlockUpdate.class)
                    .findFirst()
                    .orElseThrow();
            var update = new WorldPackets.BlockUpdate(pos, piston);
            var later = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, update, true);
            later.receiptProof(proof);
            reader.handleBlockChange(f.player, later, update);
            // The encoder finishes the batch before running after-send callbacks.
            event.getTasksAfterSend().forEach(Runnable::run);
            later.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(proof);
            assertEquals(piston, f.player.compensatedWorld.getBlockStateIdAt(pos));
            assertTrue(f.player.compensatedWorld.nextBlockEntityPass().isEmpty());
        }
    }

    @Test
    void aPacketNamingAPistonCannotAnimateADifferentCurrentBlock() throws Exception {
        try (var f = new JavaTickFixture()) {
            var pos = new BlockPos(3, JavaTickFixture.FLOOR_Y, 3);
            var registry = DataTables.defaults().registry();
            f.player.compensatedWorld.updateBlock(pos, BlockIds.STONE.defaultState());
            f.player.compensatedWorld.pistons.handleBlockEvent(
                    pos, registry.blockIndex(BlockIds.STICKY_PISTON.defaultState()), 0, 1, 1);
            assertEquals(BlockIds.AIR.defaultState(), f.player.compensatedWorld.getBlockStateIdAt(pos.above()));
            assertTrue(f.player.compensatedWorld.nextBlockEntityPass().isEmpty());
        }
    }
}
