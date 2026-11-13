package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockMetadata;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * A slime floor beside the head of a piston that faces it. The head collides as a plate against the slime, a rod and
 * the rod's thick end, but an actor stands on it as on its whole cell.
 */
public final class BedrockStandingSurfaceBlockTest {
    private static final Vec3d RESTING = new Vec3d(0.0D, -0.0784D, 0.0D);
    private static final float SLIME_SLOWDOWN = 0.0784F * 0.2F + 0.4F;

    @Test
    public void slimeSlowsAWalkThatEndsOverItAcrossTheSeamOfAPistonHead() {
        // A first step from rest, 0.04 of a block inside the slime's cell with the box over the head's plate.
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(276.29998779296875D, 83.0D, -34.01358413696289D),
                new Vec3d(0.0D, -0.0784D, -1.7247697714495303E-9D),
                BedrockInputFrame.idle(2976L),
                BedrockCollisionFlags.ON_GROUND);
        BedrockInputFrame frame = new BedrockInputFrame(2977L, 41.5157F, 61.153183F, false, false, false, Set.of());

        BedrockMovementResult result = BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        previous,
                        frame,
                        frame.intent(),
                        BedrockWorldSnapshot.fromContext(context()),
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        new Vec3d(0.0D, 0.0D, -1.0D)))
                .getFirst()
                .movementResult();
        assertTrue(result.predictedState().collisionFlags().onGround());
        BedrockMovementState next = BedrockForwardTick.finish(result, result.predictedState());

        assertEquals(276.327392578125D, next.physicalFeetPosition().x(), 2.0E-6D);
        assertEquals(83.0D, next.physicalFeetPosition().y(), 0.0D);
        assertEquals(-34.04454040527344D, next.physicalFeetPosition().z(), 2.0E-6D);
        // The slime's friction took the step to (0.019949883, -0.022536797); its slowdown takes what is left.
        assertEquals(0.008292767D, next.velocity().x(), 1.0E-6D);
        assertEquals(-0.0784D, next.velocity().y(), 1.0E-7D);
        assertEquals(-0.009368096D, next.velocity().z(), 1.0E-6D);
    }

    @Test
    public void theNearerCellIsStoodOnWhereBothReachTheFeet() {
        BlockCollisionWorld world = world();

        // Over the slime's cell, though the head's plate is the nearer box.
        assertEquals(SLIME_SLOWDOWN, slowdown(world, -34.04454040527344D), 0.0F);
        assertEquals(SLIME_SLOWDOWN, slowdown(world, -34.18D), 0.0F);
        // Over the head's cell.
        assertEquals(BedrockBlockSurfaceMovement.NO_STANDING_SLOWDOWN, slowdown(world, -33.95D), 0.0F);
    }

    @Test
    public void aFenceIsStoodOnByAllItSpansAndReachesAboveTheFloorBesideIt() {
        // The box hangs over the open corner between the fence's two arms: it touches neither, but the fence's
        // span holds it and is taller than the slime the actor rests on.
        BlockPosition corner = new BlockPosition(277, 82, -35);
        var registry = DataTables.defaults().registry();
        int fence = registry.with(
                registry.with(registry.block("minecraft:oak_fence").defaultState(), "north", "true"), "west", "true");
        PlacedBlockCollision placed = builder().block(corner, fence, BedrockCollisionShapeQuery.NONE);
        List<PlacedBlockCollision> blocks = new ArrayList<>(world().blocks());
        blocks.add(placed);

        assertEquals(2, placed.collisionBoxes().size());
        assertEquals(
                List.of(277.0D, 82.0D, -35.0D, 277.625D, 83.5D, -34.375D),
                placed.bedrockState().get(BedrockBlockMetadata.STANDING_SHAPE));
        assertEquals(
                BedrockBlockSurfaceMovement.NO_STANDING_SLOWDOWN,
                slowdown(new BlockCollisionWorld(blocks), 276.9D, -35.0D),
                0.0F);
        assertEquals(SLIME_SLOWDOWN, slowdown(world(), 276.9D, -35.0D), 0.0F);
    }

    @Test
    public void aBrewingStandIsStoodOnByItsOwnBox() {
        int stand = DataTables.defaults()
                .registry()
                .block("minecraft:brewing_stand")
                .defaultState();

        PlacedBlockCollision placed =
                builder().block(new BlockPosition(3, 64, -7), stand, BedrockCollisionShapeQuery.NONE);

        assertEquals(
                List.of(3.125D, 64.0D, -6.875D, 3.875D, 64.875D, -6.125D),
                placed.bedrockState().get(BedrockBlockMetadata.STANDING_SHAPE));
    }

    private static BedrockCollisionWorldBuilder builder() {
        return new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
    }

    private static float slowdown(BlockCollisionWorld world, double z) {
        return slowdown(world, 276.327392578125D, z);
    }

    private static float slowdown(BlockCollisionWorld world, double x, double z) {
        return BedrockBlockSurfaceMovement.standingSlowdownScale(
                RESTING, new Vec3d(x, 83.0D, z), false, false, true, world, PlayerDimensionsState.DEFAULT);
    }

    private static BedrockMovementContext context() {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world()),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(true, true, false, true, .05, false, false, false, false, .35, 0),
                PlayerDimensionsState.DEFAULT);
    }

    private static BlockCollisionWorld world() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 275; x <= 276; x++) {
            for (int z = -37; z <= -35; z++) {
                BlockPosition slime = new BlockPosition(x, 82, z);
                blocks.add(PlacedBlockCollision.manual(
                        slime, "minecraft:slime_block", "minecraft:slime", List.of(cube(slime))));
            }
        }
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(276, 82, -34),
                "minecraft:piston_head",
                "minecraft:sticky_piston_arm_collision",
                List.of(
                        new WorldCollisionBox(276.3125D, 82.3125D, -33.25D, 276.6875D, 82.6875D, -32.75D),
                        new WorldCollisionBox(276.375D, 82.375D, -33.75D, 276.625D, 82.625D, -33.25D),
                        new WorldCollisionBox(276.0D, 82.0D, -34.0D, 277.0D, 83.0D, -33.75D))));
        BlockPosition piston = new BlockPosition(276, 82, -33);
        blocks.add(PlacedBlockCollision.manual(
                piston, "minecraft:sticky_piston", "minecraft:sticky_piston", List.of(cube(piston))));
        return new BlockCollisionWorld(blocks);
    }

    private static WorldCollisionBox cube(BlockPosition position) {
        return new WorldCollisionBox(
                position.x(),
                position.y(),
                position.z(),
                position.x() + 1.0D,
                position.y() + 1.0D,
                position.z() + 1.0D);
    }
}
