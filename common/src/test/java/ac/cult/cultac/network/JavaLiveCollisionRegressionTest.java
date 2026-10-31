package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Regressions reduced from DefineOutside's vanilla 1.21.4 live reports on 2026-10-25. */
class JavaLiveCollisionRegressionTest {
    /**
     * Flag 62, 13:21:09 CDT: raw deltaMovement and look from the captured tick, translated away from the map.
     * 1.21.4 LivingEntity#travel chooses travelFallFlying even on a climbable; it adds no walking input.
     */
    @Test
    void a1214GliderOnAClimbableDoesNotAddWalkingInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            Vec3 start = new Vec3(0.12747665247525, FLOOR_Y + 0.55056689792166, 0.57791808766972);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.VINE.defaultState());
            f.place(start, new Vec3(0.16121010996041166, -0.15750607143081652, -0.06835079063497686));
            f.player.isGliding = true;
            f.player.pose = Pose.FALL_FLYING;
            f.player.boundingBox = GetBoundingBox.getPlayerBoundingBox(f.player, start.x, start.y, start.z);
            f.player.xRot = -90.473564F;
            f.player.yRot = 22.650663F;
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, true));
            var result = f.tick(start.add(0.1751176025866812, -0.16711826242740813, -0.06116011313980607), false);
            assertTrue(exact(result), describe(result));
            assertEquals(Vec3.ZERO, result.getInitialStartingVel().getTravelInput());
            assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
        }
    }

    /**
     * Flags 55-56 lost the 0.3 carry when target-seeking erased the input into the wall before clipping it.
     * LivingEntity#travelInFluid tests this Entity#move's horizontalCollision and Entity#isFree after drag.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void walkingIntoAFluidWallKeepsTheCollisionAndTheHop(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -1; x <= 0; x++)
                for (int z = -1; z <= 1; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.WATER.defaultState());
            for (int z = -1; z <= 1; z++)
                f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, z), BlockIds.STONE.defaultState());
            Vec3 start = new Vec3(1 - (double) (0.6F / 2), FLOOR_Y + 0.5, 0.5);
            f.place(start, new Vec3(0, 0.14, 0));
            f.input(new ServerboundPlayerInput(false, false, true, false, false, false, false));
            var result = f.tick(start.add(0, 0.14, 0), false);
            assertTrue(exact(result), describe(result));
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertTrue(move.attempted().x > 0, "the wall hides walking input from the position report");
            assertTrue(move.collidedX());
            assertEquals(0, move.collided().x);
            assertEquals(
                    1,
                    f.player
                            .checkManager
                            .getSimulationProcessor()
                            .getValidPlayerStartingVels()
                            .size());
            assertEquals(
                    (double) 0.3F,
                    f.player
                            .checkManager
                            .getSimulationProcessor()
                            .getValidPlayerStartingVels()
                            .iterator()
                            .next()
                            .y);
            assertTrue(exact(f.tick(start.add(0, 0.14 + (double) 0.3F, 0), false)));
        }
    }

    /** The live map's flowing water must push along its flow, while the walking input still hits the side wall. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aCurrentCannotCancelTheWalkingInputThatCausesTheFluidHop(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int z = -3; z <= 0; z++) {
                for (int x : new int[] {-1, 1})
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(0, FLOOR_Y, z),
                        z == 0
                                ? BlockIds.STONE.defaultState()
                                : DataTables.defaults()
                                        .registry()
                                        .with(BlockIds.WATER.defaultState(), "level", "" + Math.max(0, z + 2)));
            }
            Vec3 start = new Vec3((double) (0.6F / 2), FLOOR_Y + 0.3, -1.5);
            f.place(start, new Vec3(0, 0.14, 0));
            f.player.xRot = 90;
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            var result = f.tick(start.add(0, 0.14, 0.014), false);
            assertTrue(exact(result), describe(result));
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertTrue(move.attempted().x < -0.019, "flow cannot replace the exact input into the wall");
            assertTrue(move.collidedX());
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertEquals(1, carry.size(), carry.toString());
            assertEquals((double) 0.3F, carry.iterator().next().y);
        }
    }

    /** Flag 64, 13:21:26 CDT: the previous coarse honey contact must not shorten a glide before floor collision. */
    @Test
    void aGlideLandingBesideHoneyKeepsTheMovesGroundState() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            f.place(new Vec3(0.75, FLOOR_Y, 0.75), new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var previous = f.tick(new Vec3(0.75, FLOOR_Y, 0.75), true);
            var previousWorld = previous.getSimulationContext().getWorldData();
            JavaTickFixture.setField(previousWorld.getClass(), previousWorld, "honeySlide", DesyncStatus.TRUE);
            Vec3 start = new Vec3(0.7521549848727, FLOOR_Y + 0.11242173265482, 0.763823599797185);
            f.place(start, new Vec3(-0.0021048277196085334, -0.125936914554444, 0.0325671625836891));
            f.player.isGliding = true;
            f.player.pose = Pose.FALL_FLYING;
            f.player.boundingBox = GetBoundingBox.getPlayerBoundingBox(f.player, start.x, start.y, start.z);
            f.player.xRot = 8.076614F;
            f.player.yRot = 14.550717F;
            var result = f.tick(new Vec3(0.74994643819775, FLOOR_Y, 0.808407572349516), true);
            assertTrue(exact(result), describe(result));
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertTrue(move.attempted().y < -0.12);
            assertTrue(move.verticalCollisionBelow());
            assertTrue(f.player.onGround);
        }
    }

    /** Real glide-spam piston tick 6432: the corner pushes replace X/Z before glide physics, not after it. */
    @Test
    void anOpposingPistonOverlapPushesOutBeforeUpdatingGlideMovement() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            Vec3 start = new Vec3(0.08895331373935, FLOOR_Y, 0.77919816101131);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            f.place(start, new Vec3(-0.09100000262260438, -0.0784000015258789, 0.09100000262259919));
            f.player.onGround = f.player.lastOnGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            f.player.isGliding = true;
            f.player.pose = Pose.CROUCHING;
            f.player.boundingBox = GetBoundingBox.getPlayerBoundingBox(f.player, start.x, start.y, start.z);
            f.player.xRot = 32.49F;
            f.player.yRot = 13;
            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            var result = f.tick(start.add(-0.10122900348119401, 0, 0.10814740940682555), true);
            assertTrue(exact(result), describe(result));
            var collision = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertTrue(collision.attempted().x < -0.1);
            assertTrue(collision.attempted().z > 0.1);
            assertTrue(collision.verticalCollisionBelow());
            assertEquals(Pose.CROUCHING, result.getSimulationContext().getPose());
        }
    }

    /** Player#isPushedByFluid returns false while ability flight is active, even inside flowing water. */
    @Test
    void abilityFlightDoesNotReceiveTheNewExactFluidCurrent() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int z = -3; z <= 0; z++) {
                for (int x : new int[] {-1, 1})
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(0, FLOOR_Y, z),
                        z == 0
                                ? BlockIds.STONE.defaultState()
                                : DataTables.defaults()
                                        .registry()
                                        .with(BlockIds.WATER.defaultState(), "level", "" + Math.max(0, z + 2)));
            }
            Vec3 start = new Vec3(0.5, FLOOR_Y + 0.3, -1.5);
            f.place(start, Vec3.ZERO);
            f.player.canFly = f.player.isFlying = true;
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var result = f.tick(start, false);
            // Check the simulated move itself; ability flight retains the existing post-check exemption.
            assertEquals(0, result.getOffset(), 1.0E-12, describe(result));
            var collision = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(Vec3.ZERO, collision.attempted());
        }
    }
}
