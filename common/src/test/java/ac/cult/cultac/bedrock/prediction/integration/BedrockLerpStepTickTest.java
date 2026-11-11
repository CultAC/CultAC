package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
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
 * A vehicle the client starts predicting while it still lerps toward a move takes the lerp step first, which drops the
 * motion the tick carries in; its rider's input and the rest of the tick then run as on a tick from rest. With no
 * input a donkey in water reports (0, -0.02, 0) and a boat on the ground (0, -0.0392, 0); a boat put back under its
 * rider still takes its paddles' push.
 */
public final class BedrockLerpStepTickTest {
    private static final PlayerDimensionsState DONKEY = new PlayerDimensionsState(1.3965F, 1.6F);

    @Test
    public void theLerpStepDropsTheCarriedMotionAndTheInputStillApplies() {
        for (var vehicle : List.of(horse(), boat())) {
            var moving =
                    vehicle.withVelocityAndCollisionFlags(new Vec3d(0, 0, 0.4), BedrockCollisionFlags.ON_GROUND);
            var atRest = vehicle.withVelocityAndCollisionFlags(Vec3d.ZERO, BedrockCollisionFlags.ON_GROUND);

            var lerp = forward(moving.withLerpStep());
            var fromRest = forward(atRest);
            assertEquals(fromRest.physicalFeetPosition(), lerp.physicalFeetPosition());
            assertEquals(fromRest.velocity(), lerp.velocity());
            assertNotEquals(vehicle.physicalFeetPosition(), lerp.physicalFeetPosition());
            assertEquals(BedrockMovementState.TickStart.MOVE, lerp.tickStart());

            assertNotEquals(lerp.physicalFeetPosition(), forward(moving).physicalFeetPosition());
        }
    }

    /**
     * A donkey mounted with its box 0.19825 into a block face at z=1 and only a turn sent since its spawn, with no
     * input: its move on the lerp step's tick still takes it out of the block, to z 1.69825 flush with the face.
     */
    @Test
    public void theMoveAfterTheLerpStepStillLeavesAnOverlappedBlock() {
        var donkey = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(10.5, 120, 1.5),
                        Vec3d.ZERO,
                        BedrockInputFrame.idle(0),
                        BedrockCollisionFlags.ON_GROUND)
                .withHorse(new BedrockHorseState(null, false, 0, false, false, 0, -1, false, 0))
                .withPlayerDimensions(DONKEY, true);
        var blocks = new ArrayList<PlacedBlockCollision>();
        blocks.add(stone(new WorldCollisionBox(5, 119, -2, 16, 120, 6), 5, 119, -2));
        blocks.add(stone(new WorldCollisionBox(9, 120, 0, 12, 121, 1), 9, 120, 0));

        var next = forward(donkey.withLerpStep(), world(blocks), Set.of(), Vec3d.ZERO);

        assertEquals(1.69825F, (float) next.physicalFeetPosition().z(), 1.0E-6F);
        assertEquals(0.0, next.velocity().z(), 0);
        assertEquals(-0.0784, next.velocity().y(), 1.0E-6);
    }

    private static BedrockMovementState forward(BedrockMovementState state) {
        return forward(state, BedrockActorHistoryTest.ground(), Set.of("UP"), new Vec3d(0, 0, 1));
    }

    private static BedrockMovementState forward(
            BedrockMovementState state, BedrockWorldSnapshot world, Set<String> flags, Vec3d controls) {
        var input = new BedrockInputFrame(1, 82.30463F, 5.652817F, false, false, false, flags);
        var request = new BedrockSimulation.Input(
                state,
                input,
                input.intent(),
                world,
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                controls);
        var movement = BedrockForwardTick.simulate(request).getFirst().movementResult();
        return BedrockForwardTick.finish(movement, movement.predictedState()).getFirst();
    }

    private static PlacedBlockCollision stone(WorldCollisionBox box, int x, int y, int z) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z), "minecraft:stone", "minecraft:stone", List.of(box));
    }

    private static BedrockWorldSnapshot world(List<PlacedBlockCollision> blocks) {
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                DONKEY));
    }

    private static BedrockMovementState boat() {
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(289, 82, -86), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .withBoat(new BedrockBoatState(
                        null,
                        BedrockBoatProperties.initial(12),
                        0,
                        0,
                        0,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.PaddleInput.ANALOG));
    }

    private static BedrockMovementState horse() {
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(289, 82, -86), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .withHorse(new BedrockHorseState(null, false, 0, false, false, 0, -1, false, 0));
    }
}
