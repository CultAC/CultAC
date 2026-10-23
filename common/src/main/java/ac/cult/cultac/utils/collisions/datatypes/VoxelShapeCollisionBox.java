package ac.cult.cultac.utils.collisions.datatypes;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.utils.nmsutil.ClientShapeBoxes;
import java.util.List;

/** A Java voxel collider retained through the existing axis/step runner. */
public final class VoxelShapeCollisionBox extends SimpleCollisionBox {
    private VoxelShape shape;
    private boolean fullBlock;
    private double offsetX, offsetY, offsetZ;
    private boolean translated;

    public VoxelShapeCollisionBox(VoxelShape shape) {
        this(shape, false);
    }

    public VoxelShapeCollisionBox(VoxelShape shape, boolean fullBlock) {
        init(shape, fullBlock);
    }

    /** Leaves this box as {@code new VoxelShapeCollisionBox(shape, fullBlock)} leaves a new one. */
    VoxelShapeCollisionBox reset(VoxelShape shape, boolean fullBlock) {
        offsetX = offsetY = offsetZ = 0;
        translated = false;
        init(shape, fullBlock);
        return this;
    }

    private void init(VoxelShape shape, boolean fullBlock) {
        this.shape = shape;
        this.fullBlock = fullBlock;
        if (shape.isEmpty()) throw new IllegalArgumentException("Empty voxel collider");
        if (shape == Shapes.block()) {
            minX = minY = minZ = 0.0;
            maxX = maxY = maxZ = 1.0;
            isFullBlock = true;
            return;
        }
        var boxes = ClientShapeBoxes.cached(shape);
        int count;
        if (boxes != null) {
            minX = minY = minZ = Double.POSITIVE_INFINITY;
            maxX = maxY = maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < boxes.size(); i++) {
                var box = boxes.get(i);
                minX = Math.min(minX, box.minX());
                minY = Math.min(minY, box.minY());
                minZ = Math.min(minZ, box.minZ());
                maxX = Math.max(maxX, box.maxX());
                maxY = Math.max(maxY, box.maxY());
                maxZ = Math.max(maxZ, box.maxZ());
            }
            count = boxes.size();
        } else {
            var bounds = new Bounds();
            ClientShapeBoxes.forEach(shape, bounds);
            minX = bounds.minX;
            minY = bounds.minY;
            minZ = bounds.minZ;
            maxX = bounds.maxX;
            maxY = bounds.maxY;
            maxZ = bounds.maxZ;
            count = bounds.count;
        }
        // Preserve CollisionBox's local full-cube classification through offsets/copies.
        isFullBlock = count == 1 && minX == 0 && minY == 0 && minZ == 0 && maxX == 1 && maxY == 1 && maxZ == 1;
    }

    private static final class Bounds implements VoxelShape.BoxConsumer {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        int count;

        @Override
        public void accept(double x1, double y1, double z1, double x2, double y2, double z2) {
            minX = Math.min(minX, x1);
            minY = Math.min(minY, y1);
            minZ = Math.min(minZ, z1);
            maxX = Math.max(maxX, x2);
            maxY = Math.max(maxY, y2);
            maxZ = Math.max(maxZ, z2);
            count++;
        }
    }

    public double[] stepCoordinates() {
        var result = shape.coordinates(1);
        if (translated) for (int i = 0; i < result.length; i++) result[i] += offsetY;
        return result;
    }

    public int stepCoordinateCount() {
        return shape.coordinateCount(1);
    }

    public double stepCoordinateAt(int index) {
        double value = shape.coordinateAt(1, index);
        return translated ? value + offsetY : value;
    }

    public VoxelShape voxelShape() {
        if (translated) {
            shape = shape.move(offsetX, offsetY, offsetZ);
            translated = false;
            offsetX = offsetY = offsetZ = 0;
        }
        return shape;
    }

    public boolean sameGrid(VoxelShapeCollisionBox other) {
        for (int axis = 0; axis < 3; axis++) {
            if (shape.coordinateCount(axis) != other.shape.coordinateCount(axis)) return false;
            double offset = axis == 0 ? offsetX : axis == 1 ? offsetY : offsetZ;
            double otherOffset = axis == 0 ? other.offsetX : axis == 1 ? other.offsetY : other.offsetZ;
            for (int i = 0; i < shape.coordinateCount(axis); i++) {
                double first = shape.coordinateAt(axis, i), second = other.shape.coordinateAt(axis, i);
                if (translated) first += offset;
                if (other.translated) second += otherOffset;
                if (Double.doubleToLongBits(first) != Double.doubleToLongBits(second)) return false;
            }
        }
        return true;
    }

    @Override
    public VoxelShapeCollisionBox copy() {
        if (translated && !finiteOffsets()) {
            var result = new VoxelShapeCollisionBox(voxelShape(), fullBlock);
            result.isFullBlock = isFullBlock;
            return result;
        }
        var result = new VoxelShapeCollisionBox(shape, fullBlock);
        if (translated) result.offset(offsetX, offsetY, offsetZ);
        result.isFullBlock = isFullBlock;
        return result;
    }

    public boolean usesStrictFullBlockIntersection() {
        return fullBlock;
    }

    /** BlockCollisions uses strict AABB intersection for Shapes.block(), voxel AND for other shapes. */
    public boolean intersectsQuery(SimpleCollisionBox query, VoxelShape queryShape) {
        return fullBlock
                ? query.minX < maxX
                        && query.maxX > minX
                        && query.minY < maxY
                        && query.maxY > minY
                        && query.minZ < maxZ
                        && query.maxZ > minZ
                : shape.intersectsAabb(queryShape, offsetX, offsetY, offsetZ, translated);
    }

    /** {@link #intersectsQuery(SimpleCollisionBox, VoxelShape)} with {@code Shapes.create} of the query's bounds. */
    public boolean intersectsQuery(SimpleCollisionBox query) {
        return fullBlock
                ? query.minX < maxX
                        && query.maxX > minX
                        && query.minY < maxY
                        && query.maxY > minY
                        && query.minZ < maxZ
                        && query.maxZ > minZ
                : shape.intersectsAabb(
                        query.minX,
                        query.minY,
                        query.minZ,
                        query.maxX,
                        query.maxY,
                        query.maxZ,
                        offsetX,
                        offsetY,
                        offsetZ,
                        translated);
    }

    @Override
    public VoxelShapeCollisionBox offset(double x, double y, double z) {
        super.offset(x, y, z);
        // Preserve vanilla's sequential additions for repeated translations.
        // The ordinary block-world translation needs no arrays or moved shape.
        if (translated) voxelShape();
        offsetX = x;
        offsetY = y;
        offsetZ = z;
        translated = true;
        return this;
    }

    @Override
    public void downCast(List<SimpleCollisionBox> list) {
        if (!shape.hasFiniteCoordinates() || (translated && !finiteOffsets())) {
            // The original method validates the entire decomposition before mutating the caller's list.
            for (Box box : voxelShape().boxes())
                list.add(SimpleCollisionBox.between(
                        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
            return;
        }
        if (translated) {
            double x = offsetX, y = offsetY, z = offsetZ;
            ClientShapeBoxes.forEach(
                    shape,
                    (minX, minY, minZ, maxX, maxY, maxZ) -> list.add(
                            SimpleCollisionBox.between(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z)));
            return;
        }
        ClientShapeBoxes.forEach(
                shape,
                (minX, minY, minZ, maxX, maxY, maxZ) ->
                        list.add(SimpleCollisionBox.between(minX, minY, minZ, maxX, maxY, maxZ)));
    }

    @Override
    public void downCastForMovement(List<SimpleCollisionBox> list) {
        list.add(this);
    }

    @Override
    public boolean isCollided(SimpleCollisionBox other) {
        return overlaps(other, false);
    }

    @Override
    public boolean isIntersected(SimpleCollisionBox other) {
        return overlaps(other, true);
    }

    private boolean overlaps(SimpleCollisionBox other, boolean strict) {
        if (!shape.hasFiniteCoordinates() || (translated && !finiteOffsets())) {
            for (Box box : voxelShape().boxes()) {
                var part = SimpleCollisionBox.between(
                        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
                if (strict ? part.isIntersected(other) : part.isCollided(other)) return true;
            }
            return false;
        }
        var test = new Overlap(other, strict, translated, offsetX, offsetY, offsetZ);
        ClientShapeBoxes.forEach(shape, test);
        return test.hit;
    }

    private boolean finiteOffsets() {
        return Double.isFinite(offsetX) && Double.isFinite(offsetY) && Double.isFinite(offsetZ);
    }

    private static final class Overlap implements VoxelShape.BoxConsumer {
        final SimpleCollisionBox other;
        final boolean strict;
        final boolean translated;
        final double offsetX, offsetY, offsetZ;
        boolean hit;

        Overlap(
                SimpleCollisionBox other,
                boolean strict,
                boolean translated,
                double offsetX,
                double offsetY,
                double offsetZ) {
            this.other = other;
            this.strict = strict;
            this.translated = translated;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }

        @Override
        public void accept(double x1, double y1, double z1, double x2, double y2, double z2) {
            if (hit) return;
            if (translated) {
                x1 += offsetX;
                y1 += offsetY;
                z1 += offsetZ;
                x2 += offsetX;
                y2 += offsetY;
                z2 += offsetZ;
            }
            double minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
            double maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
            hit = strict
                    ? other.maxX - COLLISION_EPSILON > minX
                            && other.minX + COLLISION_EPSILON < maxX
                            && other.maxY - COLLISION_EPSILON > minY
                            && other.minY + COLLISION_EPSILON < maxY
                            && other.maxZ - COLLISION_EPSILON > minZ
                            && other.minZ + COLLISION_EPSILON < maxZ
                    : other.maxX >= minX
                            && other.minX <= maxX
                            && other.maxY >= minY
                            && other.minY <= maxY
                            && other.maxZ >= minZ
                            && other.minZ <= maxZ;
        }
    }

    private double collide(int axis, SimpleCollisionBox moving, double distance) {
        if (translated)
            return shape.collideTranslated(
                    axis,
                    moving.minX,
                    moving.minY,
                    moving.minZ,
                    moving.maxX,
                    moving.maxY,
                    moving.maxZ,
                    distance,
                    offsetX,
                    offsetY,
                    offsetZ);
        return shape.collide(
                axis, moving.minX, moving.minY, moving.minZ, moving.maxX, moving.maxY, moving.maxZ, distance);
    }

    @Override
    public double collideX(SimpleCollisionBox moving, double distance, AxisEpsilon epsilon) {
        return collide(0, moving, distance);
    }

    @Override
    public double collideY(SimpleCollisionBox moving, double distance, AxisEpsilon epsilon) {
        return collide(1, moving, distance);
    }

    @Override
    public double collideZ(SimpleCollisionBox moving, double distance, AxisEpsilon epsilon) {
        return collide(2, moving, distance);
    }
}
