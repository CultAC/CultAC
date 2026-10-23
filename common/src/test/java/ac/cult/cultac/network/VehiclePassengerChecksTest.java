package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsR;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#sendChanges sends a passenger's Rot every tick, changed or not, and a ServerboundMoveVehiclePacket only
 * when the root vehicle is Entity#isLocalInstanceAuthoritative, which needs a controlling passenger that is the local
 * player. A pig is controlled only by a rider holding a carrot on a stick, so an idle rider sends nothing else.
 */
class VehiclePassengerChecksTest {
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 1, 0.5);

    @Test
    void anInventoryOpenedWhileRidingIsNotRotatedByThePassengersUnchangedRot() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            MultiActionsO check = enabled(f, MultiActionsO.class);
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            for (int tick = 0; tick < 5; tick++) f.rideTick();

            assertEquals(0, check.violations);
        }
    }

    @Test
    void rotatingTheLookInAnInventoryWhileRidingIsStillFlagged() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            MultiActionsO check = enabled(f, MultiActionsO.class);
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.rideTick();
            f.rideTick();
            rot(f, f.player.xRot + 20, f.player.yRot);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void anIdleRiderOfAPigItDoesNotControlStillSendsItsRot() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            ((PacketEntityRideable) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            BadPacketsR check = enabled(f, BadPacketsR.class);
            for (int tick = 0; tick < 20; tick++) passengerTick(f, check, false);
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void aBoatPassengerThatStopsSendingTheVehicleIsStillFlagged() throws Exception {
        try (var f = ride(EntityTypeIds.OAK_BOAT)) {
            BadPacketsR check = enabled(f, BadPacketsR.class);
            // The Rot alone cannot stand for a vehicle the rider controls.
            for (int tick = 0; tick < 20; tick++) passengerTick(f, check, false);
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aBoatPassengerSteeringItIsNotFlagged() throws Exception {
        try (var f = ride(EntityTypeIds.OAK_BOAT)) {
            BadPacketsR check = enabled(f, BadPacketsR.class);
            for (int tick = 0; tick < 20; tick++) passengerTick(f, check, true);
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    @Test
    void aPlayerOnFootThatStopsSendingPositionsIsStillFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            pongAfterTwoSeconds(f, check);

            assertEquals(1, check.violations);
        }
    }

    @Test
    void aRespawnedPlayerThatHasNotLoadedTheLevelIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            BadPacketsR check = enabled(f, BadPacketsR.class);
            respawn(f);
            pongAfterTwoSeconds(f, check);

            assertEquals(0, check.violations);
        }
    }

    /** The server's respawn, whose new LocalPlayer ticks only once ClientPacketListener#hasClientLoaded. */
    private static void respawn(JavaTickFixture f) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertFalse(f.player.packetStateData.playerLoadedIntoLevel, "the respawn did not reach the client");
    }

    private static JavaTickFixture ride(int type) throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(VEHICLE, type, SEAT, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        return f;
    }

    private static <T extends ac.cult.cultac.checks.Check> T enabled(JavaTickFixture f, Class<T> type) {
        f.player.setExperimentalChecks(true);
        T check = f.player.checkManager.getCheck(type);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    /** One ridden client tick as BadPacketsR sees it: the passenger Rot, and the vehicle's move when it controls it. */
    private static void passengerTick(JavaTickFixture f, BadPacketsR check, boolean echoVehicle) {
        var rot =
                new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, f.player.onGround, false, false, true);
        check.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        if (!echoVehicle) return;
        var move = new ServerboundMoveVehicle(
                new Vec3d(SEAT.x, SEAT.y, SEAT.z), f.player.xRot, f.player.yRot, f.player.onGround, true);
        check.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
    }

    private static void rot(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    /** The first pong after more than two seconds without a position, which BadPacketsR judges. */
    private static void pongAfterTwoSeconds(JavaTickFixture f, BadPacketsR check) throws Exception {
        long clock = System.nanoTime() - 5_000_000_000L;
        JavaTickFixture.setField(BadPacketsR.class, check, "clock", clock);
        JavaTickFixture.setField(BadPacketsR.class, check, "lastTransTime", 0L);
        var packet = new ServerboundPong(17);
        var event = f.event(ServerboundPackets.PONG, packet);
        event.setAcceptedTransactionResponse(true);
        check.onPong(event, f.player, packet);
    }
}
