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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import org.junit.Test;

public final class BedrockCarpetBounceTest {
    @Test
    public void carpetOverSlimeReboundsFromTheCarpetTop() {
        // Captured fall onto white carpet over slime: -1.024809 a tick with 0.37796 left to the carpet top. The client
        // reported 0.954423: the rebound corrected by that fall, not by the 0.44046 left to the slime under it.
        BedrockMovementState falling = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 1.0625D + 0.37796D, 0.5D),
                new Vec3d(0.0D, -1.024809D, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);
        PlacedBlockCollision slime = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:slime_block",
                "minecraft:slime",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)));
        PlacedBlockCollision carpet = PlacedBlockCollision.manual(
                new BlockPosition(0, 1, 0),
                "minecraft:white_carpet",
                "minecraft:carpet",
                List.of(new WorldCollisionBox(0.0D, 1.0D, 0.0D, 1.0D, 1.0625D, 1.0D)));
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(slime, carpet))),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);

        BedrockMovementResult result = BedrockSimulation.move(
                falling,
                BedrockInputFrame.idle(2L),
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);

        assertEquals(1.0625D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(0.954423D, result.predictedVelocity().y(), 1.0E-5D);
    }
}
