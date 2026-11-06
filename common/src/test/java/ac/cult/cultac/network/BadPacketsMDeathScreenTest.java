package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsM;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientCommand;
import ac.cult.cultac.protocol.value.ClientCommandAction;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The death screen a lethal DATA_HEALTH_ID opens, and the respawn its button sends once health is back. */
class BadPacketsMDeathScreenTest {

    @Test
    void respawnFromTheDeathScreenALethalMetadataHealthOpenedIsAccepted() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(metadataHealth(f, 0.0F));
            f.pongThrough(setHealth(f, 20.0F));
            respawn(f);
            assertEquals(0, flags(f));
        }
    }

    @Test
    void respawnWhileAliveStillFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(metadataHealth(f, 20.0F));
            respawn(f);
            assertEquals(1, flags(f));
        }
    }

    @Test
    void respawnBeforeTheLethalMetadataIsProvenStillFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            int proof = metadataHealth(f, 0.0F);
            respawn(f);
            assertEquals(1, flags(f));
            f.pongThrough(proof);
        }
    }

    @Test
    void aSecondRespawnFromTheSameDeathScreenStillFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(metadataHealth(f, 0.0F));
            f.pongThrough(setHealth(f, 20.0F));
            respawn(f);
            respawn(f);
            assertEquals(1, flags(f));
        }
    }

    @Test
    void respawnAfterMetadataRestoresHealthStillFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.packetStateData.showsDeathScreen = false;
            f.pongThrough(metadataHealth(f, 0.0F));
            f.pongThrough(metadataHealth(f, 20.0F));
            respawn(f);
            assertEquals(1, flags(f));
        }
    }

    private static int metadataHealth(JavaTickFixture f, float health) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(EntityMetadata.Entry.health(
                        WatchableIndexUtil.LIVING_HEALTH, f.fixture.data.version(), health)));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static int setHealth(JavaTickFixture f, float health) {
        var packet = new ClientboundSetHealth(health, 20, 5.0F);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_HEALTH, packet, false);
        new PacketPlayerRespawn().onSetHealth(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static void respawn(JavaTickFixture f) {
        var packet = new ServerboundClientCommand(ClientCommandAction.PERFORM_RESPAWN);
        f.player.checkManager
                .getListener(BadPacketsM.class)
                .onClientCommand(f.event(ServerboundPackets.CLIENT_COMMAND, packet), f.player, packet);
    }

    private static double flags(JavaTickFixture f) {
        return f.player.checkManager.getListener(BadPacketsM.class).violations;
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
