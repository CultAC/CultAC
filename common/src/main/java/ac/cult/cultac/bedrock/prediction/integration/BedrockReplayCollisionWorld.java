package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;

/**
 * Client uses collision snapshots for replay logic, outside the snapshot reads live world
 */
final class BedrockReplayCollisionWorld {
    private BedrockReplayCollisionWorld() {}

    static BlockCollisionWorld reuse(
            BlockCollisionWorld recorded, BlockCollisionWorld live, WorldCollisionBox fetched) {
        var blocks = new ArrayList<PlacedBlockCollision>(recorded.blocks().size());
        var solidActors = new ArrayList<PlacedBlockCollision>();
        var retained = new HashSet<PlacedBlockCollision>();
        for (PlacedBlockCollision block : recorded.blocks()) {
            if (block.isEntityCollision()) {
                solidActors.add(block);
            } else if (block.collisionBoxes().isEmpty()
                    || block.collisionBoxes().stream().anyMatch(fetched::intersects)) {
                blocks.add(block);
                retained.add(block);
            }
        }
        for (PlacedBlockCollision block : live.blocks()) {
            if (block.isEntityCollision() || retained.contains(block)) continue;
            if (block.collisionBoxes().stream().anyMatch(box -> !contains(fetched, box))) blocks.add(block);
        }
        // Blocks keep the order the client gathers them in, solid actors after them.
        blocks.sort(Comparator.comparing(PlacedBlockCollision::position, BedrockSolidBlockSampler.GATHER_ORDER));
        blocks.addAll(solidActors);
        return new BlockCollisionWorld(blocks, recorded.coordinateFrame());
    }

    /**
     * The client's replay of a corrected tick still collides with the solid actors the tick met, but none of them
     * raises the move's depenetration limit, so it is not pushed out of one it overlaps.
     */
    static BedrockWorldSnapshot withReplayedActors(BedrockWorldSnapshot snapshot) {
        var recorded = snapshot.blockCollisionWorld();
        if (recorded.blocks().stream().noneMatch(BedrockDepenetrationLimit::raisesLimit)) return snapshot;
        var blocks = recorded.blocks().stream()
                .map(BedrockDepenetrationLimit::replayed)
                .toList();
        return snapshot.withBlockCollisionWorld(new BlockCollisionWorld(blocks, recorded.coordinateFrame()));
    }

    private static boolean contains(WorldCollisionBox outer, WorldCollisionBox inner) {
        return inner.minX() >= outer.minX()
                && inner.maxX() <= outer.maxX()
                && inner.minY() >= outer.minY()
                && inner.maxY() <= outer.maxY()
                && inner.minZ() >= outer.minZ()
                && inner.maxZ() <= outer.maxZ();
    }
}
