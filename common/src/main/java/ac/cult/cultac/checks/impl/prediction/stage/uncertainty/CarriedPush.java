package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;

/** Where the inherited-velocity transforms of the teleports since the last travel take a push along X and along Z. */
public record CarriedPush(double xx, double xy, double xz, double zx, double zy, double zz) {
    public static final CarriedPush IDENTITY = new CarriedPush(1.0, 0.0, 0.0, 0.0, 0.0, 1.0);

    public CarriedPush after(TeleportData teleport) {
        Vec3 x = teleport.transformInheritedVelocity(new Vec3(xx, xy, xz));
        Vec3 z = teleport.transformInheritedVelocity(new Vec3(zx, zy, zz));
        return new CarriedPush(x.x, x.y, x.z, z.x, z.y, z.z);
    }

    public boolean isIdentity() {
        return xx == 1.0 && xy == 0.0 && xz == 0.0 && zx == 0.0 && zy == 0.0 && zz == 1.0;
    }

    public boolean isZero() {
        return xx == 0.0 && xy == 0.0 && xz == 0.0 && zx == 0.0 && zy == 0.0 && zz == 0.0;
    }

    /** Moves start toward end by the image of a horizontal push of length at most radius. */
    public PredVector push(PredVector start, Vec3 end, double radius) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double xLength = xx * xx + xy * xy + xz * xz;
        double zLength = zx * zx + zy * zy + zz * zz;
        double cross = xx * zx + xy * zy + xz * zz;
        double xToEnd = xx * dx + xy * dy + xz * dz;
        double zToEnd = zx * dx + zy * dy + zz * dz;
        double determinant = xLength * zLength - cross * cross;
        double u;
        double v;
        if (determinant > 1.0E-12 * xLength * zLength) {
            u = (zLength * xToEnd - cross * zToEnd) / determinant;
            v = (xLength * zToEnd - cross * xToEnd) / determinant;
            double length = Math.sqrt(u * u + v * v);
            if (length > radius) {
                u *= radius / length;
                v *= radius / length;
            }
        } else {
            // Near one line: push only along the dominant right-singular vector s, so |(u, v)| stays within radius.
            double halfGap = (xLength - zLength) * 0.5;
            double top = (xLength + zLength) * 0.5 + Math.sqrt(halfGap * halfGap + cross * cross);
            double sx = xLength >= zLength ? top - zLength : cross;
            double sz = xLength >= zLength ? cross : top - xLength;
            double sLength = Math.sqrt(sx * sx + sz * sz);
            if (sLength == 0.0) return start;
            sx /= sLength;
            sz /= sLength;
            double wx = xx * sx + zx * sz;
            double wy = xy * sx + zy * sz;
            double wz = xz * sx + zz * sz;
            double wLength = wx * wx + wy * wy + wz * wz;
            if (wLength == 0.0) return start;
            double along = (wx * dx + wy * dy + wz * dz) / wLength;
            along = Math.max(-radius, Math.min(radius, along));
            u = sx * along;
            v = sz * along;
        }
        return start.withXYZOrSelf(
                start.x + xx * u + zx * v, start.y + xy * u + zy * v, start.z + xz * u + zz * v, "carried push");
    }
}
