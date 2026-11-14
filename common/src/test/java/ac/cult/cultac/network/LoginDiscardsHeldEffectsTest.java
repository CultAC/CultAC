package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleLogin replaces the LocalPlayer, so an effect or attribute the client handles before a
 * login reaches only the discarded player, however late the client answers its ping.
 */
class LoginDiscardsHeldEffectsTest {
    private static final int JUMP_BOOST_II = 1;
    private static final double DEFAULT_SPEED = 0.10000000149011612D;

    @Test
    void anEffectAnsweredAfterALoginWithTheSameIdStaysWithTheFormerPlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(f.player.lastTransactionSent.get());
            int id = f.player.entityID;
            jumpBoost(f, id);
            login(f, id);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertNull(jumpBoostLevel(f));
        }
    }

    @Test
    void anEffectAnsweredAfterALoginWithAnotherIdStaysWithTheFormerPlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(f.player.lastTransactionSent.get());
            int id = f.player.entityID;
            jumpBoost(f, id + 1);
            login(f, id + 1);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertNull(jumpBoostLevel(f));
        }
    }

    @Test
    void anEffectAnsweredBeforeTheLoginStaysWithTheFormerPlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            int id = f.player.entityID;
            jumpBoost(f, id);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(Integer.valueOf(JUMP_BOOST_II), jumpBoostLevel(f));
            login(f, id);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertNull(jumpBoostLevel(f));
        }
    }

    /** The new server's own effect, sent after the login while the old one's ping is still held, applies. */
    @Test
    void anEffectSentAfterTheLoginAppliesWithTheOldPingsStillHeld() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(f.player.lastTransactionSent.get());
            int id = f.player.entityID;
            jumpBoost(f, id);
            login(f, id);
            jumpBoost(f, id);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(Integer.valueOf(JUMP_BOOST_II), jumpBoostLevel(f));
        }
    }

    @Test
    void anAttributeAnsweredAfterALoginStaysWithTheFormerPlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(f.player.lastTransactionSent.get());
            int id = f.player.entityID;
            movementSpeed(f, id, 1.0);
            login(f, id);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(DEFAULT_SPEED, f.player.compensatedEntities.getSelf().playerSpeed);
        }
    }

    @Test
    void anAttributeSentAfterTheLoginAppliesWithTheOldPingsStillHeld() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.pongThrough(f.player.lastTransactionSent.get());
            int id = f.player.entityID;
            movementSpeed(f, id, 1.0);
            login(f, id);
            movementSpeed(f, id, 0.2);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(0.2, f.player.compensatedEntities.getSelf().playerSpeed);
        }
    }

    private static void jumpBoost(JavaTickFixture f, int entityId) {
        var packet = new ClientboundUpdateMobEffect(entityId, "minecraft:jump_boost", JUMP_BOOST_II);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
        f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void movementSpeed(JavaTickFixture f, int entityId, double speed) {
        var packet = new ClientboundUpdateAttributes(
                entityId, List.of(new AttributeSnapshot("minecraft:movement_speed", speed, List.of())));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void login(JavaTickFixture f, int playerId) {
        int dimension = ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld");
        var spawn = new PlayerSpawnInfo(dimension, "minecraft:overworld", GameMode.SURVIVAL);
        var login = new ClientboundLogin(playerId, false, spawn);
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, login, false);
        new PacketPlayerRespawn().onLogin(event, f.player, login);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static Integer jumpBoostLevel(JavaTickFixture f) {
        return f.player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.JUMP_BOOST);
    }
}
