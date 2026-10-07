package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.HitboxData;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.HitData;
import ac.cult.cultac.utils.data.Pair;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.jetbrains.annotations.Nullable;

public class TraverseBlocks {
    // Copied from MCP...
    // Returns null if there isn't anything.
    //
    // I do have to admit that I'm starting to like bifunctions/new java 8 things more than I originally did.
    // although I still don't understand Mojang's obsession with streams in some of the hottest methods... that kills
    // performance
    public static HitData traverseBlocks(
            CultPlayer player, Vec3 start, Vec3 end, BiFunction<Integer, BlockPos, HitData> predicate) {
        // I guess go back by the collision epsilon?
        double endX = CultMath.lerp(-1.0E-7D, end.x, start.x);
        double endY = CultMath.lerp(-1.0E-7D, end.y, start.y);
        double endZ = CultMath.lerp(-1.0E-7D, end.z, start.z);
        double startX = CultMath.lerp(-1.0E-7D, start.x, end.x);
        double startY = CultMath.lerp(-1.0E-7D, start.y, end.y);
        double startZ = CultMath.lerp(-1.0E-7D, start.z, end.z);
        int floorStartX = CultMath.floor(startX);
        int floorStartY = CultMath.floor(startY);
        int floorStartZ = CultMath.floor(startZ);

        if (start.equals(end)) return null;

        int state = player.compensatedWorld.getBlockStateIdAt(floorStartX, floorStartY, floorStartZ);
        HitData apply = predicate.apply(state, new BlockPos(floorStartX, floorStartY, floorStartZ));

        if (apply != null) {
            return apply;
        }

        double xDiff = endX - startX;
        double yDiff = endY - startY;
        double zDiff = endZ - startZ;
        double xSign = Math.signum(xDiff);
        double ySign = Math.signum(yDiff);
        double zSign = Math.signum(zDiff);

        double posXInverse = xSign == 0 ? Double.MAX_VALUE : xSign / xDiff;
        double posYInverse = ySign == 0 ? Double.MAX_VALUE : ySign / yDiff;
        double posZInverse = zSign == 0 ? Double.MAX_VALUE : zSign / zDiff;

        double d12 = posXInverse * (xSign > 0 ? 1.0D - CultMath.frac(startX) : CultMath.frac(startX));
        double d13 = posYInverse * (ySign > 0 ? 1.0D - CultMath.frac(startY) : CultMath.frac(startY));
        double d14 = posZInverse * (zSign > 0 ? 1.0D - CultMath.frac(startZ) : CultMath.frac(startZ));

        // Can't figure out what this code does currently
        while (d12 <= 1.0D || d13 <= 1.0D || d14 <= 1.0D) {
            if (d12 < d13) {
                if (d12 < d14) {
                    floorStartX += xSign;
                    d12 += posXInverse;
                } else {
                    floorStartZ += zSign;
                    d14 += posZInverse;
                }
            } else if (d13 < d14) {
                floorStartY += ySign;
                d13 += posYInverse;
            } else {
                floorStartZ += zSign;
                d14 += posZInverse;
            }

            state = player.compensatedWorld.getBlockStateIdAt(floorStartX, floorStartY, floorStartZ);
            apply = predicate.apply(state, new BlockPos(floorStartX, floorStartY, floorStartZ));

            if (apply != null) {
                return apply;
            }
        }

        return null;
    }

