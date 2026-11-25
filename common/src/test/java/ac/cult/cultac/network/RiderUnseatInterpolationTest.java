package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A rider leaves its vehicle while a position packet is still interpolating. Entity#stopRiding does not touch the
 * rider's InterpolationHandler, so LivingEntity#aiStep (1.21.11) or Entity#commonTick (26.3) takes the remaining
 * steps from the last seat. While it rode, Entity#positionRider put it back on the seat after each step and the
 * handler carried its target along by that move, so every step, before or after the unseat, covers a third of the
 * way from where the packet found the rider to the packet's position.
 */
class RiderUnseatInterpolationTest {
    private static final int CHICKEN = 100;
    private static final int STEPS = 3;
    // Player#STANDING_DIMENSIONS is 1.8F tall and has no passenger attachment of its own, so the seat is its top.
    private static final double SEAT_HEIGHT = 1.8F;

    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0", "V_1_21_11, 1", "V_1_21_11, 2", "V_26_3, 0", "V_26_3, 1", "V_26_3, 2"})
    void anUnseatedRiderTakesTheRemainingStepsOfItsPositionPacket(ClientVersion version, int ridingSteps)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version);
                var ignored = observeOnly()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y + 40, 0.5);
            Vec3 server = new Vec3(3.5, FLOOR_Y, 3.5);
            f.place(position, Vec3.ZERO);
            f.addEntity(CHICKEN, "minecraft:chicken", server);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.setPassengers(f.player.entityID, List.of(CHICKEN));
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketEntity chicken = f.player.compensatedEntities.getEntity(CHICKEN);

            // LivingEntity#travel moves by the current delta, then applies gravity and drag.
            double motion = 0;
            for (int tick = 0; tick < 3; tick++) {
                position = new Vec3(position.x, position.y + motion, position.z);
                motion = (motion - 0.08) * 0.98F;
                assertExact(f.tick(position, false), "riding tick " + tick);
            }
            Vec3 seatAtPacket = seat(position);
            assertAt(seatAtPacket, chicken, "seat before the packet");

            // ServerEntity#sendChanges repeats an unmoved entity's position every 60 ticks.
            var packet = new ClientboundMoveEntity(CHICKEN, EntityDelta.ZERO, 0, 0, true, true, false);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
            f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());

            for (int step = 0; step < ridingSteps; step++) {
                position = new Vec3(position.x, position.y + motion, position.z);
                motion = (motion - 0.08) * 0.98F;
                assertExact(f.tick(position, false), "riding step " + step);
                assertAt(seat(position), chicken, "seat after riding step " + step);
            }
            Vec3 seatAtUnseat = seat(position);

            f.setPassengers(f.player.entityID, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());

            Vec3 third = server.subtract(seatAtPacket).scale(1.0 / STEPS);
            for (int tick = 1; tick <= STEPS + 1; tick++) {
                position = new Vec3(position.x, position.y + motion, position.z);
                motion = (motion - 0.08) * 0.98F;
                assertExact(f.tick(position, false), "tick " + tick + " after the unseat");
                int taken = Math.min(tick, STEPS - ridingSteps);
                assertAt(seatAtUnseat.add(third.scale(taken)), chicken, "tick " + tick + " after the unseat");
            }
        }
    }

    private static Vec3 seat(Vec3 player) {
        return new Vec3(player.x, player.y + SEAT_HEIGHT, player.z);
    }

    private static void assertExact(PredictionResult result, String when) {
        assertTrue(exact(result), when + ": " + describe(result));
    }

    private static void assertAt(Vec3 expected, PacketEntity entity, String when) {
        assertEquals(1, entity.possibleMovementCollisionBoxCandidateCount(), "positions at " + when);
        SimpleCollisionBox box = entity.getPossibleMovementCollisionBoxes();
        assertEquals(expected.x, (box.minX + box.maxX) / 2, 1.0E-6, "x at " + when);
        assertEquals(expected.y, box.minY, 1.0E-6, "y at " + when);
        assertEquals(expected.z, (box.minZ + box.maxZ) / 2, 1.0E-6, "z at " + when);
    }
}
