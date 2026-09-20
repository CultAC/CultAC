package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedrockEntityLiquidFlowTest {
    @Test
    public void entityGeometryDoesNotBecomeAFluidNeighborMaterial() {
        for (String identifier : List.of("minecraft:boat_entity_collision",
                "minecraft:minecart_entity_collision", "minecraft:hard_entity_collision")) {
            var water = PlacedBlockCollision.manual(new BlockPosition(0, 0, 0),
                    "minecraft:water[level=8]", "minecraft:water", List.of());
            var box = new WorldCollisionBox(1, 0, 0, 2, 1, 1);
            var entity = PlacedBlockCollision.manual(new BlockPosition(1, 0, 0),
                    identifier, identifier, List.of(box));
            var world = new BlockCollisionWorld(List.of(water, entity));
            assertTrue(world.blockAt(entity.position()).isEmpty());
            assertEquals(List.of(box), world.collisionBoxes());
            assertSame(entity, world.collisions().getFirst().block().orElseThrow());
            assertEquals(BedrockLiquidFlowVector.NONE,
                    BedrockLiquidFlowResolver.flowVector(water, BedrockLiquidKind.WATER, world.blocksByPosition()));

            var stone = PlacedBlockCollision.manual(entity.position(),
                    "minecraft:stone", "minecraft:stone", List.of());
            var overlapping = new BlockCollisionWorld(List.of(water, entity, stone));
            assertSame(stone, overlapping.blockAt(stone.position()).orElseThrow());
            assertEquals(-1.0D, BedrockLiquidFlowResolver.flowVector(
                    water, BedrockLiquidKind.WATER, overlapping.blocksByPosition()).y(), 0.0D);
        }
    }
}
