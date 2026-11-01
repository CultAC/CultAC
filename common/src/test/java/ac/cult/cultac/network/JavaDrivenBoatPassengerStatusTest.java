package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A boat the player drives rests on the floor over one water cell, the player's box reaching into that water below
 * the boat. A teleport the client interpolates carries it under a water ceiling. AbstractBoat#tick takes getStatus()
 * before InterpolationHandler#interpolate: on land, not under water, so the passenger's fluid box starts at the moved
 * boat's top and the passenger, still at its previous seat, travels in air. The first move after the dismount falls by
 * that ride tick's gravity.
 */
class JavaDrivenBoatPassengerStatusTest {
    private static final int BOAT = 100;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y, -0.5);
    /** The client interpolates this over AbstractBoat's three steps; the first ends 2.6 blocks on, under the ceiling. */
    private static final Vec3 TARGET = BOAT_AT.add(0, 0, 7.8);
    private static final double FALL = -0.08 * 0.98F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @CsvSource({"V_1_21_11, true", "V_1_21_11, false", "V_26_2, true", "V_26_2, false", "V_26_3, true",
        "V_26_3, false"})
    void theStepUnderTheCeilingKeepsTheStatusFromBeforeIt(ClientVersion version, boolean underCeiling)
            throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.input(NONE);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, -1), BlockIds.WATER.defaultState());
            if (underCeiling) {
                for (int x = -3; x <= 3; x++) {
                    for (int z = 2; z <= 8; z++) {
                        for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++)
                            f.player.compensatedWorld.updateBlock(
                                    new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                    }
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 standingAt = new Vec3(3.5, FLOOR_Y, -0.5);
            f.place(standingAt, new Vec3(0, FALL, 0));
            PredictionResult standing = f.tick(standingAt, true);
            assertTrue(exact(standing), describe(standing));

            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 3; tick++) f.rideTick(BOAT_AT, true);

            teleport(f, TARGET);
            f.pongThrough(f.player.lastTransactionSent.get());
            // MoveVehicle reports the interpolation target.
            f.rideTick(TARGET, false);

            Vec3 seat = f.player.compensatedEntities.lastRideTickSeat();
            f.setPassengers(BOAT, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            // The first free move then travels in air from the seat, by the delta the ride tick left.
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, -1), BlockIds.STONE.defaultState());
            for (int x = -3; x <= 3; x++) {
                for (int z = 2; z <= 8; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.AIR.defaultState());
                }
            }
            PredictionResult first = f.tick(seat.add(0, FALL, 0), false);
            assertTrue(exact(first), version + (underCeiling ? " under a ceiling: " : " in the open: ")
                    + describe(first));
        }
    }

    private static void teleport(JavaTickFixture f, Vec3 to) {
        var packet =
                new ClientboundTeleportEntity(BOAT, new Vec3d(to.x, to.y, to.z), Vec3d.ZERO, 0, 0, Set.of(), false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static AutoCloseable observeOnly() throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, true);
        return () -> field.setBoolean(manager, original);
    }
}
