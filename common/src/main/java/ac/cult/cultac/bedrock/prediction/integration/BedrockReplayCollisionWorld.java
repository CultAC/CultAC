package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;

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

    static BedrockWorldSnapshot withNearbyActors(
            BedrockWorldSnapshot snapshot, List<HardCollidingEntityCollision> nearby) {
        var recorded = snapshot.blockCollisionWorld();
        var blocks = recorded.blocks().stream().filter(block -> !block.isEntityCollision()).toList();
        return snapshot.withBlockCollisionWorld(BedrockWorldSnapshotBuilder.withHardCollidingEntities(
                new BlockCollisionWorld(blocks, recorded.coordinateFrame()), nearby));
    }

    /**
     * Replayed block shapes may come from history, but solid actors come from the nearby set at receipt, refreshed
     * to active interpolation targets. They keep the depenetration limits they give an ordinary move.
     */
    static BedrockWorldSnapshot withReplayedActors(BedrockWorldSnapshot snapshot, BedrockWorldSnapshot live) {
        var recorded = snapshot.blockCollisionWorld();
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (var block : recorded.blocks()) if (!block.isEntityCollision()) blocks.add(block);
        for (var block : live.blockCollisionWorld().blocks())
            if (block.isEntityCollision()) blocks.add(BedrockDepenetrationLimit.replayed(block));
        if (blocks.equals(recorded.blocks())) return snapshot;
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
