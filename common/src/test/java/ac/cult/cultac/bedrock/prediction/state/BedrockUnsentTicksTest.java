package ac.cult.cultac.bedrock.prediction.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import org.junit.Test;

public class BedrockUnsentTicksTest {
    @Test
    public void theSwimAmountRunsOutOverTheTicksAfterTheFirst() {
        // Captured deaths while swimming with the jump key held on respawn: after 44 unsent ticks the client
        // jumps as it lands, after one it waits out the whole ten ticks.
        assertEquals(0.0D, swimming(1.0D).afterUnsentTicks(44L).swimAmount(), 0.0D);
        assertEquals(1.0D, swimming(1.0D).afterUnsentTicks(1L).swimAmount(), 0.0D);
        assertEquals(
                BedrockSwimmingPoseProgress.nextSwimAmount(
                        BedrockSwimmingPoseProgress.nextSwimAmount(1.0D, false), false),
                swimming(1.0D).afterUnsentTicks(3L).swimAmount(),
                0.0D);
    }

    @Test
    public void aDolphinBoostWearsOffOverEachUnsentTick() {
        BedrockMovementState boosted = swimming(1.0D).withDolphinBoost(new BedrockDolphinBoost(25, 32, true));

        assertEquals(new BedrockDolphinBoost(25, 22, true), boosted.afterUnsentTicks(10L).dolphinBoost());
        assertEquals(0, boosted.afterUnsentTicks(116L).dolphinBoost().effectTicks());
        // The scan for dolphins waits while the actor does not swim.
        assertEquals(25, boosted.afterUnsentTicks(116L).dolphinBoost().scanTicks());
    }

    @Test
    public void aStateWithNothingRunningIsLeftAsItIs() {
        BedrockMovementState idle = swimming(0.0D);

        assertSame(idle, idle.afterUnsentTicks(60L));
    }

    private static BedrockMovementState swimming(double swimAmount) {
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
                Vec3d.ZERO, Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        return initial.advance(new BedrockMovementUpdate(
                Vec3d.ZERO,
                Vec3d.ZERO,
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR,
                initial.boundingBoxMode(),
                initial.playerDimensions(),
                0.0F,
                0L,
                new BedrockMovementUpdate.Glide(false, false),
                swimAmount > 0.0D,
                swimAmount,
                new BedrockMovementUpdate.Riptide(0L, false, 0L),
                new BedrockMovementUpdate.ItemUse(false, 0L)));
    }
}
