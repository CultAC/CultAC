package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Entity#sendTeleportTransitionToPlayers sends the boat's controller the command's relatives, so {@code /tp ~ ~5 ~} of
 * a ridden boat reaches its rider as a relative teleport. ClientPacketListener#handleTeleportEntity adds it to
 * PositionMoveRotation#of(entity) as the client handles it, and the boat the client drives keeps moving by the client's
 * own ticks after the server sends it. The boat goes five blocks above where the client has it then.
 */
class BoatRelativeTeleportTest {
    private static final int BOAT = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 20, 0.5);
    /** TeleportCommand#getRelatives for {@code ~ ~ ~} with no rotation, apart from the velocity it keeps. */
    private static final Set<Relative> COMMAND =
            EnumSet.of(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT);

    private static final int FALL_TICKS = 4;
    /** Ticks the client falls after the server sent the teleport, before handling it. */
    private static final int IN_FLIGHT_TICKS = 4;

    /**
     * A relative teleport makes handleTeleportEntity interpolate: AbstractInterpolationHandler#interpolate moves the
     * boat a third, a half, then all of the way to the target, which
     * AbstractInterpolationHandler#adjustInterpolationTargetFromDeltas moves by the boat's own travel.
     * ServerboundMoveVehiclePacket#fromEntity reports that target while steps remain, then the boat itself.
     */
    @Test
    void aRelativeTeleportStartsFromWhereTheClientHandlesIt() throws Exception {
        Vec3 rise = new Vec3(0, 5, 0);
        try (JavaTickFixture f = mount()) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < FALL_TICKS + IN_FLIGHT_TICKS; tick++) {
                if (tick == FALL_TICKS) sendTeleport(f, rise);
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                assertVehicleExact(f.rideTick(position, false), tick);
            }
            f.pongThrough(f.player.lastTransactionSent.get());

            // The teleport sets the boat's velocity outright.
            velocity = Vec3.ZERO;
            Vec3 target = position.add(rise);
            for (int step = 3; step > 1; step--) {
                position = position.lerp(target, 1.0 / step);
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                PredictionResult result = f.rideTick(target, false);
                assertTrue(exact(result), "step " + step + " " + describe(result));
                Vec3 travel = result.getAcceptedClosestToTarget();
                assertEquals(velocity.x, travel.x, 1e-9, "travel " + travel);
                assertEquals(velocity.y, travel.y, 1e-9, "travel " + travel);
                assertEquals(velocity.z, travel.z, 1e-9, "travel " + travel);
                target = target.add(velocity);
            }
            position = target;
            for (int tick = 0; tick < 2; tick++) {
                velocity = floatBoatInAir(velocity);
                position = position.add(velocity);
                assertVehicleExact(f.rideTick(position, false), tick);
            }
        }
    }

    /** AbstractBoat#floatBoat in the air: 0.9F friction and getDefaultGravity's 0.04, then no input. */
    private static Vec3 floatBoatInAir(Vec3 velocity) {
        return new Vec3(velocity.x * 0.9F, velocity.y - 0.04, velocity.z * 0.9F);
    }

    private static JavaTickFixture mount() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        return f;
    }

    /** The command's teleport of the boat by {@code change}, which the client does not answer yet. */
    private static void sendTeleport(JavaTickFixture f, Vec3 change) {
        var packet = new ClientboundTeleportEntity(
                BOAT, new Vec3d(change.x, change.y, change.z), new Vec3d(0, 0, 0), 0, 0, COMMAND, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }
}
