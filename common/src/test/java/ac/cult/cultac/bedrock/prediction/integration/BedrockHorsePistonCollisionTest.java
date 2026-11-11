package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockEntityMove;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * A ridden horse stands on a floor of upward pistons, pressed along +Z against a piston that faces east at z 26. The
 * two floor pistons under it fire: on the tick their arms appear the client reports the horse 0.199501 back along X,
 * out of the arm boxes it overlaps, and still pressed against the side piston on Z. The client gathers block shapes
 * cell by cell, X outermost, then Z, then Y, and its swept move takes them in reverse, so the side piston is the last
 * shape to clip the Z the horse is pushed out of an arm by.
 */
public final class BedrockHorsePistonCollisionTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final int STONE = REGISTRY.block("minecraft:stone").defaultState();
    private static final PlayerDimensionsState HORSE = new PlayerDimensionsState(1.3965F, 1.6F);
    private static final float AHEAD = 0.2141F;
    private static final int EXPANDING = 1;
    private static final int RETRACTING = 3;

    @Test
    public void blocksAreGatheredWithXOutermostThenZThenY() {
        run(player -> {
            for (int x = 100; x <= 101; x++) {
                for (int y = 100; y <= 101; y++) {
                    for (int z = 100; z <= 101; z++) {
                        place(player, x, y, z, STONE);
                    }
                }
            }
            var query = new SimpleCollisionBox(100.2, 100.2, 100.2, 101.8, 101.8, 101.8);
            List<BlockPosition> positions = sample(player, query).blocks().stream()
                    .map(PlacedBlockCollision::position)
                    .toList();

            assertEquals(
                    List.of(
                            new BlockPosition(100, 100, 100),
                            new BlockPosition(100, 101, 100),
                            new BlockPosition(100, 100, 101),
                            new BlockPosition(100, 101, 101),
                            new BlockPosition(101, 100, 100),
                            new BlockPosition(101, 101, 100),
                            new BlockPosition(101, 100, 101),
                            new BlockPosition(101, 101, 101)),
                    positions);
        });
    }

    @Test
    public void armsThatAppearUnderTheHorsePushItBackAlongXOnly() {
        run(player -> {
            pistons(player);
            var result = move(player, new Vec3d(121.50125F, 120.0F, 25.30175F), 4.5343251E-5F);

            assertEquals(121.30175F, (float) result.position().x(), 0.0F);
            assertEquals(120.0F, (float) result.position().y(), 0.0F);
            assertEquals(25.30175F, (float) result.position().z(), 0.0F);
            assertEquals(0.0, result.velocity().x(), 0.0);
            assertFalse(result.steppedUp());
        });
    }

    @Test
    public void aHorseCorrectedBackOntoTheArmsIsPushedOutTheSameWay() {
        run(player -> {
            pistons(player);
            var result = move(player, new Vec3d(121.50075F, 120.0F, 25.30175F), 2.0585800E-5F);

            assertEquals(121.30175F, (float) result.position().x(), 0.0F);
            assertEquals(25.30175F, (float) result.position().z(), 0.0F);
            assertFalse(result.steppedUp());
        });
    }

    /**
     * Pushed out of the arms, the horse steps up onto them on the next tick: the step gains only the 0.0000206 X the
     * horse is asked to move, which its move without the step loses to an arm box, and the client keeps it.
     */
    @Test
    public void aStepThatGainsTheSmallestHorizontalMoveIsKept() {
        run(player -> {
            pistons(player);
            var result = move(player, new Vec3d(121.30175F, 120.0F, 25.30175F), 2.0585800E-5F);

            assertTrue(result.steppedUp());
            assertEquals(121.30177F, (float) result.position().x(), 0.0F);
            assertEquals(121.0F, (float) result.position().y(), 0.0F);
            assertEquals(25.30175F, (float) result.position().z(), 0.0F);
        });
    }

    /**
     * An arm's boxes are its piston's: they start a cell back, at the piston, and its progress pushes them out along
     * its facing, half a block per client tick.
     */
    @Test
    public void anArmMidStrokeCollidesWhereItsPistonsProgressHasPushedIt() {
        run(player -> {
            pistons(player);
            var pistonBlocks = player.bedrockState.pistonBlocks;
            pistonBlocks.pistonArm(new BlockPos(121, 119, 24), EXPANDING);
            pistonBlocks.pistonArm(new BlockPos(120, 120, 26), RETRACTING);
            pistonBlocks.tick(player.compensatedWorld::getBlockStateIdAt);
            var world = sample(player, new SimpleCollisionBox(119.5, 119.5, 23.5, 122.5, 121.5, 27.5));

            assertEquals(
                    List.of(
                            new WorldCollisionBox(121.3125, 119.25, 24.3125, 121.6875, 119.75, 24.6875),
                            new WorldCollisionBox(121.375, 119.75, 24.375, 121.625, 120.25, 24.625),
                            new WorldCollisionBox(121.0, 120.25, 24.0, 122.0, 120.5, 25.0)),
                    boxesAt(world, 121, 120, 24));
            assertEquals(
                    List.of(
                            new WorldCollisionBox(120.25, 120.3125, 26.3125, 120.75, 120.6875, 26.6875),
                            new WorldCollisionBox(120.75, 120.375, 26.375, 121.25, 120.625, 26.625),
                            new WorldCollisionBox(121.25, 120.0, 26.0, 121.5, 121.0, 27.0)),
                    boxesAt(world, 121, 120, 26));
            // The arm of the piston without data is out.
            assertEquals(
                    List.of(
                            new WorldCollisionBox(122.3125, 119.75, 24.3125, 122.6875, 120.25, 24.6875),
                            new WorldCollisionBox(122.375, 120.25, 24.375, 122.625, 120.75, 24.625),
                            new WorldCollisionBox(122.0, 120.75, 24.0, 123.0, 121.0, 25.0)),
                    boxesAt(world, 122, 120, 24));
        });
    }

    @Test
    public void anArmWithoutAPistonBehindItHasNoBoxes() {
        run(player -> {
            pistons(player);
            place(player, 121, 119, 24, STONE);
            var world = sample(player, new SimpleCollisionBox(120.5, 119.5, 23.5, 122.5, 121.5, 25.5));

            assertEquals(List.of(), boxesAt(world, 121, 120, 24));
        });
    }

    private static List<WorldCollisionBox> boxesAt(BlockCollisionWorld world, int x, int y, int z) {
        return world.blockAt(new BlockPosition(x, y, z)).orElseThrow().collisionBoxes();
    }

    private static BedrockEntityMove.Result move(CultPlayer player, Vec3d feet, float moveX) {
        var requested = new Vec3d(moveX, -0.0784F, AHEAD);
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet, requested, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .withHorse(new BedrockHorseState(null, false, 0, false, false, 0, -1, false, 0))
                .withPlayerDimensions(HORSE, true);
        var box = new SimpleCollisionBox(
                feet.x() - HORSE.radius(),
                feet.y(),
                feet.z() - HORSE.radius(),
                feet.x() + HORSE.radius(),
                feet.y() + HORSE.height(),
                feet.z() + HORSE.radius());
        return BedrockEntityMove.move(
                state,
                BedrockInputFrame.idle(1),
                sample(player, box.expand(2.0)),
                HORSE,
                feet.add(requested),
                requested,
                requested.y(),
                true,
                1.0625D,
                false);
    }

    /**
     * A stone floor at y 119 with upward pistons at x 121 and 122, the pair at z 24 extended; a piston at x 120, z 26
     * facing east, two high and extended, with stone behind it.
     */
    private static void pistons(CultPlayer player) {
        for (int x = 112; x <= 130; x++) {
            for (int z = 16; z <= 35; z++) {
                place(player, x, 119, z, STONE);
            }
        }
        for (int z = 20; z <= 32; z += 4) {
            for (int x = 121; x <= 122; x++) {
                place(player, x, 119, z, piston("up", z == 24));
                if (z == 24) place(player, x, 120, z, head("up"));
            }
        }
        for (int y = 120; y <= 121; y++) {
            place(player, 119, y, 26, STONE);
            place(player, 120, y, 26, piston("east", true));
            place(player, 121, y, 26, head("east"));
        }
    }

    private static int piston(String facing, boolean extended) {
        int state = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", facing);
        return REGISTRY.with(state, "extended", Boolean.toString(extended));
    }

    private static int head(String facing) {
        return REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", facing);
    }

    private static void place(CultPlayer player, int x, int y, int z, int state) {
        player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
        player.compensatedWorld.updateBlock(x, y, z, state);
    }

    private static BlockCollisionWorld sample(CultPlayer player, SimpleCollisionBox query) {
        return new BedrockSolidBlockSampler(new BedrockFluidBlockSampler())
                .sample(player, query, BedrockClientBlockShapeMappings.catalog(), BedrockCollisionShapeQuery.NONE);
    }

    private static void run(Consumer<CultPlayer> body) {
        OfflinePlayers.installConfig();
        BedrockClientBlockShapeMappings.initialize();
        CultPlayer player = OfflinePlayers.player();
        try {
            body.accept(player);
        } finally {
            OfflinePlayers.close(player);
        }
    }
}
