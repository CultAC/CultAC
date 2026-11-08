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
 * (BedrockAttackPick), so the target is hidden only when it is hidden from every eye on every leg of that path. Each
 * leg is split into pieces; for a piece with centre c and half-size h, a target grown by h that stays hidden from c
 * behind the blockers shrunk by h is hidden from every eye in the piece, as ClientVisibility proves for its eye buffer.
 * A piece that touches a blocker is never judged: an eye inside a block may pick through it.
 */
public final class BedrockSight {
    // The largest half-size of a piece along the path, and of a band of eye heights when the engine gave none.
    private static final double PIECE = 0.03;
    private static final double BAND = 0.1;
    private static final double CLIP_EPSILON = 1.0E-7;
    // Targets farther than any Bedrock pick ray from the path are not judged, nor are more blocks than this.
    private static final double MAX_DISTANCE = 13.0;
    private static final int MAX_CELLS = 8192;
    // A leg longer than this is a teleport, not a move; more pieces than this are not judged.
    private static final double MAX_LEG = 8.0;
    private static final int MAX_PIECES = 4096;
    // An eye this near a block may lie inside it.
    private static final double TOUCH = 1.0E-3;
    // Some Bedrock pick boxes sit slightly inside Java's outline; every blocker gives up this much more.
    private static final double INSET = 1.0E-3;

    private BedrockSight() {}

    /**
     * A leg of the eye path: the feet move from {@code from} to {@code to} while the eye rises from each height's
     * {@code before} to its {@code now} above them, or, when the heights are not exact, may lie anywhere between the
     * lowest and the highest of them.
     */
    public record Leg(Vec3 from, Vec3 to, BedrockEye.Heights heights) {}

    private record Piece(Vec3 centre, double x, double y, double z) {
        SimpleCollisionBox box() {
            return new SimpleCollisionBox(
                    centre.x - x, centre.y - y, centre.z - z, centre.x + x, centre.y + y, centre.z + z);
        }
    }

    /**
     * Whether no ray from an eye on {@code legs} reaches {@code target} without first meeting a block the client's
     * pick certainly meets; {@code excluded}, the block the target belongs to, never blocks. False when the path or
     * the target cannot be judged.
     */
    public static boolean hidden(CultPlayer player, List<Leg> legs, SimpleCollisionBox target, BlockPos excluded) {
        if (!finite(target)) return false;
        List<Piece> pieces = new ArrayList<>();
        for (Leg leg : legs) {
            if (!finite(leg.from()) || !finite(leg.to()) || leg.from().distanceTo(leg.to()) > MAX_LEG) return false;
            split(leg, pieces);
        }
        if (pieces.isEmpty() || pieces.size() > MAX_PIECES) return false;

        SimpleCollisionBox bounds = target.copy();
        for (Piece piece : pieces) {
            SimpleCollisionBox box = piece.box();
            bounds = union(bounds, box);
            if (distance(piece.centre(), target) > MAX_DISTANCE) return false;
        }
        int minX = (int) Math.floor(bounds.minX), maxX = (int) Math.floor(bounds.maxX);
        int minY = (int) Math.floor(bounds.minY), maxY = (int) Math.floor(bounds.maxY);
        int minZ = (int) Math.floor(bounds.minZ), maxZ = (int) Math.floor(bounds.maxZ);
        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_CELLS) return false;

        List<SimpleCollisionBox> blockers = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (excluded != null && excluded.getX() == x && excluded.getY() == y && excluded.getZ() == z)
                        continue;
                    SimpleCollisionBox core = BedrockPickBlocks.core(player, x, y, z);
                    if (core != null) blockers.add(core);
                }
            }
        }
        if (blockers.isEmpty()) return false;
        for (Piece piece : pieces) {
            SimpleCollisionBox box = piece.box();
            for (SimpleCollisionBox blocker : blockers) if (touches(box, blocker)) return false;
        }
        blockers = OccluderBoxes.coalesce(blockers);
        // Most picks are seen from the first eye; only a hidden one needs the proof for the whole path.
        if (OcclusionGeometry.hasVisiblePoint(
                pieces.getFirst().centre(), target, null, blockers, null, Double.POSITIVE_INFINITY)) return false;
        for (Piece piece : pieces) {
            var envelope =
                    target.copy().expand(piece.x() + CLIP_EPSILON, piece.y() + CLIP_EPSILON, piece.z() + CLIP_EPSILON);
            List<SimpleCollisionBox> interiors = new ArrayList<>(blockers.size());
            for (SimpleCollisionBox blocker : blockers) {
                SimpleCollisionBox interior = shrink(blocker, piece.x() + INSET, piece.y() + INSET, piece.z() + INSET);
                if (interior != null) interiors.add(interior);
            }
            if (!OcclusionGeometry.fullyOccluded(piece.centre(), envelope, interiors)) return false;
        }
        return true;
    }

    private static void split(Leg leg, List<Piece> pieces) {
        BedrockEye.Heights heights = leg.heights();
        if (heights.exact()) {
            for (BedrockEye eye : heights.eyes()) {
                split(leg.from().add(0, eye.before(), 0), leg.to().add(0, eye.now(), 0), 0, pieces);
            }
            return;
        }
        double low = heights.lowest(), high = heights.highest();
        int bands = Math.max(1, (int) Math.ceil((high - low) / (2 * BAND)));
        double half = (high - low) / (2 * bands);
        for (int band = 0; band < bands; band++) {
            double height = low + half * (2 * band + 1);
            split(leg.from().add(0, height, 0), leg.to().add(0, height, 0), half, pieces);
        }
    }

    private static void split(Vec3 from, Vec3 to, double spread, List<Piece> pieces) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double longest = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int count = Math.max(1, (int) Math.ceil(longest / (2 * PIECE)));
        for (int i = 0; i < count; i++) {
            double along = (i + 0.5) / count;
            pieces.add(new Piece(
                    new Vec3(from.x + dx * along, from.y + dy * along, from.z + dz * along),
                    Math.abs(dx) / (2 * count) + CLIP_EPSILON,
                    Math.abs(dy) / (2 * count) + spread + CLIP_EPSILON,
                    Math.abs(dz) / (2 * count) + CLIP_EPSILON));
        }
    }

    private static boolean touches(SimpleCollisionBox a, SimpleCollisionBox b) {
        return a.minX <= b.maxX + TOUCH
                && a.maxX >= b.minX - TOUCH
                && a.minY <= b.maxY + TOUCH
                && a.maxY >= b.minY - TOUCH
                && a.minZ <= b.maxZ + TOUCH
                && a.maxZ >= b.minZ - TOUCH;
    }

    // The box shrunk by the half-sizes on every side, or null when nothing is left.
    private static SimpleCollisionBox shrink(SimpleCollisionBox box, double x, double y, double z) {
        double minX = box.minX + x, maxX = box.maxX - x;
        double minY = box.minY + y, maxY = box.maxY - y;
        double minZ = box.minZ + z, maxZ = box.maxZ - z;
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return null;
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static SimpleCollisionBox union(SimpleCollisionBox a, SimpleCollisionBox b) {
        return new SimpleCollisionBox(
                Math.min(a.minX, b.minX),
                Math.min(a.minY, b.minY),
                Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX),
                Math.max(a.maxY, b.maxY),
                Math.max(a.maxZ, b.maxZ));
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
