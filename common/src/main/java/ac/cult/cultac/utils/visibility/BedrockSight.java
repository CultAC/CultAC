package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a Bedrock client's pick can have reached a target from some eye it may have had, past the blocks its block
 * pick certainly meets ({@link BedrockPickBlocks}). A pick may come from any eye on the path between tick positions
 * (BedrockAttackPick), so the target is hidden only when it is hidden from every eye on every leg of that path, which
 * {@link SightProof} proves for a whole leg at once. Only the blocks that can lie between the eyes and the target are
 * looked up ({@link SightBeam}). Eyes that touch a blocker are never judged: an eye inside a block may pick through
 * it.
 */
public final class BedrockSight {
    // Targets farther than any Bedrock pick ray from the path are not judged, nor are more blocks than this.
    private static final double MAX_DISTANCE = 13.0;
    private static final int MAX_CELLS = 8192;
    // A leg longer than this is a teleport, not a move.
    private static final double MAX_LEG = 8.0;
    // No more blockers than this are judged, the largest.
    private static final int MAX_BLOCKERS = 64;
    // An eye this near a block may lie inside it.
    private static final double TOUCH = 1.0E-3;
    // Some Bedrock pick boxes sit slightly inside Java's outline; every blocker gives up this much.
    private static final double INSET = 1.0E-3;

    private BedrockSight() {}

    /**
     * A leg of the eye path: the feet move from {@code from} to {@code to} while the eye rises from each height's
     * {@code before} to its {@code now} above them, or, when the heights are not exact, may lie anywhere between the
     * lowest and the highest of them.
     */
    public record Leg(Vec3 from, Vec3 to, BedrockEye.Heights heights) {}

    /** The box of the block at x, y, z a pick certainly meets, or null. */
    interface Cores {
        SimpleCollisionBox at(int x, int y, int z);
    }

    /**
     * Whether no ray from an eye on {@code legs} reaches {@code target} without first meeting a block the client's
     * pick certainly meets; {@code excluded}, the block the target belongs to, never blocks. False when the path or
     * the target cannot be judged.
     */
    public static boolean hidden(CultPlayer player, List<Leg> legs, SimpleCollisionBox target, BlockPos excluded) {
        return hidden((x, y, z) -> BedrockPickBlocks.core(player, x, y, z), legs, target, excluded);
    }

    static boolean hidden(Cores cores, List<Leg> legs, SimpleCollisionBox target, BlockPos excluded) {
        if (!finite(target)) return false;
        List<SightEyes> paths = new ArrayList<>();
        for (Leg leg : legs) {
            if (!finite(leg.from()) || !finite(leg.to()) || leg.from().distanceTo(leg.to()) > MAX_LEG) return false;
            eyes(leg, paths);
        }
        if (paths.isEmpty()) return false;

        List<Vec3> corners = new ArrayList<>();
        SimpleCollisionBox bounds = target.copy();
        for (SightEyes eyes : paths) {
            for (Vec3 corner : eyes.corners()) {
                if (distance(corner, target) > MAX_DISTANCE) return false;
                bounds.minX = Math.min(bounds.minX, corner.x);
                bounds.minY = Math.min(bounds.minY, corner.y);
                bounds.minZ = Math.min(bounds.minZ, corner.z);
                bounds.maxX = Math.max(bounds.maxX, corner.x);
                bounds.maxY = Math.max(bounds.maxY, corner.y);
                bounds.maxZ = Math.max(bounds.maxZ, corner.z);
                corners.add(corner);
            }
        }
        int minX = (int) Math.floor(bounds.minX), maxX = (int) Math.floor(bounds.maxX);
        int minY = (int) Math.floor(bounds.minY), maxY = (int) Math.floor(bounds.maxY);
        int minZ = (int) Math.floor(bounds.minZ), maxZ = (int) Math.floor(bounds.maxZ);
        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_CELLS) return false;

