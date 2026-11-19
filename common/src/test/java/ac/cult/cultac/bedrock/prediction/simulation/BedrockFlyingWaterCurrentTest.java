package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
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
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class BedrockFlyingWaterCurrentTest {
    private static final Vec3d FEET = new Vec3d(0.5D, 70.0D, 0.5D);

    @Test
    public void walkingPlayerIsPushedByTheCurrent() {
        var velocity = tick(false).movementResult().predictedVelocity();

        assertEquals(0.0D, velocity.x(), 1.0E-9D);
        assertEquals(-0.014D * 0.8D, velocity.z(), 1.0E-5D);
    }

    // Define_Outside, protocol 2225, flag-29-2026-11-12T01-05-46.774530937Z: flying forward
    // through flowing water moved exactly the current's 0.014 push further than predicted.
    @Test
    public void flyingPlayerIsNotPushedByTheCurrent() {
        var velocity = tick(true).movementResult().predictedVelocity();

        assertEquals(0.0D, velocity.x(), 1.0E-9D);
        assertEquals(0.0D, velocity.z(), 1.0E-9D);
    }

    private static BedrockSimulation.Candidate tick(boolean flying) {
        var frame = new BedrockInputFrame(1L, 0.0F, 0.0F, false, false, false, Set.of("ACTOR_POSE_SNAPSHOT"));
        var state = BedrockMovementState.fromPhysicalFeet(
                        FEET, Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR)
                .withWasInWaterFlag(true)
                .withWaterTravelFlag(true);
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (int y = 68; y <= 73; y++) {
            // Level 1 beside level 2 to its north: the water flows towards negative Z.
            blocks.add(water(0, y, 0, 1));
            blocks.add(water(0, y, -1, 2));
        }
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(
                        Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks, BedrockCoordinateFrame.IDENTITY)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(false, true, flying, false, 0.05D, false, false, false, false, 0.35D, 0L),
                PlayerDimensionsState.DEFAULT);
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
                        Vec3d.ZERO))
                .getFirst();
    }

    private static PlacedBlockCollision water(int x, int y, int z, int level) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z), "minecraft:water[level=" + level + "]", "minecraft:water", List.of());
    }
}
