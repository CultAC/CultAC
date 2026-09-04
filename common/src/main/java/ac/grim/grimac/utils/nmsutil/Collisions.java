package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.events.packets.PacketWorldBorder;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.ClientBlockShapes;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.latency.CompensatedWorld.CachedChunk;
import ac.grim.grimac.utils.latency.CompensatedWorld.CachedSection;
import org.bukkit.block.data.BlockData;
import org.bukkit.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class Collisions {
    private static final double COLLISION_EPSILON = 1.0E-7;
    private static final Vec3 COBWEB_STUCK_SPEED = new Vec3(0.25, 0.05000000074505806, 0.25);
    private static final Vec3 SWEET_BERRY_BUSH_STUCK_SPEED = new Vec3(0.800000011920929, 0.75, 0.800000011920929);
    private static final Vec3 POWDER_SNOW_STUCK_SPEED = new Vec3(0.8999999761581421, 1.5, 0.8999999761581421);

    private static final boolean IS_FOURTEEN = true; // Current runtime is newer than 1.14.

    public static Vec3 collide(GrimPlayer player, double desiredX, double desiredY, double desiredZ) {
        return collide(player, player.boundingBox, desiredX, desiredY, desiredZ);
    }

    public static Vec3 collide(GrimPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ) {
        return collide(player, box, desiredX, desiredY, desiredZ, getAxisStepOrder(new Vec3(desiredX, desiredY, desiredZ)), true);
    }

    public static Vec3 collideWithAdditionalCollisionBoxes(GrimPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ, List<SimpleCollisionBox> additionalCollisionBoxes) {
        return collide(player, box, desiredX, desiredY, desiredZ, getAxisStepOrder(new Vec3(desiredX, desiredY, desiredZ)), true, box.minY, additionalCollisionBoxes);
    }

    public static Vec3 collideWithAdditionalCollisionBoxes(GrimPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ, List<Axis> order, boolean allowStepping, List<SimpleCollisionBox> additionalCollisionBoxes, boolean onGroundForStep) {
        return collide(player, box, desiredX, desiredY, desiredZ, order, allowStepping, box.minY, additionalCollisionBoxes, onGroundForStep);
    }

    public static Vec3 collide(GrimPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ, List<Axis> order, boolean allowStepping) {
        return collide(player, box, desiredX, desiredY, desiredZ, order, allowStepping, box.minY);
    }

    private static Vec3 collide(GrimPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ, List<Axis> order, boolean allowStepping, double entityBottom) {
        return collide(player, box, desiredX, desiredY, desiredZ, order, allowStepping, entityBottom, null);
    }

    private static Vec3 collide(GrimPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ, List<Axis> order, boolean allowStepping, double entityBottom, List<SimpleCollisionBox> additionalCollisionBoxes) {
        return collide(player, box, desiredX, desiredY, desiredZ, order, allowStepping, entityBottom, additionalCollisionBoxes, player.lastOnGround);
    }

    private static Vec3 collide(GrimPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ, List<Axis> order, boolean allowStepping, double entityBottom, List<SimpleCollisionBox> additionalCollisionBoxes, boolean onGroundForStep) {
        if (desiredX == 0 && desiredY == 0 && desiredZ == 0) return Vec3.ZERO;

        SimpleCollisionBox grabBoxesBB = box.copy();
        double stepUpHeight = player.getMaxUpStep();

        if (desiredX == 0.0 && desiredZ == 0.0) {
            if (desiredY > 0.0) {
                grabBoxesBB.maxY += desiredY;
            } else {
                grabBoxesBB.minY += desiredY;
            }
        } else {
            if (allowStepping && (stepUpHeight > 0.0 && (onGroundForStep || desiredY < 0))) {
                // don't bother getting the collisions if we don't need them.
                if (desiredY <= 0.0) {
                    grabBoxesBB.expandToCoordinate(desiredX, desiredY, desiredZ);
                    grabBoxesBB.maxY += stepUpHeight;
                } else {
                    grabBoxesBB.expandToCoordinate(desiredX, Math.max(stepUpHeight, desiredY), desiredZ);
                }
            } else {
                grabBoxesBB.expandToCoordinate(desiredX, desiredY, desiredZ);
            }
        }

        List<SimpleCollisionBox> desiredMovementCollisionBoxes = new ArrayList<>();
        getCollisionBoxes(player, grabBoxesBB, desiredMovementCollisionBoxes, false, entityBottom);
        addAdditionalCollisionBoxes(grabBoxesBB, desiredMovementCollisionBoxes, additionalCollisionBoxes);

        Vec3 collisionResult = collideBoundingBoxLegacy(new Vec3(desiredX, desiredY, desiredZ), box, desiredMovementCollisionBoxes, order);

        // While running up stairs and holding space, the player activates the "lastOnGround" part without otherwise being able to step
        boolean movingIntoGround = (onGroundForStep || (collisionResult.y != desiredY && desiredY < 0)) && allowStepping;

        // If the player has x or z collision, is going in the downwards direction in the last or this tick, and can step up
        // If not, just return the collisions without stepping up that we calculated earlier
        if (stepUpHeight > 0.0F && movingIntoGround && (collisionResult.x != desiredX || collisionResult.z != desiredZ)) {
            if (!player.getClientVersion().usesModernEntityStepCollision()) {
                return collideLegacyStep(box, desiredMovementCollisionBoxes, order, collisionResult, desiredX, desiredY, desiredZ, stepUpHeight);
            }

            // MCP-Reborn and ero-minecraft-source 1.21 Entity#collide no longer
            // tries a fixed maxUpStep and then falls back down. It builds a
            // search box from the post-downward-collision start, tries each
            // collider Y coordinate as a candidate step height, and accepts the
            // first candidate that improves horizontal movement.
            boolean collidedDown = collisionResult.y != desiredY && desiredY < 0.0D;
            SimpleCollisionBox stepStartBox = collidedDown ? box.copy().offset(0.0D, collisionResult.y, 0.0D) : box.copy();
            SimpleCollisionBox stepSearchBox = stepStartBox.copy().expandToCoordinate(desiredX, stepUpHeight, desiredZ);
            if (!collidedDown) {
                stepSearchBox.expandToCoordinate(0.0D, -1.0E-5D, 0.0D);
            }

            List<SimpleCollisionBox> stepCollisionBoxes = new ArrayList<>();
            getCollisionBoxes(player, stepSearchBox, stepCollisionBoxes, false, entityBottom);
            addAdditionalCollisionBoxes(stepSearchBox, stepCollisionBoxes, additionalCollisionBoxes);
            for (double candidateHeight : collectCandidateStepUpHeights(stepStartBox, stepCollisionBoxes, stepUpHeight, collisionResult.y)) {
                Vec3 stepResult = collideBoundingBoxLegacy(new Vec3(desiredX, candidateHeight, desiredZ), stepStartBox, stepCollisionBoxes, order);
                if (getHorizontalDistanceSqr(stepResult) > getHorizontalDistanceSqr(collisionResult)) {
                    double alreadyMovedDown = box.minY - stepStartBox.minY;
                    return stepResult.subtract(0.0D, alreadyMovedDown, 0.0D);
                }
            }
        }

        return collisionResult;
    }

    private static void addAdditionalCollisionBoxes(SimpleCollisionBox queryBox, List<SimpleCollisionBox> collisionBoxes, List<SimpleCollisionBox> additionalCollisionBoxes) {
        if (additionalCollisionBoxes == null || additionalCollisionBoxes.isEmpty()) {
            return;
        }

        for (SimpleCollisionBox box : additionalCollisionBoxes) {
            if (box.isCollided(queryBox)) {
                collisionBoxes.add(box);
            }
        }
    }

    private static Vec3 collideLegacyStep(SimpleCollisionBox box, List<SimpleCollisionBox> collisions, List<Axis> order,
                                          Vec3 collisionResult, double desiredX, double desiredY, double desiredZ, double stepUpHeight) {
        Vec3 directStep = collideBoundingBoxLegacy(new Vec3(desiredX, stepUpHeight, desiredZ), box, collisions, order);
        Vec3 verticalStep = collideBoundingBoxLegacy(new Vec3(0.0D, stepUpHeight, 0.0D),
                box.copy().expandToCoordinate(desiredX, 0.0D, desiredZ), collisions, order);

        if (verticalStep.y < stepUpHeight) {
            Vec3 horizontalAfterStep = collideBoundingBoxLegacy(new Vec3(desiredX, 0.0D, desiredZ),
                    box.copy().offset(verticalStep), collisions, order).add(verticalStep);
            if (getHorizontalDistanceSqr(horizontalAfterStep) > getHorizontalDistanceSqr(directStep)) {
                directStep = horizontalAfterStep;
            }
        }

        if (getHorizontalDistanceSqr(directStep) > getHorizontalDistanceSqr(collisionResult)) {
            Vec3 downwardStep = collideBoundingBoxLegacy(new Vec3(0.0D, -directStep.y + desiredY, 0.0D),
                    box.copy().offset(directStep), collisions, order);
            return directStep.add(downwardStep);
        }

        return collisionResult;
    }

    private static List<Axis> getAxisStepOrder(Vec3 movement) {
        return Math.abs(movement.x) < Math.abs(movement.z) ? Arrays.asList(Axis.Y, Axis.Z, Axis.X) : Arrays.asList(Axis.Y, Axis.X, Axis.Z);
    }

    private static List<Double> collectCandidateStepUpHeights(SimpleCollisionBox boundingBox, List<SimpleCollisionBox> colliders, double maxStepHeight, double stepHeightToSkip) {
        List<Float> heights = new ArrayList<>(4);
        float maxStepHeightFloat = (float) maxStepHeight;
        float stepHeightToSkipFloat = (float) stepHeightToSkip;
        for (SimpleCollisionBox collider : colliders) {
            addStepCandidate(heights, (float) (collider.minY - boundingBox.minY), maxStepHeightFloat, stepHeightToSkipFloat);
            addStepCandidate(heights, (float) (collider.maxY - boundingBox.minY), maxStepHeightFloat, stepHeightToSkipFloat);
        }
        heights.sort(Float::compare);

        List<Double> result = new ArrayList<>(heights.size());
        for (float height : heights) {
            result.add((double) height);
        }
        return result;
    }

    private static void addStepCandidate(List<Float> heights, float height, float maxStepHeight, float stepHeightToSkip) {
        if (height < 0.0F || height > maxStepHeight || height == stepHeightToSkip) {
            return;
        }

        for (float existing : heights) {
            if (existing == height) {
                return;
            }
        }
        heights.add(height);
    }

    public static boolean addWorldBorder(GrimPlayer player, SimpleCollisionBox wantedBB, List<SimpleCollisionBox> listOfBlocks, boolean onlyCheckCollide) {
        // MCP-Reborn 26.2 Entity#collectCollidersIgnoringWorldBorder: the Java client
        // no longer collides with the world border. Bedrock players keep their own
        // client behavior, so only exempt Java 26.2+ clients.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2) && player.bedrockState == null) {
            return false;
        }
        final PacketWorldBorder border = player.checkManager.getListener(PacketWorldBorder.class);
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();

        // For some reason, the game limits the border to 29999984 blocks wide
        double size = border.getCurrentDiameter() / 2;
        double absoluteMaxSize = border.getAbsoluteMaxSize();

        double minX = Math.floor(GrimMath.clamp(centerX - size, -absoluteMaxSize, absoluteMaxSize));
        double minZ = Math.floor(GrimMath.clamp(centerZ - size, -absoluteMaxSize, absoluteMaxSize));
        double maxX = Math.ceil(GrimMath.clamp(centerX + size, -absoluteMaxSize, absoluteMaxSize));
        double maxZ = Math.ceil(GrimMath.clamp(centerZ + size, -absoluteMaxSize, absoluteMaxSize));

        // If the player is fully within the worldborder
        double toMinX = player.lastX - minX;
        double toMaxX = maxX - player.lastX;
        double minimumInXDirection = Math.min(toMinX, toMaxX);

        double toMinZ = player.lastZ - minZ;
        double toMaxZ = maxZ - player.lastZ;
        double minimumInZDirection = Math.min(toMinZ, toMaxZ);

        double distanceToBorder = Math.min(minimumInXDirection, minimumInZDirection);

        // If the player's is within 16 blocks of the worldborder, add the worldborder to the collisions (optimization)
        if (distanceToBorder < 16 && player.lastX > minX && player.lastX < maxX && player.lastZ > minZ && player.lastZ < maxZ) {
            if (listOfBlocks == null) listOfBlocks = new ArrayList<>();

            // South border
            listOfBlocks.add(new SimpleCollisionBox(minX - 10, Double.NEGATIVE_INFINITY, maxZ, maxX + 10, Double.POSITIVE_INFINITY, maxZ, false));
            // North border
            listOfBlocks.add(new SimpleCollisionBox(minX - 10, Double.NEGATIVE_INFINITY, minZ, maxX + 10, Double.POSITIVE_INFINITY, minZ, false));
            // East border
            listOfBlocks.add(new SimpleCollisionBox(maxX, Double.NEGATIVE_INFINITY, minZ - 10, maxX, Double.POSITIVE_INFINITY, maxZ + 10, false));
            // West border
            listOfBlocks.add(new SimpleCollisionBox(minX, Double.NEGATIVE_INFINITY, minZ - 10, minX, Double.POSITIVE_INFINITY, maxZ + 10, false));

            if (onlyCheckCollide) {
                for (SimpleCollisionBox box : listOfBlocks) {
                    if (box.isIntersected(wantedBB)) return true;
                }
            }
        }
        return false;
    }

    // This is mostly taken from Tuinity collisions
    public static boolean getCollisionBoxes(GrimPlayer player, SimpleCollisionBox wantedBB, List<SimpleCollisionBox> listOfBlocks, boolean onlyCheckCollide) {
        return getCollisionBoxes(player, wantedBB, listOfBlocks, onlyCheckCollide, player == null ? Double.NaN : player.y);
    }

    public static boolean getCollisionBoxes(GrimPlayer player, SimpleCollisionBox wantedBB, List<SimpleCollisionBox> listOfBlocks, boolean onlyCheckCollide, double entityBottom) {
        return getCollisionBoxes(player, wantedBB, listOfBlocks, onlyCheckCollide, entityBottom, JavaCollisionState.current(player));
    }

    public static boolean getCollisionBoxes(GrimPlayer player, SimpleCollisionBox wantedBB, List<SimpleCollisionBox> listOfBlocks,
                                            boolean onlyCheckCollide, double entityBottom, JavaCollisionState actor) {
        SimpleCollisionBox expandedBB = wantedBB.copy();

        boolean collided = addWorldBorder(player, wantedBB, listOfBlocks, onlyCheckCollide);
        if (onlyCheckCollide && collided) return true;

        int minBlockX = (int) Math.floor(expandedBB.minX - COLLISION_EPSILON) - 1;
        int maxBlockX = (int) Math.floor(expandedBB.maxX + COLLISION_EPSILON) + 1;
        int minBlockY = (int) Math.floor(expandedBB.minY - COLLISION_EPSILON) - 1;
        int maxBlockY = (int) Math.floor(expandedBB.maxY + COLLISION_EPSILON) + 1;
        int minBlockZ = (int) Math.floor(expandedBB.minZ - COLLISION_EPSILON) - 1;
        int maxBlockZ = (int) Math.floor(expandedBB.maxZ + COLLISION_EPSILON) + 1;

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
            int minZ = currChunkZ == minChunkZ ? minBlockZ & 15 : 0; // coordinate in chunk
            int maxZ = currChunkZ == maxChunkZ ? maxBlockZ & 15 : 15; // coordinate in chunk

            for (int currChunkX = minChunkX; currChunkX <= maxChunkX; ++currChunkX) {
                int minX = currChunkX == minChunkX ? minBlockX & 15 : 0; // coordinate in chunk
                int maxX = currChunkX == maxChunkX ? maxBlockX & 15 : 15; // coordinate in chunk

                int chunkXGlobalPos = currChunkX << 4;
                int chunkZGlobalPos = currChunkZ << 4;

                CachedChunk chunk = player.compensatedWorld.getChunk(currChunkX, currChunkZ);
                if (chunk == null) continue;

                for (int y = minYIterate; y <= maxYIterate; ++y) {
                    int sectionIndex = (y >> 4) - minSection;

                    CachedSection section = chunk.getSection(sectionIndex);

                    if (section == null || (IS_FOURTEEN && section.isEmpty())) { // Check for empty on 1.13+ servers
                        // empty
                        // skip to next section
                        y = (y & ~(15)) + 15; // increment by 15: iterator loop increments by the extra one
                        continue;
                    }

                    for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                        for (int currX = minX; currX <= maxX; ++currX) {
                            int x = currX | chunkXGlobalPos;
                            int z = currZ | chunkZGlobalPos;

                            BlockState data = section.getState(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));

                            if (data.isAir()) continue;
                            Material material = data.getBukkitMaterial();
                            CollisionBox collisionBox = ClientBlockShapes.movement(player, data, x, y, z, entityBottom, actor);
                            if (collisionBox.isNull()) continue;
                            // Thanks SpottedLeaf for this optimization, I took edgeCount from Tuinity
                            int edgeCount = ((x == minBlockX || x == maxBlockX) ? 1 : 0) +
                                    ((y == minBlockY || y == maxBlockY) ? 1 : 0) +
                                    ((z == minBlockZ || z == maxBlockZ) ? 1 : 0);

                            if (edgeCount != 3 && (edgeCount != 1 || data.hasLargeCollisionShape())
                                    && (edgeCount != 2 || material == Material.PISTON_HEAD)) {
                                // Don't add to a list if we only care if the player intersects with the block
                                if (!onlyCheckCollide) {
                                    collisionBox.downCast(listOfBlocks);
                                } else if (collisionBox.isIntersected(wantedBB)) {
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

    public static Vec3 collideBoundingBoxLegacy(Vec3 toCollide, SimpleCollisionBox
            box, List<SimpleCollisionBox> desiredMovementCollisionBoxes, List<Axis> order) {
        return collideBoundingBoxLegacy(
                toCollide, box, desiredMovementCollisionBoxes, order, SimpleCollisionBox.AxisEpsilon.JAVA);
    }

    public static Vec3 collideBoundingBoxLegacy(Vec3 toCollide, SimpleCollisionBox
            box, List<SimpleCollisionBox> desiredMovementCollisionBoxes, List<Axis> order,
                                                SimpleCollisionBox.AxisEpsilon epsilon) {
        double x = toCollide.x;
        double y = toCollide.y;
        double z = toCollide.z;

        SimpleCollisionBox setBB = box.copy();

        for (Axis axis : order) {
            if (axis == Axis.X) {
                for (SimpleCollisionBox bb : desiredMovementCollisionBoxes) {
                    x = bb.collideX(setBB, x, epsilon);
                }
                setBB.offset(x, 0.0D, 0.0D);
            } else if (axis == Axis.Y) {
                for (SimpleCollisionBox bb : desiredMovementCollisionBoxes) {
                    y = bb.collideY(setBB, y, epsilon);
                }
                setBB.offset(0.0D, y, 0.0D);
            } else if (axis == Axis.Z) {
                for (SimpleCollisionBox bb : desiredMovementCollisionBoxes) {
                    z = bb.collideZ(setBB, z, epsilon);
                }
                setBB.offset(0.0D, 0.0D, z);
            }
        }

        return new Vec3(x, y, z);
    }

    public static boolean isEmpty(GrimPlayer player, SimpleCollisionBox playerBB) {
        return isEmpty(player, playerBB, player == null ? Double.NaN : player.y);
    }

    public static boolean isEmpty(GrimPlayer player, SimpleCollisionBox playerBB, double entityBottom) {
        return !getCollisionBoxes(player, playerBB, null, true, entityBottom);
    }

    public static double getHorizontalDistanceSqr(Vec3 vector) {
        return vector.x * vector.x + vector.z * vector.z;
    }

    public static Vector maybeBackOffFromEdge(Vector vec3, GrimPlayer player, boolean lastOnGround, SimpleCollisionBox playerBB, boolean overrideVersion) {
        if (!player.isFlying && player.isSneaking && isAboveGround(player, lastOnGround, playerBB)) {
            double x = vec3.getX();
            double z = vec3.getZ();

            double maxStepDown = -player.getMaxUpStep();

            while (x != 0.0 && isEmpty(player, player.boundingBox.copy().offset(x, maxStepDown, 0.0))) {
                if (x < 0.05D && x >= -0.05D) {
                    x = 0.0D;
                } else if (x > 0.0D) {
                    x -= 0.05D;
                } else {
                    x += 0.05D;
                }
            }
            while (z != 0.0 && isEmpty(player, player.boundingBox.copy().offset(0.0, maxStepDown, z))) {
                if (z < 0.05D && z >= -0.05D) {
                    z = 0.0D;
                } else if (z > 0.0D) {
                    z -= 0.05D;
                } else {
                    z += 0.05D;
                }
            }
            while (x != 0.0 && z != 0.0 && isEmpty(player, player.boundingBox.copy().offset(x, maxStepDown, z))) {
                if (x < 0.05D && x >= -0.05D) {
                    x = 0.0D;
                } else if (x > 0.0D) {
                    x -= 0.05D;
                } else {
                    x += 0.05D;
                }

                if (z < 0.05D && z >= -0.05D) {
                    z = 0.0D;
                } else if (z > 0.0D) {
                    z -= 0.05D;
                } else {
                    z += 0.05D;
                }
            }
            vec3 = new Vector(x, vec3.getY(), z);
        }

        return vec3;
    }

    public static boolean isAboveGround(GrimPlayer player, boolean lastOnGround, SimpleCollisionBox playerBB) {
        // https://bugs.mojang.com/browse/MC-2404
        return lastOnGround || !isEmpty(player, playerBB.copy().offset(0.0, -player.getMaxUpStep(), 0.0));
    }

    public static boolean isSlidingDown(GrimPlayer player, int locationX, int locationY, int locationZ, Vec3 pos) {
        if (player.onGround) {
            return false;
        } else if (pos.y > locationY + 0.9375D - 1.0E-7D) {
            return false;
        } else {
            double d0 = Math.abs(locationX + 0.5D - pos.x);
            double d1 = Math.abs(locationZ + 0.5D - pos.z);
            // Calculate player width using bounding box, which will change while swimming or gliding
            double d2 = 0.4375D + ((0.6F) / 2.0F);
            return d0 + 1.0E-7D > d2 || d1 + 1.0E-7D > d2;
        }
    }


    public static Vec3 checkStuckSpeed(GrimPlayer player, SimpleCollisionBox aabb) {
        return checkStuckSpeed(player, aabb, true);
    }

    public static Vec3 checkStuckSpeed(GrimPlayer player, SimpleCollisionBox aabb, boolean powderSnowCanApply) {
        // Use the bounding box for after the player's movement is applied
        double expandAmount = 1e-7;
        Vec3 blockPos = new Vec3(aabb.minX + expandAmount, aabb.minY + expandAmount, aabb.minZ + expandAmount);
        Vec3 blockPos2 = new Vec3(aabb.maxX - expandAmount, aabb.maxY - expandAmount, aabb.maxZ - expandAmount);

        int blockPosX = GrimMath.floor(blockPos.x);
        int blockPosY = GrimMath.floor(blockPos.y);
        int blockPosZ = GrimMath.floor(blockPos.z);
        int blockPos2X = GrimMath.floor(blockPos2.x);
        int blockPos2Y = GrimMath.floor(blockPos2.y);
        int blockPos2Z = GrimMath.floor(blockPos2.z);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z))
            return null;

        Vec3 stuckSpeed = null;
        // Order matters
        for (int i = blockPosX; i <= blockPos2X; ++i) {
            for (int j = blockPosY; j <= blockPos2Y; ++j) {
                for (int k = blockPosZ; k <= blockPos2Z; ++k) {
                    BlockData block = player.compensatedWorld.getBlockDataAt(i, j, k);
                    Vec3 blockStuckSpeed = getStuckSpeedForBlock(player, block, powderSnowCanApply);
                    if (blockStuckSpeed != null) {
                        // Legacy/provisional contact summary. Java 26.2 commits actual
                        // inside shapes and effects through JavaInsideBlockEffects.
                        stuckSpeed = blockStuckSpeed;
                    }
                }
            }
        }

        return stuckSpeed;
    }

    public static Vec3 checkStuckSpeedAlongMovement(GrimPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb) {
        return checkStuckSpeedAlongMovement(player, fromAabb, toAabb, true);
    }

    public static Vec3 checkStuckSpeedAlongMovement(GrimPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, boolean powderSnowCanApply) {
        SimpleCollisionBox sweptCandidates = fromAabb.copy().union(toAabb);
        double expandAmount = 1e-7;
        Vec3 blockPos = new Vec3(sweptCandidates.minX + expandAmount, sweptCandidates.minY + expandAmount, sweptCandidates.minZ + expandAmount);
        Vec3 blockPos2 = new Vec3(sweptCandidates.maxX - expandAmount, sweptCandidates.maxY - expandAmount, sweptCandidates.maxZ - expandAmount);

        int blockPosX = GrimMath.floor(blockPos.x);
        int blockPosY = GrimMath.floor(blockPos.y);
        int blockPosZ = GrimMath.floor(blockPos.z);
        int blockPos2X = GrimMath.floor(blockPos2.x);
        int blockPos2Y = GrimMath.floor(blockPos2.y);
        int blockPos2Z = GrimMath.floor(blockPos2.z);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z))
            return null;

        Vec3 stuckSpeed = null;
        for (int i = blockPosX; i <= blockPos2X; ++i) {
            for (int j = blockPosY; j <= blockPos2Y; ++j) {
                for (int k = blockPosZ; k <= blockPos2Z; ++k) {
                    BlockData block = player.compensatedWorld.getBlockDataAt(i, j, k);
                    Vec3 blockStuckSpeed = getStuckSpeedForBlock(player, block, powderSnowCanApply);
                    if (blockStuckSpeed != null && sweptFullBlockEntityInside(fromAabb, toAabb, i, j, k)) {
                        // MCP-Reborn Entity#checkInsideBlocks replays the move
                        // path one axis at a time and calls blockstate.entityInside
                        // for every intersected block. Powder snow therefore
                        // follows the same swept AABB test as webs/berry bushes
                        // instead of a special "feet block only" rule.
                        stuckSpeed = blockStuckSpeed;
                    }
                }
            }
        }

        return stuckSpeed;
    }

    public static Vec3 checkStuckSpeedAlongMovement(GrimPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement) {
        return checkStuckSpeedAlongMovement(player, fromAabb, toAabb, axisOrderMovement, true);
    }

    public static Vec3 checkStuckSpeedAlongMovement(GrimPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement, boolean powderSnowCanApply) {
        Vec3 movement = boxCenter(toAabb).subtract(boxCenter(fromAabb));
        if (movement.lengthSqr() == 0.0D) {
            return checkStuckSpeedAlongMovement(player, fromAabb, toAabb, powderSnowCanApply);
        }

        Vec3 stuckSpeed = null;
        SimpleCollisionBox stepFrom = fromAabb.copy();
        // MCP-Reborn Entity#move stores the clipped delta plus the original movement.
        // Entity#checkInsideBlocks then replays the clipped delta one axis at a time
        // using Direction.axisStepOrder(originalMovement), so corner-only diagonal
        // sweeps must not trigger cobweb/powder-snow/berry-bush stuck speed.
        for (Axis axis : getAxisStepOrder(axisOrderMovement)) {
            double movementOnAxis = getAxisValue(movement, axis);
            if (movementOnAxis == 0.0D) {
                continue;
            }

            SimpleCollisionBox stepTo = stepFrom.copy();
            offsetAlongAxis(stepTo, axis, movementOnAxis);
            Vec3 stepStuckSpeed = checkStuckSpeedAlongMovement(player, stepFrom, stepTo, powderSnowCanApply);
            if (stepStuckSpeed != null) {
                stuckSpeed = stepStuckSpeed;
            }
            stepFrom = stepTo;
        }

        return stuckSpeed;
    }

    /**
     * Replays the 26.2 {@code Entity#checkInsideBlocks} traversal used after a
     * movement. Unlike the legacy approximation above, the visited-block set is
     * shared by every axis and block effects retain vanilla's visit order. This
     * matters because {@code Entity#makeStuckInBlock} overwrites, rather than
     * combines, the multiplier when (for example) a web and berry bush are both
     * crossed in one movement.
     */
    public static Vec3 checkStuckSpeedAlongMovement26Dot2(GrimPlayer player, SimpleCollisionBox fromAabb,
                                                           SimpleCollisionBox toAabb, Vec3 axisOrderMovement,
                                                           boolean powderSnowCanApply) {
        SimpleCollisionBox sweptCandidates = fromAabb.copy().union(toAabb);
        int minX = GrimMath.floor(sweptCandidates.minX + COLLISION_EPSILON);
        int minY = GrimMath.floor(sweptCandidates.minY + COLLISION_EPSILON);
        int minZ = GrimMath.floor(sweptCandidates.minZ + COLLISION_EPSILON);
        int maxX = GrimMath.floor(sweptCandidates.maxX - COLLISION_EPSILON);
        int maxY = GrimMath.floor(sweptCandidates.maxY - COLLISION_EPSILON);
        int maxZ = GrimMath.floor(sweptCandidates.maxZ - COLLISION_EPSILON);
        if (CheckIfChunksLoaded.isChunksUnloadedAt(player, minX, minY, minZ, maxX, maxY, maxZ)) {
            return null;
        }

        return resolveOrderedStuckSpeed26Dot2(fromAabb, toAabb, axisOrderMovement,
                (x, y, z) -> getStuckSpeedForBlock(
                        player,
                        player.compensatedWorld.getBlockDataAt(x, y, z),
                        powderSnowCanApply));
    }

    static Vec3 resolveOrderedStuckSpeed26Dot2(SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb,
                                                Vec3 axisOrderMovement, StuckSpeedLookup lookup) {
        Vec3[] result = new Vec3[1];
        visitInsideBlocks26Dot2(fromAabb, toAabb, axisOrderMovement, (from, to, pos, precise) -> {
            Vec3 speed = lookup.get(pos.getX(), pos.getY(), pos.getZ());
            if (speed == null || !sweptFullBlockEntityInside(from, to, pos.getX(), pos.getY(), pos.getZ())) return false;
            result[0] = speed;
            return true;
        });
        return result[0];
    }

    /** Entity#checkInsideBlocks: one visited set and iteration budget across the axis segments. */
    public static void visitInsideBlocks26Dot2(SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb,
                                               Vec3 originalMovement, InsideBlockVisitor visitor) {
        LongSet visited = new LongOpenHashSet();
        int remaining = 16;
        Vec3 delta = boxCenter(toAabb).subtract(boxCenter(fromAabb));
        if (originalMovement != null && delta.lengthSqr() > 0.0) {
            SimpleCollisionBox from = fromAabb.copy();
            for (Axis axis : getAxisStepOrder(originalMovement)) {
                double amount = getAxisValue(delta, axis);
                if (amount == 0.0) continue;
                SimpleCollisionBox to = from.copy();
                offsetAlongAxis(to, axis, amount);
                remaining -= visitInsideSegment26Dot2(from, to, visited, remaining, visitor);
                from = to;
            }
        } else {
            remaining -= visitInsideSegment26Dot2(fromAabb, toAabb, visited, remaining, visitor);
        }
        if (remaining <= 0) visitInsideSegment26Dot2(toAabb, toAabb, visited, 1, visitor);
    }

    private static int visitInsideSegment26Dot2(SimpleCollisionBox from, SimpleCollisionBox to,
                                                LongSet visited, int budget, InsideBlockVisitor visitor) {
        int[] iteration = {0};
        AABB deflated = new AABB(to.minX, to.minY, to.minZ, to.maxX, to.maxY, to.maxZ).deflate(1.0E-5F);
        boolean movedFar = boxCenter(from).distanceToSqr(boxCenter(to)) > GrimMath.square(0.9999900000002526);
        forEachBlockIntersectedBetween26Dot2(boxCenter(from), boxCenter(to), to, (pos, step) -> {
            if (step >= budget) return false;
            iteration[0] = step;
            if (!visited.contains(pos.asLong())
                    && visitor.visit(from, to, pos, movedFar || deflated.intersects(pos))) visited.add(pos.asLong());
            return true;
        });
        return iteration[0] + 1;
    }

    @FunctionalInterface
    public interface InsideBlockVisitor {
        /** Return true only when the block or its fluid was actually contacted. */
        boolean visit(SimpleCollisionBox from, SimpleCollisionBox to, BlockPos pos, boolean precise);
    }

    private static boolean forEachBlockIntersectedBetween26Dot2(Vec3 from, Vec3 to,
                                                                 SimpleCollisionBox boxAtTarget,
                                                                 OrderedBlockVisitor visitor) {
        Vec3 travel = to.subtract(from);
        AABB target = new AABB(boxAtTarget.minX, boxAtTarget.minY, boxAtTarget.minZ,
                boxAtTarget.maxX, boxAtTarget.maxY, boxAtTarget.maxZ).deflate(1.0E-5F);
        if (travel.lengthSqr() < GrimMath.square(1.0E-5F)) {
            for (BlockPos pos : BlockPos.betweenClosed(target)) {
                if (!visitor.visit(pos, 0)) return false;
            }
            return true;
        }

        LongSet visited = new LongOpenHashSet();
        for (BlockPos pos : BlockPos.betweenCornersInDirection(target.move(travel.scale(-1.0D)), travel)) {
            if (!visitor.visit(pos, 0)) return false;
            visited.add(pos.asLong());
        }

        int iterations = addBlocksAlongTravel26Dot2(visited, travel, target, visitor);
        if (iterations < 0) return false;
        for (BlockPos pos : BlockPos.betweenCornersInDirection(target, travel)) {
            if (visited.add(pos.asLong()) && !visitor.visit(pos, iterations + 1)) return false;
        }
        return true;
    }

    private static int addBlocksAlongTravel26Dot2(LongSet visited, Vec3 travel, AABB target,
                                                   OrderedBlockVisitor visitor) {
        int[] corner = furthestCorner26Dot2(travel);
        Vec3 center = target.getCenter();
        Vec3 toCorner = new Vec3(
                center.x + target.getXsize() * 0.5D * corner[0],
                center.y + target.getYsize() * 0.5D * corner[1],
                center.z + target.getZsize() * 0.5D * corner[2]);
        Vec3 fromCorner = toCorner.subtract(travel);
        int x = GrimMath.floor(fromCorner.x);
        int y = GrimMath.floor(fromCorner.y);
        int z = GrimMath.floor(fromCorner.z);
        int signX = (int) Math.signum(travel.x);
        int signY = (int) Math.signum(travel.y);
        int signZ = (int) Math.signum(travel.z);
        double deltaX = signX == 0 ? Double.MAX_VALUE : signX / travel.x;
        double deltaY = signY == 0 ? Double.MAX_VALUE : signY / travel.y;
        double deltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / travel.z;
        double nextX = deltaX * (signX > 0 ? 1.0D - GrimMath.frac(fromCorner.x) : GrimMath.frac(fromCorner.x));
        double nextY = deltaY * (signY > 0 ? 1.0D - GrimMath.frac(fromCorner.y) : GrimMath.frac(fromCorner.y));
        double nextZ = deltaZ * (signZ > 0 ? 1.0D - GrimMath.frac(fromCorner.z) : GrimMath.frac(fromCorner.z));
        int iterations = 0;

        while (nextX <= 1.0D || nextY <= 1.0D || nextZ <= 1.0D) {
            if (nextX < nextY) {
                if (nextX < nextZ) {
                    x += signX;
                    nextX += deltaX;
                } else {
                    z += signZ;
                    nextZ += deltaZ;
                }
            } else if (nextY < nextZ) {
                y += signY;
                nextY += deltaY;
            } else {
                z += signZ;
                nextZ += deltaZ;
            }

            Optional<Vec3> hit = AABB.clip(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D, fromCorner, toCorner);
            if (hit.isEmpty()) continue;
            iterations++;
            Vec3 point = hit.get();
            double hitX = clamp(point.x, x + 1.0E-5F, x + 1.0D - 1.0E-5F);
            double hitY = clamp(point.y, y + 1.0E-5F, y + 1.0D - 1.0E-5F);
            double hitZ = clamp(point.z, z + 1.0E-5F, z + 1.0D - 1.0E-5F);
            int oppositeX = GrimMath.floor(hitX - target.getXsize() * corner[0]);
            int oppositeY = GrimMath.floor(hitY - target.getYsize() * corner[1]);
            int oppositeZ = GrimMath.floor(hitZ - target.getZsize() * corner[2]);

            for (BlockPos pos : BlockPos.betweenCornersInDirection(
                    new BlockPos(x, y, z), new BlockPos(oppositeX, oppositeY, oppositeZ), travel)) {
                if (visited.add(pos.asLong()) && !visitor.visit(pos, iterations)) return -1;
            }
        }
        return iterations;
    }

    private static int[] furthestCorner26Dot2(Vec3 movement) {
        double x = Math.abs(movement.x);
        double y = Math.abs(movement.y);
        double z = Math.abs(movement.z);
        int xSign = movement.x >= 0.0D ? 1 : -1;
        int ySign = movement.y >= 0.0D ? 1 : -1;
        int zSign = movement.z >= 0.0D ? 1 : -1;
        if (x <= y && x <= z) return new int[]{-xSign, -zSign, ySign};
        if (y <= z) return new int[]{zSign, -ySign, -xSign};
        return new int[]{-ySign, xSign, -zSign};
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @FunctionalInterface
    interface StuckSpeedLookup {
        Vec3 get(int x, int y, int z);
    }

    @FunctionalInterface
    private interface OrderedBlockVisitor {
        boolean visit(BlockPos pos, int iteration);
    }

    public static boolean canFullBlockAffectInsideBlockMovement(SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement, BlockPos pos) {
        if (sweptFullBlockEntityInside(toAabb, toAabb, pos.getX(), pos.getY(), pos.getZ())) {
            return true;
        }

        Vec3 movement = boxCenter(toAabb).subtract(boxCenter(fromAabb));
        if (movement.lengthSqr() == 0.0D) {
            return sweptFullBlockEntityInside(fromAabb, toAabb, pos.getX(), pos.getY(), pos.getZ());
        }

        SimpleCollisionBox stepFrom = fromAabb.copy();
        for (Axis axis : getAxisStepOrder(axisOrderMovement)) {
            double movementOnAxis = getAxisValue(movement, axis);
            if (movementOnAxis == 0.0D) {
                continue;
            }

            SimpleCollisionBox stepTo = stepFrom.copy();
            offsetAlongAxis(stepTo, axis, movementOnAxis);
            if (sweptFullBlockEntityInside(stepFrom, stepTo, pos.getX(), pos.getY(), pos.getZ())) {
                return true;
            }
            stepFrom = stepTo;
        }

        return false;
    }

    public static boolean sweptFullBlockEntityInside(SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, int x, int y, int z) {
        // Equivalent to MCP-Reborn Entity#collidedWithShapeMovingFrom for one axis
        // step: inflate the block shape by the entity half-size, then clip the
        // entity-center movement vector through that inflated box.
        double halfX = (fromAabb.maxX - fromAabb.minX) * 0.5D - COLLISION_EPSILON;
        double halfY = (fromAabb.maxY - fromAabb.minY) * 0.5D - COLLISION_EPSILON;
        double halfZ = (fromAabb.maxZ - fromAabb.minZ) * 0.5D - COLLISION_EPSILON;
        double minX = x - halfX;
        double minY = y - halfY;
        double minZ = z - halfZ;
        double maxX = x + 1.0D + halfX;
        double maxY = y + 1.0D + halfY;
        double maxZ = z + 1.0D + halfZ;

        Vec3 fromCenter = boxCenter(fromAabb);
        Vec3 toCenter = boxCenter(toAabb);
        return containsHalfOpen(minX, minY, minZ, maxX, maxY, maxZ, fromCenter)
                || containsHalfOpen(minX, minY, minZ, maxX, maxY, maxZ, toCenter)
                || segmentClipsBox(minX, minY, minZ, maxX, maxY, maxZ, fromCenter, toCenter);
    }

    public static String describeStuckSpeedSources(GrimPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement) {
        StringBuilder sb = new StringBuilder();
        sb.append("from=").append(formatBox(fromAabb))
                .append(" to=").append(formatBox(toAabb))
                .append(" direct=").append(collectDirectStuckSpeedHits(player, toAabb));

        if (axisOrderMovement == null || boxCenter(toAabb).subtract(boxCenter(fromAabb)).lengthSqr() == 0.0D) {
            sb.append(" swept=").append(collectSweptStuckSpeedHits(player, fromAabb, toAabb));
            return sb.toString();
        }

        sb.append(" axisSweep=");
        SimpleCollisionBox stepFrom = fromAabb.copy();
        boolean wroteAxis = false;
        for (Axis axis : getAxisStepOrder(axisOrderMovement)) {
            double movementOnAxis = getAxisValue(boxCenter(toAabb).subtract(boxCenter(fromAabb)), axis);
            if (movementOnAxis == 0.0D) {
                continue;
            }

            SimpleCollisionBox stepTo = stepFrom.copy();
            offsetAlongAxis(stepTo, axis, movementOnAxis);
            if (wroteAxis) {
                sb.append(' ');
            }
            sb.append(axis).append('=').append(collectSweptStuckSpeedHits(player, stepFrom, stepTo));
            wroteAxis = true;
            stepFrom = stepTo;
        }

        if (!wroteAxis) {
            sb.append("[]");
        }

        return sb.toString();
    }

    private static double getAxisValue(Vec3 vector, Axis axis) {
        return switch (axis) {
            case X -> vector.x;
            case Y -> vector.y;
            case Z -> vector.z;
        };
    }

    private static void offsetAlongAxis(SimpleCollisionBox box, Axis axis, double movement) {
        switch (axis) {
            case X -> box.offset(movement, 0.0D, 0.0D);
            case Y -> box.offset(0.0D, movement, 0.0D);
            case Z -> box.offset(0.0D, 0.0D, movement);
        }
    }

    private static Vec3 boxCenter(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) * 0.5D, (box.minY + box.maxY) * 0.5D, (box.minZ + box.maxZ) * 0.5D);
    }

    private static String collectDirectStuckSpeedHits(GrimPlayer player, SimpleCollisionBox aabb) {
        double expandAmount = 1e-7;
        Vec3 blockPos = new Vec3(aabb.minX + expandAmount, aabb.minY + expandAmount, aabb.minZ + expandAmount);
        Vec3 blockPos2 = new Vec3(aabb.maxX - expandAmount, aabb.maxY - expandAmount, aabb.maxZ - expandAmount);

        int blockPosX = GrimMath.floor(blockPos.x);
        int blockPosY = GrimMath.floor(blockPos.y);
        int blockPosZ = GrimMath.floor(blockPos.z);
        int blockPos2X = GrimMath.floor(blockPos2.x);
        int blockPos2Y = GrimMath.floor(blockPos2.y);
        int blockPos2Z = GrimMath.floor(blockPos2.z);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z)) {
            return "[unloaded]";
        }

        List<String> hits = new ArrayList<>();
        for (int x = blockPosX; x <= blockPos2X; ++x) {
            for (int y = blockPosY; y <= blockPos2Y; ++y) {
                for (int z = blockPosZ; z <= blockPos2Z; ++z) {
                    appendStuckSpeedHit(player, hits, x, y, z, "direct");
                }
            }
        }
        return hits.toString();
    }

    private static String collectSweptStuckSpeedHits(GrimPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb) {
        SimpleCollisionBox sweptCandidates = fromAabb.copy().union(toAabb);
        double expandAmount = 1e-7;
        Vec3 blockPos = new Vec3(sweptCandidates.minX + expandAmount, sweptCandidates.minY + expandAmount, sweptCandidates.minZ + expandAmount);
        Vec3 blockPos2 = new Vec3(sweptCandidates.maxX - expandAmount, sweptCandidates.maxY - expandAmount, sweptCandidates.maxZ - expandAmount);

        int blockPosX = GrimMath.floor(blockPos.x);
        int blockPosY = GrimMath.floor(blockPos.y);
        int blockPosZ = GrimMath.floor(blockPos.z);
        int blockPos2X = GrimMath.floor(blockPos2.x);
        int blockPos2Y = GrimMath.floor(blockPos2.y);
        int blockPos2Z = GrimMath.floor(blockPos2.z);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z)) {
            return "[unloaded]";
        }

        List<String> hits = new ArrayList<>();
        for (int x = blockPosX; x <= blockPos2X; ++x) {
            for (int y = blockPosY; y <= blockPos2Y; ++y) {
                for (int z = blockPosZ; z <= blockPos2Z; ++z) {
                    BlockData block = player.compensatedWorld.getBlockDataAt(x, y, z);
                    Vec3 blockStuckSpeed = getStuckSpeedForBlock(player, block);
                    if (blockStuckSpeed != null && sweptFullBlockEntityInside(fromAabb, toAabb, x, y, z)) {
                        hits.add(x + "," + y + "," + z + ":" + block.getMaterial() + "=" + blockStuckSpeed);
                    }
                }
            }
        }
        return hits.toString();
    }

    private static void appendStuckSpeedHit(GrimPlayer player, List<String> hits, int x, int y, int z, String mode) {
        BlockData block = player.compensatedWorld.getBlockDataAt(x, y, z);
        Vec3 blockStuckSpeed = getStuckSpeedForBlock(player, block);
        if (blockStuckSpeed != null) {
            hits.add(x + "," + y + "," + z + ":" + block.getMaterial() + "=" + blockStuckSpeed + ":" + mode);
        }
    }

    private static String formatBox(SimpleCollisionBox box) {
        return "["
                + box.minX + "," + box.minY + "," + box.minZ
                + " -> "
                + box.maxX + "," + box.maxY + "," + box.maxZ
                + "]";
    }

    private static boolean containsHalfOpen(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Vec3 point) {
        return point.x >= minX && point.x < maxX
                && point.y >= minY && point.y < maxY
                && point.z >= minZ && point.z < maxZ;
    }

    private static boolean segmentClipsBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        if (dx > COLLISION_EPSILON && clipsFace(dx, dy, dz, minX, minY, maxY, minZ, maxZ, from.x, from.y, from.z)) return true;
        if (dx < -COLLISION_EPSILON && clipsFace(dx, dy, dz, maxX, minY, maxY, minZ, maxZ, from.x, from.y, from.z)) return true;
        if (dy > COLLISION_EPSILON && clipsFace(dy, dz, dx, minY, minZ, maxZ, minX, maxX, from.y, from.z, from.x)) return true;
        if (dy < -COLLISION_EPSILON && clipsFace(dy, dz, dx, maxY, minZ, maxZ, minX, maxX, from.y, from.z, from.x)) return true;
        if (dz > COLLISION_EPSILON && clipsFace(dz, dx, dy, minZ, minX, maxX, minY, maxY, from.z, from.x, from.y)) return true;
        return dz < -COLLISION_EPSILON && clipsFace(dz, dx, dy, maxZ, minX, maxX, minY, maxY, from.z, from.x, from.y);
    }

    private static boolean clipsFace(double da, double db, double dc, double plane, double minB, double maxB, double minC, double maxC,
                                     double fromA, double fromB, double fromC) {
        double scale = (plane - fromA) / da;
        double b = fromB + scale * db;
        double c = fromC + scale * dc;
        return 0.0D < scale && scale < 1.0D
                && minB - COLLISION_EPSILON < b && b < maxB + COLLISION_EPSILON
                && minC - COLLISION_EPSILON < c && c < maxC + COLLISION_EPSILON;
    }

    public static Vec3 getStuckSpeedForBlock(GrimPlayer player, BlockData blockData) {
        return getStuckSpeedForBlock(player, blockData, true);
    }

    public static Vec3 getStuckSpeedForBlock(GrimPlayer player, BlockData blockData, boolean powderSnowCanApply) {
        Block block = NmsBlockTags.toNmsState(blockData).getBlock();

        // Vanilla applies these through entityInside -> Entity.makeStuckInBlock. Calling
        // that path would mutate a live/detached entity, so Grim mirrors the NMS constants
        // after identifying the affected block by its NMS behavior class.
        if (block instanceof WebBlock) {
            return COBWEB_STUCK_SPEED;
        }

        if (block instanceof SweetBerryBushBlock) {
            return SWEET_BERRY_BUSH_STUCK_SPEED;
        }

        if (block instanceof PowderSnowBlock) {
            return powderSnowCanApply ? POWDER_SNOW_STUCK_SPEED : null;
        }
        return null;
    }

    public static boolean suffocatesAt(GrimPlayer player, SimpleCollisionBox playerBB) {
        // Blocks are stored in YZX order
        for (int y = (int) Math.floor(playerBB.minY); y < Math.ceil(playerBB.maxY); y++) {
            for (int z = (int) Math.floor(playerBB.minZ); z < Math.ceil(playerBB.maxZ); z++) {
                for (int x = (int) Math.floor(playerBB.minX); x < Math.ceil(playerBB.maxX); x++) {
                    if (doesBlockSuffocate(player, x, y, z)) {
                        // Mojang re-added soul sand pushing by checking if the player is actually in the block
                        // (This is why from 1.14-1.15 soul sand didn't push)
                        BlockState data = player.compensatedWorld.getBlockStateAt(x, y, z);
                        CollisionBox box = ClientBlockShapes.movement(player, data, x, y, z);

                        if (!box.isIntersected(playerBB)) continue;

                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean doesBlockSuffocate(GrimPlayer player, int x, int y, int z) {
        BlockState data = player.compensatedWorld.getBlockStateAt(x, y, z);
        return doesBlockSuffocate(data, player.compensatedWorld, new BlockPos(x, y, z));
    }

    public static boolean doesBlockSuffocate(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isSuffocating(level, pos);
    }

    // Thanks Tuinity
    public static boolean hasMaterial(GrimPlayer player, SimpleCollisionBox checkBox, Predicate<Pair<BlockData, BlockPos>> searchingFor) {
        int minBlockX = (int) Math.floor(checkBox.minX);
        int maxBlockX = (int) Math.floor(checkBox.maxX);
        int minBlockY = (int) Math.floor(checkBox.minY);
        int maxBlockY = (int) Math.floor(checkBox.maxY);
        int minBlockZ = (int) Math.floor(checkBox.minZ);
        int maxBlockZ = (int) Math.floor(checkBox.maxZ);

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
            int minZ = currChunkZ == minChunkZ ? minBlockZ & 15 : 0; // coordinate in chunk
            int maxZ = currChunkZ == maxChunkZ ? maxBlockZ & 15 : 15; // coordinate in chunk

            for (int currChunkX = minChunkX; currChunkX <= maxChunkX; ++currChunkX) {
                int minX = currChunkX == minChunkX ? minBlockX & 15 : 0; // coordinate in chunk
                int maxX = currChunkX == maxChunkX ? maxBlockX & 15 : 15; // coordinate in chunk

                int chunkXGlobalPos = currChunkX << 4;
                int chunkZGlobalPos = currChunkZ << 4;

                CachedChunk chunk = player.compensatedWorld.getChunk(currChunkX, currChunkZ);

                if (chunk == null) continue;
                for (int y = minYIterate; y <= maxYIterate; ++y) {
                    CachedSection section = chunk.getSection((y >> 4) - minSection);

                    if (section == null || (IS_FOURTEEN && section.isEmpty())) { // Check for empty on 1.13+ servers
                        // empty
                        // skip to next section
                        y = (y & ~(15)) + 15; // increment by 15: iterator loop increments by the extra one
                        continue;
                    }

                    for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                        for (int currX = minX; currX <= maxX; ++currX) {
                            int x = currX | chunkXGlobalPos;
                            int z = currZ | chunkZGlobalPos;

                            BlockState data = section.getState(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));

                            if (searchingFor.test(new Pair<>(ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(data), new BlockPos(x, y, z)))) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static boolean onClimbable(GrimPlayer player, double x, double y, double z) {
        return onClimbable(player, x, y, z, player.isGliding);
    }

    public static boolean onClimbable(GrimPlayer player, double x, double y, double z, boolean gliding) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        BlockState nmsState = player.compensatedWorld.getBlockStateAt(blockX, blockY, blockZ);
        if (gliding
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)
                && canGlideThrough(nmsState)) {
            return false;
        }
        if (isClimbable(nmsState)) {
            return true;
        }

        return trapdoorUsableAsLadder(player, x, y, z, nmsState);
    }

    private static boolean canGlideThrough(BlockState state) {
        Material material = ac.grim.grimac.network.protocol.util.SpigotConversionUtil
                .fromNmsBlockState(state)
                .getMaterial();
        return switch (material.name()) {
            case "VINE", "TWISTING_VINES", "TWISTING_VINES_PLANT",
                 "WEEPING_VINES", "WEEPING_VINES_PLANT",
                 "CAVE_VINES", "CAVE_VINES_PLANT" -> true;
            default -> false;
        };
    }

    public static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE);
    }

    public static boolean trapdoorUsableAsLadder(GrimPlayer player, double x, double y, double z, BlockState blockState) {
        if (!NmsBlockTags.isTrapdoor(blockState)) return false;
        if (NmsBlockTags.getBoolean(blockState, BlockStateProperties.OPEN)) {
            BlockState blockBelow = player.compensatedWorld.getBlockStateAt((int) Math.floor(x), (int) Math.floor(y - 1), (int) Math.floor(z));

            if (blockBelow.getBlock() == Blocks.LADDER) {
                return NmsBlockTags.getFacing(blockState) == NmsBlockTags.getFacing(blockBelow);
            }
        }

        return false;
    }

    public enum Axis {
        X,
        Y,
        Z
    }
}
