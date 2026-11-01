package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LocalPlayer ticks on either side of the transaction-delayed mount boundary. */
class PendingMountMovementTest {
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 4, 0.5);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    @Test
    void pendingMountMovementUpdatesRotationAndChecksUseItemBeforeCancellingTheWire() throws Exception {
        try (var f = pendingMount()) {
            useItem(f, 35, 12);
            var event = posRot(f, SEAT, 35, 12);
            f.tickEnd();

            assertEquals(0.0, f.player.checkManager.getCheck(BadPacketsJ.class).violations);
            assertExactPrediction(f);
            assertEquals(35, f.player.xRot);
            assertEquals(12, f.player.yRot);
            assertTrue(event.isCancelled(), "Paper must not receive ordinary movement after its mount");
        }
    }

    @Test
    void pendingMountMovementStillFlagsMismatchedUseItemRotation() throws Exception {
        try (var f = pendingMount()) {
            useItem(f, 70, 12);
            var event = posRot(f, SEAT, 35, 12);
            f.tickEnd();

            assertTrue(f.player.checkManager.getCheck(BadPacketsJ.class).violations > 0);
            assertExactPrediction(f);
            assertTrue(event.isCancelled());
        }
    }

    @Test
    void confirmedMountStillCancelsOrdinaryMovementBeforePrediction() throws Exception {
        try (var f = pendingMount()) {
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            PredictionResult before =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            var event = posRot(f, SEAT, 35, 12);
            f.tickEnd();

            assertTrue(event.isCancelled());
            assertSame(before, f.player.checkManager.getSimulationProcessor().getLastPrediction());
            assertEquals(0, f.player.xRot, "rejected mounted movement must not update rotation");
        }
    }

    @Test
    void rotationOnlyTickBeforeTheMountPongIsPredicted() throws Exception {
        try (var f = pendingMountOnFloor()) {
            PredictionResult before =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            var rot = new ServerboundMovePlayer(0, 0, 0, 35, 12, true, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            PredictionResult after =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertNotSame(before, after, "an unmounted client's Rot must advance prediction");
            assertExactPrediction(f);
            f.tickEnd();
            assertSame(after, f.player.checkManager.getSimulationProcessor().getLastPrediction());
        }
    }

    @Test
    void idleTickBeforeTheMountPongIsPredicted() throws Exception {
        try (var f = pendingMountOnFloor()) {
            // A previously loaded LocalPlayer can send no movement at all in an idle client tick.
            f.player.packetStateData.playerLoadedIntoLevel = true;
            PredictionResult before =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            f.tickEnd();
            assertNotSame(before, f.player.checkManager.getSimulationProcessor().getLastPrediction());
            assertExactPrediction(f);
        }
    }

    @Test
    void shiftReleasedDuringPassiveRidingDoesNotSlowTheFirstFreeTick() throws Exception {
        try (var f = ridingAfterSneaking()) {
            f.input(NONE);
            for (int tick = 0; tick < 3; tick++) f.rideTick();
            dismount(f);
            f.input(FORWARD);
            PredictionResult result = f.tick(SEAT.add(new Vec3(0, 0, 0.02F * 0.98F)), false);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void shiftHeldThroughDismountStillRequiresSneakSlowdown() throws Exception {
        try (var f = ridingAfterSneaking()) {
            for (int tick = 0; tick < 3; tick++) f.rideTick();
            dismount(f);
            // aiStep crouches from the previous tick's shift even if input.tick releases it now.
            f.input(FORWARD);
            PredictionResult result = f.tick(SEAT.add(new Vec3(0, 0, 0.02F * 0.98F)), false);
            assertRejected(result, "NoSneakSlow");
        }
    }

    private static JavaTickFixture pendingMount() throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        mount(f);
        return f;
    }

    private static JavaTickFixture pendingMountOnFloor() throws Exception {
        var f = new JavaTickFixture();
        f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
        mount(f);
        return f;
    }

    private static void mount(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, SEAT, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        assertTrue(f.player.compensatedEntities.vehicles.hasPlayerPassengerState());
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
    }

    private static JavaTickFixture ridingAfterSneaking() throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.input(SHIFT);
        assertTrue(exact(f.tick(SEAT, false)));
        mount(f);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.rideTick();
        return f;
    }

    private static void dismount(JavaTickFixture f) {
        var before = f.player.getSetbackTeleportUtil().pendingTeleports.stream()
                .map(teleport -> teleport.getTeleportId())
                .toList();
        f.setPassengers(VEHICLE, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        for (var teleport : List.copyOf(f.player.getSetbackTeleportUtil().pendingTeleports)) {
            if (before.contains(teleport.getTeleportId())) continue;
            Vec3 target = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    teleport.getTeleportId(),
                    new Vec3d(target.x, target.y, target.z),
                    teleport.getFinalYaw(),
                    teleport.getFinalPitch());
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
            assertFalse(event.isCancelled());
        }
        assertFalse(f.player.compensatedEntities.vehicles.hasPlayerPassengerState());
    }

    private static void useItem(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundUseItem(Hand.MAIN_HAND, 1, yaw, pitch);
        f.listener.onUseItem(f.event(ServerboundPackets.USE_ITEM, packet), f.player, packet);
    }

    private static PacketReceiveEvent<ServerboundMovePlayer> posRot(
            JavaTickFixture f, Vec3 to, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(to.x, to.y, to.z, yaw, pitch, false, false, true, true);
        var event = f.event(ServerboundPackets.MOVE_PLAYER, packet);
        f.listener.onMovePlayer(event, f.player, packet);
        return event;
    }

    private static void assertExactPrediction(JavaTickFixture f) {
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        assertTrue(exact(result), describe(result));
    }
}
