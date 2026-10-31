package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;

/** LocalPlayer#aiStep's four ordered moveTowardsClosestSpace calls, before LivingEntity#aiStep and travel. */
public final class JavaBlockPushOut {
    private static final int[] X = {-1, 1, 0, 0};
    private static final int[] Z = {0, 0, -1, 1};

    private JavaBlockPushOut() {}

    public static Vec3 apply(CultPlayer player, SimulationContext context, Vec3 velocity) {
        if (player.isBedrockMovement() || context.getVehicle() != null || player.gamemode == GameMode.SPECTATOR)
            return velocity;
        SimpleCollisionBox box = context.getFromActualPose();
        double width = context.getPose().width * context.getScale() * 0.35D;
        Vec3 start = context.getStart();
        velocity = corner(player, box, start.x - width, start.z + width, velocity);
        velocity = corner(player, box, start.x - width, start.z - width, velocity);
        velocity = corner(player, box, start.x + width, start.z - width, velocity);
        return corner(player, box, start.x + width, start.z + width, velocity);
    }

    private static Vec3 corner(CultPlayer player, SimpleCollisionBox box, double x, double z, Vec3 velocity) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!suffocates(player, box, blockX, blockZ)) return velocity;
        double localX = x - blockX;
        double localZ = z - blockZ;
        double closest = Double.MAX_VALUE;
        int chosen = -1;
        // Strict comparison preserves vanilla's WEST, EAST, NORTH, SOUTH tie order.
        for (int i = 0; i < 4; i++) {
            double distance = i == 0 ? localX : i == 1 ? 1 - localX : i == 2 ? localZ : 1 - localZ;
            if (distance < closest && !suffocates(player, box, blockX + X[i], blockZ + Z[i])) {
                closest = distance;
                chosen = i;
            }
        }
        if (chosen < 0) return velocity;
        return chosen < 2
                ? new Vec3(0.1D * X[chosen], velocity.y, velocity.z)
                : new Vec3(velocity.x, velocity.y, 0.1D * Z[chosen]);
    }

    private static boolean suffocates(CultPlayer player, SimpleCollisionBox box, int x, int z) {
        return Collisions.suffocatesAt(
                player, new SimpleCollisionBox(x, box.minY, z, x + 1.0D, box.maxY, z + 1.0D).expand(-1.0E-7D));
    }
}
