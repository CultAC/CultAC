package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

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
import java.util.Set;
import org.junit.Test;

public final class BedrockMoveClampTest {
    private static final Vec3d ON_FLOOR = new Vec3d(0.5D, 1.0D, 0.5D);

    @Test
    public void aMoveLongerThanSixteenBlocksIsCutToSixteen() {
        // Jumping with jump boost amplifier 250 the client leaves with 25.52 a tick, moves 16.0 and reports
        // (25.52 - 0.08) * 0.98 = 24.9312: the move is cut, the velocity is not.
        BedrockInputFrame jump =
                new BedrockInputFrame(2L, 0.0F, 0.0F, true, false, false, Set.of("JUMPING", "START_JUMPING"));
        BedrockMovementState grounded = BedrockMovementState.fromPhysicalFeet(
                ON_FLOOR, Vec3d.ZERO, BedrockInputFrame.idle(1L), BedrockCollisionFlags.ON_GROUND);

        BedrockMovementResult result = move(grounded, jump, BedrockEffectState.NONE.withJumpBoostLevel(251));

        assertEquals(ON_FLOOR.y() + 16.0D, result.predictedPosition().y(), 1.0E-5D);
        assertEquals(24.9312D, result.predictedVelocity().y(), 1.0E-4D);
    }

    @Test
    public void aSixteenBlockMoveKeepsItsDirection() {
        BedrockMovementState flung = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 40.0D, 0.5D),
                new Vec3d(20.0D, 0.0D, 15.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);

        BedrockMovementResult result = move(flung, BedrockInputFrame.idle(2L), BedrockEffectState.NONE);

        assertEquals(0.5D + 12.8D, result.predictedPosition().x(), 1.0E-4D);
        assertEquals(0.5D + 9.6D, result.predictedPosition().z(), 1.0E-4D);
    }

    private static BedrockMovementResult move(
            BedrockMovementState state, BedrockInputFrame frame, BedrockEffectState effects) {
        PlacedBlockCollision floor = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)));
        BedrockMovementContext context = new BedrockMovementContext(
                effects,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(floor))),
                new EquipmentState(0, 0, 0, false, false),
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        return BedrockSimulation.move(
                state,
                frame,
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }
}
