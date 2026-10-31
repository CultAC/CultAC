package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A client tick end, or from 26.3 a ServerboundMoveVehiclePacket, does not prove that a boat's entity tick ran.
 * ClientLevel#tickEntities skips an entity outside tickingEntities (its chunk is not loaded on the client) and, while
 * the tick rate is frozen, one TickRateManager#isEntityFrozen holds for: an entity with no player among its passengers.
 * A skipped root skips its passengers, LocalPlayer#rideTick and its AbstractBoat#setInput included. Before 26.3
 * LocalPlayer#tick sends the rider's Rot and MoveVehicle from that rideTick; from 26.3 Minecraft#tick runs
 * LocalPlayer#sendChanges after tickEntities whether or not the root ticked, so an unticked boat reports its unchanged
 * position.
 */
class BoatSkippedEntityTickTest {
    private static final int BOAT = 100;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 ON_FOOT = new Vec3(4.5, FLOOR_Y, 0.5);
    /** AbstractBoat#controlBoat's forward acceleration at yaw 0: Mth.cos(0) * 0.04F. */
    private static final Vec3 THRUST = new Vec3(0, 0, 0.04F);

    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput RIGHT =
            new ServerboundPlayerInput(false, false, false, true, false, false, false);

    /**
     * A new boat holds no keys until the first LocalPlayer#rideTick on it hands it the rider's. Mounted in an unloaded
     * chunk, neither the boat nor its rider ticks until the chunk loads, so the first boat tick still coasts though the
     * player held forward all along.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_11", "V_26_3"})
    void aBoatMountedInAnUnloadedChunkHoldsNoKeysOnItsFirstTick(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = JavaTickFixture.observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            CompensatedWorld world = f.player.compensatedWorld;
            var chunk = world.getChunk(0, 0);
            f.place(BOAT_AT, Vec3.ZERO);
            f.player.xRot = 0;
            f.player.yRot = 0;
            world.removeChunkLater(0, 0, 0);
            assertFalse(world.isChunkLoaded(0, 0));
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            // The keys of the player's last tick on foot.
            f.input(FORWARD);
            seat(f, List.of(f.player.entityID));
            for (int tick = 0; tick < 3; tick++) skippedTick(f, version, BOAT_AT, 0);
            world.putChunk(0, 0, chunk);

            assertExact(f.rideTick(BOAT_AT, true));
            assertExact(f.rideTick(BOAT_AT.add(THRUST), true));
        }
    }

    /**
     * Steering right on stone, the boat's second tick turns it by deltaRotation 1. A 26.3 report from a tick its
     * unloaded chunk skipped neither decays nor steers that turn, so the first tick back turns by 1 * 0.6 + 1.
     */
    @Test
    void anUnloadedBoatsReportKeepsItsTurn() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = JavaTickFixture.observeOnly()) {
            CompensatedWorld world = f.player.compensatedWorld;
            var chunk = world.getChunk(0, 0);
            board(f);
            f.input(RIGHT);
            assertExact(rideTick(f, BOAT_AT, 0));
            Vec3 turned = BOAT_AT.add(thrust(f, 1, 0.005F));
            assertExact(rideTick(f, turned, 1));
            assertEquals(1F, f.player.boatData.deltaRotation);

            world.removeChunkLater(0, 0, 0);
            for (int tick = 0; tick < 3; tick++) {
                skippedTick(f, ClientVersion.V_26_3, turned, 1);
                assertEquals(1F, f.player.boatData.deltaRotation);
            }
            world.putChunk(0, 0, chunk);

            float turn = 1 * landFriction() + 1;
            Vec3 velocity = thrust(f, 1, 0.005F);
            Vec3 next = turned.add(thrust(f, 1 + turn, 0.005F)
                    .add(velocity.x * landFriction(), 0, velocity.z * landFriction()));
            assertExact(rideTick(f, next, 1 + turn));
            assertEquals(turn, f.player.boatData.deltaRotation);
        }
    }

    /**
     * From 26.3 AbstractBoat#tick zeroes deltaRotation on a tick the client's instance does not control the boat. While
     * the tick rate is frozen an unoccupied boat does not tick, so the boat left turning keeps its turn until ticks run
     * again.
     */
    @Test
    void aBoatLeftTurningWhileTicksAreFrozenKeepsItsTurnUntilItTicks() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = JavaTickFixture.observeOnly()) {
            board(f);
            f.input(RIGHT);
            assertExact(rideTick(f, BOAT_AT, 0));
            assertExact(rideTick(f, BOAT_AT.add(thrust(f, 1, 0.005F)), 1));
            assertEquals(1F, f.player.boatData.deltaRotation);

            f.player.packetStateData.serverTicksFrozen = true;
            seat(f, List.of());
            f.input(NONE);
            // The teleport zeroed the delta movement, so only the first tick's Entity#move is still and airborne.
            f.teleport(ON_FOOT, new RelativeFlag(0));
            for (int tick = 0; tick < 3; tick++) {
                assertExact(f.tick(ON_FOOT, tick > 0));
                assertEquals(1F, f.player.boatData.deltaRotation);
            }

            f.player.packetStateData.serverTicksFrozen = false;
            assertExact(f.tick(ON_FOOT, true));
            assertEquals(0F, f.player.boatData.deltaRotation);
        }
    }

    /** Puts the player on a new boat at {@link #BOAT_AT}, yaw 0, as its only passenger. */
    private static void board(JavaTickFixture f) {
        f.place(BOAT_AT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
        f.player.xRot = 0;
        f.player.yRot = 0;
        seat(f, List.of(f.player.entityID));
    }

    private static void seat(JavaTickFixture f, List<Integer> passengers) {
        f.setPassengers(BOAT, passengers);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertEquals(!passengers.isEmpty(), f.player.compensatedEntities.getSelf().inVehicle());
    }

    /**
     * A client tick whose ClientLevel#tickEntities skipped the boat and its rider: before 26.3 only the tick end, from
     * 26.3 LocalPlayer#sendChanges' Rot and the boat's unchanged MoveVehicle first.
     */
    private static void skippedTick(JavaTickFixture f, ClientVersion version, Vec3 at, float boatYaw) {
        if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
            rot(f);
            f.moveVehicle(at, boatYaw, 0, true);
        }
        f.tickEnd();
    }

    /** A ridden tick whose boat reports {@code boatYaw}; the committed prediction. */
    private static PredictionResult rideTick(JavaTickFixture f, Vec3 to, float boatYaw) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        rot(f);
        f.moveVehicle(to, boatYaw, 0, true);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != before, "the tick committed no prediction");
        return result;
    }

    private static void rot(JavaTickFixture f) {
        var packet = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    /** AbstractBoat#controlBoat's acceleration {@code force} along the boat's yaw after it turned. */
    private static Vec3 thrust(JavaTickFixture f, float yaw, float force) {
        return new Vec3(
                f.player.trigHandler.sin(-yaw * ((float) Math.PI / 180F)) * force,
                0,
                f.player.trigHandler.cos(yaw * ((float) Math.PI / 180F)) * force);
    }

    // AbstractBoat#getGroundFriction: the mean friction of the nine stone blocks under the boat.
    private static float landFriction() {
        float friction = 0;
        for (int block = 0; block < 9; block++) friction += 0.6F;
        return friction / 9;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
