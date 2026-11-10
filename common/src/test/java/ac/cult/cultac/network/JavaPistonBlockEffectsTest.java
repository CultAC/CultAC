package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPistonBlockEffectsTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final Vec3 BERRY = new Vec3(0.8F, 0.75, 0.8F);
    private static final Vec3 VELOCITY = new Vec3(0.04, -0.0784000015258789, 0.02);

    /** A piston's separate inside-block pass remembers head contact even though the feet get no stuck speed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void pushingTheHeadIntoPowderSnowRemembersContactWithoutStickingTheFeet(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y + 1, 0), BlockIds.POWDER_SNOW.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var context = f.tick(position, true).getSimulationContext();
            int head = REGISTRY.with(BlockIds.PISTON_HEAD.defaultState(), "facing", "east");
            var mover = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y + 1, 0), head, Direction.EAST, true, true, 0);
            var start = new JavaPassState(
                    position,
                    VELOCITY,
                    true,
                    new MainSupportingBlockData(null, true),
                    null,
                    0,
                    ClientPistonDeltas.NONE);
            var after = JavaBlockEntityPass.run(
                            f.player,
                            context,
                            new ClientBlockEntityPass(List.of(mover), 10),
                            start,
                            Pose.STANDING,
                            false,
                            position)
                    .state();
            assertEquals(0.5 + (double) (0.6F / 2) + 0.01, after.position().x, 1e-12);
            assertTrue(after.powderContact(), "the explicit piston callback sets the current tick's powder flag");
            assertNull(after.stuckSpeed(), "head contact cannot apply the feet-only powder stuck multiplier");
            assertEquals(VELOCITY, after.velocity());
        }
    }

    /** HoneyBlock#entityInside sees the falling state after a piston clips against its inset side. */
    @Test
    void aPistonPushIntoTheHoneySideRunsItsSlideCallback() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(1, FLOOR_Y, 0),
                    REGISTRY.block("minecraft:honey_block").defaultState());
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.7, FLOOR_Y + 0.5, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var context = f.tick(position, false).getSimulationContext();
            int head = REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "east");
            var mover = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y + 1, 0), head, Direction.EAST, true, true, 0);
            var state = new JavaPassState(
                    position,
                    new Vec3(0.04, -0.4, 0.02),
                    false,
                    new MainSupportingBlockData(null, false),
                    null,
                    3,
                    ClientPistonDeltas.NONE);
            var after = JavaBlockEntityPass.run(
                            f.player,
                            context,
                            new ClientBlockEntityPass(List.of(mover), 10),
                            state,
                            Pose.STANDING,
                            false,
                            position)
                    .state();
            assertEquals(1.0625 - (double) (0.6F / 2), after.position().x, 1.0E-12);
            assertEquals(position.y, after.position().y);
            assertEquals(-0.0D, after.velocity().x);
            assertEquals((-0.05 - 0.08) * 0.98F, after.velocity().y);
            assertEquals(0.02 * (-0.05 / (-0.4 / 0.98F + 0.08)), after.velocity().z);
            assertEquals(0, after.fallDistance());
            assertFalse(after.onGround());
        }
    }

    /**
     * PistonMovingBlockEntity#moveEntityByPiston runs applyEffectsFromBlocks(from, to) after Entity#move.
     * Entering a bush must set the multiplier for the next move, without retroactively slowing this shove
     * or zeroing its stored velocity. A subsequent shove consumes that multiplier and clears velocity.
     * Before 1.21.10 Entity#move also scales the piston delta; modern pistons clear it without scaling.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void pushingThroughBerriesAppliesAndThenConsumesTheStuckEffect(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 1),
                    REGISTRY.block("minecraft:sweet_berry_bush").defaultState());
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 2.31);
            f.place(position, VELOCITY);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var context = f.tick(position.add(0.04, 0, 0.02), true).getSimulationContext();
            if (version == ClientVersion.V_1_21_9)
                context.setMovementPatch(
                        release.equals("1.21.10") ? JavaMovementPatch.V_1_21_10 : JavaMovementPatch.V_1_21_9);
            int head = REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "north");
            var mover = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y + 1, 1), head, Direction.NORTH, true, true, 0);
            var state = new JavaPassState(
                    position,
                    VELOCITY,
                    true,
                    new MainSupportingBlockData(null, true),
                    null,
                    3,
                    ClientPistonDeltas.NONE);
            var entered = JavaBlockEntityPass.run(
                            f.player,
                            context,
                            new ClientBlockEntityPass(List.of(mover), 10),
                            state,
                            Pose.STANDING,
                            false,
                            position)
                    .state();
            assertEquals(position.z - 0.51, entered.position().z, 1.0E-12);
            assertEquals(VELOCITY, entered.velocity());
            assertEquals(BERRY, entered.stuckSpeed());
            assertEquals(0, entered.fallDistance());
            assertFalse(entered.onGround());

            var nextMover = new ClientBlockEntityPass.Piston(mover.pos(), head, Direction.NORTH, true, true, 0.5F);
            var consumed = JavaBlockEntityPass.run(
                            f.player,
                            context,
                            new ClientBlockEntityPass(List.of(nextMover), 11),
                            entered,
                            Pose.STANDING,
                            false,
                            position)
                    .state();
            double scale =
                    version.isOlderThan(ClientVersion.V_1_21_11) && !release.equals("1.21.10") ? (double) 0.8F : 1;
            assertEquals(entered.position().z - 0.51 * scale, consumed.position().z, 1.0E-12);
            assertEquals(Vec3.ZERO, consumed.velocity());
            assertEquals(BERRY, consumed.stuckSpeed(), "the shove's swept block pass visits the bush again");
        }
    }
}
