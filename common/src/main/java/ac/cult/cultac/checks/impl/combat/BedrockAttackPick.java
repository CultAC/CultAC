package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.BedrockEye;
import java.util.List;

/**
 * Where a Bedrock client's attack came from, as Cult models it: an eye on the path from one of its {@code eyes}' {@code
 * before} height above its last tick position to its {@code now} height above its latest, the same fraction along both,
 * and a view between the rotation it last sent and the one it sends with its next tick. It reports where the pick
 * entered the target's box.
 */
record BedrockAttackPick(
        Vec3 last,
        Vec3 now,
        Vec3 hit,
        double hitPrecision,
        float yawBefore,
        float pitchBefore,
        float yaw,
        float pitch,
        boolean screenPick,
        List<BedrockEye> eyes) {

    // The angle precision of a view direction, with room for the float hit point.
    private static final double ANGLE_PRECISION = 0.01;
    // The view to the hit point is followed along the eye path in pieces of at most this many degrees.
    private static final double PIECE_DEGREES = 0.5;
    private static final int MAX_PIECES = 512;

    /** The least distance from an eye on the path to {@code point}. */
    double reach(Vec3 point) {
        double least = Double.POSITIVE_INFINITY;
        for (BedrockEye eye : eyes) least = Math.min(least, distanceToPath(eye, point));
        return least;
    }

    /**
     * Whether an eye on the path could have looked at the hit point on {@code box} from its frame: along a view from
     * {@link #yaw}/{@link #pitch} toward {@code nextYaw}/{@code nextPitch}, or along any view from inside the box,
     * where the pick hits at the eye. The view turns every frame and the ticks only sample it, so one that turns back
     * within a tick can look past both; it may run up to the larger of the two neighbouring ticks' turns past them.
     * Cult does not judge the look of a touch or motion controller pick.
     */
    boolean looksAtHit(float nextYaw, float nextPitch, SimpleCollisionBox box) {
        if (screenPick) return true;
        for (BedrockEye eye : eyes) {
            if (pathMeets(eye, box)) return true;
        }
        double turnYaw = wrap(nextYaw - yaw);
        double turnPitch = nextPitch - pitch;
        double yawRoom = Math.max(Math.abs(wrap(yaw - yawBefore)), Math.abs(turnYaw)) + ANGLE_PRECISION;
        double pitchRoom = Math.max(Math.abs(pitch - pitchBefore), Math.abs(turnPitch)) + ANGLE_PRECISION;
        for (BedrockEye eye : eyes) {
            if (looksAtHit(eye, turnYaw, turnPitch, yawRoom, pitchRoom)) return true;
        }
        return false;
    }

    private boolean looksAtHit(BedrockEye eye, double turnYaw, double turnPitch, double yawRoom, double pitchRoom) {
        double nearest = distanceToPath(eye, hit);
        if (nearest < 1.0E-6) return true; // The pick starts inside the box.
        Vec3 start = start(eye);
        Vec3 path = path(eye);
        // The view to the hit turns fastest where the path passes nearest it.
        int pieces = (int) Math.min(
                MAX_PIECES, Math.max(1, Math.ceil(Math.toDegrees(path.length() / nearest) / PIECE_DEGREES)));

        double[] yaws = new double[pieces + 1];
        double[] pitches = new double[pieces + 1];
        double steepest = 0;
        for (int i = 0; i <= pieces; i++) {
            double along = (double) i / pieces;
            double x = hit.x - (start.x + path.x * along);
            double y = hit.y - (start.y + path.y * along);
            double z = hit.z - (start.z + path.z * along);
            double flat = Math.sqrt(x * x + z * z);
            double viewYaw = Math.toDegrees(Math.atan2(-x, z));
            yaws[i] = i == 0 ? wrap(viewYaw - yaw) : yaws[i - 1] + wrap(viewYaw - yaws[i - 1] - yaw);
            pitches[i] = Math.toDegrees(Math.atan2(-y, flat)) - pitch;
            steepest = Math.max(steepest, Math.abs(pitches[i] + pitch));
        }
        // Near straight up or down a yaw step barely moves the view.
        double yawPrecision = ANGLE_PRECISION / Math.max(Math.cos(Math.toRadians(steepest)), 0.01);
        double yawLimit = yawRoom + yawPrecision;
        for (int i = 0; i < pieces; i++) {
            for (int turn = -1; turn <= 1; turn++) {
                double shift = 360.0 * turn;
                if (meets(
                        (yaws[i] + shift) / yawLimit,
                        pitches[i] / pitchRoom,
                        (yaws[i + 1] - yaws[i]) / yawLimit,
                        (pitches[i + 1] - pitches[i]) / pitchRoom,
                        turnYaw / yawLimit,
                        turnPitch / pitchRoom)) return true;
            }
        }
        return false;
    }

    // Whether the piece from (x, y) along (dx, dy) comes within 1 on both axes of the turn from the origin to
    // (turnX, turnY): a point of the 2D polygon {s, b in [0, 1]: |x + s dx - b turnX| <= 1, |y + s dy - b turnY| <= 1},
    // which, if not empty, has a vertex where two of its eight edge lines meet.
    private static boolean meets(double x, double y, double dx, double dy, double turnX, double turnY) {
        // Each line a s + b b = c.
        double[][] lines = {
            {1, 0, 0},
            {1, 0, 1},
            {0, 1, 0},
            {0, 1, 1},
            {dx, -turnX, 1 - x},
            {dx, -turnX, -1 - x},
            {dy, -turnY, 1 - y},
            {dy, -turnY, -1 - y}
        };
        for (int i = 0; i < lines.length; i++) {
            for (int j = i + 1; j < lines.length; j++) {
                double det = lines[i][0] * lines[j][1] - lines[i][1] * lines[j][0];
                if (Math.abs(det) < 1.0E-12) continue;
                double s = (lines[i][2] * lines[j][1] - lines[i][1] * lines[j][2]) / det;
                double b = (lines[i][0] * lines[j][2] - lines[i][2] * lines[j][0]) / det;
                if (within(s, b, x, y, dx, dy, turnX, turnY)) return true;
            }
        }
        return false;
    }

    private static boolean within(
            double s, double b, double x, double y, double dx, double dy, double turnX, double turnY) {
        final double e = 1.0E-9;
        return s >= -e
                && s <= 1 + e
                && b >= -e
                && b <= 1 + e
                && Math.abs(x + s * dx - b * turnX) <= 1 + e
                && Math.abs(y + s * dy - b * turnY) <= 1 + e;
    }

    // Whether the eye path passes through the box.
    private boolean pathMeets(BedrockEye eye, SimpleCollisionBox box) {
        Vec3 start = start(eye);
        Vec3 delta = path(eye);
        double[] from = {start.x, start.y, start.z};
        double[] path = {delta.x, delta.y, delta.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (path[axis] == 0) {
                if (from[axis] < min[axis] || from[axis] > max[axis]) return false;
                continue;
            }
            double a = (min[axis] - from[axis]) / path[axis];
            double b = (max[axis] - from[axis]) / path[axis];
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
            if (enter > exit) return false;
        }
        return true;
    }

    // Where the eye path starts, at the client's last tick.
    private Vec3 start(BedrockEye eye) {
        return new Vec3(last.x, last.y + eye.before(), last.z);
    }

    // The eye path from its start to the client's latest tick.
    private Vec3 path(BedrockEye eye) {
        return new Vec3(now.x - last.x, now.y + eye.now() - (last.y + eye.before()), now.z - last.z);
    }

    private double distanceToPath(BedrockEye eye, Vec3 point) {
        Vec3 start = start(eye);
        Vec3 path = path(eye);
        double pathX = path.x;
        double pathY = path.y;
        double pathZ = path.z;
        double x = point.x - start.x;
        double y = point.y - start.y;
        double z = point.z - start.z;
        double length = pathX * pathX + pathY * pathY + pathZ * pathZ;
        double along = length == 0 ? 0 : Math.max(0, Math.min(1, (x * pathX + y * pathY + z * pathZ) / length));
        x -= pathX * along;
        y -= pathY * along;
        z -= pathZ * along;
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }
}
