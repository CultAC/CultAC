package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

public class Collisions {
    private static final double COLLISION_EPSILON = 1.0E-7;
    private static final List<Axis> Y_X_Z = List.of(Axis.Y, Axis.X, Axis.Z);
    private static final List<Axis> Y_Z_X = List.of(Axis.Y, Axis.Z, Axis.X);
    private static final Vec3 COBWEB_STUCK_SPEED = new Vec3(0.25, 0.05000000074505806, 0.25);
    private static final Vec3 WEAVING_COBWEB_STUCK_SPEED = new Vec3(0.5, 0.25, 0.5);
    private static final Vec3 SWEET_BERRY_BUSH_STUCK_SPEED = new Vec3(0.800000011920929, 0.75, 0.800000011920929);
    private static final Vec3 POWDER_SNOW_STUCK_SPEED = new Vec3(0.8999999761581421, 1.5, 0.8999999761581421);

    private static final boolean IS_FOURTEEN = true; // Current runtime is newer than 1.14.

    public static Vec3 collide(CultPlayer player, double desiredX, double desiredY, double desiredZ) {
        return collide(player, player.boundingBox, desiredX, desiredY, desiredZ);
    }

    public static Vec3 collide(
            CultPlayer player, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ) {
        return collide(player, box, desiredX, desiredY, desiredZ, getAxisStepOrder(desiredX, desiredZ), true);
    }

    public static Vec3 collideWithAdditionalCollisionBoxes(
            CultPlayer player,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<SimpleCollisionBox> additionalCollisionBoxes) {
        return collide(
                player,
                box,
                desiredX,
                desiredY,
                desiredZ,
                getAxisStepOrder(desiredX, desiredZ),
                true,
                box.minY,
                additionalCollisionBoxes);
    }

    public static Vec3 collideWithAdditionalCollisionBoxes(
            CultPlayer player,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<Axis> order,
            boolean allowStepping,
            List<SimpleCollisionBox> additionalCollisionBoxes,
            boolean onGroundForStep) {
        return collide(
                player,
                box,
                desiredX,
                desiredY,
                desiredZ,
                order,
                allowStepping,
                box.minY,
                additionalCollisionBoxes,
                onGroundForStep);
    }

    public static Vec3 collide(
            CultPlayer player,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<Axis> order,
            boolean allowStepping) {
        return collide(player, box, desiredX, desiredY, desiredZ, order, allowStepping, box.minY);
    }

    private static Vec3 collide(
            CultPlayer player,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<Axis> order,
            boolean allowStepping,
            double entityBottom) {
        return collide(player, box, desiredX, desiredY, desiredZ, order, allowStepping, entityBottom, null);
    }

    private static Vec3 collide(
            CultPlayer player,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<Axis> order,
            boolean allowStepping,
            double entityBottom,
            List<SimpleCollisionBox> additionalCollisionBoxes) {
        return collide(
                player,
                box,
                desiredX,
                desiredY,
                desiredZ,
                order,
                allowStepping,
                entityBottom,
                additionalCollisionBoxes,
                player.lastOnGround);
    }

