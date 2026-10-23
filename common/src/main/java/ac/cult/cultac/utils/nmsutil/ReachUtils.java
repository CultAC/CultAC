package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.Pair;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.math.VectorUtils;

public class ReachUtils {
    public static Pair<Vector3dm, Direction> calculateIntercept(
            SimpleCollisionBox self, Vector3dm origin, Vector3dm end) {
        Vector3dm minX = getIntermediateWithXValue(origin, end, self.minX);
        Vector3dm maxX = getIntermediateWithXValue(origin, end, self.maxX);
        Vector3dm minY = getIntermediateWithYValue(origin, end, self.minY);
        Vector3dm maxY = getIntermediateWithYValue(origin, end, self.maxY);
        Vector3dm minZ = getIntermediateWithZValue(origin, end, self.minZ);
        Vector3dm maxZ = getIntermediateWithZValue(origin, end, self.maxZ);

        Direction bestFace = null;

        if (!isVecInYZ(self, minX)) {
            minX = null;
        }

        if (!isVecInYZ(self, maxX)) {
            maxX = null;
        }

        if (!isVecInXZ(self, minY)) {
            minY = null;
        }

        if (!isVecInXZ(self, maxY)) {
            maxY = null;
        }

        if (!isVecInXY(self, minZ)) {
            minZ = null;
        }

        if (!isVecInXY(self, maxZ)) {
            maxZ = null;
        }

        Vector3dm best = null;

        if (minX != null) {
            best = minX;
            bestFace = Direction.WEST;
        }

        if (maxX != null && (best == null || origin.distanceSquared(maxX) < origin.distanceSquared(best))) {
            best = maxX;
            bestFace = Direction.EAST;
        }

        if (minY != null && (best == null || origin.distanceSquared(minY) < origin.distanceSquared(best))) {
            best = minY;
            bestFace = Direction.DOWN;
        }

        if (maxY != null && (best == null || origin.distanceSquared(maxY) < origin.distanceSquared(best))) {
            best = maxY;
            bestFace = Direction.UP;
        }

        if (minZ != null && (best == null || origin.distanceSquared(minZ) < origin.distanceSquared(best))) {
            best = minZ;
            bestFace = Direction.NORTH;
        }

        if (maxZ != null && (best == null || origin.distanceSquared(maxZ) < origin.distanceSquared(best))) {
            best = maxZ;
            bestFace = Direction.SOUTH;
        }

        return new Pair<>(best, bestFace);
    }

    /**
     * Returns a new vector with x value equal to the second parameter, along the line between this vector and the
     * passed in vector, or null if not possible.
     */
    public static Vector3dm getIntermediateWithXValue(Vector3dm self, Vector3dm other, double x) {
        double d0 = other.getX() - self.getX();
        double d1 = other.getY() - self.getY();
        double d2 = other.getZ() - self.getZ();

        if (d0 * d0 < 1.0000000116860974E-7D) {
            return null;
        } else {
            double d3 = (x - self.getX()) / d0;
            return d3 >= 0.0D && d3 <= 1.0D
                    ? new Vector3dm(self.getX() + d0 * d3, self.getY() + d1 * d3, self.getZ() + d2 * d3)
                    : null;
        }
    }

    /**
     * Returns a new vector with y value equal to the second parameter, along the line between this vector and the
     * passed in vector, or null if not possible.
     */
    public static Vector3dm getIntermediateWithYValue(Vector3dm self, Vector3dm other, double y) {
        double d0 = other.getX() - self.getX();
        double d1 = other.getY() - self.getY();
        double d2 = other.getZ() - self.getZ();

        if (d1 * d1 < 1.0000000116860974E-7D) {
            return null;
        } else {
            double d3 = (y - self.getY()) / d1;
            return d3 >= 0.0D && d3 <= 1.0D
                    ? new Vector3dm(self.getX() + d0 * d3, self.getY() + d1 * d3, self.getZ() + d2 * d3)
                    : null;
        }
    }

    /**
     * Returns a new vector with z value equal to the second parameter, along the line between this vector and the
     * passed in vector, or null if not possible.
     */
    public static Vector3dm getIntermediateWithZValue(Vector3dm self, Vector3dm other, double z) {
        double d0 = other.getX() - self.getX();
        double d1 = other.getY() - self.getY();
        double d2 = other.getZ() - self.getZ();

        if (d2 * d2 < 1.0000000116860974E-7D) {
            return null;
        } else {
            double d3 = (z - self.getZ()) / d2;
            return d3 >= 0.0D && d3 <= 1.0D
                    ? new Vector3dm(self.getX() + d0 * d3, self.getY() + d1 * d3, self.getZ() + d2 * d3)
                    : null;
        }
    }

    /**
     * Checks if the specified vector is within the YZ dimensions of the bounding box. Args: Vec3D
     */
    private static boolean isVecInYZ(SimpleCollisionBox self, Vector3dm vec) {
        return vec != null
                && vec.getY() >= self.minY
                && vec.getY() <= self.maxY
                && vec.getZ() >= self.minZ
                && vec.getZ() <= self.maxZ;
    }

    /**
     * Checks if the specified vector is within the XZ dimensions of the bounding box. Args: Vec3D
     */
    private static boolean isVecInXZ(SimpleCollisionBox self, Vector3dm vec) {
        return vec != null
                && vec.getX() >= self.minX
                && vec.getX() <= self.maxX
                && vec.getZ() >= self.minZ
                && vec.getZ() <= self.maxZ;
    }

    /**
     * Checks if the specified vector is within the XY dimensions of the bounding box. Args: Vec3D
     */
    private static boolean isVecInXY(SimpleCollisionBox self, Vector3dm vec) {
        return vec != null
                && vec.getX() >= self.minX
                && vec.getX() <= self.maxX
                && vec.getY() >= self.minY
                && vec.getY() <= self.maxY;
    }

    // Entity#calculateViewVector, accounting for optifine FastMath. Yaw is negated before
    // the table lookup and the products stay float, as in every client from 1.21.2 to 26.3.
    public static Vector3dm getLook(CultPlayer player, float yaw, float pitch) {
        float f = pitch * ((float) Math.PI / 180F);
        float f1 = -yaw * ((float) Math.PI / 180F);
        float f2 = player.trigHandler.clientCos(f1);
        float f3 = player.trigHandler.clientSin(f1);
        float f4 = player.trigHandler.clientCos(f);
        float f5 = player.trigHandler.clientSin(f);
        return new Vector3dm(f3 * f4, -f5, (double) (f2 * f4));
    }

    public static boolean isVecInside(SimpleCollisionBox self, Vector3dm vec) {
        return vec.getX() > self.minX
                && vec.getX() < self.maxX
                && (vec.getY() > self.minY
                        && vec.getY() < self.maxY
                        && vec.getZ() > self.minZ
                        && vec.getZ() < self.maxZ);
    }

    public static double getMinReachToBox(CultPlayer player, SimpleCollisionBox targetBox) {
        double lowest = Double.MAX_VALUE;

        for (double eyes : player.getPossibleEyeHeights()) {
            targetBox.expand(player.getMovementThreshold());
            Vector3dm from = new Vector3dm(player.x, player.y + eyes, player.z);
            Vector3dm closestPoint = VectorUtils.cutBoxToVector(from, targetBox);
            lowest = Math.min(lowest, closestPoint.distance(from));
        }

        return lowest;
    }
}
