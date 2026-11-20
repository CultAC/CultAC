package ac.cult.cultac.utils.visibility;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.List;
import java.util.Optional;

/**
 * The part of each block a Bedrock client's block pick certainly meets, judged from the blocks Cult models for its
 * world. Bedrock pick shapes differ from Java's outlines: stairs pick as their stair pieces, connected fences, panes,
 * bars and walls as one box around the post and its arms, doors and trapdoors as thinner plates, and a wall without a
 * post lower. So each block counts only with a box inside its pick shape: a full cube, a slab, a stair's slab half, a
 * door or trapdoor plate, a fence, pane or wall post, or a fence gate. It counts only while every state the client may
 * hold there has that box: not a block the client's own prediction wrote, and not one a pending server change replaces
 * with a block without it.
 */
public final class BedrockPickBlocks {
    private static final String BARRIER = "minecraft:barrier";
    private static final SimpleCollisionBox FULL = new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
    // A slab, and the slab half every stair shape holds.
    private static final SimpleCollisionBox BOTTOM_HALF = new SimpleCollisionBox(0, 0, 0, 1, 0.5, 1);
    private static final SimpleCollisionBox TOP_HALF = new SimpleCollisionBox(0, 0.5, 0, 1, 1, 1);
    // A door or trapdoor plate is 2.92/16 thick; a trapdoor's outline lies 0.0005 inside its other faces.
    private static final double PLATE = 0.18;
    private static final double TRAPDOOR_INSET = 0.001;
    private static final SimpleCollisionBox FENCE_POST =
            new SimpleCollisionBox(6 / 16.0, 0, 6 / 16.0, 10 / 16.0, 1, 10 / 16.0);
    private static final SimpleCollisionBox PANE_POST =
            new SimpleCollisionBox(7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 1, 9 / 16.0);
    // Every wall shape holds the middle of its lowest, a straight wall without a post.
    private static final SimpleCollisionBox WALL_CORE =
            new SimpleCollisionBox(5 / 16.0, 0, 5 / 16.0, 11 / 16.0, 13 / 16.0, 11 / 16.0);
    private static final double GATE_INSET = 0.001;
    private static final double EPSILON = 1.0E-6;

    private BedrockPickBlocks() {}

    /** The box of the block at x, y, z the client's pick certainly meets, in world coordinates, or null. */
    public static SimpleCollisionBox core(CultPlayer player, int x, int y, int z) {
        CompensatedWorld world = player.compensatedWorld;
        if (world.isBedrockClientWritten(x, y, z)) return null;
        int state = world.getBlockStateIdAt(x, y, z);
        SimpleCollisionBox local = localCore(player, state, x, y, z);
        if (local == null) return null;
        int sent = world.getServerSentBlockStateIdAt(new BlockPos(x, y, z));
        if (sent == CompensatedWorld.UNKNOWN_SERVER_STATE) return null;
        if (sent != state) {
            SimpleCollisionBox next = localCore(player, sent, x, y, z);
            if (next == null) return null;
            local = intersection(local, next);
            if (local == null) return null;
        }
        return local.copy().offset(x, y, z);
    }

    /**
     * Whether the ray from {@code from} to {@code to} first meets such a block at least {@code edge} inside the face
     * it enters, so that every ray within {@code edge} of it meets that block too.
     */
    public static boolean blocks(CultPlayer player, Vec3 from, Vec3 to, double edge) {
        Boolean blocked;
        try {
            blocked = BlockRaycast.traverse(
                    new ac.cult.blocksim.engine.Vec3(from.x, from.y, from.z),
                    new ac.cult.blocksim.engine.Vec3(to.x, to.y, to.z),
                    pos -> {
                        SimpleCollisionBox box = core(player, pos.x(), pos.y(), pos.z());
                        return box == null ? null : entersInside(box, from, to, edge);
                    });
        } catch (ac.cult.blocksim.engine.SimLevel.UnloadedWorldException unloaded) {
            return false; // The client's view of an unloaded block is unknown.
        }
        return blocked != null && blocked;
    }

