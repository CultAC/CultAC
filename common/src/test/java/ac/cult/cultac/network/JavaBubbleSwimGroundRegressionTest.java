package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class JavaBubbleSwimGroundRegressionTest {
    private static Stream<Arguments> inputs() {
        return Stream.of(ClientVersion.V_1_21_4, ClientVersion.V_26_3)
                .flatMap(version -> Stream.of(false, true).map(jump -> Arguments.of(version, jump)));
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void swimmingPitchUsesTheSwimmingFlagWithoutCurrentBodyWater(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var feet = new BlockPos(0, FLOOR_Y, 0);
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.WATER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(at, new Vec3(0, -0.005, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var wet = f.tick(at, true);
            assertFalse(wet.hasEffectiveFlags(), JavaTickFixture.describe(wet));
            assertTrue(wet.getSimulationContext().getWorldData().isEyeInWater());
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.AIR.defaultState());
            var names = ClientWorldDefaults.defaults().initialNames("minecraft:fluid");
            var water = List.of(
                    names.indexOf("minecraft:empty"),
                    names.indexOf("minecraft:water"),
                    names.indexOf("minecraft:flowing_water"));
            if (f.player.registryState == null) f.player.registryState = new ClientComponentRegistries();
            f.player.registryState.appendTags(new RegistryTags(Map.of(
                    "minecraft:fluid",
                    new RegistryTags.Payload(Map.of("minecraft:water", water, "minecraft:lava", List.of())))));
            f.player.sprintState.onServerFlag(true);
            // A dry swimming body is the short pose Player#updatePlayerPose left last tick. With
            // EMPTY tagged as WATER, a standing legacy box also samples the air cell above its feet.
            f.player.pose = Pose.SWIMMING;
            f.player.yRot = 14.250038F;
            var result = f.tick(at, true);
            assertFalse(result.isExempt());
            assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
            assertEquals(false, result.getSimulationContext().getWorldData().isInWater());
            assertTrue(result.getSimulationContext().getWorldData().isSwimming());
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(-0.02549697626829147, move.attempted().y, 1e-13);
            assertTrue(move.verticalCollisionBelow());
        }
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void shiftingPrecedesTheCutoffAndWaterJumpFollowsIt(ClientVersion version, boolean jump) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.WATER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 0.25, 0.5);
            f.place(start, new Vec3(0, 0.0415, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, jump, true, false));
            // goDownInWater leaves less than 0.003, which aiStep zeroes before jumpInLiquid adds 0.04F.
            double y = jump ? (double) 0.04F : 0;
            var result = f.tick(start.add(0, y, 0), false);
            assertFalse(result.isExempt());
            assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
            assertEquals(0, result.getOffset(), 1e-13);
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(y, move.attempted().y, 1e-13);
            assertFalse(move.verticalCollisionBelow());
        }
    }

    /** The 20:05:48 UTC user flag: exact position, but a zero-Y fluid range erased the floor collision. */
    @ParameterizedTest
    @MethodSource("inputs")
    void aDownwardLookingSwimmerStillHitsTheFloor(ClientVersion version, boolean jump) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int bubble = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), true);
            for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), bubble);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y - 1, 0),
                    DataTables.defaults()
                            .registry()
                            .block("minecraft:magma_block")
                            .defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.498559542041, FLOOR_Y, 0.21009841780166);
            f.player.pose = Pose.SWIMMING;
            f.player.swimState.onServerFlag(true);
            f.player.isSprinting = true;
            f.player.sprintState.onServerFlag(true);
            f.player.xRot = -12.171856F;
            f.player.yRot = 14.250038F;
            f.place(start, new Vec3(-0.06138543822625764, -0.03, 0.03943562158939706));
            f.input(new ServerboundPlayerInput(false, false, false, true, jump, false, true));

            var result = f.tick(start.add(-0.08054475051449117, 0, 0.04356667290326044), true);
            assertFalse(result.isExempt());
            assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
            assertEquals(0, result.getOffset(), 1e-13);
            assertTrue(result.getSimulationContext().getWorldData().isSwimming());
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            // Native 1.21.4 Entity.move entry, jump then Player.travel pitch; no jump is the same source sequence.
            assertEquals(jump ? -0.01177197708636522 : -0.04837197626829147, move.attempted().y, 1e-13);
            assertEquals(0, move.collided().y);
            assertTrue(move.verticalCollisionBelow());
        }
    }
}
