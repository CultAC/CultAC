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

public final class BedrockDepthStriderTest {
    private static final Vec3d ON_POOL_FLOOR = new Vec3d(2.5D, 1.0D, 2.5D);

    @Test
    public void depthStriderThreeWalksThePoolFloorAtLandSpeed() {
        // On the floor of a pool with Depth Strider III the client walks 0.098 on its first tick and keeps
        // 0.098 * 0.546 of it: the land speed and friction, not the water ones.
        BedrockMovementResult result = walk(new EquipmentState(3, 0, 0, false, false));

        assertEquals(0.098D, horizontalMove(result), 1.0E-6D);
        assertEquals(0.098D * 0.546D, horizontal(result.predictedVelocity()), 1.0E-6D);
    }

    @Test
    public void leavingThePoolFloorDragsWithTheGroundTheMoveLeft() {
        // Jumping off the floor the client still walks 0.098 from the floor it started on, then drags with
        // half the depth strider: 0.546 + (0.8 - 0.546) / 2 = 0.673 once the move has left the floor.
        BedrockMovementResult result =
                walk(new EquipmentState(3, 0, 0, false, false), Set.of("UP", "JUMPING"), true);

        assertEquals(0.098D, horizontalMove(result), 1.0E-6D);
        assertEquals(0.098D * 0.673D, horizontal(result.predictedVelocity()), 1.0E-6D);
    }

    @Test
    public void withoutDepthStriderThePoolFloorKeepsTheWaterSpeed() {
        BedrockMovementResult result = walk(EquipmentState.NONE);

        assertEquals(0.0196D, horizontalMove(result), 1.0E-6D);
        assertEquals(0.0196D * 0.8D, horizontal(result.predictedVelocity()), 1.0E-6D);
    }

    private static BedrockMovementResult walk(EquipmentState equipment) {
        return walk(equipment, Set.of("UP"), false);
    }

    private static BedrockMovementResult walk(EquipmentState equipment, Set<String> inputData, boolean jumping) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 6.0D, 1.0D, 6.0D))));
        for (int x = 0; x < 6; x++) {
            for (int y = 1; y < 4; y++) {
                for (int z = 0; z < 6; z++) {
                    blocks.add(PlacedBlockCollision.manual(
                            new BlockPosition(x, y, z), "minecraft:water[level=0]", "minecraft:water", List.of()));
                }
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
                ON_POOL_FLOOR, Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND);
        BedrockInputFrame forward = new BedrockInputFrame(1L, 0.0F, 0.0F, jumping, false, false, inputData);
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
        Vec3d move = result.predictedPosition().subtract(ON_POOL_FLOOR);
        return Math.hypot(move.x(), move.z());
    }

    private static double horizontal(Vec3d velocity) {
        return Math.hypot(velocity.x(), velocity.z());
    }
}
