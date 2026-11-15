package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * BedBlock#findStandUpPosition for the local player on the client's blocks, with
 * DismountHelper#findSafeDismountLocation's floor, danger and CollisionContext.empty() block checks.
 */
final class ClientBedStandUp {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    // EntityType#isBlockDangerous for a player, which is neither fire immune nor immune to any block.
    private static final BlockDefinition MAGMA_BLOCK = REGISTRY.block("minecraft:magma_block");
    private static final BlockDefinition LAVA_CAULDRON = REGISTRY.block("minecraft:lava_cauldron");
    private static final BlockDefinition WITHER_ROSE = REGISTRY.block("minecraft:wither_rose");
    private static final BlockDefinition CACTUS = REGISTRY.block("minecraft:cactus");
    private static final BlockDefinition SOUL_FIRE = REGISTRY.block("minecraft:soul_fire");
    private static final BlockDefinition CAMPFIRE = REGISTRY.block("minecraft:campfire");
    private static final BlockDefinition SOUL_CAMPFIRE = REGISTRY.block("minecraft:soul_campfire");
    // EntityTypes.PLAYER's dimensions, which DismountHelper boxes the stand-up position with.
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final double HEIGHT = 1.8F;

    private ClientBedStandUp() {}

    /** BedBlock#findStandUpPosition for the bed at {@code bed} facing {@code forward}; null when no offset is safe. */
    static @Nullable Vec3 find(CultPlayer player, BlockPos bed, Direction forward, float yaw) {
        Direction right = clockWise(forward);
        Direction side = isFacingAngle(player, right, yaw) ? right.getOpposite() : right;
        int[][] surround = surroundOffsets(forward, side);
        int[][] above = {{0, 0}, {-forward.getStepX(), -forward.getStepZ()}};
        if (!BlockFamilies.BED.test(player.compensatedWorld.getBlockStateIdAt(bed.relative(Direction.DOWN)))) {
            int[][] offsets = new int[surround.length + above.length][];
            System.arraycopy(surround, 0, offsets, 0, surround.length);
            System.arraycopy(above, 0, offsets, surround.length, above.length);
            Vec3 safe = atOffset(player, bed, offsets, true);
            return safe != null ? safe : atOffset(player, bed, offsets, false);
        }
        // BedBlock#findBunkBedStandUpPosition.
        BlockPos below = bed.relative(Direction.DOWN);
        Vec3 position = atOffset(player, bed, surround, true);
        if (position == null) position = atOffset(player, below, surround, true);
        if (position == null) position = atOffset(player, bed, above, true);
        if (position == null) position = atOffset(player, bed, surround, false);
        if (position == null) position = atOffset(player, below, surround, false);
        if (position == null) position = atOffset(player, bed, above, false);
        return position;
    }

    private static int[][] surroundOffsets(Direction forward, Direction side) {
        int fx = forward.getStepX(), fz = forward.getStepZ(), sx = side.getStepX(), sz = side.getStepZ();
        return new int[][] {
            {sx, sz},
            {sx - fx, sz - fz},
            {sx - fx * 2, sz - fz * 2},
            {-fx * 2, -fz * 2},
            {-sx - fx * 2, -sz - fz * 2},
            {-sx - fx, -sz - fz},
            {-sx, -sz},
            {-sx + fx, -sz + fz},
            {fx, fz},
            {sx + fx, sz + fz}
        };
    }

    private static @Nullable Vec3 atOffset(CultPlayer player, BlockPos pos, int[][] offsets, boolean checkDangerous) {
        for (int[] offset : offsets) {
            Vec3 position = safeDismountLocation(
                    player, new BlockPos(pos.getX() + offset[0], pos.getY(), pos.getZ() + offset[1]), checkDangerous);
            if (position != null) return position;
        }
        return null;
    }

    /** DismountHelper#findSafeDismountLocation for a player. */
    private static @Nullable Vec3 safeDismountLocation(CultPlayer player, BlockPos pos, boolean checkDangerous) {
        var world = player.compensatedWorld;
        int state = world.getBlockStateIdAt(pos);
        if (checkDangerous && dangerous(state)) return null;
        // BlockGetter#getBlockFloorHeight over DismountHelper#nonClimbableShape.
        double floor = top(player, pos);
        if (floor == Double.NEGATIVE_INFINITY) {
            double below = top(player, pos.relative(Direction.DOWN));
            floor = below >= 1.0 ? below - 1.0 : Double.NEGATIVE_INFINITY;
        }
        if (Double.isInfinite(floor) || floor >= 1.0) return null;
        if (checkDangerous && floor <= 0.0 && dangerous(world.getBlockStateIdAt(pos.relative(Direction.DOWN))))
            return null;
        Vec3 position = new Vec3(pos.getX() + 0.5, pos.getY() + floor, pos.getZ() + 0.5);
        double minX = position.x - HALF_WIDTH, minZ = position.z - HALF_WIDTH;
        double maxX = position.x + HALF_WIDTH, maxY = position.y + HEIGHT, maxZ = position.z + HALF_WIDTH;
        if (collides(player, minX, position.y, minZ, maxX, maxY, maxZ)) return null;
        if (BlockIds.is(state, BlockIds.END_PORTAL)
                || BlockIds.is(state, BlockIds.END_GATEWAY)
                || invalidSpawnInside(world.getBlockStateIdAt(pos.relative(Direction.UP)))) return null;
        return withinWorldBorder(player, minX, minZ, maxX, maxZ) ? position : null;
    }

