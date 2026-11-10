package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * LocalPlayer#tickDeath removes the dead local player at deathTime 20. That tick skips LivingEntity#aiStep, and
 * ClientLevel#tickEntities skips the player from the next tick until a respawn creates a new one, while
 * Minecraft#tick still ends every client tick.
 */
class DeadLocalPlayerRemovalTest {
    private static final Vec3 STANDING = new Vec3(.5, JavaTickFixture.FLOOR_Y, .5);
    private static final Vec3 SPAWN = new Vec3(4.5, JavaTickFixture.FLOOR_Y, 4.5);

    @Test
    void aRemovedPlayerOnTheDeathScreenIsNotSimulatedUntilItRespawns() throws Exception {
        try (var f = standingPlayer()) {
            die(f);
            // Dead ticks 1 to 19 still run aiStep: an immobile travel at rest.
            List<PredictionResult> dying = idleTicks(f, 19);
            assertEquals(19, dying.size(), "every dead tick before the removal travels");
            dying.forEach(DeadLocalPlayerRemovalTest::assertExact);

            List<PredictionResult> removed = idleTicks(f, 41);
            assertTrue(removed.isEmpty(), "the removed player travelled: " + describe(removed));
            assertNoSetback(f);

            respawnAt(f, SPAWN);
            assertExact(f.tick(SPAWN, true));
            assertEquals(1, idleTicks(f, 1).size(), "the respawned player's idle tick travels");
        }
    }

    @Test
    void aRemovedPlayerTheServerPlacesInTheAirStaysThere() throws Exception {
        try (var f = standingPlayer()) {
            die(f);
            idleTicks(f, 20);
            // ClientPacketListener#handleMovePlayer still places and answers for the removed player.
            Vec3 air = STANDING.add(0, 2, 0);
            f.playerPosition(air, Vec3.ZERO, Set.of());

            List<PredictionResult> removed = idleTicks(f, 40);
            assertTrue(removed.isEmpty(), "the removed player travelled: " + describe(removed));
            assertNoSetback(f);

            respawnAt(f, SPAWN);
            assertExact(f.tick(SPAWN, true));
        }
    }

    @Test
    void aPlayerWhoDiesMidFallIsNotSimulatedOnceRemoved() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.nettyScheduler.removeScheduler();
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            Vec3 at = STANDING.add(0, 40, 0);
            double motionY = -0.08 * 0.98F;
            f.place(at, new Vec3(0, motionY, 0));
            for (int tick = 0; tick < 3; tick++) {
                at = at.add(0, motionY, 0);
                motionY = (motionY - 0.08) * 0.98F;
                assertExact(f.tick(at, false));
            }
            die(f);
            // Dead ticks 1 to 19 still fall and send their position; the dead client's movement is cancelled.
            for (int tick = 0; tick < 19; tick++) {
                at = at.add(0, motionY, 0);
                motionY = (motionY - 0.08) * 0.98F;
                acceptInOrder(f);
                assertTrue(f.move(at, false).isCancelled(), "the dead client's movement reaches the server");
                f.tickEnd();
            }

