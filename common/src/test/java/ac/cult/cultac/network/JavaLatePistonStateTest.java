package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassAfterTravel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaLatePistonStateTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** Entity#move accumulates fall distance before the later piston collides with PowderSnowBlock's shape. */
    @Test
    void latePistonUsesFallDistanceAfterTravelCrossesPowderSnowThreshold() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.6, FLOOR_Y + 0.5, 0.5);
            f.place(position, new Vec3(0, -0.2, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            JavaTickFixture.setField(
                    SimulationProcessor.class,
                    f.player.checkManager.getSimulationProcessor(),
                    "profileCarry",
                    new JavaPredictionCarry(f.player.compensatedEntities.getSelf(), 2.4));
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(1, FLOOR_Y, 0),
                    BLOCKS.block("minecraft:powder_snow").defaultState());
            BlockPos base = new BlockPos(-1, FLOOR_Y + 1, 0);
            int piston = BLOCKS.with(BLOCKS.block("minecraft:piston").defaultState(), "facing", "east");
            f.player.compensatedWorld.updateBlock(base, piston);
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player.compensatedWorld.pistons.handleBlockEvent(base, BLOCKS.blockIndex(piston), 0, 5, 0);
            Vec3 report = new Vec3(1 - (double) (0.6F / 2), position.y - 0.2, position.z);
            var result = f.tick(report, false);
            assertTrue(exact(result), () -> describe(result) + " pass=" + JavaMovementEngine.passAfterTravel(result));
            var travel = JavaMovementEngine.passAfterTravel(result).travel();
            // Even with a double field, Entity#checkFallDamage subtracts (float) movement.y.
            assertEquals(2.4 - (float) (travel.position().y - position.y), travel.fallDistance(), 1e-12);
        }
    }

    /** travelFallFlying stops the flag after climbing travelInAir, before the block entities tick. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aShoveAfterClimbingTravelUsesTheStoppedGlideFlag(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.7, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var context = f.tick(position, true).getSimulationContext();
            JavaTickFixture.setField(context.getClass(), context, "gliding", true);
            f.player.isGliding = true;
            JavaTickFixture.setField(
                    context.getWorldData().getClass(), context.getWorldData(), "climbingAtStart", DesyncStatus.TRUE);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y - 1, 0),
                    BLOCKS.block("minecraft:honey_block").defaultState());
            int head = BLOCKS.with(BLOCKS.block("minecraft:piston_head").defaultState(), "facing", "east");
            var pass = new ClientBlockEntityPass(
                    List.of(new ClientBlockEntityPass.Piston(
                            new BlockPos(0, FLOOR_Y + 1, 0), head, Direction.EAST, true, true, 0)),
                    10);
            var state = new JavaPassState(
                    position,
                    new Vec3(0.1, 0, 0),
                    false,
                    new MainSupportingBlockData(null, false),
                    null,
                    0,
                    ClientPistonDeltas.NONE);
            var pushed = JavaBlockEntityPass.run(f.player, context, pass, state, Pose.STANDING, false, position)
                    .state();
            assertEquals(
                    0.1 * (version.isOlderThan(ClientVersion.V_1_21_5) ? 1.0 : (double) 0.4F),
                    pushed.velocity().x,
                    1e-12);
        }
    }

    /** SHULKER_BOX consumes and scales the multiplier set by the travel's berry callback, even in 26.3. */
    @Test
    void lateShulkerShoveConsumesTheTravelBerryMultiplier() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 delta = new Vec3(0.3, 0, 0);
            f.place(start, delta);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(1, FLOOR_Y, 0),
                    BLOCKS.block("minecraft:sweet_berry_bush").defaultState());
            var prediction = f.tick(start.add(delta), false);
            var pass = new ClientBlockEntityPass(
                    List.of(new ClientBlockEntityPass.ShulkerBox(
                            new SimpleCollisionBox(0.7, FLOOR_Y, 0, 1, FLOOR_Y + 2, 1), new Vec3(0.1, 0, 0))),
                    10);
            var replay = JavaPassAfterTravel.select(
                    f.player,
                    prediction,
                    MovementTrace.start(new PredVector(delta)),
                    pass,
                    delta.add(0.1 * (double) 0.8F, 0, 0));
            assertEquals(new Vec3(0.8F, 0.75, 0.8F), replay.travel().stuckSpeed());
            assertEquals(0.8 + 0.1 * (double) 0.8F, replay.result().state().position().x, 1e-12);
            assertEquals(Vec3.ZERO, replay.result().state().velocity());
        }
    }

    /** Entity#collide includes the boat during travel, before a perpendicular block-entity shove. */
    @Test
    void latePassSelectionKeepsTheTravelsBoatCollision() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            var prediction = f.tick(start, true);
            f.player.compensatedEntities.addEntity(
                    100, EntityTypeIds.OAK_BOAT, new Vec3(1.6, FLOOR_Y + 0.5, 0.5), 0, 0, 0);
            var pass = new ClientBlockEntityPass(
                    List.of(new ClientBlockEntityPass.ShulkerBox(
                            new SimpleCollisionBox(0, FLOOR_Y, 0, 1, FLOOR_Y + 2, 1), new Vec3(0, 0, 0.1))),
                    10);
            double clippedX = 1.6 - (double) (1.375F / 2) - (double) (0.6F / 2);
            var replay = JavaPassAfterTravel.select(
                    f.player,
                    prediction,
                    MovementTrace.start(new PredVector(new Vec3(0.3, 0, 0))),
                    pass,
                    new Vec3(clippedX - start.x, 0, 0.1));
            assertEquals(clippedX, replay.travel().position().x, 1e-12);
            assertEquals(clippedX, replay.result().state().position().x, 1e-12);
            assertEquals(0.6, replay.result().state().position().z, 1e-12);
        }
    }

    /** handleRelativeFrictionAndCalculateMovement boosts at travel's endpoint, before the piston moves off a ladder. */
    @Test
    void aPistonOffALadderKeepsTheEarlierTravelsClimbBoost() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0),
                    BLOCKS.with(BLOCKS.block("minecraft:ladder").defaultState(), "facing", "north"));
            BlockPos base = new BlockPos(-1, FLOOR_Y + 1, 0);
            int piston = BLOCKS.with(BLOCKS.block("minecraft:piston").defaultState(), "facing", "east");
            f.player.compensatedWorld.updateBlock(base, piston);
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.7, FLOOR_Y + 0.5, 0.5);
            double falling = -0.08 * (double) 0.98F;
            double climb = (0.2 - 0.08) * (double) 0.98F;
            f.place(start, new Vec3(0, falling, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            f.player.compensatedWorld.pistons.handleBlockEvent(base, BLOCKS.blockIndex(piston), 0, 5, 0);
            Vec3 first = new Vec3(0.5 + (double) (0.6F / 2) + 0.01, start.y + falling, start.z);
            var one = f.tick(first, false);
            assertTrue(exact(one), describe(one));
            var firstCarry = java.util.Set.copyOf(
                    f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            Vec3 second = first.add(0.5, climb, 0);
            var two = f.tick(second, false);
            assertTrue(
                    exact(two),
                    () -> describe(two) + " first carry=" + firstCarry + " pass="
                            + JavaMovementEngine.passAfterTravel(two));
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(carry.stream().anyMatch(v -> Math.abs(v.y - climb) < 1e-12), carry.toString());
            var three = f.tick(second.add(0, climb, 0), false);
            assertTrue(exact(three), describe(three));
        }
    }

    /** moveRelative adds backward input before handleOnClimbable clamps the original velocity, once. */
    @Test
    void latePassTravelClampsAfterAddingInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.4, FLOOR_Y + 0.5, 0.5);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0),
                    BLOCKS.with(BLOCKS.block("minecraft:ladder").defaultState(), "facing", "north"));
            f.player.xRot = -90F;
            f.place(start, Vec3.ZERO);
            var prediction = f.tick(start, false);
            f.input(new ServerboundPlayerInput(false, true, false, false, false, false, false));
            var pass = new ClientBlockEntityPass(
                    List.of(new ClientBlockEntityPass.ShulkerBox(
                            new SimpleCollisionBox(0, FLOOR_Y, 0, 1, FLOOR_Y + 3, 1), new Vec3(0, 0, 0.1))),
                    10);
            var replay = JavaPassAfterTravel.select(
                    f.player,
                    prediction,
                    MovementTrace.start(new PredVector(new Vec3(0.3, 0, 0))),
                    pass,
                    new Vec3((double) 0.15F, 0, 0.1));
            assertEquals((double) 0.15F * (double) 0.91F, replay.travel().velocity().x, 1e-12);
        }
    }

    @Test
    void aLatePistonRetainsTheInputInclusiveLadderClampInTheFullPipeline() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.4, FLOOR_Y + 0.5, 0.5);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0),
                    BLOCKS.with(BLOCKS.block("minecraft:ladder").defaultState(), "facing", "west"));
            BlockPos base = new BlockPos(0, FLOOR_Y + 1, -1);
            int piston = BLOCKS.with(BLOCKS.block("minecraft:piston").defaultState(), "facing", "south");
            f.player.compensatedWorld.updateBlock(base, piston);
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player.xRot = -90F;
            f.place(start, Vec3.ZERO);
            assertTrue(exact(f.tick(start, false)));
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.3, 0, 0));
            f.input(new ServerboundPlayerInput(false, true, false, false, false, false, false));
            f.player.compensatedWorld.pistons.handleBlockEvent(base, BLOCKS.blockIndex(piston), 0, 3, 0);
            var result = f.tick(new Vec3(start.x + (double) 0.15F, start.y, 0.5 + (double) (0.6F / 2) + 0.01), false);
            assertTrue(exact(result), () -> describe(result) + " " + JavaMovementEngine.passAfterTravel(result));
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            double expected = (double) 0.15F * (double) 0.91F;
            assertTrue(carry.stream().anyMatch(v -> Math.abs(v.x - expected) < 1e-12), carry.toString());
        }
    }

    /** Player#maybeBackOffFromEdge shortens the travel before the later shove, preserving deltaMovement. */
    @Test
    void aLatePassStartsAfterTheTravelsSneakingBackoff() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = 1; x <= 8; x++)
                for (int z = -8; z <= 8; z++)
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z),
                            BLOCKS.block("minecraft:air").defaultState());
            Vec3 start = new Vec3(0.99, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, true, false));
            var prediction = f.tick(start, true);
            var pass = new ClientBlockEntityPass(
                    List.of(new ClientBlockEntityPass.ShulkerBox(
                            new SimpleCollisionBox(-1, FLOOR_Y, -1, 3, FLOOR_Y + 3, 2), new Vec3(0, 0, 0.1))),
                    10);
            var replay = JavaPassAfterTravel.select(
                    f.player,
                    prediction,
                    MovementTrace.start(new PredVector(new Vec3(0.4, -0.0784000015258789, 0))),
                    pass,
                    new Vec3(0.30000000000000004, 0, 0.1));
            assertEquals(start.x + 0.30000000000000004, replay.travel().position().x, 1e-12);
            assertEquals(0.4 * (double) (0.6F * 0.91F), replay.travel().velocity().x, 1e-12);
            assertEquals(0.6, replay.result().state().position().z, 1e-12);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aPistonAcrossASneakingEdgePreservesTheUnshortenedVelocity(boolean diagonal) throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = 1; x <= 8; x++)
                for (int z = -8; z <= 8; z++)
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z),
                            BLOCKS.block("minecraft:air").defaultState());
            BlockPos base = new BlockPos(0, FLOOR_Y + 1, -1);
            int piston = BLOCKS.with(BLOCKS.block("minecraft:piston").defaultState(), "facing", "south");
            f.player.compensatedWorld.updateBlock(base, piston);
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.99, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, true, false));
            assertTrue(exact(f.tick(start, true)));
            assertTrue(exact(f.tick(start, true)));
            var keys = new ServerboundPlayerInput(diagonal, false, diagonal, false, false, true, false);
            f.input(keys);
            Vec3 input = ClientMoveInput.of(f, keys, 0.3F, 0.1F, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.4, -0.0784000015258789, 0));
            f.player.compensatedWorld.pistons.handleBlockEvent(base, BLOCKS.blockIndex(piston), 0, 3, 0);
            double backed = 0.4 + input.x;
            while (start.x - (double) (0.6F / 2) + backed + 1e-7 >= 1) backed -= 0.05;
            var result = f.tick(new Vec3(start.x + backed, FLOOR_Y, 0.5 + (double) (0.6F / 2) + 0.01), false);
            assertTrue(exact(result), () -> describe(result) + " " + JavaMovementEngine.passAfterTravel(result));
            double expected = (0.4 + input.x) * (double) (0.6F * 0.91F);
            double expectedZ = input.z * (double) (0.6F * 0.91F);
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carry.stream().anyMatch(v -> Math.abs(v.x - expected) < 1e-12 && Math.abs(v.z - expectedZ) < 1e-12),
                    carry.toString());
        }
    }
}
