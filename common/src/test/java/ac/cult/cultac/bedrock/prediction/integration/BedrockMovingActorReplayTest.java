package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import org.junit.Test;

public final class BedrockMovingActorReplayTest {
    private static final PlayerDimensionsState GLIDE = new PlayerDimensionsState(0.6F, 0.6F);

    @Test
    public void replayRefreshesContactAndTheFollowingMoveCarriesItsPositionAndCollisionVelocity() {
        var seed = state(48.57181930541992, 175.6588897705078, 1.6745046377182007, -0.14609602093696594)
                .withCollisionBox(new WorldCollisionBox(
                        48.271820068359375,
                        175.6588897705078,
                        14.199999809265137,
                        48.87181854248047,
                        176.25889587402344,
                        14.800000190734863));
        var input = request(seed, 10081, world(49.57014083862305), true);
        var original = simulate(input);
        assertPosition(original, 49.2701416015625, 175.51280212402344);
        var entry = new BedrockProfileState.Entry(original, BedrockMobJumpComponentState.DEFAULT);
        var frame = new BedrockActorHistory.Frame(
                10081,
                input,
                List.of(entry),
                List.of(entry),
                null,
                Vec3d.ZERO,
                Vec3d.ZERO,
                original.physicalFeetPosition(),
                original.velocity(),
                List.of(),
                new WorldCollisionBox(45, 170, 10, 55, 180, 20));
        var replayed = BedrockReplayTick.advance(
                        frame,
                        List.of(new BedrockProfileState.Entry(seed, BedrockMobJumpComponentState.DEFAULT)),
                        List.of(),
                        (old, snapshot) -> snapshot,
                        snapshot -> world(49.69013977050781))
                .getFirst()
                .state();
        assertPosition(replayed, 49.390140533447266, 175.51280212402344);
        assertEquals(0.0, replayed.velocity().x(), 1.0E-6);
        var following = simulate(request(replayed, 10082, world(49.60014343261719), false));
        assertPosition(following, 49.30014419555664, 175.36578369140625);
        assertEquals(0.0, following.velocity().x(), 1.0E-6);
        assertEquals(-0.14702057838439941, following.velocity().y(), 1.0E-6);
        assertTrue(following.collisionFlags().horizontalCollision());
    }

    @Test
    public void replayedTopOverlapKeepsTheGhastsVerticalDepenetrationLimit() {
        var seed = state(49.36014175415039, 175.96768188476562, 0.0, -0.1460941582918167);
        var input = request(seed, 629, world(49.57014083862305), false);
        var original = simulate(input);
        var entry = new BedrockProfileState.Entry(original, BedrockMobJumpComponentState.DEFAULT);
        var frame = new BedrockActorHistory.Frame(
                629,
                input,
                List.of(entry),
                List.of(entry),
                null,
                Vec3d.ZERO,
                Vec3d.ZERO,
                original.physicalFeetPosition(),
                original.velocity(),
                List.of(),
                new WorldCollisionBox(45, 170, 10, 55, 180, 20));
        var replayed = BedrockReplayTick.advance(
                        frame,
                        List.of(new BedrockProfileState.Entry(seed, BedrockMobJumpComponentState.DEFAULT)),
                        List.of(),
                        (old, snapshot) -> snapshot)
                .getFirst()
                .state();
        assertPosition(replayed, 49.37487030029297, 176.00399780273438);
        assertEquals(0.0, replayed.velocity().y(), 1.0E-6);
        assertTrue(replayed.collisionFlags().verticalCollision());
    }

    private static BedrockMovementState state(double x, double y, double vx, double vy) {
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(x, y, 14.5),
                        new Vec3d(vx, vy, 0),
                        BedrockInputFrame.idle(10080),
                        BedrockCollisionFlags.AIR)
                .withGliding(true)
                .withPlayerDimensions(GLIDE, true);
    }

    private static BedrockSimulation.Input request(
            BedrockMovementState state, long tick, BedrockWorldSnapshot world, boolean boost) {
        var frame = new BedrockInputFrame(tick, -90.0F, 4.9987946F, false, false, false);
        return new BedrockSimulation.Input(
                state,
                frame,
                frame.intent(),
                world,
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO,
                boost);
    }

    private static BedrockMovementState simulate(BedrockSimulation.Input input) {
        var result = BedrockForwardTick.simulate(input).getFirst().movementResult();
        return BedrockForwardTick.finish(result, result.predictedState());
    }

    private static BedrockWorldSnapshot world(double left) {
        var actor = BedrockDepenetrationLimit.HAPPY_GHAST.solidActor(
                new WorldCollisionBox(
                        left, 172, 12.498000144958496, left + 4.003997802734375, 176.00399780273438, 16.50200080871582),
                0.05F);
        var blocks = new BlockCollisionWorld(List.of(actor));
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, blocks),
                new EquipmentState(0, 0, 0, false, true),
                EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, 0.05, false, false, false, false, 0.35, 0),
                GLIDE));
    }

    private static void assertPosition(BedrockMovementState state, double x, double y) {
        assertEquals(x, state.physicalFeetPosition().x(), 1.0E-5);
        assertEquals(y, state.physicalFeetPosition().y(), 1.0E-5);
    }
}