            List<PredictionResult> removed = idleTicks(f, 40);
            assertTrue(removed.isEmpty(), "the removed player travelled: " + describe(removed));
            assertNoSetback(f);
        }
    }

    @Test
    void theRemovalTicksPositionReminderIsDeadMovement() throws Exception {
        // 26.2's LocalPlayer#tick runs sendPosition after tickDeath; 26.3's Minecraft#tick sends nothing once removed.
        try (var f = standingPlayer(new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_2))) {
            die(f);
            idleTicks(f, 19);
            // The removal tick skips aiStep; LocalPlayer#sendPosition still sends its 20-tick reminder.
            PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(f.move(STANDING, true).isCancelled(), "the dead client's movement reaches the server");
            f.tickEnd();
            assertTrue(
                    f.player.checkManager.getSimulationProcessor().getLastPrediction() == before,
                    "the removal tick travelled");
            assertTrue(idleTicks(f, 20).isEmpty());
            assertNoSetback(f);
        }
    }

    @Test
    void deathStartsInTheTickThatHandlesTheLethalHealth() throws Exception {
        try (var f = standingPlayer()) {
            int metadata = metadataHealth(f, 0.0F);
            int health = setHealth(f, 0.0F);
            // The client handles neither until two ticks later.
            assertEquals(2, tickEnds(f, 2).size());
            f.pongThrough(metadata);
            assertEquals(2, tickEnds(f, 2).size());
            f.pongThrough(health);
            assertEquals(17, tickEnds(f, 17).size(), "deathTime 19 still travels");
            assertTrue(tickEnds(f, 1).isEmpty(), "deathTime 20 skips aiStep");
            assertTrue(tickEnds(f, 20).isEmpty());
        }
    }

    @Test
    void deathStartsAtTheLethalSetHealthWhenTheClientHandlesItFirst() throws Exception {
        try (var f = standingPlayer()) {
            int health = setHealth(f, 0.0F);
            int metadata = metadataHealth(f, 0.0F);
            f.pongThrough(health);
            assertEquals(19, tickEnds(f, 19).size(), "deathTime 19 still travels");
            f.pongThrough(metadata);
            assertTrue(tickEnds(f, 1).isEmpty(), "deathTime 20 skips aiStep");
            assertTrue(tickEnds(f, 20).isEmpty());
        }
    }

    @Test
    void healthAfterTheRemovalDoesNotBringThePlayerBack() throws Exception {
        try (var f = standingPlayer()) {
            die(f);
            idleTicks(f, 20);
            f.pongThrough(setHealth(f, 20.0F));
            assertTrue(idleTicks(f, 20).isEmpty(), "only a respawn creates a new LocalPlayer");
        }
    }

    @Test
    void movementAfterTheRemovalIsStillJudged() throws Exception {
        try (var f = standingPlayer()) {
            die(f);
            idleTicks(f, 20);
            // The dead player's movement never reaches prediction.
            assertTrue(f.move(STANDING.add(0, 0.5, 0), false).isCancelled());
            f.tickEnd();

            // Health back without a respawn: nothing a vanilla client sends, so a move is predicted from rest.
            f.pongThrough(setHealth(f, 20.0F));
            JavaTickFixture.assertRejected(f.tick(STANDING.add(0, 0.5, 0), false), "VerticalOffset");
        }
    }

    private static JavaTickFixture standingPlayer() throws Exception {
        return standingPlayer(new JavaTickFixture());
    }

    private static JavaTickFixture standingPlayer(JavaTickFixture f) throws Exception {
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(STANDING, Vec3.ZERO);
        for (int tick = 0; tick < 3; tick++) assertExact(f.tick(STANDING, true));
        return f;
    }

    /** Paper's lethal set_health and DATA_HEALTH_ID, handled in the next client tick's packet phase. */
    private static void die(JavaTickFixture f) {
        setHealth(f, 0.0F);
        f.pongThrough(metadataHealth(f, 0.0F));
    }

    /**
     * Ends {@code ticks} client ticks that send only ServerboundClientTickEndPacket, each answering CultAC's
     * teleports the way ClientPacketListener#handleMovePlayer does; the predictions the tick ends committed.
     */
    private static List<PredictionResult> idleTicks(JavaTickFixture f, int ticks) {
        List<PredictionResult> committed = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            acceptInOrder(f);
            committed.addAll(tickEnds(f, 1));
        }
        return committed;
    }

    /** Ends {@code ticks} client ticks that answer no ping and send only ServerboundClientTickEndPacket. */
    private static List<PredictionResult> tickEnds(JavaTickFixture f, int ticks) {
        List<PredictionResult> committed = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            f.tickEnd();
            PredictionResult after = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            if (after != before) committed.add(after);
        }
        return committed;
    }

    private static void assertNoSetback(JavaTickFixture f) {
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        assertTrue(teleports.pendingTeleports.isEmpty(), teleports.getDebugStrings());
        assertFalse(teleports.isPendingSetback(), teleports.getDebugStrings());
    }

    /** The server's respawn and its placement, answered; the new player has loaded its level. */
    private static void respawnAt(JavaTickFixture f, Vec3 spawn) {
        DeadPlayerSetbackTest.respawn(
                f, 0, f.player.compensatedWorld.getLastClientboundDimension().dimension());
        var packet = new ClientboundPlayerPosition(
                1000, new Vec3d(spawn.x, spawn.y, spawn.z), new Vec3d(0, 0, 0), 0, 0, Set.of());
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        acceptInOrder(f);
        f.player.packetStateData.playerLoadedIntoLevel = true;
    }

    /** The vanilla client handles every sent packet in order, accepting each teleport where it puts it. */
    private static void acceptInOrder(JavaTickFixture f) {
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        TeleportData pending;
        while ((pending = teleports.pendingTeleports.peek()) != null) {
            f.pongThrough(pending.getTransaction());
            Vec3 target = pending.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    pending.getTeleportId(), new Vec3d(target.x, target.y, target.z), 0, 0);
            new PacketServerTeleport()
                    .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
            assertTrue(teleports.pendingTeleports.peek() != pending, "not accepted: " + target);
        }
        f.pongThrough(f.player.lastTransactionSent.get());
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

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static void assertExact(PredictionResult result) {
        assertNotNull(result);
        assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
    }

    private static String describe(List<PredictionResult> results) {
        return results.stream().map(JavaTickFixture::describe).collect(Collectors.joining("; "));
    }
}
