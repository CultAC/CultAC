package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class BedrockMovementStateImmobileTest {
    @Test
    public void metadataBoundaryOnlyZerosMotion() {
        BedrockMovementState current = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(10.0D, 64.0D, -3.0D),
                new Vec3d(0.2D, -0.1D, 0.05D),
                BedrockInputFrame.idle(41L),
                BedrockCollisionFlags.ON_GROUND);

        BedrockMovementState next = current.afterImmobileBoundary();

        assertEquals(Vec3d.ZERO, next.velocity());
        assertEquals(Vec3d.ZERO, next.lastPhysicalDisplacement());
        assertSame(current.inputFrame(), next.inputFrame());
        assertSame(current.actor(), next.actor());
        assertSame(current.memory(), next.memory());
        assertSame(current.collisionFlags(), next.collisionFlags());
    }
}
