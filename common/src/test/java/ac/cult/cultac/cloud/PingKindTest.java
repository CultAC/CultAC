package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PingKindTest {
    @Test
    void aSpeedEffectsAfterSendProofIsTrailing() {
        try (CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.SERVER, true)) {
            CultPlayer player = fixture.player;
            player.recordProvenance(true);
            var effect = new ClientboundUpdateMobEffect(player.entityID, "minecraft:speed", 1);
            PacketSendEvent<ClientboundUpdateMobEffect> event =
                    sendEvent(player, ClientboundPackets.UPDATE_MOB_EFFECT, effect);
            int before = player.lastTransactionSent.get();
            player.packetEntityReplication.onUpdateMobEffect(event, player, effect);
            event.getTasksAfterSend().forEach(Runnable::run);

            assertEquals(before + 1, player.lastTransactionSent.get());
            Integer wire = player.wireIdOf(player.lastTransactionSent.get());
            assertNotNull(wire);
            assertEquals(CultPlayer.PING_TRAILING, player.pingProvenance(wire));
        }
    }

    @Test
    void theRespawnsWritesBeforeProofIsLeading() {
        try (CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.SERVER, true)) {
            CultPlayer player = fixture.player;
            player.recordProvenance(true);
            int overworld = ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld");
            var respawn = new ClientboundRespawn(
                    new PlayerSpawnInfo(overworld, "minecraft:overworld", GameMode.SURVIVAL), (byte) 0);
            PacketSendEvent<ClientboundRespawn> event = sendEvent(player, ClientboundPackets.RESPAWN, respawn);
            new PacketPlayerWindow().onRespawn(event, player, respawn);

            assertEquals(1, event.getWritesBeforeSend().size());
            int wire = ((ClientboundPing) event.getWritesBeforeSend().get(0).packet()).id();
            assertEquals(Integer.valueOf(wire), player.wireIdOf(player.lastTransactionSent.get()));
            assertEquals(CultPlayer.PING_LEADING, player.pingProvenance(wire));
        }
    }

    @Test
    void provenanceOffAllocatesNoRing() throws Exception {
        List<Integer> off = new ArrayList<>();
        List<Integer> on = new ArrayList<>();
        try (CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.SERVER, true)) {
            CultPlayer player = fixture.player;
            int first = player.lastTransactionSent.get();
            pings(player, off);
            assertEquals(first + 3, player.lastTransactionSent.get());
            CultPlayer.TrackedTransaction deferred = player.createTrackedTransactionPacketForDeferredSend();
            assertNotNull(deferred);
            assertEquals(
                    0,
                    player.pingProvenance(((ClientboundPing) deferred.packet().packet()).id()));
            assertNull(player.wireIdOf(deferred.transaction()));
            assertNull(ring(player));
        }
        try (CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.SERVER, true)) {
            fixture.player.recordProvenance(true);
            pings(fixture.player, on);
            assertNotNull(ring(fixture.player));
            fixture.player.recordProvenance(false);
            assertNull(fixture.player.wireIdOf(fixture.player.lastTransactionSent.get()));
        }
        assertEquals(on, off);
    }

    /** Writes a leading, a trailing and a deferred ping, collecting each transaction. */
    private static void pings(CultPlayer player, List<Integer> sent) {
        player.sendTransaction();
        sent.add(player.lastTransactionSent.get());
        player.sendTrailingTransaction();
        sent.add(player.lastTransactionSent.get());
        CultPlayer.TrackedTransaction deferred = player.createTrackedTransactionPacketForDeferredSend();
        assertNotNull(deferred);
        player.markTrackedTransactionPacketSent(deferred);
        sent.add(deferred.transaction());
    }

    private static Object ring(CultPlayer player) throws Exception {
        Field field = CultPlayer.class.getDeclaredField("recentWireIds");
        field.setAccessible(true);
        return field.get(player);
    }

    private static <R extends ClientboundPacket> PacketSendEvent<R> sendEvent(
            CultPlayer player, PacketType<R> type, R packet) {
        return new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, type, packet, false);
    }
}
