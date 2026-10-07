package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;

/** The received border at this client tick; collision planes round outward to whole blocks. */
public record BorderBounds(double minX, double minZ, double maxX, double maxZ) {
    public boolean collides(Box box, Vec3 source) {
        double margin = Math.max(Math.max(Math.abs(box.maxX() - box.minX()), Math.abs(box.maxZ() - box.minZ())), 1);
        double distance = Math.min(Math.min(source.x() - minX, maxX - source.x()), Math.min(source.z() - minZ, maxZ - source.z()));
        if (!(distance < margin * 2 && source.x() >= minX - margin && source.x() < maxX + margin
            && source.z() >= minZ - margin && source.z() < maxZ + margin)) return false;
        var infinity = Shapes.create(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        var inside = Shapes.create(Math.floor(minX), Double.NEGATIVE_INFINITY, Math.floor(minZ),
            Math.ceil(maxX), Double.POSITIVE_INFINITY, Math.ceil(maxZ));
        return Shapes.joinIsNotEmpty(Shapes.join(infinity, inside, BooleanOp.ONLY_FIRST), Shapes.create(box), BooleanOp.AND);
    }
}
