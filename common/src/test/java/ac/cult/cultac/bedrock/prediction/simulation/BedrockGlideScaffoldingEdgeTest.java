package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** A sneaking glide into a scaffolding column takes the -0.15 descend velocity and still glides from it. */
public final class BedrockGlideScaffoldingEdgeTest {
    private static final PlayerDimensionsState GLIDE_SIZE = new PlayerDimensionsState(0.6F, 0.6F);

    // Flag 73, auth tick 10466: only the box edge reaches the column.
    @Test
    public void aSneakingGlideIntoAScaffoldingColumnKeepsGliding() {
        Vec3d feet = new Vec3d(342.5628356933594D, 87.6344223022461D, -61.783695220947266D);
        Vec3d velocity = new Vec3d(-0.0018115198472514749D, -0.1641506403684616D, -0.8646316528320312D);
        BedrockMovementContext context = column(84, 88);
        Set<String> inputData = Set.of(
                "ACTOR_SNEAKING",
                "SNEAK_CURRENT_RAW",
                "GLIDING",
                "WANT_DOWN",
                "SNEAK_DOWN",
                "ACTOR_POSE_SNAPSHOT",
                "SNEAKING");
        BedrockMovementState previous = gliding(
                context,
                feet,
                velocity,
                new BedrockInputFrame(10463L, -179.97182F, 8.961807F, false, true, false, inputData));
        assertTrue(previous.climbableContact().scaffolding());

        BedrockMovementResult result = tick(
                previous,
                new BedrockInputFrame(10464L, -179.97182F, 8.961807F, false, true, false, inputData),
                context);

        assertTrue(result.selectedGlidingTravel());
        assertTrue(result.predictedState().gliding());
        assertMoved(result, -0.001556396484375D, -0.15163421630859375D, -0.8708915710449219D);
    }

    // Live repro, auth ticks 3746 and 3747: the feet centre is inside the column, at (342, 87, -63).
    @Test
    public void aSneakingGlideWithItsFeetInsideScaffoldingKeepsGliding() {
        Vec3d feet = new Vec3d(342.55999755859375D, 87.12361907958984D, -62.69169998168945D);
        Vec3d velocity = new Vec3d(0.0D, -0.2505748D, -1.6692183D);
        BedrockMovementContext context = column(82, 94);
        Set<String> inputData = Set.of("SNEAKING", "SNEAK_DOWN", "WANT_DOWN", "SNEAK_CURRENT_RAW");
        BedrockMovementState previous = gliding(
                context, feet, velocity, new BedrockInputFrame(3744L, -180.0F, 8.99791F, false, true, false, inputData));

        BedrockMovementResult first =
                tick(previous, new BedrockInputFrame(3745L, -180.0F, 8.99791F, false, true, false, inputData), context);
        assertTrue(first.selectedGlidingTravel());
        assertTrue(first.predictedState().gliding());
        assertMoved(first, 0.0D, -0.15164544D, -1.6674302D);

        BedrockMovementResult second = tick(
                BedrockForwardTick.finish(first, first.predictedState()),
                new BedrockInputFrame(3746L, -180.0F, 8.99791F, false, true, false, inputData),
                context);
        assertTrue(second.selectedGlidingTravel());
        assertTrue(second.predictedState().gliding());
        assertMoved(second, 0.0D, -0.15310065D, -1.665803D);
    }

    private static BedrockMovementState gliding(
            BedrockMovementContext context, Vec3d feet, Vec3d velocity, BedrockInputFrame frame) {
        return BedrockMovementState.fromPhysicalFeet(feet, velocity, frame, BedrockCollisionFlags.AIR)
                .withGliding(true)
                .withPlayerDimensions(BedrockBoundingBoxMode.HORIZONTAL, GLIDE_SIZE, false)
                .withClimbableContact(BedrockClimbableContact.fromBlockWorld(
                        context.worldState().blockCollisionWorld(), feet, 0.6D, 0.6D));
    }

    private static BedrockMovementResult tick(
            BedrockMovementState previous, BedrockInputFrame frame, BedrockMovementContext context) {
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        previous,
                        frame,
                        frame.intent(),
                        BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), previous, frame),
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        Vec3d.ZERO))
                .getFirst()
                .movementResult();
    }

    private static void assertMoved(BedrockMovementResult result, double x, double y, double z) {
        Vec3d moved = result.predictedPosition().subtract(result.previousState().physicalFeetPosition());
        assertEquals(x, moved.x(), 1.0E-5D);
        assertEquals(y, moved.y(), 1.0E-5D);
        assertEquals(z, moved.z(), 1.0E-5D);
    }

    /** A column of scaffolding at x 342, z -63. */
    private static BedrockMovementContext column(int bottomY, int topY) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int y = bottomY; y <= topY; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(342, y, -63),
                    "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                    "minecraft:scaffolding",
                    List.of(),
                    Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING)));
        }
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(true, true, false, true, .05, false, false, false, false, .35, 0),
                GLIDE_SIZE);
    }
}
