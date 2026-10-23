package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintG;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A client rides a nautilus under water, holds shift to dismount and starts sprinting on its first tick off the
 * vehicle. Entity#rideTick runs each passenger Entity#baseTick at the seat Entity#positionRider left the tick before, so
 * the eye is in water on the last ride tick, and LocalPlayer#isUnderWater lets LocalPlayer#canStartSprinting start a
 * sprint even while the player is moving slowly. Positions come from a 26.3 client's capture, moved next to the
 * fixture's floor; the ride ticks replay the captured vehicle positions.
 */
class JavaSprintWaterDismountTest {
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD_SHIFT_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final int VEHICLE = 100;
    private static final float YAW = -1729.1234F;
    private static final float PITCH = 14.109617F;

    /**
     * The last ride tick runs its baseTick at the seat over the previous vehicle position, y 57.8758 + 0.5375, so the
     * standing eye at 60.0333 is in the water and the first tick off the nautilus is under water.
     */
    @Test
    void sprintingUnderwaterAfterDismountingANautilusIsNotSprintG() throws Exception {
        try (var f = pool(FLOOR_Y + 3);
                var ignored = observeOnly()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            SprintG sprintG = sprintG(f);
            f.player.xRot = YAW;
            f.player.yRot = PITCH;
            Vec3[] ride = {
                at(-393.39473685447786, 58.020304419673145, 685.596377163587),
                at(-393.6653336977598, 57.94839802819097, 685.6927830144158),
                at(-393.93865069723176, 57.875761242645936, 685.7898763080062),
                at(-394.21441583727466, 57.80246710244432, 685.8875883000821)
            };
            f.place(ride[0], Vec3.ZERO);
            f.player.compensatedEntities.addEntity(
                    VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), ride[0], 0, 0, 0);
            var nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
            nautilus.hasSaddle = true;
            nautilus.isTame = true;
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(FORWARD_SPRINT);
            PredictionResult lastRide = null;
            for (int tick = 1; tick < ride.length; tick++) {
                if (tick == ride.length - 1) f.input(FORWARD_SHIFT_SPRINT);
                lastRide = f.rideTick(ride[tick], false);
            }

            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.playerPosition(ride[3], Vec3.ZERO, Set.of());
            f.playerPosition(
                    at(-393.93865069723176, 58.82576123072501, 685.7898763080062),
                    Vec3.ZERO,
                    Set.of(
                            Relative.Y_ROT,
                            Relative.X_ROT,
                            Relative.DELTA_X,
                            Relative.DELTA_Y,
                            Relative.DELTA_Z,
                            Relative.ROTATE_DELTA));
            f.player.xRot = YAW;
            f.player.yRot = PITCH;

            command(f, PlayerCommandAction.START_SPRINTING);
            Vec3[] moves = {
                at(-393.9442062679277, 58.78576123161908, 685.7918024540526),
                at(-393.96772484890636, 58.73576468267913, 685.7999564717489),
                at(-393.9862434163911, 58.67845065915165, 685.8063769580494),
                at(-394.010903303721, 58.61578024414628, 685.8147531598029)
            };
            PredictionResult[] results = new PredictionResult[moves.length];
            for (int tick = 0; tick < moves.length; tick++) {
                if (tick == 1) f.input(FORWARD_SPRINT);
                results[tick] = f.tick(moves[tick], false);
            }
            assertEquals(0.0, sprintG.violations, "SprintG after the dismount");
            assertTrue(lastRide.getSimulationContext().getWorldData().isEyeInWater(), "last ride tick eye");
            for (int tick = 0; tick < moves.length; tick++) {
                PredictionResult result = results[tick];
                assertTrue(result.getOffset() < 1.0E-5, "move " + tick + ": " + describe(result));
                assertTrue(result.getSimulationContext().getWorldData().isUnderwater(), "move " + tick + " underwater");
            }
        }
    }

    /**
     * Entity#startRiding stands a swimming player up before its first Entity#rideTick, so that tick's baseTick samples
     * the standing eye where the player mounted: 1.62 over feet in one block of water, over its surface.
     */
    @Test
    void theFirstRideTickSamplesTheStandingEye() throws Exception {
        try (var f = pool(FLOOR_Y);
                var ignored = observeOnly()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.pose = Pose.SWIMMING;
            f.player.compensatedEntities.addEntity(
                    VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), position, 0, 0, 0);
            var nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
            nautilus.hasSaddle = true;
            nautilus.isTame = true;
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(FORWARD_SPRINT);
            PredictionResult first = f.rideTick(position, true);
            assertFalse(first.getSimulationContext().getWorldData().isEyeInWater(), "first ride tick eye");
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

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x + 394, y + 7, z - 685);
    }

    private static JavaTickFixture pool(int surfaceY) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= surfaceY; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private static SprintG sprintG(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        SprintG sprintG = f.player.checkManager.getCheck(SprintG.class);
        sprintG.violations = 0;
        return sprintG;
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }
}
