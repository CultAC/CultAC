package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.AttributeState;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.EquipmentState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.MovementModifierState;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementUpdate;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.EntityContactState;
import ac.grim.grimac.bedrock.prediction.world.FluidState;
import ac.grim.grimac.bedrock.prediction.world.WorldContactState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockRiptideMovementTest {
    @Test
    public void tenChargeTicksReleaseWithLevelThreeImpulse() {
        BedrockMovementState state = state(10L, false, 0L, BedrockCollisionFlags.AIR);
        BedrockInputFrame release = frame("RELEASE_USING_ITEM");

        BedrockRiptideMovement.ActorNormalTick result = tick(state, release, context(true, false, 3));

        Vec3d expected = BedrockAerialMovement.riptideImpulse(release, 3, false, false, false);
        assertEquals(expected.x(), result.velocity().x(), 0.0D);
        assertEquals(expected.y(), result.velocity().y(), 0.0D);
        assertEquals(expected.z(), result.velocity().z(), 0.0D);
        assertEquals(0L, result.step().nextChargeTicks());
        assertTrue(result.step().spinActive());
        assertEquals(1L, result.step().spinTicks());
    }

    @Test
    public void javaValidatedReleaseReusesExistingChargeAndWetProof() {
        BedrockMovementState state = state(0L, false, 0L, BedrockCollisionFlags.AIR)
            .withWasInWaterFlag(true);
        BedrockInputFrame release = frame("RELEASE_USING_ITEM", "RIPTIDE_RELEASE_VALID");

        BedrockRiptideMovement.ActorNormalTick result = tick(state, release, context(false, false, 1));

        assertTrue(result.step().spinActive());
        assertTrue(result.velocity().length() > 1.0D);
    }

    @Test
    public void impulseStrengthScalesForEveryVanillaRiptideLevel() {
        BedrockInputFrame horizontalRelease = new BedrockInputFrame(
            2L, 0.0F, 0.0F, false, false, false, Set.of("RELEASE_USING_ITEM"));
        for (int level = 1; level <= 3; level++) {
            BedrockRiptideMovement.ActorNormalTick result = tick(
                state(10L, false, 0L, BedrockCollisionFlags.AIR),
                horizontalRelease,
                context(true, false, level));
            assertEquals(0.75D * (level + 1), result.velocity().length(), 5.0E-7D);
        }
    }

    @Test
    public void currentBedrockSpinActionScalesGroundedVerticalDirection() {
        BedrockInputFrame release = new BedrockInputFrame(
            234L, -112.29094F, -20.535568F, false, false, true,
            Set.of("RELEASE_USING_ITEM", "SPRINTING", "SPRINT_DOWN"));

        Vec3d impulse = BedrockAerialMovement.riptideImpulse(release, 1, true, true, false);

        assertEquals(0.6445304D, impulse.y(), 5.0E-7D);
    }

    @Test
    public void groundedReleaseUsesPersistedWaterComponentForSurfaceExitScale() {
        BedrockInputFrame release = new BedrockInputFrame(
            234L, -112.29094F, -20.535568F, false, false, true,
            Set.of("RELEASE_USING_ITEM"));
        BedrockMovementState state = state(10L, false, 0L, BedrockCollisionFlags.ON_GROUND)
            .withWasInWaterFlag(true);

        BedrockRiptideMovement.ActorNormalTick result = tick(state, release, context(false, false, 1));
        Vec3d expected = BedrockAerialMovement.riptideImpulse(release, 1, true, true, false);

        assertEquals(expected.y(), result.velocity().y(), 0.0D);
    }

    @Test
    public void airborneSpinActionUsesNormalizedViewDirection() {
        BedrockInputFrame release = new BedrockInputFrame(
            571L, 169.65863F, -1.5891876F, true, false, false,
            Set.of("RELEASE_USING_ITEM", "JUMPING", "JUMP_CURRENT_RAW", "WANT_UP"));

        Vec3d impulse = BedrockAerialMovement.riptideImpulse(release, 1, false, false, false);

        assertEquals(0.0415559D, impulse.y(), 5.0E-7D);
    }

    @Test
    public void nineChargeTicksCannotRelease() {
        BedrockMovementState state = state(9L, false, 0L, BedrockCollisionFlags.AIR);

        BedrockRiptideMovement.ActorNormalTick result = tick(
            state, frame("RELEASE_USING_ITEM"), context(true, false, 3));

        assertEquals(Vec3d.ZERO, result.velocity());
        assertFalse(result.step().spinActive());
        assertEquals(0L, result.step().nextChargeTicks());
    }

    @Test
    public void chargeCanContinueAfterLeavingWaterButReleaseCannot() {
        BedrockMovementState state = state(6L, false, 0L, BedrockCollisionFlags.AIR);
        BedrockRiptideMovement.ActorNormalTick charging = tick(state, frame(), context(false, false, 3));
        assertEquals(7L, charging.step().nextChargeTicks());

        BedrockMovementState charged = state(10L, false, 0L, BedrockCollisionFlags.AIR);
        BedrockRiptideMovement.ActorNormalTick release = tick(
            charged, frame("RELEASE_USING_ITEM"), context(false, false, 3));
        assertEquals(Vec3d.ZERO, release.velocity());
        assertFalse(release.step().spinActive());
    }

    @Test
    public void dryStateDoesNotAuthorizeReleaseOutsideWaterOrRain() {
        BedrockMovementState state = state(10L, false, 0L, BedrockCollisionFlags.AIR)
            .withMovementBranch(Medium.AIR);

        BedrockRiptideMovement.ActorNormalTick release = tick(
            state, frame("RELEASE_USING_ITEM"), context(false, false, 1));

        assertFalse(release.step().spinActive());
        assertEquals(Vec3d.ZERO, release.velocity());
    }

    @Test
    public void retainedWaterAuthorizesSurfaceExitBeforeWaterStateRefresh() {
        BedrockMovementState state = state(10L, false, 0L, BedrockCollisionFlags.AIR)
            .withWasInWaterFlag(true)
            .withMovementBranch(Medium.AIR);

        BedrockRiptideMovement.ActorNormalTick release = tick(
            state, frame("RELEASE_USING_ITEM"), context(false, false, 1));

        assertTrue(release.step().spinActive());
        assertTrue(release.velocity().length() > 1.0D);
    }

    @Test
    public void stoppedItemUseClearsCharge() {
        BedrockRiptideMovement.ActorNormalTick result = tick(
            state(6L, false, 0L, BedrockCollisionFlags.AIR),
            frame("STOP_USING_ITEM"),
            context(true, false, 3));
        assertEquals(0L, result.step().nextChargeTicks());
    }

    @Test
    public void rainAllowsGroundedClientReleaseWithoutServerItemMove() {
        BedrockMovementState state = state(10L, false, 0L, BedrockCollisionFlags.ON_GROUND);

        BedrockRiptideMovement.ActorNormalTick result = tick(
            state, frame("RELEASE_USING_ITEM"), context(false, true, 1));

        assertTrue(result.step().spinActive());
        Vec3d expected = BedrockAerialMovement.riptideImpulse(
            frame("RELEASE_USING_ITEM"), 1, true, false, false);
        assertEquals(expected.x(), result.velocity().x(), 0.0D);
        assertEquals(expected.y(), result.velocity().y(), 0.0D);
        assertEquals(expected.z(), result.velocity().z(), 0.0D);
    }

    @Test
    public void groundedSpinActionAddsVerticalBoostUnlessExitingWater() {
        BedrockInputFrame release = frame("RELEASE_USING_ITEM");
        Vec3d airborne = BedrockAerialMovement.riptideImpulse(release, 1, false, false, false);
        Vec3d dryGround = BedrockAerialMovement.riptideImpulse(release, 1, true, false, false);
        Vec3d headInWater = BedrockAerialMovement.riptideImpulse(release, 1, true, true, true);

        assertEquals(airborne.y() + 0.08F, dryGround.y(), 5.0E-7D);
        assertEquals(airborne.y() + 0.08F, headInWater.y(), 5.0E-7D);
    }

    @Test
    public void spinStopsAtBedrockGroundAndLifetimeBoundaries() {
        BedrockRiptideMovement.ActorNormalTick grounded = tick(
            state(0L, true, 5L, BedrockCollisionFlags.ON_GROUND), frame(), context(false, false, 0));
        assertFalse(grounded.step().spinActive());

        BedrockRiptideMovement.ActorNormalTick expired = tick(
            state(0L, true, 19L, BedrockCollisionFlags.AIR), frame(), context(false, false, 0));
        assertFalse(expired.step().spinActive());
    }

    @Test
    public void explicitStopAndHorizontalCollisionEndSpin() {
        BedrockRiptideMovement.ActorNormalTick stopped = tick(
            state(0L, true, 2L, BedrockCollisionFlags.AIR),
            frame("STOP_SPIN_ATTACK"), context(false, false, 0));
        assertFalse(stopped.step().spinActive());

        BedrockCollisionFlags horizontal = new BedrockCollisionFlags(false, true, false);
        BedrockRiptideMovement.ActorNormalTick collided = tick(
            state(0L, true, 2L, horizontal), frame(), context(false, false, 0));
        assertFalse(collided.step().spinActive());
    }

    private static BedrockRiptideMovement.ActorNormalTick tick(
        BedrockMovementState state,
        BedrockInputFrame frame,
        BedrockMovementContext context
    ) {
        if (context.inWater()) {
            state = state.withWasInWaterFlag(true);
        }
        return BedrockRiptideMovement.tickActorNormal(
            state, frame, frame.intent(), context, Vec3d.ZERO);
    }

    private static BedrockMovementState state(
        long chargeTicks,
        boolean spinActive,
        long spinTicks,
        BedrockCollisionFlags collisionFlags
    ) {
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
            Vec3d.ZERO, Vec3d.ZERO, BedrockInputFrame.idle(0L), collisionFlags);
        return initial.advance(new BedrockMovementUpdate(
            Vec3d.ZERO,
            Vec3d.ZERO,
            BedrockInputFrame.idle(1L),
            collisionFlags,
            initial.boundingBoxMode(),
            initial.playerDimensions(),
            0.0F,
            0L,
            new BedrockMovementUpdate.Glide(false, false),
            false,
            0.0D,
            new BedrockMovementUpdate.Riptide(chargeTicks, spinActive, spinTicks),
            new BedrockMovementUpdate.ItemUse(false, 0L)
        ));
    }

    private static BedrockInputFrame frame(String... input) {
        return new BedrockInputFrame(2L, 0.0F, -10.0F, false, false, false, Set.of(input));
    }

    private static BedrockMovementContext context(boolean water, boolean rain, int level) {
        Medium medium = water ? Medium.WATER : Medium.AIR;
        return new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(medium, FluidState.NONE, new BlockCollisionWorld(List.of())),
            new EquipmentState(0, 0, 0, level, false, false),
            EntityContactState.NONE,
            new MovementModifierState(false, false, false, 0.05D, false,
                level > 0, rain, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT
        );
    }
}
