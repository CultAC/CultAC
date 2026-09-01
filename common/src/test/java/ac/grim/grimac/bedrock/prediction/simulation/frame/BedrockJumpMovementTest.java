package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class BedrockJumpMovementTest {
    private static final double REBOUND_Y = 0.4866231381893158D;
    private static final double JUMP_Y = 0.42D;

    @Test
    public void groundJumpPreservesLargerReboundVelocity() {
        Vec3d current = new Vec3d(0.1D, REBOUND_Y, -0.2D);

        Vec3d launched = BedrockJumpMovement.groundLaunchVelocity(
                current, BedrockInputFrame.idle(0L), JUMP_Y, false);

        assertEquals(current.x(), launched.x(), 0.0D);
        assertEquals(REBOUND_Y, launched.y(), 0.0D);
        assertEquals(current.z(), launched.z(), 0.0D);
    }

    @Test
    public void sprintGroundJumpPreservesLargerReboundVelocity() {
        Vec3d current = new Vec3d(0.1D, REBOUND_Y, -0.2D);
        BedrockInputFrame frame = new BedrockInputFrame(
                0L, -89.244316F, 0.0F, true, false, true);

        Vec3d launched = BedrockJumpMovement.groundLaunchVelocity(current, frame, JUMP_Y, true);

        assertEquals(REBOUND_Y, launched.y(), 0.0D);
        assertNotEquals(current.x(), launched.x(), 0.0D);
        assertNotEquals(current.z(), launched.z(), 0.0D);
    }

    @Test
    public void groundJumpRaisesLowerVerticalVelocity() {
        Vec3d launched = BedrockJumpMovement.groundLaunchVelocity(
                new Vec3d(0.0D, 0.2D, 0.0D), BedrockInputFrame.idle(0L), JUMP_Y, false);

        assertEquals(JUMP_Y, launched.y(), 0.0D);
    }
}
