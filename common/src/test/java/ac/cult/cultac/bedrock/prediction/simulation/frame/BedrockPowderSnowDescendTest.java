package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockPowderSnowDescendTest {
    @Test
    public void powderSnowBelowProducesPointFifteenDescendAction() {
        BedrockInputFrame frame = new BedrockInputFrame(
            1L, 0.0F, 0.0F, false, true, false, Set.of("WANT_DOWN", "START_SNEAKING")
        );
        BedrockClimbState climb = BedrockClimbMovement.resolveActions(
            state(),
            frame,
            frame.intent(),
            BedrockClimbMovement.resolveSurface(
                new BedrockClimbableContact(
                    false, false, true, false),
                false,
                false
            ),
            BedrockTravelInput.ScaffoldingVerticalBranch.DESCEND
        );

        BedrockScaffoldingAction action = BedrockScaffoldingAction.resolve(climb);
        assertTrue(climb.scaffolding().descendingThroughBlock());
        assertTrue(action.active());
        assertEquals(BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY, action.moveY(), 0.0D);
    }

    @Test
    public void descentStopsWithoutAnExitCarryWhenBelowFlagClears() {
        BedrockInputFrame frame = new BedrockInputFrame(
            1L, 0.0F, 0.0F, true, false, false, Set.of("START_JUMPING")
        );
        BedrockClimbState climb = BedrockClimbMovement.resolveActions(
            state(),
            frame,
            frame.intent(),
            BedrockClimbMovement.resolveSurface(
                new BedrockClimbableContact(
                    false, false, false, true),
                false,
                false
            ),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE
        );

        assertFalse(climb.scaffolding().descendingThroughBlock());
        assertFalse(BedrockScaffoldingAction.resolve(climb).active());
    }

    private static BedrockMovementState state() {
        return BedrockMovementState.fromPhysicalFeet(
            new Vec3d(0.5D, 4.0D, 0.5D),
            Vec3d.ZERO,
            BedrockInputFrame.idle(0L),
            BedrockCollisionFlags.ON_GROUND
        );
    }
}
