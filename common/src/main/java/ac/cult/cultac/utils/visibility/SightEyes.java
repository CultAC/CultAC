package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.utils.math.Vec3;

/**
 * The eyes a pick may have come from: {@code start + t * along + h * up} for t and h in [0, 1]. {@code up} is zero for
 * a single path of eyes, and otherwise spans the heights the eye may have above each point of it.
 */
record SightEyes(Vec3 start, Vec3 along, Vec3 up) {
    boolean band() {
        return up.x != 0 || up.y != 0 || up.z != 0;
    }

    Vec3 at(double t, double h) {
        return new Vec3(
                start.x + t * along.x + h * up.x, start.y + t * along.y + h * up.y, start.z + t * along.z + h * up.z);
    }

    /** The corners of the eyes between t0 and t1 and between h0 and h1; one when that is a single eye. */
    Vec3[] corners(double t0, double t1, double h0, double h1) {
        if (!band()) return t0 == t1 ? new Vec3[] {at(t0, 0)} : new Vec3[] {at(t0, 0), at(t1, 0)};
        if (t0 == t1 && h0 == h1) return new Vec3[] {at(t0, h0)};
        return new Vec3[] {at(t0, h0), at(t1, h0), at(t1, h1), at(t0, h1)};
    }

    Vec3[] corners() {
        return corners(0, 1, 0, 1);
    }

    Vec3 middle() {
        return at(0.5, band() ? 0.5 : 0);
    }
}
