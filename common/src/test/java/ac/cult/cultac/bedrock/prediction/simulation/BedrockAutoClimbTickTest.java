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
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The climb a wall hit starts, replayed from a client falling against a wall beside a scaffolding column. */
public final class BedrockAutoClimbTickTest {
    private static final Vec3d FEET = new Vec3d(352.6274719238281D, 83.0244140625D, -64.62248992919922D);
    private static final Vec3d VELOCITY = new Vec3d(0.15149036049842834D, -0.2276884764432907D, -0.3022023141384125D);

    // Falling at -0.227688 a tick into a stone block at x 353, the client stops with its box flush
    // against it and one column of that box reaching the scaffolding at x 353, z -66. It reports
    // -0.301535 afterwards, an ordinary fall: the block the centre of its feet is in is air.
    @Test
    public void aWallHitBesideScaffoldingKeepsFalling() {
        List<PlacedBlockCollision> blocks = wall();
        for (int y = 82; y <= 84; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(353, y, -66),
                    "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                    "minecraft:scaffolding",
                    List.of(),
                    Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING)));
        }
        var result = tick(blocks);
        assertTrue(result.predictedState().collisionFlags().horizontalCollision());
        assertTrue(result.predictedState().climbableContact().scaffolding());
        assertFalse(result.predictedState().climbableContact().ladder());
        assertEquals(-0.227688D, moved(result).y(), 1.0E-5D);
        assertEquals(-0.301535D, result.predictedVelocity().y(), 1.0E-5D);
    }

    // The same wall hit with the centre of the feet in a ladder climbs at 0.2 a tick.
    @Test
    public void aWallHitInsideALadderClimbs() {
        List<PlacedBlockCollision> blocks = wall();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(352, 82, -65),
                "minecraft:ladder[facing=west,waterlogged=false]",
                "minecraft:ladder",
                List.of(),
                Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.LADDER)));
        var result = tick(blocks);
        assertTrue(result.predictedState().collisionFlags().horizontalCollision());
        assertTrue(result.predictedState().climbableContact().ladder());
        assertEquals(0.2D, result.predictedVelocity().y(), 1.0E-6D);
    }

    private static Vec3d moved(BedrockMovementResult result) {
        return result.predictedPosition().subtract(result.previousState().physicalFeetPosition());
    }

    private static BedrockMovementResult tick(List<PlacedBlockCollision> blocks) {
        BedrockMovementContext world = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var state = BedrockMovementState.fromPhysicalFeet(
                FEET, VELOCITY, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        BedrockInputFrame frame = BedrockInputFrame.idle(1L);
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(world), state, frame);
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
                        Vec3d.ZERO))
                .getFirst()
                .movementResult();
    }

    /** A stone floor and the stone block at x 353 the player runs into. */
    private static List<PlacedBlockCollision> wall() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(352, 81, -65),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(348.0D, 81.0D, -69.0D, 357.0D, 82.0D, -61.0D))));
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(353, 83, -65),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(353.0D, 83.0D, -65.0D, 354.0D, 84.0D, -64.0D))));
        return blocks;
    }
}
