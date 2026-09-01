package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.math.GrimMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.Material;
import net.minecraft.world.phys.Vec3;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class TraverseBlocks {
    // Copied from MCP...
    // Returns null if there isn't anything.
    //
    // I do have to admit that I'm starting to like bifunctions/new java 8 things more than I originally did.
    // although I still don't understand Mojang's obsession with streams in some of the hottest methods... that kills performance
    public static HitData traverseBlocks(GrimPlayer player, Vec3 start, Vec3 end, BiFunction<BlockData, BlockPos, HitData> predicate) {
        // I guess go back by the collision epsilon?
        double endX = GrimMath.lerp(-1.0E-7D, end.x, start.x);
        double endY = GrimMath.lerp(-1.0E-7D, end.y, start.y);
        double endZ = GrimMath.lerp(-1.0E-7D, end.z, start.z);
        double startX = GrimMath.lerp(-1.0E-7D, start.x, end.x);
        double startY = GrimMath.lerp(-1.0E-7D, start.y, end.y);
        double startZ = GrimMath.lerp(-1.0E-7D, start.z, end.z);
        int floorStartX = GrimMath.floor(startX);
        int floorStartY = GrimMath.floor(startY);
        int floorStartZ = GrimMath.floor(startZ);


        if (start.equals(end)) return null;

        BlockData state = player.compensatedWorld.getBlockDataAt(floorStartX, floorStartY, floorStartZ);
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

        double d12 = posXInverse * (xSign > 0 ? 1.0D - GrimMath.frac(startX) : GrimMath.frac(startX));
        double d13 = posYInverse * (ySign > 0 ? 1.0D - GrimMath.frac(startY) : GrimMath.frac(startY));
        double d14 = posZInverse * (zSign > 0 ? 1.0D - GrimMath.frac(startZ) : GrimMath.frac(startZ));

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

            state = player.compensatedWorld.getBlockDataAt(floorStartX, floorStartY, floorStartZ);
            apply = predicate.apply(state, new BlockPos(floorStartX, floorStartY, floorStartZ));

            if (apply != null) {
                return apply;
            }
        }

        return null;
    }

    public static @Nullable HitData getNearestHitResult(GrimPlayer player, Material heldItem, boolean sourcesHaveHitbox) {
        Vec3 startingPos = new Vec3(player.x, player.y + player.getEyeHeight(), player.z);
        Vector startingVec = new Vector(startingPos.x, startingPos.y, startingPos.z);
        Ray trace = new Ray(player, startingPos.x, startingPos.y, startingPos.z, player.xRot, player.yRot);
        Vector endVec = trace.getPointAtDistance(5);
        Vec3 endPos = new Vec3(endVec.getX(), endVec.getY(), endVec.getZ());

        return traverseBlocks(player, startingPos, endPos, (block, vector3i) -> {
            CollisionBox data = HitboxData.getBlockHitbox(player, heldItem, block, vector3i.getX(), vector3i.getY(), vector3i.getZ());
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            data.downCast(boxes);

            double bestHitResult = Double.MAX_VALUE;
            Vector bestHitLoc = null;
            BlockFace bestFace = null;

            for (SimpleCollisionBox box : boxes) {
                Pair<Vector, BlockFace> intercept = ReachUtils.calculateIntercept(box, trace.getOrigin(), trace.getPointAtDistance(6));
                if (intercept.getFirst() == null) continue; // No intercept

                Vector hitLoc = intercept.getFirst();

                if (hitLoc.distanceSquared(startingVec) < bestHitResult) {
                    bestHitResult = hitLoc.distanceSquared(startingVec);
                    bestHitLoc = hitLoc;
                    bestFace = intercept.getSecond();
                }
            }
            if (bestHitLoc != null) {
                return new HitData(vector3i, bestHitLoc, bestFace, block);
            }

            if (sourcesHaveHitbox &&
                    (player.compensatedWorld.isWaterSourceBlock(vector3i.getX(), vector3i.getY(), vector3i.getZ())
                            || player.compensatedWorld.getLavaFluidLevelAt(vector3i.getX(), vector3i.getY(), vector3i.getZ()) == (8 / 9f))) {
                double waterHeight = player.compensatedWorld.getFluidLevelAt(vector3i.getX(), vector3i.getY(), vector3i.getZ());
                SimpleCollisionBox box = new SimpleCollisionBox(vector3i.getX(), vector3i.getY(), vector3i.getZ(), vector3i.getX() + 1, vector3i.getY() + waterHeight, vector3i.getZ() + 1);

                Pair<Vector, BlockFace> intercept = ReachUtils.calculateIntercept(box, trace.getOrigin(), trace.getPointAtDistance(6));

                if (intercept.getFirst() != null) {
                    return new HitData(vector3i, intercept.getFirst(), intercept.getSecond(), block);
                }
            }

            return null;
        });
    }

    public static @Nullable HitData getNearestPlaceOnWaterHitResult(GrimPlayer player) {
        Vec3 startingPos = new Vec3(player.x, player.y + player.getEyeHeight(), player.z);
        Ray trace = new Ray(player, startingPos.x, startingPos.y, startingPos.z, player.xRot, player.yRot);
        double range = getBlockInteractionRange(player);
        Vector endVec = trace.getPointAtDistance(range);
        Vec3 endPos = new Vec3(endVec.getX(), endVec.getY(), endVec.getZ());

        // This mirrors PlaceOnWaterBlockItem#getPlayerPOVHitResult: OUTLINE blocks, SOURCE_ONLY fluids.
        BlockHitResult hitResult = player.compensatedWorld.clip(new ClipContext(
                startingPos,
                endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY,
                NativeBlockCollisionHelper.collisionContext(player)
        ));
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockPos blockPos = hitResult.getBlockPos().immutable();
        Vec3 location = hitResult.getLocation();
        return new HitData(
                blockPos,
                new Vector(location.x, location.y, location.z),
                toBlockFace(hitResult.getDirection()),
                player.compensatedWorld.getBlockDataAt(blockPos)
        );
    }

    private static double getBlockInteractionRange(GrimPlayer player) {
        return player.compensatedEntities.getSelf().getBlockInteractionRange();
    }

    private static BlockFace toBlockFace(Direction direction) {
        return switch (direction) {
            case DOWN -> BlockFace.DOWN;
            case UP -> BlockFace.UP;
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case WEST -> BlockFace.WEST;
            case EAST -> BlockFace.EAST;
        };
    }
}