        SightBeam beam = new SightBeam(corners, target);
        List<SimpleCollisionBox> blockers = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                // A block within TOUCH of the eyes is looked up too, so that they are known to touch it.
                if (!beam.meets(2, x - TOUCH, y - TOUCH, x + 1 + TOUCH, y + 1 + TOUCH)) continue;
                for (int z = minZ; z <= maxZ; z++) {
                    if (!beam.meets(0, y - TOUCH, z - TOUCH, y + 1 + TOUCH, z + 1 + TOUCH)) continue;
                    if (!beam.meets(1, z - TOUCH, x - TOUCH, z + 1 + TOUCH, x + 1 + TOUCH)) continue;
                    if (excluded != null && excluded.getX() == x && excluded.getY() == y && excluded.getZ() == z)
                        continue;
                    SimpleCollisionBox core = cores.at(x, y, z);
                    if (core == null) continue;
                    for (SightEyes eyes : paths) if (touches(eyes, core)) return false;
                    blockers.add(core);
                }
            }
        }
        if (blockers.isEmpty()) return false;
        blockers = SightBeam.merge(blockers, MAX_BLOCKERS);
        List<SimpleCollisionBox> interiors = new ArrayList<>(blockers.size());
        for (SimpleCollisionBox blocker : blockers) {
            SimpleCollisionBox interior = shrink(blocker, INSET);
            if (interior != null) interiors.add(interior);
        }
        for (SightEyes eyes : paths) if (!SightProof.hidden(eyes, target, interiors)) return false;
        return true;
    }

    private static void eyes(Leg leg, List<SightEyes> paths) {
        BedrockEye.Heights heights = leg.heights();
        Vec3 move = leg.to().subtract(leg.from());
        if (heights.exact()) {
            for (BedrockEye eye : heights.eyes()) {
                paths.add(new SightEyes(
                        leg.from().add(0, eye.before(), 0), move.add(0, eye.now() - eye.before(), 0), Vec3.ZERO));
            }
            return;
        }
        if (heights.eyes().isEmpty()) return;
        double low = heights.lowest(), high = heights.highest();
        paths.add(new SightEyes(leg.from().add(0, low, 0), move, new Vec3(0, high - low, 0)));
    }

    // Whether some eye lies within TOUCH of the box: the lowest eyes' path meets the box grown down by their heights.
    private static boolean touches(SightEyes eyes, SimpleCollisionBox box) {
        double[] start = {eyes.start().x, eyes.start().y, eyes.start().z};
        double[] delta = {eyes.along().x, eyes.along().y, eyes.along().z};
        double[] min = {box.minX - TOUCH, box.minY - TOUCH - eyes.up().y, box.minZ - TOUCH};
        double[] max = {box.maxX + TOUCH, box.maxY + TOUCH, box.maxZ + TOUCH};
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (delta[axis] == 0) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) return false;
                continue;
            }
            double a = (min[axis] - start[axis]) / delta[axis], b = (max[axis] - start[axis]) / delta[axis];
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
            if (enter > exit) return false;
        }
        return true;
    }

    // The box shrunk by this much on every side, or null when nothing is left.
    private static SimpleCollisionBox shrink(SimpleCollisionBox box, double by) {
        double minX = box.minX + by, maxX = box.maxX - by;
        double minY = box.minY + by, maxY = box.maxY - by;
        double minZ = box.minZ + by, maxZ = box.maxZ - by;
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return null;
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double distance(Vec3 point, SimpleCollisionBox box) {
        double x = Math.max(box.minX - point.x, Math.max(0, point.x - box.maxX));
        double y = Math.max(box.minY - point.y, Math.max(0, point.y - box.maxY));
        double z = Math.max(box.minZ - point.z, Math.max(0, point.z - box.maxZ));
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean finite(SimpleCollisionBox box) {
        return Double.isFinite(box.minX)
                && Double.isFinite(box.minY)
                && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX)
                && Double.isFinite(box.maxY)
                && Double.isFinite(box.maxZ);
    }
}
