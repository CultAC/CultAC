package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.events.packets.worldreader.BasePacketWorldReader;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaPistonTimeProofTest {
    @Test
    @SuppressWarnings("unchecked")
    void aProvenTimeCorrectionCanPreserveThePreviousTicksPistonLimit() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(101, Map.of()));
            var type = (PacketType<WorldPackets.TimeUpdate>) ClientPacketCodecs.connectionCatalog().stream()
                    .filter(t -> t.recordClass() == WorldPackets.TimeUpdate.class)
                    .findFirst()
                    .orElseThrow();
            var packet = new WorldPackets.TimeUpdate(100, Map.of());
            var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, true);
            // A proof already belonging to this packet's flush, after an earlier piston event allocated it.
            int proof = f.player.lastTransactionSent.incrementAndGet();
            event.receiptProof(proof);
            new BasePacketWorldReader().onTimeUpdate(event, f.player, packet);
            assertEquals(101, f.player.compensatedWorld.clientGameTime());
            f.pongThrough(proof);
            assertEquals(
                    100,
                    f.player.compensatedWorld.clientGameTime(),
                    "ClientPacketListener handles the time update in the proven bundle, before the next tick");
            var previous = new ClientPistonDeltas(0.51, 0, 0, 100);
            assertEquals(
                    Vec3.ZERO,
                    previous.limit(new Vec3(0.51, 0, 0), f.player.compensatedWorld.clientGameTime())
                            .movement());
        }
    }
}
