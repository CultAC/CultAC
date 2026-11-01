package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.List;
import java.util.Optional;

/**
 * The 515d08404 collider gathering as the equivalence oracle for the optimized path, verbatim except that
 * {@code IS_FOURTEEN && section.isEmpty()} reads {@code section.isEmpty()} because {@code IS_FOURTEEN} is constant
 * {@code true}.
 */
public final class ReferenceCollisionGathering {
    private static final double COLLISION_EPSILON = 1.0E-7;

    private ReferenceCollisionGathering() {}

    public static boolean getCollisionBoxes(
            CultPlayer player,
            SimpleCollisionBox wantedBB,
            List<SimpleCollisionBox> listOfBlocks,
            boolean onlyCheckCollide,
            double entityBottom,
            JavaCollisionState actor) {
        boolean modernJava = !player.isBedrockMovement();
        if (modernJava)
            Box.validateBounds(
                    wantedBB.minX, wantedBB.minY, wantedBB.minZ, wantedBB.maxX, wantedBB.maxY, wantedBB.maxZ);
        VoxelShape queryShape = null;

        boolean collided = Collisions.addWorldBorder(player, wantedBB, listOfBlocks, onlyCheckCollide);
        if (onlyCheckCollide && collided) return true;

        int minBlockX = (int) Math.floor(wantedBB.minX - COLLISION_EPSILON) - 1;
        int maxBlockX = (int) Math.floor(wantedBB.maxX + COLLISION_EPSILON) + 1;
        int minBlockY = (int) Math.floor(wantedBB.minY - COLLISION_EPSILON) - 1;
        int maxBlockY = (int) Math.floor(wantedBB.maxY + COLLISION_EPSILON) + 1;
        int minBlockZ = (int) Math.floor(wantedBB.minZ - COLLISION_EPSILON) - 1;
        int maxBlockZ = (int) Math.floor(wantedBB.maxZ + COLLISION_EPSILON) + 1;

        final int minSection = player.compensatedWorld.getMinHeight() >> 4;
        final int minBlock = minSection << 4;
        final int maxBlock = player.compensatedWorld.getMaxHeight() - 1;

        int minChunkX = minBlockX >> 4;
        int maxChunkX = maxBlockX >> 4;

        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = maxBlockZ >> 4;

        int minYIterate = Math.max(minBlock, minBlockY);
        int maxYIterate = Math.min(maxBlock, maxBlockY);

        for (int currChunkZ = minChunkZ; currChunkZ <= maxChunkZ; ++currChunkZ) {
            int minZ = currChunkZ == minChunkZ ? minBlockZ & 15 : 0;
            int maxZ = currChunkZ == maxChunkZ ? maxBlockZ & 15 : 15;

            for (int currChunkX = minChunkX; currChunkX <= maxChunkX; ++currChunkX) {
                int minX = currChunkX == minChunkX ? minBlockX & 15 : 0;
                int maxX = currChunkX == maxChunkX ? maxBlockX & 15 : 15;

                int chunkXGlobalPos = currChunkX << 4;
                int chunkZGlobalPos = currChunkZ << 4;

                CachedChunk chunk = player.compensatedWorld.getChunk(currChunkX, currChunkZ);
                if (chunk == null) continue;

                for (int y = minYIterate; y <= maxYIterate; ++y) {
                    int sectionIndex = (y >> 4) - minSection;

                    CachedSection section = chunk.getSection(sectionIndex);

                    if (section == null || section.isEmpty()) {
                        y = (y & ~(15)) + 15;
                        continue;
                    }

                    for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                        for (int currX = minX; currX <= maxX; ++currX) {
                            int x = currX | chunkXGlobalPos;
                            int z = currZ | chunkZGlobalPos;

                            int data = section.getStateId(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));
                            var facts = DataTables.defaults().registry().facts(data);
                            if (facts.has(StateFacts.AIR)) continue;
                            int edgeCount = ((x == minBlockX || x == maxBlockX) ? 1 : 0)
                                    + ((y == minBlockY || y == maxBlockY) ? 1 : 0)
                                    + ((z == minBlockZ || z == maxBlockZ) ? 1 : 0);

                            if (edgeCount != 3
                                    && (edgeCount != 1 || facts.has(StateFacts.LARGE_COLLISION))
                                    && (edgeCount != 2
                                            || BlockIds.is(
                                                    data,
                                                    modernJava ? BlockIds.MOVING_PISTON : BlockIds.PISTON_HEAD))) {
                                if (modernJava
                                        && onlyCheckCollide
                                        && hasStaticFullMovement(player, data, x, y, z, entityBottom)) {
                                    if (wantedBB.minX < x + 1.0
                                            && wantedBB.maxX > x
                                            && wantedBB.minY < y + 1.0
                                            && wantedBB.maxY > y
                                            && wantedBB.minZ < z + 1.0
                                            && wantedBB.maxZ > z) return true;
                                    continue;
                                }
                                CollisionBox collisionBox = movement(player, data, x, y, z, entityBottom, actor);
                                if (collisionBox.isNull()) continue;
                                if (modernJava
                                        && queryShape == null
                                        && collisionBox instanceof VoxelShapeCollisionBox voxel
                                        && !voxel.usesStrictFullBlockIntersection()) {
                                    queryShape = Shapes.create(
                                            wantedBB.minX,
                                            wantedBB.minY,
                                            wantedBB.minZ,
                                            wantedBB.maxX,
                                            wantedBB.maxY,
                                            wantedBB.maxZ);
                                }
                                if (modernJava
                                        && !(collisionBox instanceof VoxelShapeCollisionBox voxel
                                                ? voxel.intersectsQuery(wantedBB, queryShape)
                                                : collisionBox.isIntersected(wantedBB))) continue;
                                if (!onlyCheckCollide) {
                                    collisionBox.downCastForMovement(listOfBlocks);
                                } else if (modernJava || collisionBox.isIntersected(wantedBB)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    public static CollisionBox movement(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        CollisionBox latestJava = getCollisionBox(player, state, x, y, z, entityBottom, actor);
        return selectIfDifferent(latestJava, movementAlternative(player, state, x, y, z, entityBottom));
    }

    public static boolean hasStaticFullMovement(
            CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        return player.compensatedWorld.geometry().staticCollision(state) == Shapes.block()
                && movementAlternative(player, state, x, y, z, entityBottom).isEmpty();
    }

    private static CollisionBox getCollisionBox(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return NoCollisionBox.INSTANCE;
        }

        VoxelShape shape = NativeBlockCollisionHelper.getCollisionShape(player, state, x, y, z, entityBottom, actor);
        boolean voxelMovement = !player.isBedrockMovement();
        if (voxelMovement && !shape.isEmpty())
            return new VoxelShapeCollisionBox(shape, shape == Shapes.block()).offset(x, y, z);
        return NativeBlockCollisionHelper.fromShape(shape, x, y, z);
    }

    private static Optional<CollisionBox> movementAlternative(
            CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        Optional<CollisionBox> bedrock = BedrockClientBlockShapeMappings.movement(player, state, x, y, z);
        if (bedrock.isPresent()) {
            return bedrock;
        }
        Optional<CollisionBox> viaReplacement =
                ViaClientBlockShapeMappings.movement(player, state, x, y, z, entityBottom);
        if (viaReplacement.isPresent()) {
            return viaReplacement;
        }
        return VersionedJavaBlockShapes.movement(player, state, x, y, z);
    }

    private static CollisionBox selectIfDifferent(CollisionBox latestJava, Optional<CollisionBox> alternative) {
        if (alternative.isEmpty() || ClientBlockShapes.sameShape(latestJava, alternative.get())) {
            return latestJava;
        }
        return alternative.get();
    }
}