    /** WorldBorder#isWithinBounds(AABB). */
    private static boolean withinWorldBorder(CultPlayer player, double minX, double minZ, double maxX, double maxZ) {
        var border = player.checkManager.getWorldBorder();
        double half = border.getCurrentDiameter() / 2.0, limit = border.getAbsoluteMaxSize();
        double westX = CultMath.clamp(border.getCenterX() - half, -limit, limit);
        double eastX = CultMath.clamp(border.getCenterX() + half, -limit, limit);
        double northZ = CultMath.clamp(border.getCenterZ() - half, -limit, limit);
        double southZ = CultMath.clamp(border.getCenterZ() + half, -limit, limit);
        double farX = maxX - 1.0E-5F, farZ = maxZ - 1.0E-5F;
        return minX >= westX && minX < eastX && minZ >= northZ && minZ < southZ
                && farX >= westX && farX < eastX && farZ >= northZ && farZ < southZ;
    }

    private static boolean invalidSpawnInside(int state) {
        return BlockIds.is(state, BlockIds.END_PORTAL) || BlockIds.is(state, BlockIds.END_GATEWAY);
    }

    /** The top of DismountHelper#nonClimbableShape at {@code pos}, or negative infinity when it is empty. */
    private static double top(CultPlayer player, BlockPos pos) {
        int state = player.compensatedWorld.getBlockStateIdAt(pos);
        if (BlockTags.CLIMBABLE.test(state)
                || BlockFamilies.TRAP_DOOR.test(state) && "true".equals(REGISTRY.value(state, "open")))
            return Double.NEGATIVE_INFINITY;
        double[] top = {Double.NEGATIVE_INFINITY};
        NativeBlockCollisionHelper.getContextFreeCollisionShape(player, state, pos.getX(), pos.getY(), pos.getZ())
                .forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> top[0] = Math.max(top[0], maxY));
        return top[0];
    }

    /** Whether CollisionGetter#getBlockCollisions(null, box) holds any shape. */
    private static boolean collides(
            CultPlayer player, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean[] hit = {false};
        int x0 = (int) Math.floor(minX - 1.0E-7) - 1, x1 = (int) Math.floor(maxX + 1.0E-7) + 1;
        int y0 = (int) Math.floor(minY - 1.0E-7) - 1, y1 = (int) Math.floor(maxY + 1.0E-7) + 1;
        int z0 = (int) Math.floor(minZ - 1.0E-7) - 1, z1 = (int) Math.floor(maxZ + 1.0E-7) + 1;
        for (int x = x0; x <= x1 && !hit[0]; x++) {
            for (int y = y0; y <= y1 && !hit[0]; y++) {
                for (int z = z0; z <= z1 && !hit[0]; z++) {
                    int state = player.compensatedWorld.getBlockStateIdAt(x, y, z);
                    int bx = x, by = y, bz = z;
                    NativeBlockCollisionHelper.getContextFreeCollisionShape(player, state, x, y, z)
                            .forEachBox((ax, ay, az, bx1, by1, bz1) -> {
                                if (bx + ax < maxX && bx + bx1 > minX && by + ay < maxY && by + by1 > minY
                                        && bz + az < maxZ && bz + bz1 > minZ) hit[0] = true;
                            });
                }
            }
        }
        return hit[0];
    }

    /** EntityType#isBlockDangerous for a player: NodeEvaluator#isBurningBlock and the blocks that hurt to stand in. */
    private static boolean dangerous(int state) {
        return BlockIds.is(state, BlockIds.FIRE)
                || BlockIds.is(state, SOUL_FIRE)
                || BlockIds.is(state, BlockIds.LAVA)
                || BlockIds.is(state, MAGMA_BLOCK)
                || (BlockIds.is(state, CAMPFIRE) || BlockIds.is(state, SOUL_CAMPFIRE))
                        && "true".equals(REGISTRY.value(state, "lit"))
                || BlockIds.is(state, LAVA_CAULDRON)
                || BlockIds.is(state, WITHER_ROSE)
                || BlockIds.is(state, BlockIds.SWEET_BERRY_BUSH)
                || BlockIds.is(state, CACTUS)
                || BlockIds.is(state, BlockIds.POWDER_SNOW);
    }

    /** Direction#isFacingAngle. */
    private static boolean isFacingAngle(CultPlayer player, Direction direction, float yaw) {
        float radians = yaw * (float) (Math.PI / 180.0);
        float dx = -player.trigHandler.sin(radians);
        float dz = player.trigHandler.cos(radians);
        return direction.getStepX() * dx + direction.getStepZ() * dz > 0.0F;
    }

    /** Direction#getClockWise of a horizontal direction. */
    private static Direction clockWise(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Unable to get Y-rotated facing of " + direction);
        };
    }
}
