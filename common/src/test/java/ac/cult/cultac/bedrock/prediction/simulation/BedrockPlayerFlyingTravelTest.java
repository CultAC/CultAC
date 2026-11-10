package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
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
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Per tick numbers of a flying player, taken from replays of server enabled flight. */
public final class BedrockPlayerFlyingTravelTest {
    private static final Vec3d FORWARD = new Vec3d(0.0D, 0.0D, 1.0D);
    private static final Vec3d DIAGONAL = new Vec3d(0.70710677D, 0.0D, 0.70710677D);
    private static final Vec3d ON_FLOOR = new Vec3d(0.5D, 64.0D, 0.5D);
    private static final Vec3d ALOFT = new Vec3d(0.5D, 70.0D, 0.5D);
    private static final Vec3d RESTING = new Vec3d(0.0D, -0.0784D, 0.0D);

    // Holding forward, the client gains 0.049 a tick, and 0.098 from the tick it sprints.
    // At fly speeds 0.15 and 0.025 a sprinting client gains 0.294 and 0.049.
    @Test
    public void flightGainsTheFlySpeedAndTwiceThatOnlyWhileSprinting() {
        assertEquals(0.049D, gain(aloft(Vec3d.ZERO), 0.05D), 1.0E-6D);
        assertEquals(0.098D, gain(aloft(Vec3d.ZERO).withSprinting(true), 0.05D), 1.0E-6D);
        assertEquals(0.294D, gain(aloft(Vec3d.ZERO).withSprinting(true), 0.15D), 1.0E-6D);
        assertEquals(0.049D, gain(aloft(Vec3d.ZERO).withSprinting(true), 0.025D), 1.0E-6D);
    }

    // A sprinting client at fly speed 0.15 with velocity 0.13377 steps 0.42777 and reports
    // 0.389271, air friction. A move that started on the ground is followed by 0.6 * 0.91.
    @Test
    public void flightFrictionFollowsTheGroundContactAtTheStartOfTheTick() {
        var aloft = tick(
                aloft(new Vec3d(0.0D, 0.0D, 0.13377D)).withSprinting(true),
                BedrockInputFrame.idle(1L),
                FORWARD,
                flying(0.15D));
        assertEquals(0.42777D, moved(aloft).z(), 1.0E-6D);
        assertEquals(0.389271D, aloft.predictedVelocity().z(), 1.0E-6D);

        var grounded = tick(
                grounded(new Vec3d(0.0D, -0.0784D, 0.2D)), BedrockInputFrame.idle(1L), FORWARD, flying(0.05D));
        assertEquals(0.249D, moved(grounded).z(), 1.0E-6D);
        assertEquals(0.249D * 0.6D * 0.91D, grounded.predictedVelocity().z(), 1.0E-6D);
    }

    // A client with vertical velocity -0.075444 and jump held steps 0.074556 and reports 0.044734.
    @Test
    public void heldJumpAddsToAFallingVelocity() {
        var result = tick(
                aloft(new Vec3d(0.0D, -0.075444D, 0.0D)),
                new BedrockInputFrame(1L, 0.0F, 0.0F, true, false, false),
                Vec3d.ZERO,
                flying(0.05D));
        assertEquals(0.074556D, moved(result).y(), 2.0E-5D);
        assertEquals(0.044734D, result.predictedVelocity().y(), 1.0E-6D);
    }

    // Sneaking with a diagonal input while flying against the ground, the client gains
    // 0.034648 on each axis: the full 0.049, not 0.3 of it.
    @Test
    public void sneakDoesNotScaleFlightInput() {
        var frame = new BedrockInputFrame(1L, 0.0F, 0.0F, false, true, false, Set.of("SNEAKING", "SNEAK_DOWN"));
        var result = tick(grounded(RESTING), frame, DIAGONAL, flying(0.05D));
        Vec3d moved = moved(result);

        assertEquals(0.034648D, Math.abs(moved.x()), 1.0E-6D);
        assertEquals(0.034648D, Math.abs(moved.z()), 1.0E-6D);
        assertEquals(0.0D, moved.y(), 0.0D);
        assertTrue(result.predictedState().collisionFlags().onGround());
    }

