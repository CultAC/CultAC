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

public final class BedrockGlideScaffoldingEdgeTest {
    // Flag 73, auth tick 10466: a sneaking glide whose box edge reaches a scaffolding column.
    // The client takes the -0.15 descend velocity and still glides from it.
    @Test
    public void aSneakingGlideIntoAScaffoldingColumnKeepsGliding() {
        PlayerDimensionsState glideSize = new PlayerDimensionsState(0.6F, 0.6F);
        Vec3d feet = new Vec3d(342.5628356933594D, 87.6344223022461D, -61.783695220947266D);
        Vec3d velocity = new Vec3d(-0.0018115198472514749D, -0.1641506403684616D, -0.8646316528320312D);
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int y = 84; y <= 88; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(342, y, -63),
                    "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                    "minecraft:scaffolding",
                    List.of(),
                    Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING)));
        }
        BlockCollisionWorld blockWorld = new BlockCollisionWorld(blocks);
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, blockWorld),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(true, true, false, true, .05, false, false, false, false, .35, 0),
                glideSize);
        Set<String> inputData = Set.of(
                "ACTOR_SNEAKING",
                "SNEAK_CURRENT_RAW",
                "GLIDING",
                "WANT_DOWN",
                "SNEAK_DOWN",
                "ACTOR_POSE_SNAPSHOT",
                "SNEAKING");
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                        feet,
                        velocity,
                        new BedrockInputFrame(10463L, -179.97182F, 8.961807F, false, true, false, inputData),
                        BedrockCollisionFlags.AIR)
                .withGliding(true)
                .withPlayerDimensions(BedrockBoundingBoxMode.HORIZONTAL, glideSize, false)
                .withClimbableContact(BedrockClimbableContact.fromBlockWorld(blockWorld, feet, 0.6D, 0.6D));
        BedrockInputFrame frame =
                new BedrockInputFrame(10464L, -179.97182F, 8.961807F, false, true, false, inputData);
        assertTrue(previous.climbableContact().scaffolding());

        BedrockMovementResult result = BedrockSimulation.candidates(new BedrockSimulation.Input(
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

        assertTrue(result.selectedGlidingTravel());
        assertTrue(result.predictedState().gliding());
        Vec3d moved = result.predictedPosition().subtract(feet);
        assertEquals(-0.001556396484375D, moved.x(), 1.0E-6D);
        assertEquals(-0.15163421630859375D, moved.y(), 1.0E-6D);
        assertEquals(-0.8708915710449219D, moved.z(), 1.0E-6D);
    }
}
