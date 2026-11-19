package ac.cult.cultac.bedrock.prediction.simulation.frame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import org.junit.Test;

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
    public void heldJumpInLavaIsWithheldWhileTheSwimPoseBlends() {
        BedrockMobJump jump = BedrockMobJump.resolve(lavaJumpInput(false, true), BedrockMobJumpComponentState.DEFAULT);

        assertEquals(BedrockMobJump.Branch.SWIM_JUMP_WITHHELD, jump.branch());
        assertFalse(jump.lavaSwimUpApplied());
        assertFalse(jump.zeroedWaterVelocity());
        assertEquals(0.016D, jump.applyVelocityMutation(new Vec3d(0.0D, 0.016D, 0.0D)).y(), 0.0D);
    }

    @Test
    public void heldJumpOutsideWaterIsWithheldWhileSwimmingWithTheHeadOut() {
        BedrockMobJump jump = BedrockMobJump.resolve(lavaJumpInput(true, false), BedrockMobJumpComponentState.DEFAULT);

        assertEquals(BedrockMobJump.Branch.SWIM_JUMP_WITHHELD, jump.branch());
        assertEquals(-0.012D, jump.applyVelocityMutation(new Vec3d(0.0D, -0.012D, 0.0D)).y(), 0.0D);
    }

    @Test
    public void heldJumpInLavaAddsTheImpulseOnceTheSwimPoseIsSettled() {
        BedrockMobJump jump = BedrockMobJump.resolve(
                new BedrockMobJumpInput(true, false, false, false, false, false, true, false),
                BedrockMobJumpComponentState.DEFAULT);

        assertEquals(BedrockMobJump.Branch.LAVA_SWIM_UP, jump.branch());
        assertEquals(0.072D, jump.applyVelocityMutation(new Vec3d(0.0D, 0.032D, 0.0D)).y(), 1.0E-6D);
    }

    @Test
    public void withheldJumpInWaterStillStopsTheVerticalVelocity() {
        BedrockMobJump jump = BedrockMobJump.resolve(
                new BedrockMobJumpInput(true, false, true, false, false, true, false, false),
                BedrockMobJumpComponentState.DEFAULT);

        assertEquals(BedrockMobJump.Branch.WATER_SWIM_TRANSITION, jump.branch());
        assertTrue(jump.zeroedWaterVelocity());
    }

    /** Lava with a ladder at the feet and a ground to launch from: every later branch is available. */
    private static BedrockMobJumpInput lavaJumpInput(boolean autoSurfaceSwim, boolean swimTransition) {
        return new BedrockMobJumpInput(true, autoSurfaceSwim, swimTransition, false, true, false, true, true);
    }

    @Test
    public void waterNextTickVelocityAppliesFrictionAndWaterGravity() {
        double velocityY = BedrockLiquidVerticalMovement.waterNextTickVelocityY(-0.08D, true);

        assertEquals(-0.069D, velocityY, 1.0E-12D);
    }

    @Test
    public void waterNextTickVelocityCanSkipWaterGravityWhenSwimmingActorGateIsActive() {
        double velocityY = BedrockLiquidVerticalMovement.waterNextTickVelocityY(0.0D, false);

        assertEquals(0.0D, velocityY, 1.0E-12D);
    }
}
