package ac.cult.blocksim.data;

public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Box {
        if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) {
            throw new IllegalArgumentException("Invalid box bounds");
        }
    }
    public Box move(double x, double y, double z) {
        return new Box(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }
    public boolean intersects(Box other) {
        return minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY
            && minZ < other.maxZ && maxZ > other.minZ;
    }
    public Box inflate(double amount) {
        return new Box(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
    }
    public Box expandTowards(ac.cult.blocksim.engine.Vec3 delta) {
        return new Box(minX + Math.min(0, delta.x()), minY + Math.min(0, delta.y()), minZ + Math.min(0, delta.z()),
            maxX + Math.max(0, delta.x()), maxY + Math.max(0, delta.y()), maxZ + Math.max(0, delta.z()));
    }
    public boolean contains(ac.cult.blocksim.engine.Vec3 point) {
        return minX <= point.x() && point.x() < maxX && minY <= point.y() && point.y() < maxY
            && minZ <= point.z() && point.z() < maxZ;
    }
    /** AABB.clip: entry faces only, strict segment endpoints, and 1e-7 side tolerances. */
    public ac.cult.blocksim.engine.Vec3 clip(ac.cult.blocksim.engine.Vec3 from, ac.cult.blocksim.engine.Vec3 to) {
        return clip(minX, minY, minZ, maxX, maxY, maxZ, from, to);
    }
    /** Raw bounds for callers whose packet-derived boxes may contain non-finite coordinates. */
    public static ac.cult.blocksim.engine.Vec3 clip(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, ac.cult.blocksim.engine.Vec3 from, ac.cult.blocksim.engine.Vec3 to) {
        double[] origin = {from.x(), from.y(), from.z()}, delta = {to.x() - from.x(), to.y() - from.y(), to.z() - from.z()};
        double[] lower = {minX, minY, minZ}, upper = {maxX, maxY, maxZ};
        double nearest = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            double movement = delta[axis];
            if (Math.abs(movement) <= 1.0E-7) continue;
            double t = ((movement > 0 ? lower[axis] : upper[axis]) - origin[axis]) / movement;
            if (!(0.0 < t && t < nearest)) continue;
            int b = (axis + 1) % 3, c = (axis + 2) % 3;
            double pb = origin[b] + t * delta[b], pc = origin[c] + t * delta[c];
            if (lower[b] - 1.0E-7 < pb && pb < upper[b] + 1.0E-7 && lower[c] - 1.0E-7 < pc && pc < upper[c] + 1.0E-7)
                nearest = t;
        }
        return nearest == 1.0 ? null : from.addScaled(to.subtract(from), nearest);
    }
    public ac.cult.blocksim.engine.Vec3 center() {
        return new ac.cult.blocksim.engine.Vec3(minX + .5 * (maxX - minX), minY + .5 * (maxY - minY), minZ + .5 * (maxZ - minZ));
    }
    public Box nextDeflated() {
        return new Box(Math.nextUp(minX), Math.nextUp(minY), Math.nextUp(minZ),
            Math.nextDown(maxX), Math.nextDown(maxY), Math.nextDown(maxZ));
    }
}
