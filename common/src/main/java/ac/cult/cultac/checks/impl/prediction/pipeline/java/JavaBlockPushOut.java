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

    /**
     * The four calls for {@code context}. Each tests only blocks around the start and its pose, never the velocity,
     * so one result holds for every velocity of the context.
     */
    public static Pushes pushes(CultPlayer player, SimulationContext context) {
        if (player.isBedrockMovement() || context.getVehicle() != null || player.gamemode == GameMode.SPECTATOR)
            return Pushes.NONE;
        SimpleCollisionBox box = context.getFromActualPose();
        double width = context.getPose().width * context.getScale() * 0.35D;
        Vec3 start = context.getStart();
        int first = corner(player, box, start.x - width, start.z + width);
        int second = corner(player, box, start.x - width, start.z - width);
        int third = corner(player, box, start.x + width, start.z - width);
        int fourth = corner(player, box, start.x + width, start.z + width);
        return first < 0 && second < 0 && third < 0 && fourth < 0
                ? Pushes.NONE
                : new Pushes(first, second, third, fourth);
    }

    /** The direction the call at this corner pushes towards, or -1 when it leaves the velocity unchanged. */
    private static int corner(CultPlayer player, SimpleCollisionBox box, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!suffocates(player, box, blockX, blockZ)) return -1;
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
        return chosen;
    }

    private static boolean suffocates(CultPlayer player, SimpleCollisionBox box, int x, int z) {
        return Collisions.suffocatesAt(
                player, new SimpleCollisionBox(x, box.minY, z, x + 1.0D, box.maxY, z + 1.0D).expand(-1.0E-7D));
    }

    /** The direction each call pushes towards in order, -1 for a call that leaves the velocity unchanged. */
    public record Pushes(int first, int second, int third, int fourth) {
        static final Pushes NONE = new Pushes(-1, -1, -1, -1);

        /** {@code velocity} after the calls; the same instance when none pushes. */
        public Vec3 apply(Vec3 velocity) {
            return push(fourth, push(third, push(second, push(first, velocity))));
        }

        private static Vec3 push(int direction, Vec3 velocity) {
            if (direction < 0) return velocity;
            return direction < 2
                    ? new Vec3(0.1D * X[direction], velocity.y, velocity.z)
                    : new Vec3(velocity.x, velocity.y, 0.1D * Z[direction]);
        }
    }
}
