package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementUpdate;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockVelocitySystems;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockGlidingTravelMovementTest {
    @Test
    public void bothGlideStartPacketsRequireUsableElytra() {
        for (String start : List.of("START_GLIDING", "START_GLIDING_ACTION")) {
            assertStartResult(start, BedrockCollisionFlags.AIR, false, false, false);
            assertStartResult(start, BedrockCollisionFlags.AIR, true, false, true);
        }
    }

    @Test
    public void playerActionCannotBypassGroundedOrFlyingEligibility() {
        for (String start : List.of("START_GLIDING", "START_GLIDING_ACTION")) {
            assertStartResult(start, BedrockCollisionFlags.ON_GROUND, true, false, false);
            assertStartResult(start, BedrockCollisionFlags.AIR, true, true, false);
        }
    }

    @Test
    public void stopWinsOverEitherEligibleStartPacket() {
        for (String start : List.of("START_GLIDING", "START_GLIDING_ACTION")) {
            for (String stop : List.of("STOP_GLIDING", "STOP_GLIDING_ACTION")) {
                BedrockInputFrame frame = new BedrockInputFrame(
                    2L, 0.0F, 0.0F, false, false, false, Set.of(start, stop));
                BedrockGlideState glide = BedrockGlidingTravelMovement.resolve(
                    airborneState(), frame.intent(), glideContext(true, false));
                assertFalse(glide.activeAfterActions());
                assertFalse(glide.requestAfterActions());
            }
        }
    }

    @Test
    public void serverEstablishedGlidingDoesNotRequireEquippedElytra() {
        BedrockGlideState glide = BedrockGlidingTravelMovement.resolve(
            airborneState().withAcknowledgedGliding(true), BedrockInputFrame.idle(2L).intent(),
            glideContext(false, false));

        assertTrue(glide.activeAfterActions());
        assertTrue(glide.activeAtTravelSensing());
        assertTrue(glide.activeAtGlideInputSystem());
    }

    private static void assertStartResult(
        String start, BedrockCollisionFlags flags, boolean usableElytra, boolean flying, boolean expected
    ) {
        BedrockMovementState current = airborneState().withVelocityAndCollisionFlags(Vec3d.ZERO, flags);
        BedrockInputFrame frame = new BedrockInputFrame(
            2L, 0.0F, 0.0F, false, false, false, Set.of(start));
        BedrockTravelInput input = new BedrockTravelInput(
            current, frame, frame.intent(), BedrockWorldSnapshot.fromContext(glideContext(usableElytra, flying)),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE, current.velocity(),
            BedrockTravelOptions.vanilla(false, 0.5625D));

        BedrockFrameState prepared = BedrockFrameSystems.prepare(input, BedrockMobJumpComponentState.DEFAULT);

        assertEquals(start, expected, prepared.gliding().activeAfterActions());
        assertEquals(start, expected, prepared.gliding().requestAfterActions());
        assertEquals(start, expected, prepared.gliding().activeAtGlideInputSystem());
        assertEquals(start, expected, prepared.branch().glidingTravel());
    }

    private static BedrockMovementState airborneState() {
        return BedrockMovementState.fromPhysicalFeet(
            Vec3d.ZERO, Vec3d.ZERO, BedrockInputFrame.idle(1L), BedrockCollisionFlags.AIR, Medium.AIR);
    }

    private static BedrockMovementContext glideContext(boolean usableElytra, boolean flying) {
        return new BedrockMovementContext(
            BedrockEffectState.NONE, AttributeState.DEFAULT,
            new WorldContactState(Medium.AIR, FluidState.NONE, BlockCollisionWorld.EMPTY),
            new EquipmentState(0, 0, 0, false, usableElytra), EntityContactState.NONE,
            new MovementModifierState(
                usableElytra, flying, flying, false, 0.05D, false, false, false, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT);
    }

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
    public void waterContactCancelsLocalGlideRequestAndRequestsResize() {
        BedrockMovementState state = concurrentSwimmingAndGlidingState();
        BedrockInputFrame frame = new BedrockInputFrame(
            3L, -85.967255F, 22.411652F, false, false, false,
            Set.of("SWIMMING", "GLIDING"));

        BedrockGlideState glide = BedrockGlidingTravelMovement.resolve(
            state, frame.intent(), context(Medium.WATER));

        assertFalse(glide.actorStateAfterActions());
        assertFalse(glide.activeAtTravelSensing());
        assertFalse(glide.activeAtGlideInputSystem());
        assertFalse(glide.requestAfterActions());
        assertTrue(BedrockGlidingTravelMovement.requestsResize(state, frame.intent(), context(Medium.WATER)));
    }

    @Test
    public void equipmentFlightAndPassengerStopsRequireLocalRequest() {
        var air = glideContext(true, false);
        var riding = new BedrockMovementContext(air.effectState(), air.attributeState(), air.worldState(),
            air.equipmentState(), new EntityContactState(air.entityContactState().dolphinBoostState(), true),
            air.modifierState(), air.playerDimensionsState());
        for (var context : List.of(glideContext(false, false), glideContext(true, true), riding)) {
            var frame = BedrockInputFrame.idle(2L);
            var local = airborneState().withGliding(true);
            var stopped = BedrockGlidingTravelMovement.resolve(local, frame.intent(), context);
            assertFalse(stopped.activeAfterActions());
            assertFalse(stopped.requestAfterActions());
            assertTrue(BedrockGlidingTravelMovement.requestsResize(local, frame.intent(), context));
            assertTrue(BedrockGlidingTravelMovement.resolve(airborneState().withAcknowledgedGliding(true),
                frame.intent(), context).activeAfterActions());
        }
    }

    @Test
    public void jumpCancellationRequiresNewPressAfterTenTicksOutsideCreative() {
        var press = new BedrockInputFrame(20L, 0, 0, true, false, false, Set.of("JUMPING"));
        var air = glideContext(true, false);
        assertTrue(BedrockGlidingTravelMovement.resolve(agedGlide(10, false), press.intent(), air).activeAfterActions());
        assertFalse(BedrockGlidingTravelMovement.resolve(agedGlide(11, false), press.intent(), air).activeAfterActions());
        assertTrue(BedrockGlidingTravelMovement.resolve(agedGlide(11, true), press.intent(), air).activeAfterActions());
        var creative = new BedrockMovementContext(air.effectState(), air.attributeState(), air.worldState(),
            air.equipmentState(), air.entityContactState(),
            new MovementModifierState(true, true, false, true, .05, false, false, false, false, .35, 0),
            air.playerDimensionsState());
        assertTrue(BedrockGlidingTravelMovement.resolve(agedGlide(11, false), press.intent(), creative).activeAfterActions());
    }

    @Test
    public void climbStopUsesFeetBlockAndSnowEquipmentRatherThanNearbyBlocks() {
        var state = airborneState().withGliding(true);
        var frame = BedrockInputFrame.idle(2L);
        var air = glideContext(true, false);
        for (String block : List.of("minecraft:ladder", "minecraft:vine", "minecraft:powder_snow", "minecraft:scaffolding")) {
            for (int x : List.of(0, 1)) {
                for (boolean boots : List.of(false, true)) {
                    var placed = ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.manual(
                        new ac.cult.cultac.bedrock.prediction.geometry.BlockPosition(x, 0, 0), block, block, List.of());
                    var context = new BedrockMovementContext(air.effectState(), air.attributeState(),
                        new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(placed))),
                        new EquipmentState(0, 0, 0, boots, true), air.entityContactState(),
                        air.modifierState(), air.playerDimensionsState());
                    boolean stop = x == 0 && (!block.equals("minecraft:powder_snow") || boots)
                        && !block.equals("minecraft:scaffolding");
                    assertEquals(block + " x=" + x + " boots=" + boots, !stop,
                        BedrockGlidingTravelMovement.resolve(state, frame.intent(), context).activeAfterActions());
                }
            }
        }
        assertTrue(BedrockGlidingTravelMovement.resolve(state, frame.intent(), context(Medium.LAVA)).activeAfterActions());
    }

    private static BedrockMovementState agedGlide(long ticks, boolean wasJumping) {
        var state = BedrockMovementState.fromPhysicalFeet(Vec3d.ZERO, Vec3d.ZERO,
            new BedrockInputFrame(1L, 0, 0, wasJumping, false, false), BedrockCollisionFlags.AIR).withGliding(true);
        var memory = new BedrockMovementState.TickMemory(ticks, 0, ticks, 0, 0, 0, false, 0, 0, 0,
            state.dolphinBoost());
        return new BedrockMovementState(state.motion(), state.actor(), memory);
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
                true, true, false, false, 0.05D, false, false, false, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT);
    }
}
