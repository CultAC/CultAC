package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

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

public final class BedrockSoulSpeedTest {
    private static final Vec3d ON_SOUL_SAND = new Vec3d(2.5D, 0.875D, 2.5D);

    @Test
    public void soulSpeedBootsWalkSoulSandWithTheGroundControl() {
        // With soul speed boots the client walks soul sand at its movement speed: 0.098 on the first tick.
        BedrockMovementResult result = walk(new EquipmentState(0, 3, 0, false, false));

        assertEquals(0.098D, horizontalMove(result), 1.0E-6D);
    }

    @Test
    public void withoutSoulSpeedSoulSandSlowsTheGroundControl() {
        BedrockMovementResult result = walk(EquipmentState.NONE);

        assertEquals(0.098D / (1.225D * 1.225D * 1.225D), horizontalMove(result), 1.0E-6D);
    }

    private static BedrockMovementResult walk(EquipmentState equipment) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 0, z),
                        "minecraft:soul_sand",
                        "minecraft:soul_sand",
                        List.of(new WorldCollisionBox(x, 0.0D, z, x + 1.0D, 0.875D, z + 1.0D))));
            }
        }
        BedrockWorldSnapshot world = BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                equipment,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
        BedrockMovementState standing = BedrockMovementState.fromPhysicalFeet(
                ON_SOUL_SAND, Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND);
        BedrockInputFrame forward = new BedrockInputFrame(1L, 0.0F, 0.0F, false, false, false, Set.of("UP"));
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        standing,
                        forward,
                        forward.intent(),
                        world,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        new Vec3d(0.0D, 0.0D, 1.0D)))
                .getFirst()
                .movementResult();
    }

    private static double horizontalMove(BedrockMovementResult result) {
        Vec3d move = result.predictedPosition().subtract(ON_SOUL_SAND);
        return Math.hypot(move.x(), move.z());
    }
}
