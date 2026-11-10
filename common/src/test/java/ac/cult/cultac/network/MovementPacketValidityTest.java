package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.crash.CrashC;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.data.HeadRotation;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovementPacketValidityTest {
    private static final Vec3 AIR = new Vec3(.5, 70, .5);

    @Test
    void finiteOppositeYawExtremesDoNotOverflowRotationDelta() {
        var from = new HeadRotation(-Float.MAX_VALUE, 0);
        var to = new HeadRotation(Float.MAX_VALUE, 0);
        var update = new RotationUpdate(from, to, to.yaw() - from.yaw(), 0);
        assertEquals(Float.MAX_VALUE, update.getDeltaXRot());
    }

    @Test
    void rotationOnlyNonfiniteValuesNeverReachPlayerState() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(AIR, Vec3.ZERO);
            var packet = new ServerboundMovePlayer(0, 0, 0, Float.POSITIVE_INFINITY, Float.NaN,
                    false, false, false, true);
            var event = f.event(ServerboundPackets.MOVE_PLAYER, packet);
            f.listener.onMovePlayer(event, f.player, packet);

            assertTrue(event.isCancelled());
            assertTrue(f.player.checkManager.getCheck(CrashC.class).violations > 0);
            assertFalse(f.player.packetStateData.receivedMovementThisClientTick);
            assertTrue(Float.isFinite(f.player.xRot));
            assertTrue(Float.isFinite(f.player.yRot));
        }
    }

    @Test
    void nonfiniteVehicleRotationAndPositionAreRejectedBeforePrediction() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityHorse horse = mountAirborneHorse(f);
            passengerRotation(f);

            var infiniteYaw = new ServerboundMoveVehicle(new Vec3d(AIR.x, AIR.y, AIR.z),
                    Float.POSITIVE_INFINITY, 0, false, true);
            var yawEvent = f.event(ServerboundPackets.MOVE_VEHICLE, infiniteYaw);
            f.listener.onMoveVehicle(yawEvent, f.player, infiniteYaw);
            assertTrue(yawEvent.isCancelled());
            assertTrue(Float.isFinite(horse.clientPhysicalYaw));

            var nanPosition = new ServerboundMoveVehicle(new Vec3d(Double.NaN, AIR.y, AIR.z),
                    0, 0, false, true);
            var positionEvent = f.event(ServerboundPackets.MOVE_VEHICLE, nanPosition);
            f.listener.onMoveVehicle(positionEvent, f.player, nanPosition);
            assertTrue(positionEvent.isCancelled());
            assertTrue(Double.isFinite(f.player.x));
            assertTrue(f.player.checkManager.getCheck(CrashC.class).violations >= 2);
        }
    }

    @Test
    void secondHorseMoveBeforeTickEndCannotAdvancePrediction() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityHorse horse = mountAirborneHorse(f);
            passengerRotation(f);
            var first = new ServerboundMoveVehicle(new Vec3d(AIR.x, AIR.y, AIR.z), 0, 0, false, true);
            var firstEvent = f.event(ServerboundPackets.MOVE_VEHICLE, first);
            f.listener.onMoveVehicle(firstEvent, f.player, first);
            assertFalse(firstEvent.isCancelled());
            var prediction = f.player.checkManager.getSimulationProcessor().getLastPrediction();

            passengerRotation(f);
            var second = new ServerboundMoveVehicle(new Vec3d(AIR.x, AIR.y, AIR.z), 0, 0, false, true);
            var secondEvent = f.event(ServerboundPackets.MOVE_VEHICLE, second);
            f.listener.onMoveVehicle(secondEvent, f.player, second);

            assertTrue(secondEvent.isCancelled());
            assertTrue(f.player.checkManager.getCheck(TickTimer.class).violations > 0);
            assertEquals(prediction, f.player.checkManager.getSimulationProcessor().getLastPrediction());
            assertTrue(Double.isFinite(horse.clientPhysicalPosition.x));
        }
    }

    @Test
    void rejectedSecondBoatMoveDoesNotTickBoatState() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(AIR, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(101, EntityTypeIds.OAK_BOAT, AIR, 0, 0, 0);
            f.setPassengers(101, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            passengerRotation(f);
            var move = new ServerboundMoveVehicle(new Vec3d(AIR.x, AIR.y, AIR.z), 0, 0, false, true);
            var first = f.event(ServerboundPackets.MOVE_VEHICLE, move);
            f.listener.onMoveVehicle(first, f.player, move);
            assertFalse(first.isCancelled());

            f.player.boatData.fluidInteractionBox = null;
            passengerRotation(f);
            var second = f.event(ServerboundPackets.MOVE_VEHICLE, move);
            f.listener.onMoveVehicle(second, f.player, move);

            assertTrue(second.isCancelled());
            assertNull(f.player.boatData.fluidInteractionBox);
        }
    }

    private static PacketEntityHorse mountAirborneHorse(JavaTickFixture f) {
        f.place(AIR, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(100, EntityTypeIds.HORSE, AIR, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(100);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.onGround = false;
        f.setPassengers(100, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        horse.onGround = false;
        return horse;
    }

    private static void passengerRotation(JavaTickFixture f) {
        var rotation = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rotation), f.player, rotation);
    }
}
