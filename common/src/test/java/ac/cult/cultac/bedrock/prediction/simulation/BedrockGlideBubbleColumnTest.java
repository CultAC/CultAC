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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * A glide west along z=-62.5 whose box dips into the top block of a 2x2 downward bubble column (x 274-275, z -63..-62,
 * top at y=86). Live on 2026-11-11 the client took the column's -0.03 on the entry tick while still gliding.
 */
public final class BedrockGlideBubbleColumnTest {
    private static final Vec3d START = new Vec3d(277.11874D, 86.13841D, -62.5D);
    private static final Vec3d CARRY = new Vec3d(-1.6741D, -0.0939D, 0.0D);
    private static final BedrockInputFrame FRAME =
            new BedrockInputFrame(8660L, 90.0F, 3.0F, false, false, false, Set.of("GLIDING", "ACTOR_POSE_SNAPSHOT"));

    @Test
    public void aGlideIntoTheTopOfADownwardColumnTakesItsPush() {
        BedrockMovementResult open = move(List.of());
        BedrockMovementResult column = move(column());

        assertTrue(column.selectedGlidingTravel());
        assertEquals(open.predictedPosition(), column.predictedPosition());
        // The box ends inside the column's top block only: one layer, pushed down once.
        assertTrue(column.predictedState().collisionBox().minY() > 86.0D);
        assertTrue(column.predictedState().collisionBox().minX() < 276.0D);
        assertEquals(
                open.predictedVelocity().y() - 0.03D, column.predictedVelocity().y(), 1.0E-6D);
        assertEquals(open.predictedVelocity().x(), column.predictedVelocity().x(), 0.0D);
    }

    private static List<PlacedBlockCollision> column() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 274; x <= 275; x++) {
            for (int z = -63; z <= -62; z++) {
                for (int y = 82; y <= 86; y++) {
                    blocks.add(PlacedBlockCollision.manual(
                            new BlockPosition(x, y, z),
                            "minecraft:bubble_column[drag=true]",
                            "minecraft:bubble_column",
                            Map.of("drag_down", true),
                            List.of()));
                }
            }
        }
        return blocks;
    }

    private static BedrockMovementResult move(List<PlacedBlockCollision> blocks) {
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                        START, CARRY, BedrockInputFrame.idle(8659L), BedrockCollisionFlags.AIR, Medium.AIR)
                .withGliding(true)
                .withPlayerDimensions(BedrockBoundingBoxMode.HORIZONTAL, new PlayerDimensionsState(0.6F, 0.6F), true);
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                new EquipmentState(0, 0, 0, 1, false, true),
                EntityContactState.NONE,
                new MovementModifierState(
                        true, true, false, true, 0.05000000074505806D, false, false, false, false, 0.35D, 0L),
                PlayerDimensionsState.DEFAULT);
        return BedrockSimulation.move(
                state,
                FRAME,
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }
}
