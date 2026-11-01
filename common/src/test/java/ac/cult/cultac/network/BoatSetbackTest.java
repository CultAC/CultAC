package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A vehicle setback puts the boat where one AbstractBoat#tick from the last accepted state moves it, with the
 * deltaMovement that tick carries on. ClientPacketListener#handleMoveVehicle snaps the boat there and
 * handleSetEntityMotion gives it the velocity the packet decodes to, so the client's next AbstractBoat#floatBoat
 * continues the fall from there.
 */
class BoatSetbackTest {
    private static final int BOAT = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    // Ticks fallen before the rejected one.
    private static final int FALL_TICKS = 8;
    private static final Vec3 GROUND_SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    // AbstractBoat#getGroundFriction averages, in float, the friction of the nine stone blocks under the boat.
    private static final float LAND_FRICTION = groundFriction(9);

    @Test
    void aVehicleSetbackAdvancesTheBoatByItsSimulatedTick() throws Exception {
        try (Ride ride = mount(SEAT)) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < FALL_TICKS; tick++) {
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                JavaTickFixture.assertVehicleExact(ride.f.rideTick(position, false), tick);
            }

            for (int setbacks = 0; setbacks < 2; setbacks++) {
                JavaTickFixture.assertRejected(ride.f.rideTick(position, false), "VehicleOffset");
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
                assertEquals(position.y, setback.getTeleportData().getLocation().y, 1.0E-12);
                assertEquals(velocity.y, setback.getVelocity().y, 1.0E-12);
                velocity = ride.acceptSetback(setback);
            }
        }
    }

    @Test
    void theVanillaFallAfterAVehicleSetbackIsExact() throws Exception {
        try (Ride ride = mount(SEAT)) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < FALL_TICKS; tick++) {
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                ride.f.rideTick(position, false);
            }
            JavaTickFixture.assertRejected(ride.f.rideTick(position, false), "VehicleOffset");
            SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
            velocity = ride.acceptSetback(setback);

            position = setback.getTeleportData().getLocation();
            for (int tick = 0; tick < 3; tick++) {
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                JavaTickFixture.assertVehicleExact(ride.f.rideTick(position, false), tick);
            }
        }
    }

    /** A correction that leaves the boat where it is still sets the velocity the boat falls on with. */
    @Test
    void theVanillaFallAfterACorrectionAtTheLastPositionIsExact() throws Exception {
        try (Ride ride = mount(SEAT)) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < FALL_TICKS; tick++) {
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                ride.f.rideTick(position, false);
            }
            ride.f.player.getSetbackTeleportUtil().executeForceResync("correction");
            SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
            velocity = ride.acceptSetback(setback);

            position = setback.getTeleportData().getLocation();
            for (int tick = 0; tick < 3; tick++) {
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                JavaTickFixture.assertVehicleExact(ride.f.rideTick(position, false), tick);
            }
        }
    }

    /**
     * ClientboundMoveVehiclePacket#fromEntity carries the boat's own rotation, the yaw AbstractBoat#controlBoat turned
     * it to in the tick the setback advances it by, and handleMoveVehicle snaps the client's boat to it; the boat keeps
     * turning by its deltaRotation from there.
     */
    @Test
    void aVehicleSetbackKeepsTheBoatsOwnYaw() throws Exception {
        try (Ride ride = mount(GROUND_SEAT)) {
            TurningBoat boat = new TurningBoat();
            for (int tick = 0; tick < FALL_TICKS; tick++) {
                JavaTickFixture.assertVehicleExact(boat.tick(ride.f, tick == 0 ? 0 : -1, Vec3.ZERO), tick);
            }

            JavaTickFixture.assertRejected(boat.tick(ride.f, -1, new Vec3(0.5, 0, 0)), "VehicleOffset");
            SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertEquals(1, ride.moves.size());
            assertEquals(boat.yaw, ride.moves.get(0).yaw());
            assertNotEquals(ride.f.player.xRot, ride.moves.get(0).yaw());

            boat.velocity = ride.acceptSetback(setback);
            boat.position = setback.getTeleportData().getLocation();
            for (int tick = 0; tick < 3; tick++) {
                JavaTickFixture.assertVehicleExact(boat.tick(ride.f, -1, Vec3.ZERO), tick);
            }
        }
    }

    /**
     * A boat on stone its rider drives with the left key, which turns it the ticks after the key is sent and thrusts it
     * 0.005 along its yaw; the rider looks 30 degrees to the boat's right.
     */
    private static final class TurningBoat {
        Vec3 position = GROUND_SEAT;
        Vec3 velocity = Vec3.ZERO;
        float deltaRotation;
        float yaw;
        int held;

        /** One client tick sending {@code keys}; the boat reports its vanilla move plus {@code extra}. */
        PredictionResult tick(JavaTickFixture f, int keys, Vec3 extra) {
            deltaRotation = deltaRotation * LAND_FRICTION + held;
            yaw += deltaRotation;
            float thrust = held != 0 ? 0.005F : 0.0F;
            velocity = new Vec3(
                    velocity.x * LAND_FRICTION + f.player.trigHandler.sin(-yaw * ((float) Math.PI / 180F)) * thrust,
                    0,
                    velocity.z * LAND_FRICTION + f.player.trigHandler.cos(yaw * ((float) Math.PI / 180F)) * thrust);
            position = position.add(velocity);
            if (keys != held) f.input(keys < 0 ? LEFT : RELEASED);
            held = keys;
            var rot = new ServerboundMovePlayer(0, 0, 0, yaw + 30, 0, true, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            Vec3 to = position.add(extra);
            var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, 0, true, true);
            f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
            f.tickEnd();
            return f.player.checkManager.getSimulationProcessor().getLastPrediction();
        }
    }

    /** AbstractBoat#floatBoat in the air: 0.9 friction and 0.04 gravity, then no input. */
    private static Vec3 floatBoatInAir(Vec3 velocity) {
        return new Vec3(velocity.x * 0.9F, velocity.y - 0.04F, velocity.z * 0.9F);
    }

    private static float groundFriction(int blocks) {
        float sum = 0.0F;
        for (int i = 0; i < blocks; i++) sum += 0.6F;
        return sum / blocks;
    }

    /**
     * A ridden boat and what the server sends it: the setback's ClientboundMoveVehiclePacket queues the vehicle teleport
     * its echo accepts, and its ClientboundSetEntityMotionPacket decodes to the velocity handleSetEntityMotion gives the
     * boat.
     */
    private record Ride(JavaTickFixture f, List<ClientboundMoveVehicle> moves, List<Vec3> motions)
            implements AutoCloseable {
        /**
         * The client answers the setback's ping and ClientPacketListener#handleMoveVehicle echoes the boat's snapped
         * position and rotation; returns the velocity the boat then has.
         */
        Vec3 acceptSetback(SetBackData setback) {
            f.pongThrough(f.player.lastTransactionSent.get());
            ClientboundMoveVehicle snap = moves.getLast();
            Vec3 at = setback.getTeleportData().getLocation();
            var echo = new ServerboundMoveVehicle(new Vec3d(at.x, at.y, at.z), snap.yaw(), snap.pitch(), false, true);
            f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, echo), f.player, echo);
            assertTrue(setback.isComplete(), "the setback echo was not accepted");
            f.tickEnd();
            return motions.getLast();
        }

        @Override
        public void close() throws Exception {
            f.close();
        }
    }

    private static Ride mount(Vec3 seat) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        Ride ride = new Ride(f, new ArrayList<>(), new ArrayList<>());
        f.fixture.registrar.register(routes -> {
            routes.send(ClientboundPackets.MOVE_VEHICLE, PacketListenerPriority.LOW, (event, player, packet) -> {
                ride.moves.add(packet);
                new PacketServerTeleport().onMoveVehicle(event, player, packet);
            });
            routes.send(ClientboundPackets.ENTITY_MOTION, PacketListenerPriority.LOW, (event, player, packet) -> {
                if (packet.entityId() == BOAT) {
                    ride.motions.add(new Vec3(
                            packet.velocity().x(),
                            packet.velocity().y(),
                            packet.velocity().z()));
                }
                player.packetEntityReplication.onSetEntityMotion(event, player, packet);
            });
        });
        f.place(seat, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        return ride;
    }
}
