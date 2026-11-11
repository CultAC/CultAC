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
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class BedrockMountStandingSurfaceTest {
    private static final double RESTING_Y = -0.0784D;
    private static final double HONEY_FRICTION = 0.8D * 0.91D;
    private static final Vec3d FORWARD = new Vec3d(0.0D, 0.0D, 1.0D);

    @Test
    public void riddenHorseKeepsItsSpeedWhileWalkingOnHoney() {
        // A ridden horse walking on honey with forward held: the client's moves between two corrections
        // follow v' = 0.728 v + 0.0842, honey friction with no standing slowdown.
        BedrockMovementState horse = horse(1.0D, new Vec3d(0.0D, RESTING_Y, 0.120743D * HONEY_FRICTION), true)
                .withMovementAttribute(new BedrockMovementAttributeState(0.1996F, 0, 1024, 0, 1024, 0.1F, List.of()));
        BedrockWorldSnapshot honey = floor("minecraft:honey_block", BlockContactBehavior.HONEY);

        for (double clientMove : new double[] {0.172104D, 0.209500D, 0.236721D, 0.256543D, 0.270962D}) {
            BedrockMovementResult result = tick(horse, honey, FORWARD);
            assertEquals(clientMove, moveZ(result), 1.0E-5D);
            horse = next(result);
        }
    }

    @Test
    public void riddenHorseCoastsOnHoneyWithBlockFrictionOnly() {
        // The same mount coasting after a landing: each move is 0.728 of the one before.
        BedrockMovementState horse = horse(1.0D, new Vec3d(0.0D, RESTING_Y, 0.050042D), true);
        BedrockWorldSnapshot honey = floor("minecraft:honey_block", BlockContactBehavior.HONEY);

        for (double clientMove : new double[] {0.050042D, 0.036431D, 0.026522D, 0.019307D}) {
            BedrockMovementResult result = tick(horse, honey, Vec3d.ZERO);
            assertEquals(clientMove, moveZ(result), 5.0E-6D);
            horse = next(result);
        }
    }

    @Test
    public void riddenHorseLandingOnHoneyTakesAirFrictionOnly() {
        // The client's horse fell onto honey moving 0.249616 a tick and moved 0.227153 on the next tick.
        BedrockMovementState falling = horse(1.332939D, new Vec3d(0.0D, -1.08271D, 0.249616D), false);
        BedrockWorldSnapshot honey = floor("minecraft:honey_block", BlockContactBehavior.HONEY);

        BedrockMovementResult landing = tick(falling, honey, Vec3d.ZERO);

        assertEquals(1.0D, landing.predictedPosition().y(), 1.0E-9D);
        assertTrue(landing.predictedState().collisionFlags().onGround());
        assertEquals(0.227153D, moveZ(tick(next(landing), honey, Vec3d.ZERO)), 5.0E-6D);
    }

    @Test
    public void riddenHorseKeepsItsSpeedOnSlime() {
        BedrockMovementState horse = horse(1.0D, new Vec3d(0.0D, RESTING_Y, 0.2D), true);

        BedrockMovementResult result = tick(horse, floor("minecraft:slime", BlockContactBehavior.SLIME), Vec3d.ZERO);

        assertEquals(0.2D * HONEY_FRICTION, next(result).velocity().z(), 1.0E-6D);
    }

    @Test
    public void playerOnFootIsStillSlowedOnHoneyAndSlime() {
        for (BedrockWorldSnapshot floor : List.of(
                floor("minecraft:honey_block", BlockContactBehavior.HONEY),
                floor("minecraft:slime", BlockContactBehavior.SLIME))) {
            BedrockMovementState player = BedrockMovementState.fromPhysicalFeet(
                    new Vec3d(0.5D, 1.0D, 0.5D),
                    new Vec3d(0.0D, RESTING_Y, 0.2D),
                    BedrockInputFrame.idle(1L),
                    BedrockCollisionFlags.ON_GROUND);

            BedrockMovementResult result = tick(player, floor, null);

            float slowdown = 0.4F + 0.2F * (float) -RESTING_Y;
            assertEquals(0.2D * slowdown * HONEY_FRICTION, next(result).velocity().z(), 1.0E-6D);
        }
    }

    @Test
    public void acceptedPositionKeepsTheMountSpeedToo() {
        BedrockMovementState horse = horse(1.0D, new Vec3d(0.0D, RESTING_Y, 0.2D), true);
        BedrockMovementResult result =
                tick(horse, floor("minecraft:honey_block", BlockContactBehavior.HONEY), Vec3d.ZERO);
        var derived = BedrockForwardTick.finish(result, result.predictedState());

        assertEquals(1, derived.size());
        assertEquals(0.2D * HONEY_FRICTION, derived.getFirst().velocity().z(), 1.0E-6D);
    }

    private static double moveZ(BedrockMovementResult result) {
        return result.predictedPosition().z()
                - result.previousState().physicalFeetPosition().z();
    }

    private static BedrockMovementState horse(double y, Vec3d velocity, boolean onGround) {
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0.5D, y, 0.5D),
                        velocity,
                        BedrockInputFrame.idle(1L),
                        onGround ? BedrockCollisionFlags.ON_GROUND : BedrockCollisionFlags.AIR)
                .withHorse(new BedrockHorseState(null, false, 0, false, false, 0, -1, false, 0));
    }

    private static BedrockMovementResult tick(BedrockMovementState state, BedrockWorldSnapshot world, Vec3d control) {
        BedrockInputFrame frame = BedrockInputFrame.idle(state.clientTick() + 1L);
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        world,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        control == null ? Vec3d.ZERO : control))
                .getFirst()
                .movementResult();
    }

    private static BedrockMovementState next(BedrockMovementResult result) {
        return BedrockForwardTick.finish(result, result.predictedState()).getFirst();
    }

    private static BedrockWorldSnapshot floor(String identifier, BlockContactBehavior behavior) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 4; z++) {
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 0, z),
                        identifier,
                        identifier,
                        List.of(new WorldCollisionBox(x, 0.0D, z, x + 1.0D, 1.0D, z + 1.0D)),
                        Set.of(behavior)));
            }
        }
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
    }
}
