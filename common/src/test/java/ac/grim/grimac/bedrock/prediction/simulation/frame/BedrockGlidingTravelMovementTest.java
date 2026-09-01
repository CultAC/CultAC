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
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockVelocitySystems;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.EntityContactState;
import ac.grim.grimac.bedrock.prediction.world.FluidState;
import ac.grim.grimac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockGlidingTravelMovementTest {
    @Test
    public void airborneSwimmingActorStillUsesGlideSystems() {
        BedrockMovementState state = concurrentSwimmingAndGlidingState();
        BedrockInputFrame frame = new BedrockInputFrame(
            3L, -85.967255F, 22.411652F, false, false, false,
            Set.of("SWIMMING", "GLIDING"));

        BedrockGlideState glide = BedrockGlidingTravelMovement.resolve(
            state, frame.intent(), context(Medium.AIR));

        assertTrue(glide.actorStateAfterActions());
        assertTrue(glide.activeAtTravelSensing());
        assertTrue(glide.activeAtGlideInputSystem());
    }

    @Test
    public void framePipelineSelectsGlideMoveForAirborneSwimmingActor() {
        BedrockMovementState state = concurrentSwimmingAndGlidingState();
        BedrockInputFrame frame = new BedrockInputFrame(
            3L, -85.967255F, 22.411652F, false, false, false,
            Set.of("SWIMMING", "GLIDING"));
        BedrockMovementContext context = context(Medium.AIR);
        BedrockTravelInput input = new BedrockTravelInput(
            state,
            frame,
            frame.intent(),
            BedrockWorldSnapshot.fromContext(context),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
            state.velocity(),
            BedrockTravelOptions.vanilla(false, 0.5625D));

        BedrockFrameState prepared = BedrockFrameSystems.prepare(
            input, BedrockMobJumpComponentState.DEFAULT);
        Vec3d glideVelocity = BedrockVelocitySystems.plan(prepared).travelVelocity();

        assertTrue(prepared.branch().glidingTravel());
        assertEquals(BedrockAerialMovement.glideVelocity(state.velocity(), frame), glideVelocity);
    }

    @Test
    public void currentLiquidContactWinsTravelWithoutDisablingActorGlideInput() {
        BedrockMovementState state = concurrentSwimmingAndGlidingState();
        BedrockInputFrame frame = new BedrockInputFrame(
            3L, -85.967255F, 22.411652F, false, false, false,
            Set.of("SWIMMING", "GLIDING"));

        BedrockGlideState glide = BedrockGlidingTravelMovement.resolve(
            state, frame.intent(), context(Medium.WATER));

        assertTrue(glide.actorStateAfterActions());
        assertFalse(glide.activeAtTravelSensing());
        assertTrue(glide.activeAtGlideInputSystem());
    }

    @Test
    public void stopSwimmingEdgeDoesNotVetoContinuingAirGlide() {
        BedrockMovementState state = concurrentSwimmingAndGlidingState();
        BedrockInputFrame frame = new BedrockInputFrame(
            3L, -85.967255F, 22.411652F, false, false, false,
            Set.of("STOP_SWIMMING", "GLIDING"));

        BedrockGlideState glide = BedrockGlidingTravelMovement.resolve(
            state, frame.intent(), context(Medium.AIR));

        assertTrue(glide.activeAtTravelSensing());
        assertTrue(glide.activeAtGlideInputSystem());
    }

    private static BedrockMovementState concurrentSwimmingAndGlidingState() {
        BedrockInputFrame initialFrame = BedrockInputFrame.idle(1L);
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
            Vec3d.ZERO,
            new Vec3d(0.01085845947265625D, -0.14579594135284424D, 0.0007637023925781251D),
            initialFrame,
            BedrockCollisionFlags.AIR,
            Medium.AIR);
        return initial.advance(new BedrockMovementUpdate(
            initial.physicalFeetPosition(),
            initial.velocity(),
            new BedrockInputFrame(2L, -85.967255F, 22.411652F, false, false, false,
                Set.of("SWIMMING", "GLIDING")),
            BedrockCollisionFlags.AIR,
            initial.boundingBoxMode(),
            new PlayerDimensionsState(0.6000000238418579D, 0.6000000238418579D),
            0.30500346F,
            0L,
            new BedrockMovementUpdate.Glide(true, true),
            true,
            1.0D,
            new BedrockMovementUpdate.Riptide(0L, false, 0L),
            new BedrockMovementUpdate.ItemUse(false, 0L)));
    }

    private static BedrockMovementContext context(Medium medium) {
        return new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(medium, FluidState.NONE, new BlockCollisionWorld(List.of())),
            EquipmentState.NONE,
            EntityContactState.NONE,
            new MovementModifierState(
                true, true, false, 0.05D, false, false, false, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT);
    }
}
