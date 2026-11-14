package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#sendChanges follows the passenger Rot with MoveVehicle whenever the root is local-authoritative, so a
 * client tick that ends after that Rot without MoveVehicle is a tick in which the root travelled and stayed put.
 */
class VehicleTickWithoutMoveTest {
    private static final int BOAT = 100;
    private static final int ZOMBIE = 101;
    private static final Vec3 AIR = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    private static final Vec3 FLOOR = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final int FALL_TICKS = 6;

    private static Vec3 floatBoatInAir(Vec3 velocity) {
        return new Vec3(velocity.x * 0.9F, velocity.y - 0.04F, velocity.z * 0.9F);
    }

    private static JavaTickFixture ride(Vec3 at, boolean secondSeat) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(at, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, at, 0, 0, 0);
        if (secondSeat) f.player.compensatedEntities.addEntity(ZOMBIE, EntityTypeIds.ZOMBIE, at, 0, 0, 0);
        f.setPassengers(BOAT, secondSeat ? List.of(ZOMBIE, f.player.entityID) : List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        return f;
    }

    /** Six exact ticks of a boat falling from {@link #AIR}; where it is after them. */
    private static Vec3 fall(JavaTickFixture f) {
        Vec3 position = AIR;
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < FALL_TICKS; tick++) {
            velocity = floatBoatInAir(velocity);
            position = position.add(velocity);
            JavaTickFixture.assertVehicleExact(f.rideTick(position, false), tick);
        }
        return position;
    }

    private static PredictionResult last(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    @Test
    void aFallingBoatWhoseTickSendsNoMoveIsSetBack() throws Exception {
        try (JavaTickFixture f = ride(AIR, false)) {
            fall(f);
            PredictionResult before = last(f);
            f.rideTick();
            PredictionResult stalled = last(f);
            assertTrue(before != stalled, "the tick ran no vehicle prediction");
            JavaTickFixture.assertRejected(stalled, "VehicleOffset");
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), JavaTickFixture.describe(stalled));
        }
    }

    @Test
    void aRestingBoatWhoseTickSendsNoMoveStaysExact() throws Exception {
        try (JavaTickFixture f = ride(FLOOR, false)) {
            for (int i = 0; i < FALL_TICKS; i++) JavaTickFixture.assertVehicleExact(f.rideTick(FLOOR, true), i);
            for (int i = 0; i < 3; i++) {
                PredictionResult before = last(f);
                f.rideTick();
                assertTrue(before != last(f), "the tick ran no vehicle prediction");
                JavaTickFixture.assertVehicleExact(last(f), i);
            }
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
            JavaTickFixture.assertVehicleExact(f.rideTick(FLOOR, true), FALL_TICKS);
            assertEquals(FLOOR, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition);
        }
    }

    @Test
    void aSecondSeatPassengerSendsNoMove() throws Exception {
        try (JavaTickFixture f = ride(AIR, true)) {
            PredictionResult before = last(f);
            for (int i = 0; i < 10; i++) f.rideTick();
            assertTrue(before == last(f), "a vehicle prediction ran");
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    @Test
    void aDismountTheClientHasNotSeenRunsNoVehicleTick() throws Exception {
        try (JavaTickFixture f = ride(AIR, false)) {
            fall(f);
            f.setPassengers(BOAT, List.of());
            PredictionResult before = last(f);
            f.rideTick();
            assertTrue(before == last(f), "a vehicle prediction ran");
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    @Test
    void aMountTheClientHasNotSeenRunsNoVehicleTick() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(FLOOR, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, AIR, 0, 0, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.rideTick();
            f.rideTick();
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    @Test
    void aTimedOutTickEndRunsNoVehicleTick() throws Exception {
        try (JavaTickFixture f = ride(AIR, false)) {
            fall(f);
            PredictionResult before = last(f);
            var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            CheckManagerListener.processClientTickEndReceive(
                    new PacketReceiveEvent<>(
                            f.fixture.user,
                            ConnectionPhase.PLAY,
                            ServerboundPackets.CLIENT_TICK_END,
                            ServerboundPackets.CLIENT_TICK_END.opaqueValue(),
                            true),
                    f.player);
            assertTrue(before == last(f), "a vehicle prediction ran");
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }
}
