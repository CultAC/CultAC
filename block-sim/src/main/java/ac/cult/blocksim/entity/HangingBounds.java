package ac.cult.blocksim.entity;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;

/** Shared placement and received-entity geometry, without entity simulation or rendering state. */
public final class HangingBounds {
    private HangingBounds() { }
    public static Box frame(BlockPos anchor, Direction direction, boolean map) {
        return box(anchor, direction, map ? 1 : .75, map ? 1 : .75, false);
    }
    public static Box painting(BlockPos anchor, Direction direction, PaintingSize size) {
        return box(anchor, direction, size.width(), size.height(), true);
    }
    private static Box box(BlockPos anchor, Direction direction, double width, double height, boolean painting) {
        double x = anchor.x() + .5 - direction.x() * .46875, y = anchor.y() + .5 - direction.y() * .46875;
        double z = anchor.z() + .5 - direction.z() * .46875;
        if (painting) {
            if (width % 2 == 0) { var left = direction.counterClockwise(); x += left.x() * .5; z += left.z() * .5; }
            if (height % 2 == 0) y += .5;
        }
        double halfX = (direction.x() != 0 ? .0625 : width) / 2, halfY = (direction.y() != 0 ? .0625 : height) / 2;
        double halfZ = (direction.z() != 0 ? .0625 : width) / 2;
        return new Box(x - halfX, y - halfY, z - halfZ, x + halfX, y + halfY, z + halfZ);
    }
}
