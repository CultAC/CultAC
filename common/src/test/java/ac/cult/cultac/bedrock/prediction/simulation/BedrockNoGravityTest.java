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

public final class BedrockNoGravityTest {
    private static final Vec3d ON_FLOOR = new Vec3d(0.5D, 1.0D, 0.5D);

    @Test
    public void aJumpWithoutGravityKeepsItsVelocity() {
        // Without gravity the client rises 0.42 on the jump tick and 0.42 on each tick after it.
        BedrockInputFrame jump =
                new BedrockInputFrame(2L, 0.0F, 0.0F, true, false, false, Set.of("JUMPING", "START_JUMPING"));
        BedrockMovementState grounded = BedrockMovementState.fromPhysicalFeet(
                ON_FLOOR, Vec3d.ZERO, BedrockInputFrame.idle(1L), BedrockCollisionFlags.ON_GROUND);

        BedrockMovementResult jumped = move(grounded, jump, false);

        assertEquals(ON_FLOOR.y() + 0.42D, jumped.predictedPosition().y(), 1.0E-6D);
        assertEquals(0.42D, jumped.predictedVelocity().y(), 1.0E-6D);

        BedrockMovementState rising = BedrockMovementState.fromPhysicalFeet(
                jumped.predictedPosition(), jumped.predictedVelocity(), jump, BedrockCollisionFlags.AIR);
        BedrockMovementResult next = move(rising, BedrockInputFrame.idle(3L), false);

        assertEquals(jumped.predictedPosition().y() + 0.42D, next.predictedPosition().y(), 1.0E-6D);
        assertEquals(0.42D, next.predictedVelocity().y(), 1.0E-6D);
    }

    @Test
    public void aFallWithoutGravityKeepsItsVelocity() {
        // A corrected fall of -0.075447 per tick stays at -0.075447 while the gravity flag is off.
        BedrockMovementState falling = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 20.0D, 0.5D),
                new Vec3d(0.0D, -0.075447D, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);

        BedrockMovementResult result = move(falling, BedrockInputFrame.idle(2L), false);

        assertEquals(20.0D - 0.075447D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(-0.075447D, result.predictedVelocity().y(), 1.0E-7D);
        assertEquals(
                (-0.075447D - 0.08D) * 0.98D,
                move(falling, BedrockInputFrame.idle(2L), true).predictedVelocity().y(),
                1.0E-6D);
    }

    private static BedrockMovementResult move(
            BedrockMovementState state, BedrockInputFrame frame, boolean hasGravity) {
        PlacedBlockCollision floor = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)));
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(floor))),
                new EquipmentState(0, 0, 0, false, false),
                EntityContactState.NONE,
                new MovementModifierState(
                        false, false, false, false, 0.05D, false, false, false, false, 0.35D, 0L, hasGravity, false),
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