    private static Vec3 collide(
            CultPlayer player,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<Axis> order,
            boolean allowStepping,
            double entityBottom,
            List<SimpleCollisionBox> additionalCollisionBoxes,
            boolean onGroundForStep) {
        if (desiredX == 0 && desiredY == 0 && desiredZ == 0) return Vec3.ZERO;

        SimpleCollisionBox grabBoxesBB = box.copy();
        double stepUpHeight = player.getMaxUpStep();
        boolean separateStepQuery = !player.isBedrockMovement();

        if (desiredX == 0.0 && desiredZ == 0.0) {
            if (desiredY > 0.0) {
                grabBoxesBB.maxY += desiredY;
            } else {
                grabBoxesBB.minY += desiredY;
            }
        } else {
            if (!separateStepQuery && allowStepping && (stepUpHeight > 0.0 && (onGroundForStep || desiredY < 0))) {
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

        SimpleCollisionBox moved = new SimpleCollisionBox();
        Vec3 collisionResult = collideBoundingBoxLegacy(
                desiredX,
                desiredY,
                desiredZ,
                box,
                desiredMovementCollisionBoxes,
                order,
                SimpleCollisionBox.AxisEpsilon.JAVA,
                moved);

        // While running up stairs and holding space, the player activates the "lastOnGround" part without otherwise
        // being able to step
        boolean movingIntoGround =
                (onGroundForStep || (collisionResult.y != desiredY && desiredY < 0)) && allowStepping;

        // If the player has x or z collision, is going in the downwards direction in the last or this tick, and can
        // step up
        // If not, just return the collisions without stepping up that we calculated earlier
        if (stepUpHeight > 0.0F
                && movingIntoGround
                && (collisionResult.x != desiredX || collisionResult.z != desiredZ)) {
            // MCP-Reborn and ero-minecraft-source 1.21 Entity#collide no longer
            // tries a fixed maxUpStep and then falls back down. It builds a
            // search box from the post-downward-collision start, tries each
            // collider Y coordinate as a candidate step height, and accepts the
            // first candidate that improves horizontal movement.
            boolean collidedDown = collisionResult.y != desiredY && desiredY < 0.0D;
            SimpleCollisionBox stepStartBox =
                    collidedDown ? box.copy().offset(0.0D, collisionResult.y, 0.0D) : box.copy();
            SimpleCollisionBox stepSearchBox = stepStartBox.copy().expandToCoordinate(desiredX, stepUpHeight, desiredZ);
            if (!collidedDown) {
                stepSearchBox.expandToCoordinate(0.0D, separateStepQuery ? -(double) 1.0E-5F : -1.0E-5D, 0.0D);
            }

            List<SimpleCollisionBox> stepCollisionBoxes = new ArrayList<>();
            getCollisionBoxes(player, stepSearchBox, stepCollisionBoxes, false, entityBottom);
            addAdditionalCollisionBoxes(stepSearchBox, stepCollisionBoxes, additionalCollisionBoxes);
            for (double candidateHeight :
                    collectCandidateStepUpHeights(stepStartBox, stepCollisionBoxes, stepUpHeight, collisionResult.y)) {
                Vec3 stepResult = collideBoundingBoxLegacy(
                        desiredX,
                        candidateHeight,
                        desiredZ,
                        stepStartBox,
                        stepCollisionBoxes,
                        order,
                        SimpleCollisionBox.AxisEpsilon.JAVA,
                        moved);
                if (getHorizontalDistanceSqr(stepResult) > getHorizontalDistanceSqr(collisionResult)) {
                    double alreadyMovedDown = box.minY - stepStartBox.minY;
                    return stepResult.subtract(0.0D, alreadyMovedDown, 0.0D);
                }
            }
        }

        return collisionResult;
    }

    private static void addAdditionalCollisionBoxes(
            SimpleCollisionBox queryBox,
            List<SimpleCollisionBox> collisionBoxes,
            List<SimpleCollisionBox> additionalCollisionBoxes) {
        if (additionalCollisionBoxes == null || additionalCollisionBoxes.isEmpty()) {
            return;
        }

        for (SimpleCollisionBox box : additionalCollisionBoxes) {
            if (box.isCollided(queryBox)) {
                collisionBoxes.add(box);
            }
        }
    }

    private static List<Axis> getAxisStepOrder(Vec3 movement) {
        return getAxisStepOrder(movement.x, movement.z);
    }

    private static List<Axis> getAxisStepOrder(double x, double z) {
        return Math.abs(x) < Math.abs(z) ? Y_Z_X : Y_X_Z;
    }

    private static float[] collectCandidateStepUpHeights(
            SimpleCollisionBox boundingBox,
            List<SimpleCollisionBox> colliders,
            double maxStepHeight,
            double stepHeightToSkip) {
        StepHeights heights = new StepHeights();
        float maxStepHeightFloat = (float) maxStepHeight;
        float stepHeightToSkipFloat = (float) stepHeightToSkip;
        for (SimpleCollisionBox collider : colliders) {
            if (collider instanceof ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox voxel) {
                for (int i = 0; i < voxel.stepCoordinateCount(); i++)
                    heights.add(
                            (float) (voxel.stepCoordinateAt(i) - boundingBox.minY),
                            maxStepHeightFloat,
                            stepHeightToSkipFloat);
                continue;
            }
            heights.add((float) (collider.minY - boundingBox.minY), maxStepHeightFloat, stepHeightToSkipFloat);
            heights.add((float) (collider.maxY - boundingBox.minY), maxStepHeightFloat, stepHeightToSkipFloat);
        }
        return heights.sorted();
    }

    private static final class StepHeights {
        private float[] values = new float[4];
        private int size;

        void add(float height, float maxStepHeight, float stepHeightToSkip) {
            if (height < 0.0F || height > maxStepHeight || height == stepHeightToSkip) return;
            for (int i = 0; i < size; i++) if (values[i] == height) return;
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = height;
        }

        float[] sorted() {
            java.util.Arrays.sort(values, 0, size);
            return size == values.length ? values : java.util.Arrays.copyOf(values, size);
        }
    }

    /** Read-only single-axis clip: no copied actor box, singleton order list, or result vector. */
    public static double collideAxis(
            double distance,
            SimpleCollisionBox actor,
            List<SimpleCollisionBox> colliders,
            Axis axis,
            SimpleCollisionBox.AxisEpsilon epsilon) {
        for (int i = 0; i < colliders.size(); i++) {
            var collider = colliders.get(i);
            distance = switch (axis) {
                case X -> collider.collideX(actor, distance, epsilon);
                case Y -> collider.collideY(actor, distance, epsilon);
                case Z -> collider.collideZ(actor, distance, epsilon);
            };
        }
        return distance;
    }

    public static boolean addWorldBorder(
            CultPlayer player,
            SimpleCollisionBox wantedBB,
            List<SimpleCollisionBox> listOfBlocks,
            boolean onlyCheckCollide) {
        // Both 26.2 and 26.3 still add the border in the Entity overload of
        // collectCollidersIgnoringWorldBorder. Only its CollisionContext
        // overload omits it; ordinary entity travel uses the former.
        final PacketWorldBorder border = player.checkManager.getListener(PacketWorldBorder.class);
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();

        // For some reason, the game limits the border to 29999984 blocks wide
        double size = border.getCurrentDiameter() / 2;
        double absoluteMaxSize = border.getAbsoluteMaxSize();

        double minX = Math.floor(CultMath.clamp(centerX - size, -absoluteMaxSize, absoluteMaxSize));
        double minZ = Math.floor(CultMath.clamp(centerZ - size, -absoluteMaxSize, absoluteMaxSize));
        double maxX = Math.ceil(CultMath.clamp(centerX + size, -absoluteMaxSize, absoluteMaxSize));
        double maxZ = Math.ceil(CultMath.clamp(centerZ + size, -absoluteMaxSize, absoluteMaxSize));

        // If the player is fully within the worldborder
        double toMinX = player.lastX - minX;
        double toMaxX = maxX - player.lastX;
        double minimumInXDirection = Math.min(toMinX, toMaxX);

        double toMinZ = player.lastZ - minZ;
        double toMaxZ = maxZ - player.lastZ;
        double minimumInZDirection = Math.min(toMinZ, toMaxZ);

        double distanceToBorder = Math.min(minimumInXDirection, minimumInZDirection);

        // If the player's is within 16 blocks of the worldborder, add the worldborder to the collisions (optimization)
        if (distanceToBorder < 16
                && player.lastX > minX
                && player.lastX < maxX
                && player.lastZ > minZ
                && player.lastZ < maxZ) {
            if (listOfBlocks == null) listOfBlocks = new ArrayList<>();

            // South border
            listOfBlocks.add(new SimpleCollisionBox(
                    minX - 10, Double.NEGATIVE_INFINITY, maxZ, maxX + 10, Double.POSITIVE_INFINITY, maxZ, false));
            // North border
            listOfBlocks.add(new SimpleCollisionBox(
                    minX - 10, Double.NEGATIVE_INFINITY, minZ, maxX + 10, Double.POSITIVE_INFINITY, minZ, false));
            // East border
            listOfBlocks.add(new SimpleCollisionBox(
                    maxX, Double.NEGATIVE_INFINITY, minZ - 10, maxX, Double.POSITIVE_INFINITY, maxZ + 10, false));
            // West border
            listOfBlocks.add(new SimpleCollisionBox(
                    minX, Double.NEGATIVE_INFINITY, minZ - 10, minX, Double.POSITIVE_INFINITY, maxZ + 10, false));

            if (onlyCheckCollide) {
                for (SimpleCollisionBox box : listOfBlocks) {
                    if (box.isIntersected(wantedBB)) return true;
                }
            }
        }
        return false;
    }

    // This is mostly taken from Tuinity collisions
    public static boolean getCollisionBoxes(
            CultPlayer player,
            SimpleCollisionBox wantedBB,
            List<SimpleCollisionBox> listOfBlocks,
            boolean onlyCheckCollide) {
        return getCollisionBoxes(
                player, wantedBB, listOfBlocks, onlyCheckCollide, player == null ? Double.NaN : player.y);
    }

    public static boolean getCollisionBoxes(
            CultPlayer player,
            SimpleCollisionBox wantedBB,
            List<SimpleCollisionBox> listOfBlocks,
            boolean onlyCheckCollide,
            double entityBottom) {
        return getCollisionBoxes(
                player, wantedBB, listOfBlocks, onlyCheckCollide, entityBottom, JavaCollisionState.current(player));
    }

    public static boolean getCollisionBoxes(
            CultPlayer player,
            SimpleCollisionBox wantedBB,
            List<SimpleCollisionBox> listOfBlocks,
            boolean onlyCheckCollide,
            double entityBottom,
            JavaCollisionState actor) {
        boolean modernJava = !player.isBedrockMovement();
        if (modernJava)
            ac.cult.blocksim.data.Box.validateBounds(
                    wantedBB.minX, wantedBB.minY, wantedBB.minZ, wantedBB.maxX, wantedBB.maxY, wantedBB.maxZ);
        ac.cult.blocksim.engine.shapes.VoxelShape queryShape = null;

        boolean collided = addWorldBorder(player, wantedBB, listOfBlocks, onlyCheckCollide);
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

                            int data = section.getStateId(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));
                            var facts = ac.cult.blocksim.data.DataTables.defaults()
                                    .registry()
                                    .facts(data);
                            if (facts.has(ac.cult.blocksim.data.StateFacts.AIR)) continue;
                            // Thanks SpottedLeaf for this optimization, I took edgeCount from Tuinity
                            int edgeCount = ((x == minBlockX || x == maxBlockX) ? 1 : 0)
                                    + ((y == minBlockY || y == maxBlockY) ? 1 : 0)
                                    + ((z == minBlockZ || z == maxBlockZ) ? 1 : 0);

                            if (edgeCount != 3
                                    && (edgeCount != 1 || facts.has(ac.cult.blocksim.data.StateFacts.LARGE_COLLISION))
                                    && (edgeCount != 2
                                            || ac.cult.blocksim.data.BlockIds.is(
                                                    data,
                                                    modernJava
                                                            ? ac.cult.blocksim.data.BlockIds.MOVING_PISTON
                                                            : ac.cult.blocksim.data.BlockIds.PISTON_HEAD))) {
                                if (modernJava
                                        && onlyCheckCollide
                                        && ClientBlockShapes.hasStaticFullMovement(
                                                player, data, x, y, z, entityBottom)) {
                                    // Paper's check-only scan: no collider, translation or query shape is needed.
                                    if (wantedBB.minX < x + 1.0
                                            && wantedBB.maxX > x
                                            && wantedBB.minY < y + 1.0
                                            && wantedBB.maxY > y
                                            && wantedBB.minZ < z + 1.0
                                            && wantedBB.maxZ > z) return true;
                                    continue;
                                }
                                CollisionBox collisionBox =
                                        ClientBlockShapes.movement(player, data, x, y, z, entityBottom, actor);
                                if (collisionBox.isNull()) continue;
                                if (modernJava
                                        && queryShape == null
                                        && collisionBox
                                                instanceof
                                                ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox voxel
                                        && !voxel.usesStrictFullBlockIntersection()) {
                                    queryShape = ac.cult.blocksim.engine.shapes.Shapes.create(
                                            wantedBB.minX,
                                            wantedBB.minY,
                                            wantedBB.minZ,
                                            wantedBB.maxX,
                                            wantedBB.maxY,
                                            wantedBB.maxZ);
                                }
                                // Vanilla BlockCollisions filters the swept query before axis clipping
                                // or collecting step heights. The full-block path deliberately uses
                                // strict AABB intersection; other voxel shapes use Shapes AND.
                                if (modernJava
                                        && !(collisionBox
                                                        instanceof
                                                        ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox
                                                                voxel
                                                ? voxel.intersectsQuery(wantedBB, queryShape)
                                                : collisionBox.isIntersected(wantedBB))) continue;
                                // Don't add to a list if we only care if the player intersects with the block
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

    public static Vec3 collideBoundingBoxLegacy(
            Vec3 toCollide,
            SimpleCollisionBox box,
            List<SimpleCollisionBox> desiredMovementCollisionBoxes,
            List<Axis> order) {
        return collideBoundingBoxLegacy(
                toCollide, box, desiredMovementCollisionBoxes, order, SimpleCollisionBox.AxisEpsilon.JAVA);
    }

    public static Vec3 collideBoundingBoxLegacy(
            Vec3 toCollide,
            SimpleCollisionBox box,
            List<SimpleCollisionBox> desiredMovementCollisionBoxes,
            List<Axis> order,
            SimpleCollisionBox.AxisEpsilon epsilon) {
        return collideBoundingBoxLegacy(
                toCollide.x,
                toCollide.y,
                toCollide.z,
                box,
                desiredMovementCollisionBoxes,
                order,
                epsilon,
                new SimpleCollisionBox());
    }

    /** Collides {@code (x, y, z)} from {@code box}, moving {@code setBB} as its working copy of {@code box}. */
    private static Vec3 collideBoundingBoxLegacy(
            double x,
            double y,
            double z,
            SimpleCollisionBox box,
            List<SimpleCollisionBox> desiredMovementCollisionBoxes,
            List<Axis> order,
            SimpleCollisionBox.AxisEpsilon epsilon,
            SimpleCollisionBox setBB) {
        setBB.set(box);

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

    public static boolean isEmpty(CultPlayer player, SimpleCollisionBox playerBB) {
        return isEmpty(player, playerBB, player == null ? Double.NaN : player.y);
    }

    public static boolean isEmpty(CultPlayer player, SimpleCollisionBox playerBB, double entityBottom) {
        return !getCollisionBoxes(player, playerBB, null, true, entityBottom);
    }

    public static double getHorizontalDistanceSqr(Vec3 vector) {
        return vector.x * vector.x + vector.z * vector.z;
    }

    public static Vector3dm maybeBackOffFromEdge(
            Vector3dm vec3,
            CultPlayer player,
            boolean lastOnGround,
            SimpleCollisionBox playerBB,
            boolean overrideVersion) {
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
            while (x != 0.0
                    && z != 0.0
                    && isEmpty(player, player.boundingBox.copy().offset(x, maxStepDown, z))) {
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
            vec3 = new Vector3dm(x, vec3.getY(), z);
        }

        return vec3;
    }

    public static boolean isAboveGround(CultPlayer player, boolean lastOnGround, SimpleCollisionBox playerBB) {
        // https://bugs.mojang.com/browse/MC-2404
        return lastOnGround || !isEmpty(player, playerBB.copy().offset(0.0, -player.getMaxUpStep(), 0.0));
    }

    public static boolean isSlidingDown(CultPlayer player, int locationX, int locationY, int locationZ, Vec3 pos) {
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

    public static Vec3 checkStuckSpeed(CultPlayer player, SimpleCollisionBox aabb) {
        return checkStuckSpeed(player, aabb, true);
    }

    public static Vec3 checkStuckSpeed(CultPlayer player, SimpleCollisionBox aabb, boolean powderSnowCanApply) {
        // Use the bounding box for after the player's movement is applied
        double expandAmount = 1e-7;
        Vec3 blockPos = new Vec3(aabb.minX + expandAmount, aabb.minY + expandAmount, aabb.minZ + expandAmount);
        Vec3 blockPos2 = new Vec3(aabb.maxX - expandAmount, aabb.maxY - expandAmount, aabb.maxZ - expandAmount);

        int blockPosX = CultMath.floor(blockPos.x);
        int blockPosY = CultMath.floor(blockPos.y);
        int blockPosZ = CultMath.floor(blockPos.z);
        int blockPos2X = CultMath.floor(blockPos2.x);
        int blockPos2Y = CultMath.floor(blockPos2.y);
        int blockPos2Z = CultMath.floor(blockPos2.z);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(
                player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z)) return null;

        Vec3 stuckSpeed = null;
        // Order matters
        for (int i = blockPosX; i <= blockPos2X; ++i) {
            for (int j = blockPosY; j <= blockPos2Y; ++j) {
                for (int k = blockPosZ; k <= blockPos2Z; ++k) {
                    int block = player.compensatedWorld.getBlockStateIdAt(i, j, k);
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

    public static Vec3 checkStuckSpeedAlongMovement(
            CultPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb) {
        return checkStuckSpeedAlongMovement(player, fromAabb, toAabb, true);
    }

    public static Vec3 checkStuckSpeedAlongMovement(
            CultPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, boolean powderSnowCanApply) {
        double expandAmount = 1e-7;
        int blockPosX = CultMath.floor(Math.min(fromAabb.minX, toAabb.minX) + expandAmount);
        int blockPosY = CultMath.floor(Math.min(fromAabb.minY, toAabb.minY) + expandAmount);
        int blockPosZ = CultMath.floor(Math.min(fromAabb.minZ, toAabb.minZ) + expandAmount);
        int blockPos2X = CultMath.floor(Math.max(fromAabb.maxX, toAabb.maxX) - expandAmount);
        int blockPos2Y = CultMath.floor(Math.max(fromAabb.maxY, toAabb.maxY) - expandAmount);
        int blockPos2Z = CultMath.floor(Math.max(fromAabb.maxZ, toAabb.maxZ) - expandAmount);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(
                player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z)) return null;

        Vec3 stuckSpeed = null;
        for (int i = blockPosX; i <= blockPos2X; ++i) {
            for (int j = blockPosY; j <= blockPos2Y; ++j) {
                for (int k = blockPosZ; k <= blockPos2Z; ++k) {
                    int block = player.compensatedWorld.getBlockStateIdAt(i, j, k);
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

    public static Vec3 checkStuckSpeedAlongMovement(
            CultPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement) {
        return checkStuckSpeedAlongMovement(player, fromAabb, toAabb, axisOrderMovement, true);
    }

    public static Vec3 checkStuckSpeedAlongMovement(
            CultPlayer player,
            SimpleCollisionBox fromAabb,
            SimpleCollisionBox toAabb,
            Vec3 axisOrderMovement,
            boolean powderSnowCanApply) {
        double movementX = centerX(toAabb) - centerX(fromAabb);
        double movementY = centerY(toAabb) - centerY(fromAabb);
        double movementZ = centerZ(toAabb) - centerZ(fromAabb);
        if (movementX * movementX + movementY * movementY + movementZ * movementZ == 0.0D) {
            return checkStuckSpeedAlongMovement(player, fromAabb, toAabb, powderSnowCanApply);
        }

        Vec3 stuckSpeed = null;
        SimpleCollisionBox stepFrom = fromAabb.copy();
        // MCP-Reborn Entity#move stores the clipped delta plus the original movement.
        // Entity#checkInsideBlocks then replays the clipped delta one axis at a time
        // using Direction.axisStepOrder(originalMovement), so corner-only diagonal
        // sweeps must not trigger cobweb/powder-snow/berry-bush stuck speed.
        for (Axis axis : getAxisStepOrder(axisOrderMovement)) {
            double movementOnAxis = switch (axis) {
                case X -> movementX;
                case Y -> movementY;
                case Z -> movementZ;
            };
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
    public static Vec3 checkStuckSpeedAlongMovement26Dot2(
            CultPlayer player,
            SimpleCollisionBox fromAabb,
            SimpleCollisionBox toAabb,
            Vec3 axisOrderMovement,
            boolean powderSnowCanApply) {
        if (!InsideBlockEffectScan.mayHaveEffects(player, fromAabb, toAabb, true)) return null;
        int minX = CultMath.floor(Math.min(fromAabb.minX, toAabb.minX) + COLLISION_EPSILON);
        int minY = CultMath.floor(Math.min(fromAabb.minY, toAabb.minY) + COLLISION_EPSILON);
        int minZ = CultMath.floor(Math.min(fromAabb.minZ, toAabb.minZ) + COLLISION_EPSILON);
        int maxX = CultMath.floor(Math.max(fromAabb.maxX, toAabb.maxX) - COLLISION_EPSILON);
        int maxY = CultMath.floor(Math.max(fromAabb.maxY, toAabb.maxY) - COLLISION_EPSILON);
        int maxZ = CultMath.floor(Math.max(fromAabb.maxZ, toAabb.maxZ) - COLLISION_EPSILON);
        if (CheckIfChunksLoaded.isChunksUnloadedAt(player, minX, minY, minZ, maxX, maxY, maxZ)) {
            return null;
        }

        return resolveOrderedStuckSpeed26Dot2(
                fromAabb,
                toAabb,
                axisOrderMovement,
                (x, y, z) -> getStuckSpeedForBlock(
                        player, player.compensatedWorld.getBlockStateIdAt(x, y, z), powderSnowCanApply));
    }

    static Vec3 resolveOrderedStuckSpeed26Dot2(
            SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement, StuckSpeedLookup lookup) {
        Vec3[] result = new Vec3[1];
        visitInsideBlocks26Dot2(fromAabb, toAabb, axisOrderMovement, (from, to, pos, precise) -> {
            Vec3 speed = lookup.get(pos.getX(), pos.getY(), pos.getZ());
            if (speed == null || !sweptFullBlockEntityInside(from, to, pos.getX(), pos.getY(), pos.getZ()))
                return false;
            result[0] = speed;
            return true;
        });
        return result[0];
    }

    /** Entity#checkInsideBlocks: one visited set and iteration budget across the axis segments. */
    public static void visitInsideBlocks26Dot2(
            SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 originalMovement, InsideBlockVisitor visitor) {
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

    private static int visitInsideSegment26Dot2(
            SimpleCollisionBox from, SimpleCollisionBox to, LongSet visited, int budget, InsideBlockVisitor visitor) {
        int[] iteration = {0};
        SimpleCollisionBox deflated = SimpleCollisionBox.between(to.minX, to.minY, to.minZ, to.maxX, to.maxY, to.maxZ)
                .deflate(1.0E-5F);
        boolean movedFar = boxCenter(from).distanceToSqr(boxCenter(to)) > CultMath.square(0.9999900000002526);
        forEachBlockIntersectedBetween26Dot2(boxCenter(from), boxCenter(to), to, (pos, step) -> {
            if (step >= budget) return false;
            iteration[0] = step;
            if (!visited.contains(pos.asLong())
                    && visitor.visit(
                            from,
                            to,
                            pos,
                            movedFar
                                    || deflated.intersects(
                                            pos.getX(),
                                            pos.getY(),
                                            pos.getZ(),
                                            pos.getX() + 1,
                                            pos.getY() + 1,
                                            pos.getZ() + 1))) {
                visited.add(pos.asLong());
            }
            return true;
        });
        return iteration[0] + 1;
    }

    @FunctionalInterface
    public interface InsideBlockVisitor {
        /** Return true only when the block or its fluid was actually contacted. */
        boolean visit(SimpleCollisionBox from, SimpleCollisionBox to, BlockPos pos, boolean precise);
    }

    private static boolean forEachBlockIntersectedBetween26Dot2(
            Vec3 from, Vec3 to, SimpleCollisionBox boxAtTarget, OrderedBlockVisitor visitor) {
        Vec3 travel = to.subtract(from);
        SimpleCollisionBox target = SimpleCollisionBox.between(
                        boxAtTarget.minX,
                        boxAtTarget.minY,
                        boxAtTarget.minZ,
                        boxAtTarget.maxX,
                        boxAtTarget.maxY,
                        boxAtTarget.maxZ)
                .deflate(1.0E-5F);
        if (travel.lengthSqr() < CultMath.square(1.0E-5F)) {
            for (BlockPos pos : BlockPos.betweenClosed(
                    BlockPos.containing(target.minX, target.minY, target.minZ),
                    BlockPos.containing(target.maxX, target.maxY, target.maxZ))) {
                if (!visitor.visit(pos, 0)) return false;
            }
            return true;
        }

        LongSet visited = new LongOpenHashSet();
        if (!forEachBlockInDirection26Dot2(target.move(-travel.x, -travel.y, -travel.z), travel, 0, (pos, step) -> {
            if (!visitor.visit(pos, step)) return false;
            visited.add(pos.asLong());
            return true;
        })) return false;

        int iterations = addBlocksAlongTravel26Dot2(visited, travel, target, visitor);
        if (iterations < 0) return false;
        return forEachBlockInDirection26Dot2(
                target, travel, iterations + 1, (pos, step) -> !visited.add(pos.asLong()) || visitor.visit(pos, step));
    }

    private static boolean forEachBlockInDirection26Dot2(
            SimpleCollisionBox box, Vec3 direction, int iteration, OrderedBlockVisitor visitor) {
        return forEachBlockInDirection26Dot2(
                CultMath.floor(box.minX),
                CultMath.floor(box.minY),
                CultMath.floor(box.minZ),
                CultMath.floor(box.maxX),
                CultMath.floor(box.maxY),
                CultMath.floor(box.maxZ),
                direction,
                iteration,
                visitor);
    }

    private static boolean forEachBlockInDirection26Dot2(
            int firstX,
            int firstY,
            int firstZ,
            int secondX,
            int secondY,
            int secondZ,
            Vec3 direction,
            int iteration,
            OrderedBlockVisitor visitor) {
        // Vanilla 26.2 BlockPos#betweenCornersInDirection, without linking to
        // that newer server API. Direction#axisStepOrder is YXZ or YZX, with
        // the last axis visited fastest and ties choosing YXZ.
        int minX = Math.min(firstX, secondX), maxX = Math.max(firstX, secondX);
        int minY = Math.min(firstY, secondY), maxY = Math.max(firstY, secondY);
        int minZ = Math.min(firstZ, secondZ), maxZ = Math.max(firstZ, secondZ);
        int stepX = direction.x >= 0.0 ? 1 : -1;
        int stepY = direction.y >= 0.0 ? 1 : -1;
        int stepZ = direction.z >= 0.0 ? 1 : -1;
        int startX = stepX > 0 ? minX : maxX;
        int startY = stepY > 0 ? minY : maxY;
        int startZ = stepZ > 0 ? minZ : maxZ;
        boolean xFirst = !(Math.abs(direction.x) < Math.abs(direction.z));
        int middleMax = xFirst ? maxX - minX : maxZ - minZ;
        int innerMax = xFirst ? maxZ - minZ : maxX - minX;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y <= maxY - minY; y++) {
            for (int middle = 0; middle <= middleMax; middle++) {
                for (int inner = 0; inner <= innerMax; inner++) {
                    cursor.set(
                            startX + stepX * (xFirst ? middle : inner),
                            startY + stepY * y,
                            startZ + stepZ * (xFirst ? inner : middle));
                    if (!visitor.visit(cursor, iteration)) return false;
                }
            }
        }
        return true;
    }

    private static int addBlocksAlongTravel26Dot2(
            LongSet visited, Vec3 travel, SimpleCollisionBox target, OrderedBlockVisitor visitor) {
        int[] corner = furthestCorner26Dot2(travel);
        double sizeX = target.getXsize();
        double sizeY = target.getYsize();
        double sizeZ = target.getZsize();
        double toCornerX = CultMath.lerp(0.5, target.minX, target.maxX) + sizeX * 0.5D * corner[0];
        double toCornerY = CultMath.lerp(0.5, target.minY, target.maxY) + sizeY * 0.5D * corner[1];
        double toCornerZ = CultMath.lerp(0.5, target.minZ, target.maxZ) + sizeZ * 0.5D * corner[2];
        double fromCornerX = toCornerX - travel.x;
        double fromCornerY = toCornerY - travel.y;
        double fromCornerZ = toCornerZ - travel.z;
        // AABB#clip steps along to - from, which can round differently from the travel.
        double clipX = toCornerX - fromCornerX;
        double clipY = toCornerY - fromCornerY;
        double clipZ = toCornerZ - fromCornerZ;
        int x = CultMath.floor(fromCornerX);
        int y = CultMath.floor(fromCornerY);
        int z = CultMath.floor(fromCornerZ);
        int signX = (int) Math.signum(travel.x);
        int signY = (int) Math.signum(travel.y);
        int signZ = (int) Math.signum(travel.z);
        double deltaX = signX == 0 ? Double.MAX_VALUE : signX / travel.x;
        double deltaY = signY == 0 ? Double.MAX_VALUE : signY / travel.y;
        double deltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / travel.z;
        double nextX = deltaX * (signX > 0 ? 1.0D - CultMath.frac(fromCornerX) : CultMath.frac(fromCornerX));
        double nextY = deltaY * (signY > 0 ? 1.0D - CultMath.frac(fromCornerY) : CultMath.frac(fromCornerY));
        double nextZ = deltaZ * (signZ > 0 ? 1.0D - CultMath.frac(fromCornerZ) : CultMath.frac(fromCornerZ));
        int iterations = 0;
        OrderedBlockVisitor unvisited = (pos, step) -> !visited.add(pos.asLong()) || visitor.visit(pos, step);

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

            double scale = clipScale26Dot2(
                    x, y, z, x + 1.0D, y + 1.0D, z + 1.0D, fromCornerX, fromCornerY, fromCornerZ, clipX, clipY, clipZ);
            if (scale < 0.0D) continue;
            iterations++;
            double hitX = clamp(fromCornerX + scale * clipX, x + 1.0E-5F, x + 1.0D - 1.0E-5F);
            double hitY = clamp(fromCornerY + scale * clipY, y + 1.0E-5F, y + 1.0D - 1.0E-5F);
            double hitZ = clamp(fromCornerZ + scale * clipZ, z + 1.0E-5F, z + 1.0D - 1.0E-5F);
            int oppositeX = CultMath.floor(hitX - sizeX * corner[0]);
            int oppositeY = CultMath.floor(hitY - sizeY * corner[1]);
            int oppositeZ = CultMath.floor(hitZ - sizeZ * corner[2]);

            if (!forEachBlockInDirection26Dot2(x, y, z, oppositeX, oppositeY, oppositeZ, travel, iterations, unvisited))
                return -1;
        }
        return iterations;
    }

    /**
     * {@link SimpleCollisionBox#clip(double, double, double, double, double, double, Vec3, Vec3)} from {@code from}
     * along {@code delta}: the scale of the hit, which is {@code from + scale * delta}, or -1 when it misses.
     */
    static double clipScale26Dot2(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            double fromX,
            double fromY,
            double fromZ,
            double deltaX,
            double deltaY,
            double deltaZ) {
        double nearest = 1.0D;
        nearest = clipAxis26Dot2(
                nearest, deltaX, minX, maxX, fromX, fromY, deltaY, minY, maxY, fromZ, deltaZ, minZ, maxZ);
        nearest = clipAxis26Dot2(
                nearest, deltaY, minY, maxY, fromY, fromZ, deltaZ, minZ, maxZ, fromX, deltaX, minX, maxX);
        nearest = clipAxis26Dot2(
                nearest, deltaZ, minZ, maxZ, fromZ, fromX, deltaX, minX, maxX, fromY, deltaY, minY, maxY);
        return nearest == 1.0D ? -1.0D : nearest;
    }

    /** One entry face of AABB#clip: {@code nearest}, or the smaller scale at which the segment enters that face. */
    private static double clipAxis26Dot2(
            double nearest,
            double movement,
            double lower,
            double upper,
            double origin,
            double originB,
            double deltaB,
            double lowerB,
            double upperB,
            double originC,
            double deltaC,
            double lowerC,
            double upperC) {
        if (Math.abs(movement) <= 1.0E-7D) return nearest;
        double t = ((movement > 0 ? lower : upper) - origin) / movement;
        if (!(0.0D < t && t < nearest)) return nearest;
        double pb = originB + t * deltaB;
        double pc = originC + t * deltaC;
        return lowerB - 1.0E-7D < pb && pb < upperB + 1.0E-7D && lowerC - 1.0E-7D < pc && pc < upperC + 1.0E-7D
                ? t
                : nearest;
    }

    private static int[] furthestCorner26Dot2(Vec3 movement) {
        double x = Math.abs(movement.x);
        double y = Math.abs(movement.y);
        double z = Math.abs(movement.z);
        int xSign = movement.x >= 0.0D ? 1 : -1;
        int ySign = movement.y >= 0.0D ? 1 : -1;
        int zSign = movement.z >= 0.0D ? 1 : -1;
        int signs = (xSign < 0 ? 1 : 0) | (ySign < 0 ? 2 : 0) | (zSign < 0 ? 4 : 0);
        if (x <= y && x <= z) return FURTHEST_CORNERS[signs];
        if (y <= z) return FURTHEST_CORNERS[8 + signs];
        return FURTHEST_CORNERS[16 + signs];
    }

    // Read-only: the three corner orders for each combination of movement signs.
    private static final int[][] FURTHEST_CORNERS = new int[24][];

    static {
        for (int signs = 0; signs < 8; signs++) {
            int xSign = (signs & 1) != 0 ? -1 : 1;
            int ySign = (signs & 2) != 0 ? -1 : 1;
            int zSign = (signs & 4) != 0 ? -1 : 1;
            FURTHEST_CORNERS[signs] = new int[] {-xSign, -zSign, ySign};
            FURTHEST_CORNERS[8 + signs] = new int[] {zSign, -ySign, -xSign};
            FURTHEST_CORNERS[16 + signs] = new int[] {-ySign, xSign, -zSign};
        }
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

    public static boolean canFullBlockAffectInsideBlockMovement(
            SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement, BlockPos pos) {
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

    public static boolean sweptFullBlockEntityInside(
            SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, int x, int y, int z) {
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

        double fromX = centerX(fromAabb), fromY = centerY(fromAabb), fromZ = centerZ(fromAabb);
        double toX = centerX(toAabb), toY = centerY(toAabb), toZ = centerZ(toAabb);
        return containsHalfOpen(minX, minY, minZ, maxX, maxY, maxZ, fromX, fromY, fromZ)
                || containsHalfOpen(minX, minY, minZ, maxX, maxY, maxZ, toX, toY, toZ)
                || segmentClipsBox(minX, minY, minZ, maxX, maxY, maxZ, fromX, fromY, fromZ, toX, toY, toZ);
    }

    public static String describeStuckSpeedSources(
            CultPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb, Vec3 axisOrderMovement) {
        StringBuilder sb = new StringBuilder();
        sb.append("from=")
                .append(formatBox(fromAabb))
                .append(" to=")
                .append(formatBox(toAabb))
                .append(" direct=")
                .append(collectDirectStuckSpeedHits(player, toAabb));

        if (axisOrderMovement == null
                || boxCenter(toAabb).subtract(boxCenter(fromAabb)).lengthSqr() == 0.0D) {
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

    private static double centerX(SimpleCollisionBox box) {
        return (box.minX + box.maxX) * 0.5D;
    }

    private static double centerY(SimpleCollisionBox box) {
        return (box.minY + box.maxY) * 0.5D;
    }

    private static double centerZ(SimpleCollisionBox box) {
        return (box.minZ + box.maxZ) * 0.5D;
    }

    private static Vec3 boxCenter(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) * 0.5D, (box.minY + box.maxY) * 0.5D, (box.minZ + box.maxZ) * 0.5D);
    }

    private static String collectDirectStuckSpeedHits(CultPlayer player, SimpleCollisionBox aabb) {
        double expandAmount = 1e-7;
        Vec3 blockPos = new Vec3(aabb.minX + expandAmount, aabb.minY + expandAmount, aabb.minZ + expandAmount);
        Vec3 blockPos2 = new Vec3(aabb.maxX - expandAmount, aabb.maxY - expandAmount, aabb.maxZ - expandAmount);

        int blockPosX = CultMath.floor(blockPos.x);
        int blockPosY = CultMath.floor(blockPos.y);
        int blockPosZ = CultMath.floor(blockPos.z);
        int blockPos2X = CultMath.floor(blockPos2.x);
        int blockPos2Y = CultMath.floor(blockPos2.y);
        int blockPos2Z = CultMath.floor(blockPos2.z);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(
                player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z)) {
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

    private static String collectSweptStuckSpeedHits(
            CultPlayer player, SimpleCollisionBox fromAabb, SimpleCollisionBox toAabb) {
        SimpleCollisionBox sweptCandidates = fromAabb.copy().union(toAabb);
        double expandAmount = 1e-7;
        Vec3 blockPos = new Vec3(
                sweptCandidates.minX + expandAmount,
                sweptCandidates.minY + expandAmount,
                sweptCandidates.minZ + expandAmount);
        Vec3 blockPos2 = new Vec3(
                sweptCandidates.maxX - expandAmount,
                sweptCandidates.maxY - expandAmount,
                sweptCandidates.maxZ - expandAmount);

        int blockPosX = CultMath.floor(blockPos.x);
        int blockPosY = CultMath.floor(blockPos.y);
        int blockPosZ = CultMath.floor(blockPos.z);
        int blockPos2X = CultMath.floor(blockPos2.x);
        int blockPos2Y = CultMath.floor(blockPos2.y);
        int blockPos2Z = CultMath.floor(blockPos2.z);

        if (CheckIfChunksLoaded.isChunksUnloadedAt(
                player, blockPosX, blockPosY, blockPosZ, blockPos2X, blockPos2Y, blockPos2Z)) {
            return "[unloaded]";
        }

        List<String> hits = new ArrayList<>();
        for (int x = blockPosX; x <= blockPos2X; ++x) {
            for (int y = blockPosY; y <= blockPos2Y; ++y) {
                for (int z = blockPosZ; z <= blockPos2Z; ++z) {
                    int block = player.compensatedWorld.getBlockStateIdAt(x, y, z);
                    Vec3 blockStuckSpeed = getStuckSpeedForBlock(player, block);
                    if (blockStuckSpeed != null && sweptFullBlockEntityInside(fromAabb, toAabb, x, y, z)) {
                        hits.add(x + "," + y + "," + z + ":"
                                + DataTables.defaults().registry().block(block).key() + "=" + blockStuckSpeed);
                    }
                }
            }
        }
        return hits.toString();
    }

    private static void appendStuckSpeedHit(CultPlayer player, List<String> hits, int x, int y, int z, String mode) {
        int block = player.compensatedWorld.getBlockStateIdAt(x, y, z);
        Vec3 blockStuckSpeed = getStuckSpeedForBlock(player, block);
        if (blockStuckSpeed != null) {
            hits.add(x + "," + y + "," + z + ":"
                    + DataTables.defaults().registry().block(block).key() + "=" + blockStuckSpeed + ":" + mode);
        }
    }

    private static String formatBox(SimpleCollisionBox box) {
        return "["
                + box.minX + "," + box.minY + "," + box.minZ
                + " -> "
                + box.maxX + "," + box.maxY + "," + box.maxZ
                + "]";
    }

    private static boolean containsHalfOpen(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            double pointX,
            double pointY,
            double pointZ) {
        return pointX >= minX && pointX < maxX && pointY >= minY && pointY < maxY && pointZ >= minZ && pointZ < maxZ;
    }

    private static boolean segmentClipsBox(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            double fromX,
            double fromY,
            double fromZ,
            double toX,
            double toY,
            double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        if (dx > COLLISION_EPSILON && clipsFace(dx, dy, dz, minX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ))
            return true;
        if (dx < -COLLISION_EPSILON && clipsFace(dx, dy, dz, maxX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ))
            return true;
        if (dy > COLLISION_EPSILON && clipsFace(dy, dz, dx, minY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX))
            return true;
        if (dy < -COLLISION_EPSILON && clipsFace(dy, dz, dx, maxY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX))
            return true;
        if (dz > COLLISION_EPSILON && clipsFace(dz, dx, dy, minZ, minX, maxX, minY, maxY, fromZ, fromX, fromY))
            return true;
        return dz < -COLLISION_EPSILON && clipsFace(dz, dx, dy, maxZ, minX, maxX, minY, maxY, fromZ, fromX, fromY);
    }

    private static boolean clipsFace(
            double da,
            double db,
            double dc,
            double plane,
            double minB,
            double maxB,
            double minC,
            double maxC,
            double fromA,
            double fromB,
            double fromC) {
        double scale = (plane - fromA) / da;
        double b = fromB + scale * db;
        double c = fromC + scale * dc;
        return 0.0D < scale
                && scale < 1.0D
                && minB - COLLISION_EPSILON < b
                && b < maxB + COLLISION_EPSILON
                && minC - COLLISION_EPSILON < c
                && c < maxC + COLLISION_EPSILON;
    }

    public static Vec3 getStuckSpeedForBlock(CultPlayer player, int state) {
        return getStuckSpeedForBlock(player, state, true);
    }

    public static Vec3 getStuckSpeedForBlock(CultPlayer player, int state, boolean powderSnowCanApply) {

        // Vanilla applies these through entityInside -> Entity.makeStuckInBlock. Calling
        // that path would mutate a live/detached entity, so Cult mirrors the NMS constants
        // after identifying the affected block by its generated behavior family.
        if (BlockFamilies.WEB.test(state)) {
            return getCobwebStuckSpeed(player);
        }

        if (BlockFamilies.SWEET_BERRY_BUSH.test(state)) {
            return SWEET_BERRY_BUSH_STUCK_SPEED;
        }

        if (BlockFamilies.POWDER_SNOW.test(state)) {
            return powderSnowCanApply ? POWDER_SNOW_STUCK_SPEED : null;
        }
        return null;
    }

    public static Vec3 getCobwebStuckSpeed(CultPlayer player) {
        return getCobwebStuckSpeed(
                player.compensatedEntities.getEntityInControl().isLivingEntity(),
                player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.WEAVING) != null);
    }

    static Vec3 getCobwebStuckSpeed(boolean living, boolean weaving) {
        return living && weaving ? WEAVING_COBWEB_STUCK_SPEED : COBWEB_STUCK_SPEED;
    }

    public static boolean suffocatesAt(CultPlayer player, SimpleCollisionBox playerBB) {
        // Blocks are stored in YZX order
        for (int y = (int) Math.floor(playerBB.minY); y < Math.ceil(playerBB.maxY); y++) {
            for (int z = (int) Math.floor(playerBB.minZ); z < Math.ceil(playerBB.maxZ); z++) {
                for (int x = (int) Math.floor(playerBB.minX); x < Math.ceil(playerBB.maxX); x++) {
                    if (doesBlockSuffocate(player, x, y, z)) {
                        // Mojang re-added soul sand pushing by checking if the player is actually in the block
                        // (This is why from 1.14-1.15 soul sand didn't push)
                        int data = player.compensatedWorld.getBlockStateIdAt(x, y, z);
                        CollisionBox box = ClientBlockShapes.movement(player, data, x, y, z);

                        if (!box.isIntersected(playerBB)) continue;

                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean doesBlockSuffocate(CultPlayer player, int x, int y, int z) {
        int state = player.compensatedWorld.getBlockStateIdAt(x, y, z);
        // BlockBehaviour's predicate uses generated state/full-shape facts. The
        // shulker predicate sees no block entity in CompensatedWorld, as before.
        return DataTables.defaults().registry().facts(state).has(StateFacts.SUFFOCATING);
    }

    @FunctionalInterface
    public interface StatePredicate {
        boolean test(int state, BlockPos pos);
    }

    public static boolean hasState(CultPlayer player, SimpleCollisionBox checkBox, StatePredicate searchingFor) {
        return hasState(player, checkBox, state -> true, searchingFor);
    }

    /** {@code searchingFor} sees only states passing {@code stateFilter} and must be false for every other state. */
    public static boolean hasState(
            CultPlayer player, SimpleCollisionBox checkBox, IntPredicate stateFilter, StatePredicate searchingFor) {
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

                            int data = section.getStateId(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));
                            if (!stateFilter.test(data)) continue;
                            if (searchingFor.test(data, new BlockPos(x, y, z))) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static boolean onClimbable(CultPlayer player, double x, double y, double z) {
        return onClimbable(player, x, y, z, player.isGliding);
    }

    public static boolean onClimbable(CultPlayer player, double x, double y, double z, boolean gliding) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        int state = player.compensatedWorld.getBlockStateIdAt(blockX, blockY, blockZ);
        if (gliding
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)
                && canGlideThrough(state)) {
            return false;
        }
        if (isClimbable(state)) {
            return true;
        }

        return trapdoorUsableAsLadder(player, x, y, z, state);
    }

    private static boolean canGlideThrough(int state) {
        return BlockIds.is(state, BlockIds.VINE)
                || BlockIds.is(state, BlockIds.TWISTING_VINES)
                || BlockIds.is(state, BlockIds.TWISTING_VINES_PLANT)
                || BlockIds.is(state, BlockIds.WEEPING_VINES)
                || BlockIds.is(state, BlockIds.WEEPING_VINES_PLANT)
                || BlockIds.is(state, BlockIds.CAVE_VINES)
                || BlockIds.is(state, BlockIds.CAVE_VINES_PLANT);
    }

    public static boolean isClimbable(int state) {
        return BlockTags.CLIMBABLE.test(state);
    }

    public static boolean trapdoorUsableAsLadder(CultPlayer player, double x, double y, double z, int blockState) {
        if (!ClientBlockProperties.isTrapdoor(blockState)) return false;
        if (BlockProps.OPEN.has(blockState) && BlockProps.OPEN.booleanValue(blockState)) {
            int blockBelow = player.compensatedWorld.getBlockStateIdAt(
                    (int) Math.floor(x), (int) Math.floor(y - 1), (int) Math.floor(z));

            if (BlockIds.is(blockBelow, BlockIds.LADDER)) {
                return ClientBlockProperties.facing(blockState) == ClientBlockProperties.facing(blockBelow);
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
