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
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The client multiplies its velocity by a float friction: the block friction times the air friction, both floats.
 * Captured walk: a player holding forward and jump from rest at local x 8.5 bunny-hops east over two carpets until its
 * head meets a stone hanging at y122; the box stops on the stone's west face, then falls below it and moves on. The
 * client's positions and velocities are exact float results of that friction, so the engine must land on them, or the
 * position the client reports moves a box that stopped on the face one float step into the stone.
 */
public final class BedrockFloatFrictionTest {
    private static final BedrockCoordinateFrame FRAME = new BedrockCoordinateFrame(20112, 20288, 1);
    private static final int JUMP_TICKS = 59;
    private static final int WALL_TICK = 57;

    @Test
    public void jumpingWalkKeepsTheClientsFloatVelocityAndPosition() {
        List<BedrockMovementResult> ticks = walk(JUMP_TICKS);
        // The take-off: 0.098000005 of ground input times the 0.6 * 0.91 float friction.
        assertEquals(0.05350801F, ticks.get(0).predictedVelocity().x(), 0.0D);
        assertEquals(8.598F, localX(ticks.get(0)), 0.0D);
        // Mid-air on the third hop, where a double friction first rounds the box one float step short.
        assertEquals(0.16461997F, ticks.get(28).predictedVelocity().x(), 0.0D);
        assertEquals(13.107399F, localX(ticks.get(28)), 0.0D);
        // The head stops the box on the stone's west face.
        assertEquals(18.7F, localX(ticks.get(WALL_TICK)), 0.0D);
        assertEquals(
                19.0F,
                FRAME.localX(
                        ticks.get(WALL_TICK).predictedState().collisionBox().maxX()),
                0.0D);
        assertEquals(0.0D, ticks.get(WALL_TICK).predictedVelocity().x(), 0.0D);
    }

    @Test
    public void theFaceTheHeadStoppedOnDoesNotStopTheNextMove() {
        List<BedrockMovementResult> ticks = walk(WALL_TICK + 1);
        BedrockMovementResult wall = ticks.get(WALL_TICK);
        // The engine takes the position the client reports at the wall.
        BedrockMovementState reported = BedrockForwardTick.finish(wall, wall.predictedState())
                .withRelocatedPosition(
                        new Vec3d(
                                FRAME.originX() + (double) 18.7F,
                                wall.predictedPosition().y(),
                                20314.5D),
                        wall.predictedState().lastPhysicalDisplacementSquared());
        BedrockMovementResult next = tick(reported, snapshot(), WALL_TICK + 2L);
        // The fall takes the head below the stone, so the move keeps its X velocity.
        assertEquals(18.7196F, localX(next), 0.0D);
        assertEquals(0.017836F, next.predictedVelocity().x(), 0.0D);
    }

    private static float localX(BedrockMovementResult result) {
        return FRAME.localX(result.predictedPosition().x());
    }

    private static List<BedrockMovementResult> walk(int count) {
        BedrockWorldSnapshot snapshot = snapshot();
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(FRAME.originX() + 8.5D, 120.0D, 20314.5D),
                new Vec3d(0.0D, -0.0784D, 0.0D),
                new BedrockInputFrame(0L, -90.0F, 0.0F, false, false, false, Set.of()),
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR,
                FRAME);
        List<BedrockMovementResult> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BedrockMovementResult result = tick(state, snapshot, i + 1L);
            ticks.add(result);
            state = BedrockForwardTick.finish(result, result.predictedState());
        }
        return ticks;
    }

    private static BedrockMovementResult tick(BedrockMovementState state, BedrockWorldSnapshot snapshot, long tick) {
        var input = new BedrockInputFrame(tick, -90.0F, 0.0F, true, false, false, Set.of("UP", "JUMPING"));
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        state,
                        input,
                        input.intent(),
                        snapshot,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        new Vec3d(0.0D, 0.0D, 1.0D)))
                .getFirst()
                .movementResult();
    }

    private static BedrockWorldSnapshot snapshot() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 20118; x <= 20134; x++) {
            for (int z = 20312; z <= 20316; z++) blocks.add(block(x, 119, z, 1.0D));
        }
        blocks.add(block(20121, 120, 20314, 0.0625D));
        blocks.add(block(20123, 120, 20314, 0.0625D));
        blocks.add(block(20131, 122, 20314, 1.0D));
        var world = new BlockCollisionWorld(blocks).withCoordinateFrame(FRAME);
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
    }

    private static PlacedBlockCollision block(int x, int y, int z, double height) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(x, y, z, x + 1, y + height, z + 1)));
    }
}
