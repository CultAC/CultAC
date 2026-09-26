package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Client uses collision snapshots for replay logic, outside the snapshot reads live world
 */
final class BedrockReplayCollisionWorld {
    private BedrockReplayCollisionWorld() { }

    static BlockCollisionWorld reuse(BlockCollisionWorld recorded, BlockCollisionWorld live, WorldCollisionBox fetched) {
        var blocks = new ArrayList<PlacedBlockCollision>(recorded.blocks().size());
        var retained = new HashSet<PlacedBlockCollision>();
        for (PlacedBlockCollision block : recorded.blocks()) {
            if (block.collisionBoxes().isEmpty() || block.isEntityCollision()
                    || block.collisionBoxes().stream().anyMatch(fetched::intersects)) {
                blocks.add(block);
                retained.add(block);
            }
        }
        for (PlacedBlockCollision block : live.blocks()) {
            if (block.isEntityCollision() || retained.contains(block)) continue;
            if (block.collisionBoxes().stream().anyMatch(box -> !contains(fetched, box))) blocks.add(block);
        }
        return new BlockCollisionWorld(blocks, recorded.coordinateFrame());
    }

    private static boolean contains(WorldCollisionBox outer, WorldCollisionBox inner) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minY() >= outer.minY() && inner.maxY() <= outer.maxY()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }
}