    // Flight that starts one tick after a jump (velocity 0.3332) with jump still held rises
    // 0.4832, 0.4399, 0.4140, 0.3984, 0.3890 and settles at 0.375: each tick adds 0.15 to
    // the carried velocity, and 0.6 of the result is carried on.
    @Test
    public void heldJumpAddsToTheCarriedVerticalVelocity() {
        var state = aloft(new Vec3d(0.0D, 0.3332D, 0.0D));
        double[] rises = {0.4832D, 0.43992D, 0.413952D, 0.3983712D, 0.38902272D};
        for (int tick = 0; tick < rises.length; tick++) {
            var frame = new BedrockInputFrame(tick + 1L, 0.0F, 0.0F, true, false, false);
            var result = tick(state, frame, Vec3d.ZERO, flying(0.05D));
            assertEquals(rises[tick], moved(result).y(), 2.0E-5D);
            assertEquals(rises[tick] * 0.6D, result.predictedVelocity().y(), 1.0E-6D);
            state = result.predictedState();
        }
    }

    @Test
    public void releasedVerticalInputLeavesTheVelocityToTheDrag() {
        var result = tick(aloft(new Vec3d(0.0D, 0.225D, 0.0D)), BedrockInputFrame.idle(1L), Vec3d.ZERO, flying(0.05D));

        assertEquals(0.225D, moved(result).y(), 2.0E-5D);
        assertEquals(0.135D, result.predictedVelocity().y(), 1.0E-6D);
    }

    // Rising at 0.375 a tick (0.225 carried), the tick sneak joins held jump the client steps 0
    // and reports 0; descending at -0.548 (-0.3288 carried), adding jump does the same.
    @Test
    public void upAndDownInputTogetherStopTheVerticalVelocity() {
        var both = new BedrockInputFrame(1L, 0.0F, 0.0F, true, true, false, Set.of("SNEAKING", "SNEAK_DOWN"));
        var rising = tick(aloft(new Vec3d(0.0D, 0.225D, 0.0D)), both, Vec3d.ZERO, flying(0.05D));
        assertEquals(0.0D, moved(rising).y(), 0.0D);
        assertEquals(0.0D, rising.predictedVelocity().y(), 0.0D);
        var descending = tick(aloft(new Vec3d(0.0D, -0.328765D, 0.0D)), both, Vec3d.ZERO, flying(0.15D));
        assertEquals(0.0D, moved(descending).y(), 0.0D);
    }

    // Sneak alone while flying: from 0.00049 carried the client steps -0.21951, then from
    // -0.131706 steps -0.3517, at fly speeds 0.15 and 0.025 alike.
    @Test
    public void downInputTakesTwentyTwoHundredthsFromTheCarriedVelocity() {
        var down = new BedrockInputFrame(1L, 0.0F, 0.0F, false, true, false, Set.of("SNEAKING", "SNEAK_DOWN"));
        var first = tick(aloft(new Vec3d(0.0D, 0.00049D, 0.0D)), down, Vec3d.ZERO, flying(0.025D));
        assertEquals(-0.21951D, moved(first).y(), 2.0E-5D);
        assertEquals(-0.131706D, first.predictedVelocity().y(), 2.0E-5D);
        assertEquals(-0.3517D, moved(tick(first.predictedState(), down, Vec3d.ZERO, flying(0.15D))).y(), 2.0E-5D);
    }

    // Flight enabled on a walking player: the client keeps ground friction for two ticks and
    // has air friction from the third, and when flight is taken away it gains 0.0196 (air
    // control) on the next tick, not the ground acceleration.
    @Test
    public void hoveringAtGroundLevelLeavesTheGround() {
        var first = tick(grounded(RESTING), BedrockInputFrame.idle(1L), FORWARD, flying(0.05D));
        assertTrue(first.predictedState().collisionFlags().onGround());
        assertEquals(0.0D, moved(first).y(), 0.0D);

        var second = tick(first.predictedState(), BedrockInputFrame.idle(2L), FORWARD, flying(0.05D));
        assertFalse(second.predictedState().collisionFlags().onGround());
        assertFalse(second.predictedState().movementGrounded());
        assertEquals(0.0D, moved(second).y(), 0.0D);

        var hovering = second.predictedState()
                .withVelocityAndCollisionFlags(
                        Vec3d.ZERO, second.predictedState().collisionFlags());
        var onFoot = tick(hovering, BedrockInputFrame.idle(3L), FORWARD, context(MovementModifierState.NONE));
        assertEquals(0.0196D, moved(onFoot).z(), 1.0E-6D);
        assertEquals(0.0D, moved(onFoot).y(), 0.0D);
        assertEquals(-0.0784D, onFoot.predictedVelocity().y(), 1.0E-6D);
    }

