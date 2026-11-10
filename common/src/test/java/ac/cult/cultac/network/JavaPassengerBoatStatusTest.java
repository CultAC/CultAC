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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A boat another passenger controls carries the local player from open water under a water ceiling. Through 26.2
 * AbstractBoat#tick takes its status from the box before InterpolationHandler#interpolate moves it, and the passenger's
 * Entity#baseTick, still at its previous seat, reads that status in modifyPassengerFluidInteractionBox with the moved
 * box. On the first step under the ceiling the boat is not yet under water: the passenger's fluid box starts at the
 * moved boat's top, above the open water, so it travels in air and the first move after the dismount falls by the ride
 * tick's gravity. From 26.3 Entity#commonTick steps the boat before AbstractBoat#tick: the status is already under
 * water, the passenger's whole box reads the open water and the ride tick leaves water gravity instead.
 */
class JavaPassengerBoatStatusTest {
    private static final int BOAT = 100;
    private static final int DRIVER = 101;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y + 0.33, -0.5);
    /** A teleport the client interpolates over AbstractBoat's three steps: the first ends 2.6 blocks on. */
    private static final double DZ = 7.8;
    private static final Vec3 STANDING = new Vec3(8.5, FLOOR_Y, 0.5);
    private static final double FALL = -0.08 * 0.98F;
    /** LivingEntity#getFluidFallingAdjustedMovement from rest. */
    private static final double WATER_FALL = -0.08 / 16.0;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @CsvSource({"V_1_21_4, true", "V_1_21_4, false", "V_26_2, true", "V_26_2, false", "V_26_3, true",
        "V_26_3, false"})
    void theStepUnderTheCeilingTakesTheStatusItsVersionSamples(ClientVersion version, boolean underCeiling)
            throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.input(NONE);
            // Open still water one block deep; from z = 2 a second layer makes it a ceiling over the boat.
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 12; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.WATER.defaultState());
                    if (underCeiling && z >= 2)
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, FLOOR_Y + 1, z), BlockIds.WATER.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(STANDING, new Vec3(0, FALL, 0));
            PredictionResult standing = f.tick(STANDING, true);
            assertTrue(exact(standing), describe(standing));

            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            f.player.compensatedEntities.addEntity(DRIVER, EntityTypeIds.ARMOR_STAND, BOAT_AT, 0, 0, 0);
            f.setPassengers(BOAT, List.of(DRIVER, f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            for (int tick = 0; tick < 3; tick++) f.rideTick();

            Vec3 to = BOAT_AT.add(0, 0, DZ);
            var move = new ClientboundTeleportEntity(
                    BOAT, new Vec3d(to.x, to.y, to.z), Vec3d.ZERO, 0, 0, java.util.Set.of(), false);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, move, false);
            f.player.packetEntityReplication.onTeleportEntity(event, f.player, move);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();

            Vec3 seat = f.player.compensatedEntities.lastRideTickSeat();
            f.setPassengers(BOAT, List.of(DRIVER));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            // The first free move then travels in air from the seat, by the delta the ride tick left.
            double fall = underCeiling && version.isNewerThanOrEquals(ClientVersion.V_26_3) ? WATER_FALL : FALL;
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 12; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.AIR.defaultState());
                }
            }
            PredictionResult first = f.tick(seat.add(0, fall, 0), false);
            assertTrue(exact(first), version + (underCeiling ? " under a ceiling: " : " in open water: ")
                    + describe(first));
        }
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
