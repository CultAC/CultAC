package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * A swimmer standing on a floor at the edge of a water column, with its head already outside the water.
 * Define_Outside, protocol 2225, flag-1-2026-11-14T17-14-39.020668490Z: jump is pressed on the last tick the box
 * touches the water, and the next tick launches a ground jump of 0.42 from the same floor.
 */
public final class BedrockSwimJumpGroundTest {
    private static final double FLOOR_Y = 82.0D;
    private static final Vec3d FEET = new Vec3d(1.25D, FLOOR_Y, 0.5D);
    private static final Set<String> SWIMMING = Set.of("SWIMMING", "ACTOR_POSE_SNAPSHOT");
    private static final Set<String> SWIMMING_JUMP_PRESSED =
            Set.of("SWIMMING", "ACTOR_POSE_SNAPSHOT", "JUMPING", "WANT_UP", "JUMP_PRESSED_RAW", "JUMP_CURRENT_RAW");
    private static final Set<String> LEAVING_JUMP_HELD = Set.of(
            "SWIMMING",
            "STOP_SWIMMING",
            "ACTOR_POSE_SNAPSHOT",
            "JUMPING",
            "START_JUMPING",
            "WANT_UP",
            "JUMP_CURRENT_RAW");

    @Test
    public void aWithheldSwimJumpInWaterKeepsTheFloorTheSwimmerStandsOn() {
        Ticks ticks = settledSwimmerMovingOutOfTheWater();

        var pressed = ticks.tick(frame(SWIMMING_JUMP_PRESSED, true));

        assertEquals(FLOOR_Y, pressed.predictedPosition().y(), 0.0D);
        assertEquals(0.0D, pressed.predictedVelocity().y(), 0.0D);
        assertTrue(pressed.predictedState().collisionFlags().onGround());
    }

    @Test
    public void theSwimmerJumpsFromTheFloorOnTheTickItLeavesTheWater() {
        Ticks ticks = settledSwimmerMovingOutOfTheWater();
        ticks.tick(frame(SWIMMING_JUMP_PRESSED, true));

        var left = ticks.tick(frame(LEAVING_JUMP_HELD, true));

        assertTrue(left.groundJumpApplied());
        assertEquals((double) 0.42F, left.predictedPosition().y() - FLOOR_Y, 1.0E-5D);
    }

    /** The swim pose has finished blending, and the carried speed takes the box out of the water in one tick. */
    private static Ticks settledSwimmerMovingOutOfTheWater() {
        Ticks ticks = new Ticks(BedrockMovementState.fromPhysicalFeet(
                FEET, Vec3d.ZERO, frame(Set.of("START_SWIMMING"), false), BedrockCollisionFlags.ON_GROUND));
        for (int warmUp = 0; warmUp < 9; warmUp++) {
            ticks.tick(frame(SWIMMING, false));
        }
        assertEquals(1.0D, ticks.state.swimAmount(), 0.0D);
        assertTrue(ticks.state.swimming());
        assertTrue(ticks.state.collisionFlags().onGround());
        assertEquals(FEET, ticks.state.physicalFeetPosition());
        ticks.state =
                ticks.state.withVelocityAndCollisionFlags(new Vec3d(0.2D, 0.0D, 0.0D), ticks.state.collisionFlags());
        return ticks;
    }

    private static BedrockInputFrame frame(Set<String> inputData, boolean jumping) {
        return new BedrockInputFrame(0L, 0.0F, 0.0F, jumping, false, false, inputData);
    }

    private static final class Ticks {
        private BedrockMovementState state;
        private BedrockMobJumpComponentState component = BedrockMobJumpComponentState.DEFAULT;
        private long tick;

        private Ticks(BedrockMovementState state) {
            this.state = state;
        }

        private BedrockMovementResult tick(BedrockInputFrame input) {
            var frame = new BedrockInputFrame(
                    ++tick, input.yaw(), input.pitch(), input.jumping(), false, false, input.inputData());
            var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context()), state, frame);
            var candidate = BedrockSimulation.candidates(new BedrockSimulation.Input(
                            state, frame, frame.intent(), snapshot, false, 0.0D, component, true, false))
                    .getFirst();
            state = candidate.movementResult().predictedState();
            component = candidate.mobJumpComponent();
            return candidate.movementResult();
        }
    }

    /** Water fills the column west of the swimmer's centre; the floor runs under both. */
    private static BedrockMovementContext context() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 0; x <= 2; x++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(x, 81, 0),
                    "minecraft:stone",
                    "minecraft:stone",
                    List.of(new WorldCollisionBox(x, 81.0D, 0.0D, x + 1.0D, FLOOR_Y, 1.0D))));
        }
        for (int y = 82; y <= 84; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(0, y, 0), "minecraft:water[level=0]", "minecraft:water", List.of()));
        }
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
