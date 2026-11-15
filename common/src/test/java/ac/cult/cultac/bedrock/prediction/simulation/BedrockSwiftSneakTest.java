package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

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

public final class BedrockSwiftSneakTest {
    private static final Set<String> START = Set.of("UP", "SNEAKING", "START_SNEAKING", "SNEAK_DOWN");
    private static final Set<String> HELD = Set.of("UP", "SNEAKING", "SNEAK_DOWN");

    @Test
    public void swiftSneakScalesTheInputFromTheSecondTickAfterSneakingStarts() {
        // Sneaking off with Swift Sneak III the client walks 0.0294 and 0.04546 at the plain 0.3 scale, then
        // 0.09832 = 0.04546 * 0.546 + 0.1 * 0.75 * 0.98 once its sneaking system has written 0.75.
        BedrockWorldSnapshot world = world(new EquipmentState(0, 0, 3, false, false));
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 1.0D, 0.5D),
                Vec3d.ZERO,
                BedrockInputFrame.idle(0L),
                BedrockCollisionFlags.ON_GROUND);

        BedrockMovementState first = forward(state, world, 1L, START);
        BedrockMovementState second = forward(first, world, 2L, HELD);
        BedrockMovementState third = forward(second, world, 3L, HELD);

        assertEquals(0.0294D, first.physicalFeetPosition().z() - 0.5D, 1.0E-6D);
        assertEquals(0.04546D, second.physicalFeetPosition().z() - first.physicalFeetPosition().z(), 1.0E-5D);
        assertEquals(0.09832D, third.physicalFeetPosition().z() - second.physicalFeetPosition().z(), 1.0E-5D);
    }

    @Test
    public void sneakingAgainKeepsTheScaleTheClientLastWrote() {
        BedrockWorldSnapshot world = world(new EquipmentState(0, 0, 3, false, false));
        BedrockMovementState rested = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0.5D, 1.0D, 0.5D),
                        Vec3d.ZERO,
                        BedrockInputFrame.idle(0L),
                        BedrockCollisionFlags.ON_GROUND)
                .withSneakMoveScale(0.75F);

        BedrockMovementState first = forward(rested, world, 1L, START);

        assertEquals(0.0735D, first.physicalFeetPosition().z() - 0.5D, 1.0E-6D);
    }

    private static BedrockMovementState forward(
            BedrockMovementState state, BedrockWorldSnapshot world, long tick, Set<String> flags) {
        BedrockInputFrame frame = new BedrockInputFrame(tick, 0.0F, 0.0F, false, true, false, flags);
        var movement = BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        world,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        new Vec3d(0.0D, 0.0D, 1.0D)))
                .getFirst()
                .movementResult();
        return BedrockForwardTick.finish(movement, movement.predictedState());
    }

    private static BedrockWorldSnapshot world(EquipmentState equipment) {
        PlacedBlockCollision floor = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(-5.0D, 0.0D, -5.0D, 5.0D, 1.0D, 5.0D)));
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(floor))),
                equipment,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
    }
}
