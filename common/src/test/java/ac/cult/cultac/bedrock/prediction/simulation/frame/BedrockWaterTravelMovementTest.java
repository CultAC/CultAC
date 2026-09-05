package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class BedrockWaterTravelMovementTest {
    @Test
    public void mobJumpWaterSwimUpAddsImpulseToStateVector() {
        Vec3d velocity = new BedrockMobJump(BedrockMobJump.Branch.WATER_NON_SWIMMER_SWIM_UP)
            .applyVelocityMutation(new Vec3d(0.0D, -0.12D, 0.0D));

        assertEquals(-0.08000000089406966D, velocity.y(), 1.0E-12D);
    }

    @Test
    public void mobJumpSwimTransitionZeroesStateVectorVelocity() {
        Vec3d velocity = new BedrockMobJump(BedrockMobJump.Branch.WATER_SWIM_TRANSITION)
            .applyVelocityMutation(new Vec3d(0.0D, -0.12D, 0.0D));

        assertEquals(0.0D, velocity.y(), 1.0E-12D);
    }

    @Test
    public void mobJumpSurfaceSwimmingZeroesUpwardStateVectorVelocity() {
        Vec3d velocity = new BedrockMobJump(BedrockMobJump.Branch.WATER_AUTO_SURFACE_SWIM)
            .applyVelocityMutation(new Vec3d(0.0D, 0.142822265625D, 0.0D));

        assertEquals(0.0D, velocity.y(), 0.0D);
    }

    @Test
    public void waterNextTickVelocityAppliesFrictionAndWaterGravity() {
        double velocityY = BedrockLiquidVerticalMovement.waterNextTickVelocityY(
            -0.08D,
            true
        );

        assertEquals(-0.069D, velocityY, 1.0E-12D);
    }

    @Test
    public void waterNextTickVelocityCanSkipWaterGravityWhenSwimmingActorGateIsActive() {
        double velocityY = BedrockLiquidVerticalMovement.waterNextTickVelocityY(
            0.0D,
            false
        );

        assertEquals(0.0D, velocityY, 1.0E-12D);
    }
}
