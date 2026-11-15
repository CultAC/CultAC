package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintG;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
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
     * standing eye at 60.0333 is in the water and the first tick off the nautilus is under water. The server's
     * nautilus stayed at the vehicle move before the last one and its move_entity packets bring the client's back
     * there: from the second tick off it the player's feet are inside its box, LivingEntity#pushEntities pushes the
     * player back by nearly the speed it carries, and LivingEntity#aiStep drops the rest, under 0.003.
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
            f.player.packetEntityReplication.addEntity(
                    VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), ride[0], 0, 0, 0, -1);
            f.pongThrough(f.player.lastTransactionSent.get());
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
                if (tick == 1) {
                    f.input(FORWARD_SPRINT);
                    f.entityMotion(VEHICLE, new Vec3(0.02612464139656967, 0.0, -0.009277910028688274));
                    moveNautilus(f, -2228, -592, 792, 3, false, 0, 0);
                }
                if (tick == 2) {
                    f.entityMotion(
                            VEHICLE, new Vec3(0.04571812244399687, 0.0041506439602025935, -0.019288286638588836));
                    moveNautilus(f, 63, 19, -31, 1, true, 80.15625F, 1.40625F);
                    f.entityMotion(
                            VEHICLE, new Vec3(0.07129341390465727, 0.008423365683940753, -0.03473112372581333));
                    moveNautilus(f, 142, 38, -79, 1, true, 90.0F, -1.40625F);
                }
                if (tick == 3) {
                    f.entityMotion(
                            VEHICLE, new Vec3(0.1030336324238541, 0.012207776353537314, -0.05652200451687728));
                    moveNautilus(f, 248, 56, -151, 1, true, 99.84375F, -1.40625F);
                    f.player.xRot = -1729.0563F;
                }
                f.pongThrough(f.player.lastTransactionSent.get());
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

    /** The server's move_entity for the nautilus the player left, in 1/4096 of a block over {@code ticks} steps. */
    private static void moveNautilus(
            JavaTickFixture f, int x, int y, int z, int ticks, boolean rotates, float yaw, float pitch) {
        var move = new ClientboundMoveEntity(
                VEHICLE,
                new EntityDelta.Stepped(List.of(new EntityDelta.Step((short) x, (short) y, (short) z, ticks))),
                yaw,
                pitch,
                false,
                true,
                rotates);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x + 394, y + 7, z - 685);
    }

    private static JavaTickFixture pool(int surfaceY) throws Exception {
        var f = new JavaTickFixture();
        f.water(new BlockPos(-8, FLOOR_Y, -8), new BlockPos(8, surfaceY, 8));
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
