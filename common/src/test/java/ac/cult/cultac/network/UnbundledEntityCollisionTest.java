package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Without the reach bundle delimiter a tracked move is applied at the ping sent before it and proven by the next one.
 * Collision and prediction take it as a bundle would deliver it, handled with the first ping: the entity keeps one box,
 * and no read between the two pongs throws. Only the reach bounds keep the unmoved position until the second pong.
 */
class UnbundledEntityCollisionTest {
    private static final int BOAT = 60;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    /** Entity#move of the player's seeded horizontal velocity, with no gravity yet in the move. */
    private static final Vec3 VELOCITY = new Vec3(0.2, 0, 0);
    /** The boat's west face 0.15 beyond the player's east face; its bottom below the player's so it never pushes it. */
    private static final double ROOM = 0.15;

    private static final double BOAT_X = 0.5 + (double) (0.6F / 2) + ROOM + 1.375 / 2;
    /** A relative move of 512/4096 block east; the boat's 3-step interpolation handler moves a third of the way. */
    private static final double MOVE = 0.125;

    @ParameterizedTest
    @CsvSource({"V26_3, V_26_3", "V1_21_11, V_1_21_11"})
    void aMoveReadBetweenTheUnbundledPingsMeetsTheOneHandledBoatPosition(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = setUp(protocol, client)) {
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            boat.setClientTickOrder(-1L);
            move(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.sendTransaction();
            assertEquals(1, boat.possibleMovementCollisionBoxCandidateCount());

            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(VELOCITY);
            // VecDeltaCodec#decode adds the delta to the encoded base; the interpolation takes a third of the way.
            double target = (Math.round(BOAT_X * 4096.0) + MOVE * 4096) / 4096.0;
            double room = BOAT_X - 1.375 / 2 - (START.x + (double) (0.6F / 2)) + (target - BOAT_X) / 3;
            PredictionResult result = f.tick(START.add(room, 0, 0), false);
            assertTrue(exact(result), describe(result));
        }
    }

    @ParameterizedTest
    @CsvSource({"V26_3, V_26_3", "V1_21_11, V_1_21_11"})
    void theReachBoundsKeepTheUnmovedBoatUntilTheSecondPong(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = setUp(protocol, client)) {
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            move(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.sendTransaction();
            // A client tick takes the handled move's first interpolation step; unhandled, the boat stays.
            f.tickEnd();
            double unmovedMinX = BOAT_X - 1.375 / 2;
            assertTrue(boat.getExactMovementCollisionBox().minX > unmovedMinX + 1e-3, "collision meets the step");
            assertEquals(unmovedMinX, boat.getPossibleCollisionBoxes().minX, 1e-9, "reach keeps the unmoved boat");

            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(boat.getPossibleCollisionBoxes().minX > unmovedMinX + 1e-3, "the second pong proves the move");
        }
    }

    /** Any remaining candidate uncertainty gives collision readers the newest candidate instead of throwing. */
    @ParameterizedTest
    @CsvSource({"V26_3, V_26_3", "V1_21_11, V_1_21_11"})
    void anUnprovenPositionGivesCollisionTheNewestCandidate(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = setUp(protocol, client)) {
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            boat.onBundleFirstTransaction(true, true, MOVE, 0, 0, f.player);
            assertTrue(boat.possibleMovementCollisionBoxCandidateCount() > 1);
            assertNull(boat.getExactMovementCollisionBox());

            int newest = boat.possibleMovementCollisionBoxCandidateCount() - 1;
            assertEquals(
                    boat.possibleMovementCollisionBoxCandidate(newest, new SimpleCollisionBox()).minX,
                    boat.getClientMovementCollisionBox().minX);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(VELOCITY);
            assertNotNull(f.tick(START.add(VELOCITY), false));
        }
    }

    private static JavaTickFixture setUp(ProtocolVersion protocol, ClientVersion client) throws Exception {
        var f = new JavaTickFixture(protocol, client);
        f.place(START, Vec3.ZERO);
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        assertTrue(exact(f.tick(START, false)));
        withoutBundleDelimiter(f);
        spawnBoat(f);
        assertNotNull(f.player.compensatedEntities.getEntity(BOAT));
        return f;
    }

    private static void withoutBundleDelimiter(JavaTickFixture f) throws ReflectiveOperationException {
        Field delimiter = PacketEntityReplication.class.getDeclaredField("useReachBundleDelimiter");
        delimiter.setAccessible(true);
        delimiter.setBoolean(f.player.packetEntityReplication, false);
    }

    private static void spawnBoat(JavaTickFixture f) {
        var at = new Vec3d(BOAT_X, START.y - 0.5, START.z);
        var packet = new ClientboundAddEntity(BOAT, "minecraft:oak_boat", at, 0, 0, 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void move(JavaTickFixture f) {
        var delta = new EntityDelta.Linear((short) (MOVE * 4096), (short) 0, (short) 0);
        var packet = new ClientboundMoveEntity(BOAT, delta, 0, 0, false, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }
}
