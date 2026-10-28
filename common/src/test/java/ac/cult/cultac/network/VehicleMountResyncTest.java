package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A 26.1 client re-enters a boat resting in a corner, its box against the walls on -x and -z.
 * ClientPacketListener#handleMoveVehicle snaps the ridden vehicle to the mount resync's position, which is clear of
 * the vehicle's collisions. Entity#collide clips the boat's paddled move at the walls, and Entity#move leaves a boat
 * whose clipped move is shorter than sqrt(1.0E-7) where it is. Positions come from the capture, moved next to the
 * fixture's floor.
 */
class VehicleMountResyncTest {
    private static final int BOAT = 1041;
    private static final double BOAT_HALF_WIDTH = 1.375F / 2.0;
    private static final float BOAT_YAW = 138.46947F;
    private static final Vec3 CORNER = new Vec3(BOAT_HALF_WIDTH, FLOOR_Y, BOAT_HALF_WIDTH);
    /** The capture's first paddled move along x, from 177.68748 to 177.6609575237739. */
    private static final double PADDLED_X = 177.6609575237739 - 177.68748;

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void theMountResyncKeepsABoatInACornerClearOfTheWalls() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            Vec3 resync = mount(f, false);
            assertNotNull(resync);
            assertTrue(resync.x - BOAT_HALF_WIDTH >= 0.0 && resync.z - BOAT_HALF_WIDTH >= 0.0, "resync at " + resync);
            assertTrue(resync.distanceTo(CORNER) > 1.0E-5F, "resync at " + resync);
        }
    }

    @Test
    void aBoatPaddledIntoTheCornerAfterTheMountResyncStaysAgainstTheWalls() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            Vec3 resync = mount(f, false);
            assertVehicleExact(paddleTick(f, resync), 0);
            assertVehicleExact(paddleTick(f, resync), 1);
            assertVehicleExact(paddleTick(f, resync), 2);
        }
    }

    @Test
    void aBoatMovingIntoTheWallAfterTheMountResyncIsVehicleOffset() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            Vec3 resync = mount(f, false);
            assertVehicleExact(paddleTick(f, resync), 0);
            try (var ignored = observeOnly()) {
                assertRejected(paddleTick(f, resync.add(PADDLED_X, 0, 0)), "VehicleOffset");
            }
        }
    }

    /** End rods close the corner on +x and +z, their faces flush with the boat's box. */
    @Test
    void aBoatInAClosedPocketIsSentNoMountResync() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            assertNull(mount(f, true));
        }
    }

    @Test
    void aBoatPaddledInAClosedPocketStaysWhereItRests() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            mount(f, true);
            assertVehicleExact(paddleTick(f, CORNER), 0);
            assertVehicleExact(paddleTick(f, CORNER), 1);
            assertVehicleExact(paddleTick(f, CORNER), 2);
        }
    }

    @Test
    void aBoatMovingIntoTheWallOfAClosedPocketIsVehicleOffset() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            mount(f, true);
            assertVehicleExact(paddleTick(f, CORNER), 0);
            try (var ignored = observeOnly()) {
                assertRejected(paddleTick(f, CORNER.add(PADDLED_X, 0, 0)), "VehicleOffset");
            }
        }
    }

    /**
     * Walls on -x and -z of the corner, with {@code pocket} end rods on +x and +z, the boat resting in it and the rider
     * holding forward; the server's set_passengers, and for a resync the client's answer to its proof and
     * handleMoveVehicle's echo. Returns the resync's position, or null when none is sent.
     */
    private static @Nullable Vec3 mount(JavaTickFixture f, boolean pocket) {
        int endRod = DataTables.defaults().registry().block("minecraft:end_rod").defaultState();
        for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
            for (int i = -1; i <= 3; i++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(-1, y, i), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(i, y, -1), BlockIds.STONE.defaultState());
            }
            if (pocket) {
                f.player.compensatedWorld.updateBlock(new BlockPos(1, y, 0), endRod);
                f.player.compensatedWorld.updateBlock(new BlockPos(1, y, 1), endRod);
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 1), endRod);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(new Vec3(1.5, FLOOR_Y, 1.5), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, CORNER, 0, BOAT_YAW, 0);
        f.player.compensatedEntities.getEntity(BOAT).onGround = true;
        f.player.compensatedEntities.serverPositionsMap.put(
                BOAT, new TrackerData(CORNER.x, CORNER.y, CORNER.z, 0, BOAT_YAW, EntityTypeIds.OAK_BOAT, 0));
        f.input(FORWARD);
        f.tickEnd();

        var packet = new ClientboundSetPassengers(BOAT, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        ClientboundMoveVehicle move = event.writesAfter().stream()
                .map(CultWrite::packet)
                .filter(ClientboundMoveVehicle.class::isInstance)
                .map(ClientboundMoveVehicle.class::cast)
                .findFirst()
                .orElse(null);
        f.pongThrough(f.player.lastTransactionSent.get());
        if (move == null) return null;
        Vec3 resync = new Vec3(
                move.position().x(), move.position().y(), move.position().z());
        f.moveVehicle(resync, BOAT_YAW, 0, true);
        return resync;
    }

    /**
     * A ride tick holding forward: both paddles, the rider's Rot, the boat's move to {@code to}, the tick end. The first
     * ride tick's AbstractBoat#controlBoat runs before LocalPlayer#rideTick hands the boat the forward input.
     */
    private static PredictionResult paddleTick(JavaTickFixture f, Vec3 to) {
        var paddle = new ServerboundPaddleBoat(true, true);
        f.listener.onPaddleBoat(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player, paddle);
        var rot = new ServerboundMovePlayer(0, 0, 0, BOAT_YAW, 90, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(to, BOAT_YAW, 0, true);
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }
}