    public static @Nullable HitData getNearestHitResult(CultPlayer player, boolean sourcesHaveHitbox) {
        Vec3 startingPos = new Vec3(player.x, player.y + player.getEyeHeight(), player.z);
        Vector3dm startingVec = new Vector3dm(startingPos.x, startingPos.y, startingPos.z);
        Ray trace = new Ray(player, startingPos.x, startingPos.y, startingPos.z, player.xRot, player.yRot);
        Vector3dm endVec = trace.getPointAtDistance(5);
        Vec3 endPos = new Vec3(endVec.getX(), endVec.getY(), endVec.getZ());

        return traverseBlocks(player, startingPos, endPos, (block, vector3i) -> {
            CollisionBox data =
                    HitboxData.getBlockHitbox(player, block, vector3i.getX(), vector3i.getY(), vector3i.getZ());
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            data.downCast(boxes);

            double bestHitResult = Double.MAX_VALUE;
            Vector3dm bestHitLoc = null;
            Direction bestFace = null;

            for (SimpleCollisionBox box : boxes) {
                Pair<Vector3dm, Direction> intercept =
                        ReachUtils.calculateIntercept(box, trace.getOrigin(), trace.getPointAtDistance(6));
                if (intercept.getFirst() == null) continue; // No intercept

                Vector3dm hitLoc = intercept.getFirst();

                if (hitLoc.distanceSquared(startingVec) < bestHitResult) {
                    bestHitResult = hitLoc.distanceSquared(startingVec);
                    bestHitLoc = hitLoc;
                    bestFace = intercept.getSecond();
                }
            }
            if (bestHitLoc != null) {
                return new HitData(vector3i, bestHitLoc, bestFace, block);
            }

            if (sourcesHaveHitbox
                    && (player.compensatedWorld.isWaterSourceBlock(vector3i.getX(), vector3i.getY(), vector3i.getZ())
                            || player.compensatedWorld.getLavaFluidLevelAt(
                                            vector3i.getX(), vector3i.getY(), vector3i.getZ())
                                    == (8 / 9f))) {
                double waterHeight =
                        player.compensatedWorld.getFluidLevelAt(vector3i.getX(), vector3i.getY(), vector3i.getZ());
                SimpleCollisionBox box = new SimpleCollisionBox(
                        vector3i.getX(),
                        vector3i.getY(),
                        vector3i.getZ(),
                        vector3i.getX() + 1,
                        vector3i.getY() + waterHeight,
                        vector3i.getZ() + 1);

                Pair<Vector3dm, Direction> intercept =
                        ReachUtils.calculateIntercept(box, trace.getOrigin(), trace.getPointAtDistance(6));

                if (intercept.getFirst() != null) {
                    return new HitData(vector3i, intercept.getFirst(), intercept.getSecond(), block);
                }
            }

            return null;
        });
    }

    public static @Nullable HitData getNearestPlaceOnWaterHitResult(CultPlayer player) {
        Vec3 startingPos = new Vec3(player.x, player.y + player.getEyeHeight(), player.z);
        Ray trace = new Ray(player, startingPos.x, startingPos.y, startingPos.z, player.xRot, player.yRot);
        double range = getBlockInteractionRange(player);
        Vector3dm endVec = trace.getPointAtDistance(range);
        Vec3 endPos = new Vec3(endVec.getX(), endVec.getY(), endVec.getZ());

        // This mirrors PlaceOnWaterBlockItem#getPlayerPOVHitResult: OUTLINE blocks, SOURCE_ONLY fluids.
        var hitResult = player.compensatedWorld
                .geometry()
                .clipOutline(
                        startingPos,
                        endPos,
                        NativeBlockCollisionHelper.entityContext(player, player.y),
                        state -> ClientFluidQueries.modelFluid(state).isSource());
        if (hitResult == null) {
            return null;
        }

        BlockPos blockPos = new BlockPos(
                hitResult.pos().x(), hitResult.pos().y(), hitResult.pos().z());
        var location = hitResult.location();
        return new HitData(
                blockPos,
                new Vector3dm(location.x(), location.y(), location.z()),
                Direction.valueOf(hitResult.face().name()),
                player.compensatedWorld.getBlockStateIdAt(blockPos));
    }

    private static double getBlockInteractionRange(CultPlayer player) {
        return player.compensatedEntities.getSelf().getBlockInteractionRange();
    }
}
