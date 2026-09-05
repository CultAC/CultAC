package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbableState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockScaffoldingState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbSurface;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BedrockDefaultMoveClimbVerticalTest {
    @Test
    public void powderSnowIntersectionAloneDoesNotCapFallingStateVector() {
        Vec3d result = BedrockDefaultMoveClimbVertical.apply(
            new Vec3d(0.1D, -0.78113D, -0.2D),
            noClimb(),
            false
        );

        assertEquals(-0.78113D, result.y(), 0.0D);
    }

    @Test
    public void activeClimbCapsFallingStateVector() {
        Vec3d result = BedrockDefaultMoveClimbVertical.apply(
            new Vec3d(0.1D, -0.78113D, -0.2D),
            climbing(),
            false
        );

        assertEquals(BedrockClimbMovement.CLIMBABLE_MAX_FALL_SPEED, result.y(), 0.0D);
    }

    @Test
    public void capturedSneakDescentStillUsesClimbableFallCap() {
        Vec3d result = BedrockDefaultMoveClimbVertical.apply(
            new Vec3d(0.1D, -0.2744D, -0.2D),
            climbing(),
            false
        );

        assertEquals(BedrockClimbMovement.CLIMBABLE_MAX_FALL_SPEED, result.y(), 0.0D);
    }

    @Test
    public void standablePowderSnowAtFlooredFeetCapsFallingStateVector() {
        Vec3d result = BedrockDefaultMoveClimbVertical.apply(
            new Vec3d(0.1D, -0.78113D, -0.2D),
            noClimb(),
            true
        );

        assertEquals(BedrockClimbMovement.CLIMBABLE_MAX_FALL_SPEED, result.y(), 0.0D);
    }

    @Test
    public void embeddedPowderSlowdownKeepsUncappedFallingVelocity() {
        double carried = -0.22540149092674255D;
        Vec3d velocity = BedrockDefaultMoveClimbVertical.apply(
            new Vec3d(0.0D, carried, 0.0D),
            noClimb(),
            false
        );
        Vec3d move = BlockMovementSlowdownState.POWDER_SNOW.applyToMoveRequest(velocity);

        assertEquals((float) carried * 1.5F, move.y(), 0.0D);
    }

    @Test
    public void descendActionKeepsRawMoveAndExposesAirDraggedCollisionVelocity() {
        BedrockTravelMoveVector.Step step = BedrockTravelMoveVector.resolve(
            new Vec3d(0.0D, BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY, 0.0D),
            descendingThroughBlock()
        );

        double expected = BedrockAerialMovement.airDraggedVelocityWithoutGravity(
            BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY
        );
        assertEquals(BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY, step.move().y(), 0.0D);
        assertEquals(expected, step.collisionInputVelocity().y(), 0.0D);
    }

    private static BedrockClimbState noClimb() {
        return new BedrockClimbState(
            new BedrockClimbSurface(
                BedrockClimbSurface.Type.NONE,
                false,
                false
            ),
            BedrockScaffoldingState.NONE,
            BedrockClimbableState.NONE
        );
    }

    private static BedrockClimbState descendingThroughBlock() {
        return new BedrockClimbState(
            new BedrockClimbSurface(
                BedrockClimbSurface.Type.SCAFFOLDING,
                true,
                false
            ),
            BedrockScaffoldingState.DESCENDING,
            BedrockClimbableState.NONE
        );
    }

    private static BedrockClimbState climbing() {
        return new BedrockClimbState(
            new BedrockClimbSurface(
                BedrockClimbSurface.Type.CLIMBABLE,
                false,
                false
            ),
            BedrockScaffoldingState.NONE,
            BedrockClimbableState.NONE
        );
    }
}