    // Null when the segment misses the box; otherwise whether it enters a face from outside, at least edge inside it.
    private static Boolean entersInside(SimpleCollisionBox box, Vec3 from, Vec3 to, double edge) {
        double[] start = {from.x, from.y, from.z};
        double[] delta = {to.x - from.x, to.y - from.y, to.z - from.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        double enter = 0, exit = 1;
        int enterAxis = -1;
        for (int axis = 0; axis < 3; axis++) {
            if (delta[axis] == 0) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) return null;
                continue;
            }
            double a = (min[axis] - start[axis]) / delta[axis];
            double b = (max[axis] - start[axis]) / delta[axis];
            double near = Math.min(a, b);
            if (near > enter) {
                enter = near;
                enterAxis = axis;
            }
            exit = Math.min(exit, Math.max(a, b));
            if (enter > exit) return null;
        }
        if (enterAxis < 0) return false; // The ray starts inside the box.
        for (int axis = 0; axis < 3; axis++) {
            if (axis == enterAxis) continue;
            double at = start[axis] + delta[axis] * enter;
            if (at < min[axis] + edge || at > max[axis] - edge) return false;
        }
        return true;
    }

    // The box of this state inside its Bedrock pick shape, in block coordinates, or null.
    private static SimpleCollisionBox localCore(CultPlayer player, int state, int x, int y, int z) {
        if (state < 0) return null;
        if (fullCube(player, state, x, y, z)) return FULL;
        String key = DataTables.defaults().registry().block(state).key();
        boolean half = key.endsWith("_slab") || key.endsWith("_stairs");
        boolean plate = key.endsWith("_trapdoor") || key.endsWith("_door");
        boolean gate = key.endsWith("_fence_gate");
        SimpleCollisionBox post = key.endsWith("_fence")
                ? FENCE_POST
                : key.endsWith("_pane") || key.equals("minecraft:iron_bars")
                        ? PANE_POST
                        : key.endsWith("_wall") ? WALL_CORE : null;
        if (!half && !plate && !gate && post == null) return null;
        List<SimpleCollisionBox> outline = NativeBlockCollisionHelper.toBoxes(
                NativeBlockCollisionHelper.getSelectionShape(player, state, x, y, z));
        if (outline.isEmpty()) return null;
        SimpleCollisionBox core;
        if (half) {
            core = covered(BOTTOM_HALF, outline) ? BOTTOM_HALF : covered(TOP_HALF, outline) ? TOP_HALF : null;
        } else if (plate) {
            double inset = key.endsWith("_trapdoor") ? TRAPDOOR_INSET : 0;
            core = outline.size() == 1 ? plate(outline.getFirst(), inset) : null;
        } else if (gate) {
            core = outline.size() == 1 ? outline.getFirst().copy().expand(-GATE_INSET) : null;
        } else {
            core = post;
        }
        return core != null && covered(core, outline) ? core : null;
    }

    private static boolean fullCube(CultPlayer player, int state, int x, int y, int z) {
        if (player.compensatedWorld.geometry().staticCollision(state) != Shapes.block()) return false;
        if (NativeBlockCollisionHelper.getSelectionShape(player, state, x, y, z) != Shapes.block()) return false;
        if (BARRIER.equals(DataTables.defaults().registry().block(state).key())) return false;
        Optional<CollisionBox> bedrock = ClientBlockShapes.movementAlternative(player, state, x, y, z, Double.NaN);
        return bedrock.isEmpty() || bedrock.get().isFullBlock();
    }

    // A 3/16 plate against one face of the block, thinned to PLATE from that face and inset by inset elsewhere.
    private static SimpleCollisionBox plate(SimpleCollisionBox box, double inset) {
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        int thin = -1;
        for (int axis = 0; axis < 3; axis++) {
            if (near(max[axis] - min[axis], 3 / 16.0)) thin = axis;
            else if (!near(min[axis], 0) || !near(max[axis], 1)) return null;
        }
        if (thin < 0) return null;
        boolean low = near(min[thin], 0);
        if (low == near(max[thin], 1)) return null;
        for (int axis = 0; axis < 3; axis++) {
            min[axis] = axis == thin ? (low ? inset : 1 - PLATE) : min[axis] + inset;
            max[axis] = axis == thin ? (low ? PLATE : 1 - inset) : max[axis] - inset;
        }
        return new SimpleCollisionBox(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    // Whether the outline's boxes, which do not overlap, fill the core.
    private static boolean covered(SimpleCollisionBox core, List<SimpleCollisionBox> outline) {
        double volume = volume(core);
        double filled = 0;
        for (SimpleCollisionBox box : outline) {
            SimpleCollisionBox common = intersection(core, box);
            if (common != null) filled += volume(common);
        }
        return filled >= volume - EPSILON;
    }

    private static double volume(SimpleCollisionBox box) {
        return (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
    }

    private static SimpleCollisionBox intersection(SimpleCollisionBox a, SimpleCollisionBox b) {
        double minX = Math.max(a.minX, b.minX), maxX = Math.min(a.maxX, b.maxX);
        double minY = Math.max(a.minY, b.minY), maxY = Math.min(a.maxY, b.maxY);
        double minZ = Math.max(a.minZ, b.minZ), maxZ = Math.min(a.maxZ, b.maxZ);
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return null;
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }
}
