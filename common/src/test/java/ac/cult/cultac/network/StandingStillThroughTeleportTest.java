package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A standing client sends nothing while LocalPlayer#sendPosition sees no move within 2.0E-4 of xLast, no turn, and
 * onGround equal to lastOnGround, until its 20-tick position reminder. A player_position leaves Entity#onGround,
 * lastOnGround and xLast alone, so a teleport onto the spot the player stands on is followed by no packet at all; a
 * respawn's new LocalPlayer starts from xLast 0 and lastOnGround false, so its first tick sends a position. A ridden
 * tick sends passenger Rot outside sendPosition and writes neither xLast nor lastOnGround.
 */
class StandingStillThroughTeleportTest {
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 SPAWN = new Vec3(4.5, FLOOR_Y, 4.5);
    /** Tick ends a standing client sends before its position reminder. */
    private static final int UNSENT = 19;
    private static final int SEAT_ENTITY = 100;

    @Test
    void aTeleportOntoTheStandingSpotIsFollowedByUnsentGroundedTicks() throws Exception {
        try (var f = standing(new JavaTickFixture())) {
            List<PredictionResult> committed = new ArrayList<>();
            int teleport = sendTeleport(f, 1, STANDING);
            f.pongThrough(teleport);
            accept(f, 1, STANDING);
            committed.addAll(tickEnds(f, UNSENT));
            committed.add(f.tick(STANDING, true));
            assertClean(f, committed);
        }
    }

    @Test
    void aTeleportOntoTheStandingSpotAnsweredLateKeepsTheUnsentTicksGrounded() throws Exception {
        try (var f = standing(new JavaTickFixture())) {
            List<PredictionResult> committed = new ArrayList<>();
            int teleport = sendTeleport(f, 1, STANDING);
            // The client stands through ticks before it handles the teleport.
            committed.addAll(tickEnds(f, 6));
            f.pongThrough(teleport);
            accept(f, 1, STANDING);
            // A ping sent after the teleport stays unanswered for a while.
            int later = f.player.sendTrailingTransaction();
            committed.addAll(tickEnds(f, 8));
            f.pongThrough(later);
            committed.addAll(tickEnds(f, UNSENT - 8));
            committed.add(f.tick(STANDING, true));
            assertClean(f, committed);
        }
    }

    @Test
    void aTeleportOntoTheStandingSpotOfA1_21_11ClientKeepsItsUnsentTicksGrounded() throws Exception {
        try (var f = standing(new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11))) {
            List<PredictionResult> committed = new ArrayList<>();
            int teleport = sendTeleport(f, 1, STANDING);
            f.pongThrough(teleport);
            f.acceptTeleport(1);
            // ClientPacketListener#handleMovePlayer sends PosRot(onGround=false) after the acceptance.
            var posRot = new ServerboundMovePlayer(
                    STANDING.x, STANDING.y, STANDING.z, f.player.xRot, f.player.yRot, false, false, true, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, posRot), f.player, posRot);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            committed.addAll(tickEnds(f, UNSENT));
            committed.add(f.tick(STANDING, true));
            assertClean(f, committed);
        }
    }

    @Test
    void aRespawnedPlayerStandingAtItsSpawnSendsItsGroundFirst() throws Exception {
        try (var f = standing(new JavaTickFixture())) {
            List<PredictionResult> committed = new ArrayList<>();
            DeadPlayerSetbackTest.respawn(
                    f, 0, f.player.compensatedWorld.getLastClientboundDimension().dimension());
            int teleport = sendTeleport(f, 1000, SPAWN);
            f.pongThrough(teleport);
            accept(f, 1000, SPAWN);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.pongThrough(f.player.lastTransactionSent.get());
            committed.add(f.tick(SPAWN, true));
            committed.addAll(tickEnds(f, UNSENT));
            committed.add(f.tick(SPAWN, true));
            assertClean(f, committed);
        }
    }

    @Test
    void aSeatLeftByATeleportBackToTheSpotTheRideStartedFromIsFollowedByUnsentGroundedTicks() throws Exception {
        try (var f = standing(new JavaTickFixture())) {
            List<PredictionResult> committed = new ArrayList<>();
            f.player.compensatedEntities.addEntity(SEAT_ENTITY, EntityTypeIds.ARMOR_STAND, STANDING, 0, 0, 0);
            f.setPassengers(SEAT_ENTITY, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            // Player#tick clears onGround each ridden tick; the passenger's Rot carries it, not lastOnGround.
            for (int tick = 0; tick < 5; tick++) {
                var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, false, false, false, true);
                f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
                f.tickEnd();
            }
            f.setPassengers(SEAT_ENTITY, List.of());
            int teleport = sendTeleport(f, 1, STANDING);
            f.pongThrough(teleport);
            accept(f, 1, STANDING);
            committed.addAll(tickEnds(f, UNSENT - 5));
            committed.add(f.tick(STANDING, true));
            assertClean(f, committed);
        }
    }

    private static JavaTickFixture standing(JavaTickFixture f) {
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(STANDING, Vec3.ZERO);
        for (int tick = 0; tick < 3; tick++) {
            PredictionResult result = f.tick(STANDING, true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
        }
        return f;
    }

    /** A plugin's player_position to {@code target}, unanswered; the ping leading it. */
    private static int sendTeleport(JavaTickFixture f, int id, Vec3 target) {
        var packet = new ClientboundPlayerPosition(
                id, new Vec3d(target.x, target.y, target.z), new Vec3d(0, 0, 0), 0, 0, Set.of());
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        return leading;
    }

    private static void accept(JavaTickFixture f, int id, Vec3 target) {
        var packet = new ServerboundAcceptTeleportation(id, new Vec3d(target.x, target.y, target.z), 0, 0);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the teleport acceptance was rejected");
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** {@code ticks} client ticks that send only ServerboundClientTickEndPacket; the predictions they committed. */
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

    private static void assertClean(JavaTickFixture f, List<PredictionResult> committed) {
        for (PredictionResult result : committed) {
            assertNotNull(result);
            assertTrue(result.getFlags().isEmpty(), JavaTickFixture.describe(result));
        }
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        assertFalse(teleports.isPendingSetback(), teleports.getDebugStrings());
        assertTrue(teleports.pendingTeleports.isEmpty(), teleports.getDebugStrings());
    }
}
