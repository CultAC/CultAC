package ac.grim.grimac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollision;
import java.util.List;
import org.junit.Test;

public final class BedrockAutoStepResolverTest {
    @Test
    public void rawCollisionBoxesCanProduceNetZeroHeightStep() {
        Vec3d feet = new Vec3d(0.5D, 1.0D, 0.5D);
        Vec3d requestedMove = new Vec3d(0.8D, 0.0D, 0.0D);
        BedrockMovementState current = BedrockMovementState.fromPhysicalFeet(
            feet,
            Vec3d.ZERO,
            BedrockInputFrame.idle(0L),
            BedrockCollisionFlags.ON_GROUND
        );
        List<BlockCollision> rawShapes = List.of(
            BlockCollision.raw(new WorldCollisionBox(-10.0D, 0.0D, -10.0D, 10.0D, 1.0D, 10.0D)),
            BlockCollision.raw(new WorldCollisionBox(0.8D, 1.0D, 0.2D, 1.0D, 1.25D, 0.8D))
        );

        BedrockEntityMove.CollisionMove move = BedrockEntityMove.collide(
            current,
            requestedMove,
            rawShapes,
            PlayerDimensionsState.DEFAULT,
            true,
            0.5625D
        );

        assertTrue(move.stepRetryAllowed());
        assertTrue(move.steppedUp());
        assertEquals(requestedMove.x(), move.selectedMove().appliedDelta().x(), 1.0E-6D);
        assertEquals(0.0D, move.selectedMove().appliedDelta().y(), 1.0E-6D);
        assertFalse(move.selectedMove().yCollision());
    }
}
