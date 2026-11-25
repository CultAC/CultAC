package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import org.junit.Test;

/**
 * A spectator moves without collision. A spectator flying down 0.43 a tick from 0.2 above stone went on into it,
 * where a player with collision stops on its top face.
 */
public final class BedrockSpectatorNoClipTest {
    private static final BlockCollisionWorld STONE = new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
            new BlockPosition(0, 0, 0),
            "minecraft:stone",
            "minecraft:stone",
            List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)))));
    private static final BedrockMovementState DIVING = BedrockMovementState.fromPhysicalFeet(
            new Vec3d(0.5D, 1.2D, 0.5D),
            new Vec3d(0.0D, -0.43D, 0.0D),
            BedrockInputFrame.idle(1L),
            BedrockCollisionFlags.AIR);

    @Test
    public void aSpectatorMovesIntoBlocksAsThroughAir() {
        BedrockMovementResult spectator = move(STONE, true);
        BedrockMovementResult inAir = move(new BlockCollisionWorld(List.of()), false);

        assertEquals(inAir.predictedPosition(), spectator.predictedPosition());
        assertEquals(inAir.predictedVelocity(), spectator.predictedVelocity());
        assertEquals(1.2D - 0.43D, spectator.predictedPosition().y(), 1.0E-6D);
        assertFalse(spectator.predictedState().collisionFlags().onGround());
    }

    @Test
    public void aPlayerWithCollisionStopsOnTheStone() {
        assertEquals(1.0D, move(STONE, false).predictedPosition().y(), 1.0E-6D);
    }

    private static BedrockMovementResult move(BlockCollisionWorld world, boolean noClip) {
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                new EquipmentState(0, 0, 0, false, false),
                EntityContactState.NONE,
                new MovementModifierState(
                        false, true, true, false, 0.05D, false, false, false, false, 0.35D, 0L, true, noClip),
                PlayerDimensionsState.DEFAULT);
        return BedrockSimulation.move(
                DIVING,
                BedrockInputFrame.idle(2L),
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }
}
