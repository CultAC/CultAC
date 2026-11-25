package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;

/**
 * The boxes of the doors, trapdoors and fence gates an actor is passing through. Such a block never pushes out an
 * actor whose box reaches its cell, whoever closed it: while the box reaches the cell the block's box is kept, and a
 * block shape that lies inside a kept box takes no part in the end-of-tick block push. A kept box stays until the
 * actor's box no longer overlaps it.
 */
final class BedrockOneWayBoxes {
    /** How far into a cell the actor's box has to reach for the cell's block to count. */
    private static final float CELL_INSET = 0.001F;

    private BedrockOneWayBoxes() {}

    /**
     * The boxes a tick ends with: those it started with that the box it started with still overlaps, and the box of
     * each one-way block in a cell the box it ends with reaches.
     */
    static List<WorldCollisionBox> afterTick(
            BedrockMovementState start, WorldCollisionBox end, BlockCollisionWorld world) {
        BedrockCoordinateFrame frame = world.coordinateFrame();
        List<WorldCollisionBox> carried = start.oneWayBoxes();
        List<WorldCollisionBox> boxes = carried;
        if (!carried.isEmpty()) {
            SimpleCollisionBox startBox = local(start.collisionBox(), frame);
            boxes = new ArrayList<>(carried.size());
            for (WorldCollisionBox box : carried) {
                if (overlaps(local(box, frame), startBox)) boxes.add(box);
            }
        }
        SimpleCollisionBox endBox = local(end, frame);
        int minX = cell((float) endBox.minX + CELL_INSET), maxX = cell((float) endBox.maxX - CELL_INSET);
        int minY = cell((float) endBox.minY + CELL_INSET), maxY = cell((float) endBox.maxY - CELL_INSET);
        int minZ = cell((float) endBox.minZ + CELL_INSET), maxZ = cell((float) endBox.maxZ - CELL_INSET);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var block = world.blocksByPosition()
                            .get(new BlockPosition(frame.originX() + x, y, frame.originZ() + z));
                    if (block == null || !isOneWay(block)) continue;
                    WorldCollisionBox box = bounds(block.collisionBoxes());
                    if (box == null || boxes.contains(box)) continue;
                    if (boxes == carried) boxes = new ArrayList<>(carried);
                    boxes.add(box);
                }
            }
        }
        return boxes;
    }

    /** Whether {@code shape} lies inside one of {@code boxes}, all in the float coordinates collision uses. */
    static boolean inside(SimpleCollisionBox shape, List<SimpleCollisionBox> boxes) {
        for (SimpleCollisionBox box : boxes) {
            if (box.minX <= shape.minX
                    && shape.maxX <= box.maxX
                    && box.minY <= shape.minY
                    && shape.maxY <= box.maxY
                    && box.minZ <= shape.minZ
                    && shape.maxZ <= box.maxZ) return true;
        }
        return false;
    }

    /** The box in the frame's float coordinates, where a float step is the one collision rounds to. */
    static SimpleCollisionBox local(WorldCollisionBox box, BedrockCoordinateFrame frame) {
        return new SimpleCollisionBox(
                frame.localX(box.minX()),
                (float) box.minY(),
                frame.localZ(box.minZ()),
                frame.localX(box.maxX()),
                (float) box.maxY(),
                frame.localZ(box.maxZ()));
    }

    static boolean isOneWay(PlacedBlockCollision block) {
        return isOneWay(block.bedrockIdentifier()) || isOneWay(block.javaState());
    }

    private static boolean isOneWay(String state) {
        int properties = state.indexOf('[');
        int end = properties < 0 ? state.length() : properties;
        return state.startsWith("_door", end - 5)
                || state.startsWith("trapdoor", end - 8)
                || state.startsWith("fence_gate", end - 10);
    }

    private static WorldCollisionBox bounds(List<WorldCollisionBox> shapes) {
        if (shapes.isEmpty()) return null;
        WorldCollisionBox first = shapes.getFirst();
        double minX = first.minX(), minY = first.minY(), minZ = first.minZ();
        double maxX = first.maxX(), maxY = first.maxY(), maxZ = first.maxZ();
        for (int index = 1; index < shapes.size(); index++) {
            WorldCollisionBox shape = shapes.get(index);
            minX = Math.min(minX, shape.minX());
            minY = Math.min(minY, shape.minY());
            minZ = Math.min(minZ, shape.minZ());
            maxX = Math.max(maxX, shape.maxX());
            maxY = Math.max(maxY, shape.maxY());
            maxZ = Math.max(maxZ, shape.maxZ());
        }
        return minX < maxX && minY < maxY && minZ < maxZ
                ? new WorldCollisionBox(minX, minY, minZ, maxX, maxY, maxZ)
                : null;
    }

    private static boolean overlaps(SimpleCollisionBox left, SimpleCollisionBox right) {
        return left.minX < right.maxX
                && right.minX < left.maxX
                && left.minY < right.maxY
                && right.minY < left.maxY
                && left.minZ < right.maxZ
                && right.minZ < left.maxZ;
    }

    private static int cell(float coordinate) {
        return (int) Math.floor(coordinate);
    }
}
