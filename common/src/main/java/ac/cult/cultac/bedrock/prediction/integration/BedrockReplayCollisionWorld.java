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

    /**
     * Replayed block shapes may come from history, but solid actors are the ones the replay meets now, in place of
     * those the recorded tick met.
     */
    static BedrockWorldSnapshot withNearbyActors(
            BedrockWorldSnapshot snapshot, List<HardCollidingEntityCollision> nearby) {
        var recorded = snapshot.blockCollisionWorld();
        boolean recordedActors = false;
        for (var block : recorded.blocks()) {
            if (block.isEntityCollision()) {
                recordedActors = true;
                break;
            }
        }
        if (!recordedActors && nearby.isEmpty()) return snapshot;
        var world = recorded;
        if (recordedActors) {
            var blocks = new ArrayList<PlacedBlockCollision>();
            for (var block : recorded.blocks()) if (!block.isEntityCollision()) blocks.add(block);
            world = new BlockCollisionWorld(blocks, recorded.coordinateFrame());
        }
        return snapshot.withBlockCollisionWorld(BedrockWorldSnapshotBuilder.withHardCollidingEntities(world, nearby));
    }

    /**
     * A replay keeps each solid actor's depenetration limit. An observation from before the replay cannot select a
     * replayed position through the live position buffer, so the actors lend it none.
     */
    static BedrockWorldSnapshot withReplayedActors(BedrockWorldSnapshot snapshot) {
        var recorded = snapshot.blockCollisionWorld();
        ArrayList<PlacedBlockCollision> blocks = null;
        for (int i = 0; i < recorded.blocks().size(); i++) {
            var block = recorded.blocks().get(i);
            var replayed = BedrockDepenetrationLimit.replayed(block);
            if (replayed == block) continue;
            if (blocks == null) blocks = new ArrayList<>(recorded.blocks());
            blocks.set(i, replayed);
        }
        if (blocks == null) return snapshot;
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
