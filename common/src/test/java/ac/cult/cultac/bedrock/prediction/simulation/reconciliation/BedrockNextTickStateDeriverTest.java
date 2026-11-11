package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockLiquidClimbOutMovement;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import org.junit.Test;

public final class BedrockNextTickStateDeriverTest {
    private static final Vec3d POSITION = new Vec3d(0.5D, 64.0D, 0.5D);
    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    @Test
    public void forwardCarryKeepsOnlyTheSimulatedFluidHop() {
        Vec3d hop = new Vec3d(0.08, BedrockLiquidClimbOutMovement.CLIMB_OUT_VELOCITY_Y, 0.02);
        var state = state(POSITION, hop, BedrockCollisionFlags.AIR.withLiquidClimbOut(true), Medium.WATER);
        var result = movementResult(state, state, context(Medium.WATER, BlockCollisionWorld.EMPTY));
        assertEquals(List.of(state), BedrockNextTickStateDeriver.fromSimulated(result, state));

        Vec3d swim = new Vec3d(0.08, -0.03, 0.02);
        var noHop = state(POSITION, swim, BedrockCollisionFlags.AIR.withHorizontalBlockContact(true), Medium.WATER);
        result = movementResult(noHop, noHop, context(Medium.WATER, BlockCollisionWorld.EMPTY));
        assertEquals(List.of(noHop), BedrockNextTickStateDeriver.fromSimulated(result, noHop));
    }

    @Test
    public void forwardCarryClimbsByTheContactWhereTheMoveEnds() {
        var world = new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(1, 64, 0), "minecraft:ladder", "minecraft:ladder", List.of())));
        var previous = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        var next = state(new Vec3d(1.5, 64, 0.5), new Vec3d(0.08, -0.0784, 0), BedrockCollisionFlags.AIR, Medium.AIR);
        var states = BedrockNextTickStateDeriver.fromSimulated(
                movementResult(previous, next, context(Medium.AIR, world)), next);
        assertEquals(1, states.size());
        assertTrue(states.getFirst().climbableContact().climbing());
        assertEquals(next.velocity(), states.getFirst().velocity());

        var away = state(new Vec3d(3.5, 64, 0.5), new Vec3d(0.08, -0.0784, 0), BedrockCollisionFlags.AIR, Medium.AIR);
        states = BedrockNextTickStateDeriver.fromSimulated(
                movementResult(next.withClimbableContact(states.getFirst().climbableContact()), away, context(
                        Medium.AIR, world)),
                away);
        assertEquals(1, states.size());
        assertFalse(states.getFirst().climbableContact().climbing());
    }

    private static BedrockMovementState state(
            Vec3d position, Vec3d velocity, BedrockCollisionFlags flags, Medium movementBranch) {
        return BedrockMovementState.fromPhysicalFeet(
                position, velocity, BedrockInputFrame.idle(0L), flags, movementBranch);
    }

    private static BedrockMovementResult movementResult(
            BedrockMovementState previous, BedrockMovementState predicted, BedrockMovementContext context) {
        return new BedrockMovementResult(
                previous,
                context,
                context,
                predicted,
                predicted.physicalFeetPosition(),
                previous.velocity(),
                false,
                false,
                false,
                false,
                BlockMovementSlowdownState.NONE,
                HoneySlideState.NONE,
                false,
                predicted.waterTravelFlag(),
                0.0D,
                1.0D,
                false,
                false,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                true,
                false);
    }

    private static BedrockMovementContext context(Medium medium, BlockCollisionWorld blockWorld) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(medium, FluidState.NONE, blockWorld),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
