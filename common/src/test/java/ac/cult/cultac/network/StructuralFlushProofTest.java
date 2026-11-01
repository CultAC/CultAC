package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A packet that closes the flush bundle is proven by a ping after it, not by the bundle it closes. */
class StructuralFlushProofTest {
    @Test
    void aRespawnAfterAnOpenFlushBundleGetsItsOwnProof() {
        try (var fixture = RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            var flushProof = new AtomicInteger();
            var offered = new AtomicInteger(-1);
            var respawnProof = new AtomicInteger();
            fixture.registrar.register(routes -> {
                routes.send(
                        ClientboundPackets.ENTITY_MOTION,
                        PacketListenerPriority.LOW,
                        (event, player, packet) -> flushProof.set(player.appendFlushProofTransaction(event)));
                routes.send(ClientboundPackets.RESPAWN, PacketListenerPriority.LOW, (event, player, packet) -> {
                    offered.set(event.flushPing());
                    respawnProof.set(player.appendTrailingProofTransaction(event).transaction());
                });
            });
            // A flush closes the flush bundle; the watchdog's would land between the writes.
            fixture.player.nettyScheduler.removeScheduler();
            fixture.channel.runPendingTasks();
            fixture.channel.write(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
            assertTrue(fixture.connection.flushBundleOpen());
            fixture.channel.write(respawn(fixture));

            assertTrue(flushProof.get() > 0, "The motion opens the flush bundle with a ping");
            // ClientPacketListener#handleRespawn runs after the closed bundle's task has answered its ping.
            assertEquals(0, offered.get(), "The respawn is not offered the bundle it closes");
            assertTrue(respawnProof.get() > flushProof.get(), "The respawn is proven by a ping after it");
            assertFalse(fixture.connection.flushBundleOpen());
            fixture.channel.flush();
            fixture.channel.releaseOutbound();
        }
    }

    /** A 1.21.11 respawn into the overworld, keeping nothing. */
    private static ByteBuf respawn(RecordReceiveFixture fixture) {
        int id = fixture.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:respawn");
        assertTrue(id >= 0);
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(frame, id);
        // CommonPlayerSpawnInfo, then the data to keep.
        Wire.writeVarInt(frame, 0);
        Wire.writeString(frame, "minecraft:overworld", 32767);
        frame.writeLong(0).writeByte(0).writeByte(-1).writeBoolean(false).writeBoolean(false);
        frame.writeBoolean(false);
        Wire.writeVarInt(frame, 0);
        Wire.writeVarInt(frame, 63);
        return frame.writeByte(0);
    }
}
