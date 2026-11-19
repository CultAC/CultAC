package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.Set;
import org.junit.Test;

public final class BedrockFlyingMobJumpTest {
    // Define_Outside, protocol 2225, flag-1-2026-11-12T00-24-52.825151005Z:
    // input tick 449 rises 0.1500015 from rest; the erroneous swim-up adds another 0.04.
    @Test
    public void waterFlightFromRestMatchesFlag1() {
        var feet = new Vec3d(20134.67172050476D, 118.0D, 20108.36209011078D);
        var result = tick(feet, 0.0D, 449L, new BedrockCoordinateFrame(20112, 20096, 1L), "water", true);

        assertEquals(
                118.1500015258789D, result.movementResult().predictedPosition().y(), 1.0E-6D);
        assertEquals(0.09D, result.movementResult().predictedVelocity().y(), 1.0E-6D);
        assertFalse(result.movementResult().groundJumpApplied());
    }

    // Same client, flag-43-2026-11-12T00-30-58.388781556Z: input tick 7899
    // carries -0.022339165 and rises 0.127662659, with no horizontal input.
    @Test
    public void waterFlightWhileFallingMatchesFlag43() {
        var feet = new Vec3d(355.4186096191406D, 83.2534408569336D, -40.691062927246094D);
        var result = tick(feet, -0.022339165271759026D, 7899L, new BedrockCoordinateFrame(0, 0, 2L), "water", true);

        assertEquals(
                83.381103515625D, result.movementResult().predictedPosition().y(), 1.0E-6D);
        assertEquals(0.076596506D, result.movementResult().predictedVelocity().y(), 1.0E-6D);
    }

    @Test
    public void lavaFlightAlsoUsesOnlyTheFlightAscent() {
        var feet = new Vec3d(0.5D, 70.0D, 0.5D);
        var result = tick(feet, 0.0D, 1L, BedrockCoordinateFrame.IDENTITY, "lava", true);

        assertEquals(0.15D, result.movementResult().predictedPosition().y() - feet.y(), 1.0E-5D);
        assertEquals(0.09D, result.movementResult().predictedVelocity().y(), 1.0E-6D);
    }

    @Test
    public void flightPermissionAloneStillAllowsOrdinaryLiquidJumping() {
        var feet = new Vec3d(0.5D, 70.0D, 0.5D);
        for (String liquid : Set.of("water", "lava")) {
            var result = tick(feet, 0.0D, 1L, BedrockCoordinateFrame.IDENTITY, liquid, false);

            assertEquals(
                    liquid, 0.04D, result.movementResult().predictedPosition().y() - feet.y(), 1.0E-5D);
        }
    }

    @Test
    public void flightDoesNotConsumeASwimImpulseOrKeepTheGroundJumpCooldown() {
        var feet = new Vec3d(0.5D, 70.0D, 0.5D);
        var component = new BedrockMobJumpComponentState(true, 5);
        var result = tick(feet, 0.0D, 1L, BedrockCoordinateFrame.IDENTITY, "water", true, component);

        assertEquals(0.15D, result.movementResult().predictedPosition().y() - feet.y(), 1.0E-5D);
        assertTrue(result.mobJumpComponent().reduceNextSwimUpImpulse());
        assertEquals(0, result.mobJumpComponent().jumpCooldownTicks());
    }

    private static BedrockSimulation.Candidate tick(
            Vec3d feet,
            double velocityY,
            long tick,
            BedrockCoordinateFrame coordinates,
            String liquid,
            boolean flying) {
        return tick(feet, velocityY, tick, coordinates, liquid, flying, BedrockMobJumpComponentState.DEFAULT);
    }

    private static BedrockSimulation.Candidate tick(
            Vec3d feet,
            double velocityY,
            long tick,
            BedrockCoordinateFrame coordinates,
            String liquid,
            boolean flying,
            BedrockMobJumpComponentState component) {
        var frame = new BedrockInputFrame(
                tick,
                0.0F,
                0.0F,
                true,
                false,
                false,
                Set.of("JUMP_PRESSED_RAW", "JUMP_CURRENT_RAW", "JUMPING", "WANT_UP", "ACTOR_POSE_SNAPSHOT"));
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet,
                        new Vec3d(0.0D, velocityY, 0.0D),
                        BedrockInputFrame.idle(tick - 1L),
                        BedrockCollisionFlags.AIR)
                .withCoordinateFrame(coordinates)
                .withWasInWaterFlag(liquid.equals("water"))
                .withWaterTravelFlag(liquid.equals("water"));
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (int y = (int) Math.floor(feet.y()) - 1; y <= (int) Math.floor(feet.y()) + 3; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition((int) Math.floor(feet.x()), y, (int) Math.floor(feet.z())),
                    "minecraft:" + liquid + "[level=0]",
                    "minecraft:" + liquid,
                    java.util.List.of()));
        }
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks, coordinates)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(false, true, flying, false, 0.05D, false, false, false, false, 0.35D, 0L),
                PlayerDimensionsState.DEFAULT);
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), state, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        snapshot,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        component,
                        true,
                        false,
                        Vec3d.ZERO))
                .getFirst();
    }
}