    // Flying up and forward into a three block high wall at fly speed 0.15: from feet 122.61237
    // rising 0.35774 the client steps onto the top at 123.0 and moves its full 0.147 forward,
    // carrying 0.13377 forward and nothing upward. From 122.43449 the 0.5625 step falls short.
    @Test
    public void aFlyingPlayerStepsUpAWallWhileRising() {
        var up = new BedrockInputFrame(1L, 0.0F, 0.0F, true, false, false);
        var stepped = tick(
                flyingAt(new Vec3d(6.5D, 122.61236572265625D, 3.7D), 0.2077380D),
                up,
                FORWARD,
                flying(0.15D, wall()));
        assertTrue(stepped.steppedUp());
        assertEquals(123.0D, stepped.predictedPosition().y(), 1.0E-5D);
        assertEquals(3.847D, stepped.predictedPosition().z(), 1.0E-6D);
        assertEquals(0.0D, stepped.predictedVelocity().y(), 1.0E-6D);
        assertEquals(0.13377D, stepped.predictedVelocity().z(), 1.0E-5D);

        var blocked = tick(
                flyingAt(new Vec3d(6.5D, 122.43449401855469D, 3.7D), 0.2016372D),
                up,
                FORWARD,
                flying(0.15D, wall()));
        assertFalse(blocked.steppedUp());
        assertEquals(122.78613D, blocked.predictedPosition().y(), 1.0E-5D);
        assertEquals(3.7D, blocked.predictedPosition().z(), 1.0E-6D);
    }

    private static double gain(BedrockMovementState state, double flySpeed) {
        return moved(tick(state, BedrockInputFrame.idle(1L), FORWARD, flying(flySpeed))).z();
    }

    private static Vec3d moved(BedrockMovementResult result) {
        return result.predictedPosition().subtract(result.previousState().physicalFeetPosition());
    }

    private static BedrockMovementResult tick(
            BedrockMovementState state, BedrockInputFrame frame, Vec3d control, BedrockMovementContext context) {
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), state, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        snapshot,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        control))
                .getFirst()
                .movementResult();
    }

    private static BedrockMovementState grounded(Vec3d velocity) {
        return BedrockMovementState.fromPhysicalFeet(
                ON_FLOOR, velocity, BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND);
    }

    private static BedrockMovementState aloft(Vec3d velocity) {
        return BedrockMovementState.fromPhysicalFeet(
                ALOFT, velocity, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
    }

    private static BedrockMovementState flyingAt(Vec3d feet, double velocityY) {
        return BedrockMovementState.fromPhysicalFeet(
                feet, new Vec3d(0.0D, velocityY, 0.0D), BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
    }

    private static BedrockMovementContext flying(double flySpeed) {
        return flying(flySpeed, floor());
    }

    private static BedrockMovementContext flying(double flySpeed, List<PlacedBlockCollision> blocks) {
        return context(
                new MovementModifierState(false, true, true, false, flySpeed, false, false, false, false, 0.35D, 0L),
                blocks);
    }

    private static BedrockMovementContext context(MovementModifierState modifiers) {
        return context(modifiers, floor());
    }

    private static List<PlacedBlockCollision> floor() {
        return List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 63, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(-16.0D, 63.0D, -16.0D, 16.0D, 64.0D, 16.0D))));
    }

    private static List<PlacedBlockCollision> wall() {
        return List.of(PlacedBlockCollision.manual(
                new BlockPosition(6, 120, 4),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(4.0D, 120.0D, 4.0D, 9.0D, 123.0D, 5.0D))));
    }

    private static BedrockMovementContext context(MovementModifierState modifiers, List<PlacedBlockCollision> blocks) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                modifiers,
                PlayerDimensionsState.DEFAULT);
    }
}
