package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vector3dm;

// Copied directly from Hawk
public class Ray {

    private Vector3dm origin;
    private Vector3dm direction;

    public Ray(CultPlayer player, double x, double y, double z, float xRot, float yRot) {
        this.origin = new Vector3dm(x, y, z);
        this.direction = calculateDirection(player, xRot, yRot);
    }

    // xRot is yaw and yRot is pitch here. The client builds the ray with
    // Entity#calculateViewVector, so use the same signed lookups and float products.
    public static Vector3dm calculateDirection(CultPlayer player, float xRot, float yRot) {
        return ReachUtils.getLook(player, xRot, yRot);
    }

    public Vector3dm getPointAtDistance(double distance) {
        Vector3dm dir = new Vector3dm(direction.getX(), direction.getY(), direction.getZ());
        Vector3dm orig = new Vector3dm(origin.getX(), origin.getY(), origin.getZ());
        return orig.add(dir.multiply(distance));
    }

    public Vector3dm getOrigin() {
        return origin;
    }
}
