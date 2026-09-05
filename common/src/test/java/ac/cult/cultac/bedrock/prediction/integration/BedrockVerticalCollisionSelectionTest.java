package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public final class BedrockVerticalCollisionSelectionTest {
    @Test
    public void teleportResetDoesNotRestoreGroundFromPersistedTravelBranch() {
        BedrockMovementState grounded = BedrockMovementState.fromPhysicalFeet(
                Vec3d.ZERO,
                Vec3d.ZERO,
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BedrockMovementState teleported = grounded.afterServerTeleport(
                new Vec3d(0.0D, 64.0D, 0.0D),
                Vec3d.ZERO);

        BedrockMovementState selected = BedrockVerticalCollisionSelection.select(
                teleported,
                teleported,
                false,
                0.0D);

        assertFalse(selected.collisionFlags().onGround());
        assertSame(Medium.AIR, selected.movementBranch());
    }
}
