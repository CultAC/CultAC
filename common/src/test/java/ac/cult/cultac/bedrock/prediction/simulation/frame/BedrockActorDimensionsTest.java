package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BedrockActorDimensionsTest {
    @Test
    public void localSneakTransitionKeepsClientMovementHeight() {
        BedrockMovementState standing = state(BedrockInputFrame.idle(0L), 1.8D);
        BedrockInputFrame startSneaking = new BedrockInputFrame(1L, 0.0F, 0.0F, false, true, false);

        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
                standing, PlayerDimensionsState.DEFAULT, startSneaking);

        assertEquals(1.8D, dimensions.height(), 0.0D);
    }

    @Test
    public void sustainedSneakUsesCurrentClientPoseDimensions() {
        BedrockInputFrame sneaking = new BedrockInputFrame(1L, 0.0F, 0.0F, false, true, false);
        BedrockMovementState localSneaking = state(sneaking, 1.8D);

        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
                localSneaking, new PlayerDimensionsState(0.6D, 1.49D),
                new BedrockInputFrame(2L, 0.0F, 0.0F, false, true, false));

        assertEquals(1.49D, dimensions.height(), 0.0D);
    }

    @Test
    public void inputReleaseDoesNotOverrideAcknowledgedForcedCrouchHeight() {
        BedrockInputFrame sneaking = new BedrockInputFrame(1L, 0.0F, 0.0F, false, true, false);
        BedrockMovementState geyserSized = state(sneaking, 1.5D, true);

        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
                geyserSized, PlayerDimensionsState.DEFAULT, BedrockInputFrame.idle(2L));

        assertEquals(1.5D, dimensions.height(), 0.0D);
    }

    @Test
    public void collisionForcedCrouchRemainsUntilMetadataChanges() {
        BedrockMovementState forcedCrouch = state(BedrockInputFrame.idle(1L), 1.5D, true);

        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
                forcedCrouch, PlayerDimensionsState.DEFAULT, BedrockInputFrame.idle(2L));

        assertEquals(1.5D, dimensions.height(), 0.0D);
    }

    @Test
    public void swimmingPosePreservesAcknowledgedHeight() {
        BedrockInputFrame sneaking = new BedrockInputFrame(1L, 0.0F, 0.0F, false, true, false);
        BedrockMovementState geyserSized = state(sneaking, 1.5D, true);
        BedrockInputFrame swimming = new BedrockInputFrame(
                2L, 0.0F, 0.0F, false, false, false, Set.of(BedrockPoseInputData.SWIMMING));

        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
                geyserSized, PlayerDimensionsState.DEFAULT, swimming);

        assertEquals(1.5D, dimensions.height(), 0.0D);
    }

    @Test
    public void sustainedSwimmingPreservesAcknowledgedStandingHeight() {
        BedrockInputFrame swimming = new BedrockInputFrame(
                1L, 0.0F, 0.0F, false, false, false, Set.of(BedrockPoseInputData.SWIMMING));
        BedrockMovementState standingSize = state(swimming, 1.8D, true);
        BedrockInputFrame nextSwimming = new BedrockInputFrame(
                2L, 0.0F, 0.0F, false, false, false, Set.of(BedrockPoseInputData.SWIMMING));

        BedrockActorDimensions.Resolved resolved = BedrockActorDimensions.resolve(
                standingSize, PlayerDimensionsState.DEFAULT, nextSwimming);

        assertEquals(BedrockBoundingBoxMode.HORIZONTAL, resolved.mode());
        assertEquals(1.8D, resolved.dimensions().height(), 0.0D);
    }

    @Test
    public void glideStartPreservesAcknowledgedStandingHeight() {
        BedrockMovementState standing = state(BedrockInputFrame.idle(1L), 1.8D, true);
        BedrockInputFrame gliding = new BedrockInputFrame(
                2L, 0.0F, 0.0F, false, false, false, Set.of(BedrockPoseInputData.START_GLIDING_ACTION));

        BedrockActorDimensions.Resolved resolved = BedrockActorDimensions.resolve(
                standing, PlayerDimensionsState.DEFAULT, gliding);

        assertEquals(BedrockBoundingBoxMode.HORIZONTAL, resolved.mode());
        assertEquals(1.8D, resolved.dimensions().height(), 0.0D);
    }

    @Test
    public void leavingHorizontalPoseKeepsSizeUntilMetadataChanges() {
        BedrockInputFrame swimming = new BedrockInputFrame(
                1L, 0.0F, 0.0F, false, false, false, Set.of(BedrockPoseInputData.SWIMMING));
        BedrockMovementState horizontal = state(swimming, 0.6D, true);

        BedrockActorDimensions.Resolved resolved = BedrockActorDimensions.resolve(
                horizontal, PlayerDimensionsState.DEFAULT, BedrockInputFrame.idle(2L));

        assertEquals(BedrockBoundingBoxMode.DEFAULT, resolved.mode());
        assertEquals(0.6D, resolved.dimensions().height(), 0.0D);

        BedrockMovementState acknowledgedStanding = horizontal.withPlayerDimensions(
                BedrockBoundingBoxMode.DEFAULT, PlayerDimensionsState.DEFAULT, true);
        resolved = BedrockActorDimensions.resolve(
                acknowledgedStanding, PlayerDimensionsState.DEFAULT, BedrockInputFrame.idle(3L));
        assertEquals(1.8D, resolved.dimensions().height(), 0.0D);
    }

    private static BedrockMovementState state(BedrockInputFrame frame, double height) {
        return state(frame, height, false);
    }

    private static BedrockMovementState state(BedrockInputFrame frame, double height, boolean explicit) {
        return BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.0D, 64.0D, 0.0D),
                Vec3d.ZERO,
                frame,
                BedrockCollisionFlags.AIR)
                .withPlayerDimensions(new PlayerDimensionsState(0.6D, height), explicit);
    }
}
