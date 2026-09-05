package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertFalse;

public final class BedrockLiquidClimbOutMovementTest {
    @Test
    public void climbOutProbeTreatsLiquidAsBlockedSpace() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(229, 84, -72),
                "minecraft:bubble_column[drag=false]",
                "minecraft:bubble_column",
                Map.of("drag_down", false),
                List.of())));

        boolean applies = BedrockLiquidClimbOutMovement.applies(
            true,
            new Vec3d(229.52525329589844D, 82.9556884765625D, -71.30000305175781D),
            new Vec3d(229.5301055908203D, 83.33866882324219D, -71.30000305175781D),
            new Vec3d(0.0043617003D, 0.3737434D, 0.0D),
            BedrockCollisionFlags.AIR.withHorizontalBlockContact(true),
            world,
            PlayerDimensionsState.DEFAULT);

        assertFalse(applies);
    }
}
