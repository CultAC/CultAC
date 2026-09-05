package ac.cult.cultac.utils.collisions.datatypes;

import org.bukkit.block.BlockFace;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class SimpleCollisionBox implements CollisionBox {
    public static final double COLLISION_EPSILON = 1.0E-7;
    public double minX, minY, minZ, maxX, maxY, maxZ;
    boolean isFullBlock = false;

    public SimpleCollisionBox() {
        this(0, 0, 0, 0, 0, 0, false);
    }

    /**
     * Creates a box defined by two points in 3d space; used to represent hitboxes and collision boxes.
     * If your min/max values are > 1 you should probably check out {@link HexCollisionBox}
     *
     * @param minX      x position of first corner
     * @param minY      y position of first corner
     * @param minZ      z position of first corner
     * @param maxX      x position of second corner
     * @param maxY      y position of second corner
     * @param maxZ      z position of second corner
     * @param fullBlock - whether on not the box is a perfect 1x1x1 sized block
     */
    public SimpleCollisionBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean fullBlock) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        isFullBlock = fullBlock;
    }

    public SimpleCollisionBox(Vector min, Vector max) {
        this(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    public SimpleCollisionBox(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    // If you want to set a full block from a point
    public SimpleCollisionBox(double minX, double minY, double minZ) {
        this(minX, minY, minZ, minX + 1, minY + 1, minZ + 1, true);
    }

    /**
     * Creates a box defined by two points in 3d space; used to represent hitboxes and collision boxes.
     * If your min/max values are > 1 you should probably check out {@link HexCollisionBox}
     * Use only if you don't know the fullBlock status, which is rare
     *
     * @param minX x position of first corner
     * @param minY y position of first corner
     * @param minZ z position of first corner
     * @param maxX x position of second corner
     * @param maxY y position of second corner
     * @param maxZ z position of second corner
     */
    public SimpleCollisionBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        if (minX == 0 && minY == 0 && minZ == 0 && maxX == 1 && maxY == 1 && maxZ == 1) isFullBlock = true;
    }

    public SimpleCollisionBox(Vec3 min, Vec3 max) {
        this(min.x, min.y, min.z, max.x, max.y, max.z);
    }

    public SimpleCollisionBox(Location loc, double width, double height) {
        this(loc.toVector(), width, height);
    }

    public SimpleCollisionBox(BlockPos vec, double width, double height) {
        this(vec.getX(), vec.getY(), vec.getZ(), vec.getX(), vec.getY(), vec.getZ());
        expand(width / 2, 0, width / 2);
        maxY += height;
    }

    public SimpleCollisionBox(Vector vec, double width, double height) {
        this(vec.getX(), vec.getY(), vec.getZ(), vec.getX(), vec.getY(), vec.getZ());
        expand(width / 2, 0, width / 2);
        maxY += height;
    }

    public SimpleCollisionBox expand(double x, double y, double z) {
        this.minX -= x;
        this.minY -= y;
        this.minZ -= z;
        this.maxX += x;
        this.maxY += y;
        this.maxZ += z;
        return sort();
    }

    public SimpleCollisionBox sort() {
        double minX = Math.min(this.minX, this.maxX);
        double minY = Math.min(this.minY, this.maxY);
        double minZ = Math.min(this.minZ, this.maxZ);
        double maxX = Math.max(this.minX, this.maxX);
        double maxY = Math.max(this.minY, this.maxY);
        double maxZ = Math.max(this.minZ, this.maxZ);

        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        return this;
    }

    public SimpleCollisionBox expandMin(double x, double y, double z) {
        this.minX += x;
        this.minY += y;
        this.minZ += z;
        return this;
    }

    public SimpleCollisionBox expandMax(double x, double y, double z) {
        this.maxX += x;
        this.maxY += y;
        this.maxZ += z;
        return this;
    }

    public SimpleCollisionBox expand(BlockFace face) {
        return expand(face.getModX(), face.getModY(), face.getModZ());
    }

    public SimpleCollisionBox expand(double value) {
        this.minX -= value;
        this.minY -= value;
        this.minZ -= value;
        this.maxX += value;
        this.maxY += value;
        this.maxZ += value;
        return this;
    }

    public void unionX(double x) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
    }

    public void unionY(double y) {
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
    }

    public void unionZ(double z) {
        if (z < minZ) minZ = z;
        if (z > maxZ) maxZ = z;
    }

    public SimpleCollisionBox union(SimpleCollisionBox other) {
        this.minX = Math.min(this.minX, other.minX);
        this.minY = Math.min(this.minY, other.minY);
        this.minZ = Math.min(this.minZ, other.minZ);
        this.maxX = Math.max(this.maxX, other.maxX);
        this.maxY = Math.max(this.maxY, other.maxY);
        this.maxZ = Math.max(this.maxZ, other.maxZ);
        return this;
    }

    public SimpleCollisionBox expandToAbsoluteCoordinates(double x, double y, double z) {
        return expandToCoordinate(x - ((minX + maxX) / 2), y - ((minY + maxY) / 2), z - ((minZ + maxZ) / 2));
    }

    public SimpleCollisionBox expandToCoordinate(Vec3 vec) {
        return expandToCoordinate(vec.x, vec.y, vec.z);
    }

    public SimpleCollisionBox expandToCoordinate(double x, double y, double z) {
        if (x < 0.0D) {
            minX += x;
        } else {
            maxX += x;
        }

        if (y < 0.0D) {
            minY += y;
        } else {
            maxY += y;
        }

        if (z < 0.0D) {
            minZ += z;
        } else {
            maxZ += z;
        }

        return this;
    }

    public boolean hasPoint(Vec3 point) {
        return hasPoint(point.x, point.y, point.z);
    }

    public boolean hasPoint(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override
    public boolean isCollided(SimpleCollisionBox other) {
        return other.maxX >= this.minX && other.minX <= this.maxX
                && other.maxY >= this.minY && other.minY <= this.maxY
                && other.maxZ >= this.minZ && other.minZ <= this.maxZ;
    }

    @Override
    public boolean isIntersected(SimpleCollisionBox other) {
        return other.maxX - SimpleCollisionBox.COLLISION_EPSILON > this.minX && other.minX + SimpleCollisionBox.COLLISION_EPSILON < this.maxX
                && other.maxY - SimpleCollisionBox.COLLISION_EPSILON > this.minY && other.minY + SimpleCollisionBox.COLLISION_EPSILON < this.maxY
                && other.maxZ - SimpleCollisionBox.COLLISION_EPSILON > this.minZ && other.minZ + SimpleCollisionBox.COLLISION_EPSILON < this.maxZ;
    }

    public boolean isIntersected(CollisionBox other) {
        // Optimization - don't allocate a list if this is just a SimpleCollisionBox
        if (other instanceof SimpleCollisionBox) {
            return isIntersected((SimpleCollisionBox) other);
        }

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        other.downCast(boxes);

        for (SimpleCollisionBox box : boxes) {
            if (isIntersected(box)) return true;
        }

        return false;
    }

    public boolean collidesVertically(SimpleCollisionBox other) {
        return other.maxX > this.minX && other.minX < this.maxX
                && other.maxY >= this.minY && other.minY <= this.maxY
                && other.maxZ > this.minZ && other.minZ < this.maxZ;
    }

    public SimpleCollisionBox copy() {
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ, isFullBlock);
    }

    public SimpleCollisionBox offset(Vec3 vector) {
        return offset(vector.x, vector.y, vector.z);
    }

    public SimpleCollisionBox offset(BlockFace blockFace) {
        return offset(blockFace.getModX(), blockFace.getModY(), blockFace.getModZ());
    }

    public SimpleCollisionBox offset(double x, double y, double z) {
        this.minX += x;
        this.minY += y;
        this.minZ += z;
        this.maxX += x;
        this.maxY += y;
        this.maxZ += z;
        return this;
    }

    @Override
    public void downCast(List<SimpleCollisionBox> list) {
        list.add(this);
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public boolean isFullBlock() {
        return isFullBlock;
    }

    public boolean isFullBlockNoCache() {
        return minX == 0 && minY == 0 && minZ == 0 && maxX == 1 && maxY == 1 && maxZ == 1;
    }

    public boolean isHorizEmpty() {
        return minX == maxX && minZ == maxZ;
    }
    public boolean isEmpty() {
        return minX == maxX && minY == maxY && minZ == maxZ;
    }

    /**
     * if {@code this} and {@code other} overlap in the Y and Z dimensions, calculate the offset between them
     * in the X dimension. return {@code offsetX} if the bounding boxes do not overlap or if {@code offsetX}
     * is closer to {@code 0} then the calculated offset. Otherwise return the calculated offset.
     */
    public double collideX(SimpleCollisionBox other, double offsetX) {
        return collideX(other, offsetX, AxisEpsilon.JAVA);
    }

    public double collideX(SimpleCollisionBox other, double offsetX, AxisEpsilon epsilon) {
        if (offsetX != 0 && (other.minY - maxY) < -epsilon.y && (other.maxY - minY) > epsilon.y &&
                (other.minZ - maxZ) < -epsilon.z && (other.maxZ - minZ) > epsilon.z) {

            if (offsetX >= 0.0) {
                double max_move = minX - other.maxX; // < 0.0 if no strict collision
                if (max_move < -epsilon.x) {
                    return offsetX;
                }
                return Math.min(max_move, offsetX);
            } else {
                double max_move = maxX - other.minX; // > 0.0 if no strict collision
                if (max_move > epsilon.x) {
                    return offsetX;
                }
                return Math.max(max_move, offsetX);
            }
        }
        return offsetX;
    }

    /**
     * if {@code this} and {@code other} overlap in the X and Z dimensions, calculate the offset between them
     * in the Y dimension. return {@code offsetY} if the bounding boxes do not overlap or if {@code offsetY}
     * is closer to {@code 0} then the calculated offset. Otherwise return the calculated offset.
     */
    public double collideY(SimpleCollisionBox other, double offsetY) {
        return collideY(other, offsetY, AxisEpsilon.JAVA);
    }

    public double collideY(SimpleCollisionBox other, double offsetY, AxisEpsilon epsilon) {
        if (offsetY != 0 && (other.minX - maxX) < -epsilon.x && (other.maxX - minX) > epsilon.x &&
                (other.minZ - maxZ) < -epsilon.z && (other.maxZ - minZ) > epsilon.z) {
            if (offsetY >= 0.0) {
                double max_move = minY - other.maxY; // < 0.0 if no strict collision
                if (max_move < -epsilon.y) {
                    return offsetY;
                }
                return Math.min(max_move, offsetY);
            } else {
                double max_move = maxY - other.minY; // > 0.0 if no strict collision
                if (max_move > epsilon.y) {
                    return offsetY;
                }
                return Math.max(max_move, offsetY);
            }
        }
        return offsetY;
    }

    /**
     * if {@code this} and {@code other} overlap in the Y and X dimensions, calculate the offset between them
     * in the Z dimension. return {@code offsetZ} if the bounding boxes do not overlap or if {@code offsetZ}
     * is closer to {@code 0} then the calculated offset. Otherwise return the calculated offset.
     */
    public double collideZ(SimpleCollisionBox other, double offsetZ) {
        return collideZ(other, offsetZ, AxisEpsilon.JAVA);
    }

    public double collideZ(SimpleCollisionBox other, double offsetZ, AxisEpsilon epsilon) {
        if (offsetZ != 0 && (other.minX - maxX) < -epsilon.x && (other.maxX - minX) > epsilon.x &&
                (other.minY - maxY) < -epsilon.y && (other.maxY - minY) > epsilon.y) {
            if (offsetZ >= 0.0) {
                double max_move = minZ - other.maxZ; // < 0.0 if no strict collision
                if (max_move < -epsilon.z) {
                    return offsetZ;
                }
                return Math.min(max_move, offsetZ);
            } else {
                double max_move = maxZ - other.minZ; // > 0.0 if no strict collision
                if (max_move > epsilon.z) {
                    return offsetZ;
                }
                return Math.max(max_move, offsetZ);
            }
        }
        return offsetZ;
    }

    public record AxisEpsilon(double x, double y, double z) {
        public static final AxisEpsilon JAVA = uniform(COLLISION_EPSILON);

        public AxisEpsilon {
            if (!valid(x) || !valid(y) || !valid(z)) {
                throw new IllegalArgumentException("collision epsilon must be finite and non-negative");
            }
        }

        public static AxisEpsilon uniform(double epsilon) {
            return new AxisEpsilon(epsilon, epsilon, epsilon);
        }

        private static boolean valid(double value) {
            return Double.isFinite(value) && value >= 0.0D;
        }
    }

    public double distance(SimpleCollisionBox box) {
        double xwidth = (maxX - minX) / 2, zwidth = (maxZ - minZ) / 2;
        double bxwidth = (box.maxX - box.minX) / 2, bzwidth = (box.maxZ - box.minZ) / 2;
        double hxz = Math.hypot(minX - box.minX, minZ - box.minZ);
        return hxz - (xwidth + zwidth + bxwidth + bzwidth) / 4;
    }

    public Vec3 max() {
        return new Vec3(maxX, maxY, maxZ);
    }

    public Vec3 min() {
        return new Vec3(minX, minY, minZ);
    }

    public SimpleCollisionBox toSimpleCollisionBox() { return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ); }

    public double middleX() { return (minX + maxX) / 2.0D; }

    public double middleY() { return (minY + maxY) / 2.0D; }

    public double middleZ() { return (minZ + maxZ) / 2.0D; }

    @Override
    public String toString() {
        return "SimpleCollisionBox{" +
                "minX=" + minX +
                ", minY=" + minY +
                ", minZ=" + minZ +
                ", maxX=" + maxX +
                ", maxY=" + maxY +
                ", maxZ=" + maxZ +
                ", isFullBlock=" + isFullBlock +
                '}';
    }
}
