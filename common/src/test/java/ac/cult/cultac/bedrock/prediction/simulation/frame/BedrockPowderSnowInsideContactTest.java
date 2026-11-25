package ac.cult.cultac.bedrock.prediction.simulation.frame;

import static org.junit.Assert.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import java.util.List;
import org.junit.Test;

public final class BedrockPowderSnowInsideContactTest {
    @Test
    public void landingOnTheFallShapeIsAlreadyInsideThePowderSnow() {
        // A fall onto powder snow stops on its 0.9 high shape; on the next tick the client is already slowed by it,
        // moving 0.1176 = 0.0784 * 1.5 down.
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        int powderSnow = DataTables.defaults().registry().block("minecraft:powder_snow").defaultState();
        var falling = BedrockCollisionShapeQuery.actor(
                new WorldCollisionBox(0.2D, 1.5D, 0.2D, 0.8D, 3.3D, 0.8D), false, false, 3.0F);
        PlacedBlockCollision block = builder.block(new BlockPosition(0, 0, 0), powderSnow, falling);

        assertEquals(0.9D, block.collisionBoxes().getFirst().maxY(), 1.0E-6D);
        assertEquals(
                BlockMovementSlowdownState.POWDER_SNOW,
                BedrockBlockMovementSlowdownResolver.fromBlockWorld(
                        0.6D, 1.8D, new Vec3d(0.5D, 0.9D, 0.5D), new BlockCollisionWorld(List.of(block)), false));
    }
}
