package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ClientBlockShapes {
    private static final double SHAPE_EPSILON = 1.0E-7D;

    private ClientBlockShapes() {}

    public static CollisionBox movement(CultPlayer player, int state, int x, int y, int z) {
        return movement(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static CollisionBox movement(CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        return movement(
                player,
                state,
                x,
                y,
                z,
                entityBottom,
                BlockIds.is(state, BlockIds.POWDER_SNOW) ? JavaCollisionState.current(player) : null);
    }

    public static CollisionBox movement(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        CollisionBox latestJava =
                NativeBlockCollisionHelper.getCollisionBox(player, state, x, y, z, entityBottom, actor);
        return selectIfDifferent(latestJava, movementAlternative(player, state, x, y, z, entityBottom));
    }

    /** Source-proven unit cube with no client mapping override; safe for a boolean-only scan. */
    public static boolean hasStaticFullMovement(
            CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        return player.compensatedWorld.geometry().staticCollision(state)
                        == ac.cult.blocksim.engine.shapes.Shapes.block()
                && movementAlternative(player, state, x, y, z, entityBottom).isEmpty();
    }

    /** {@code nativeOnly} is {@link #hasMovementAlternatives}'s negation, resolved once per scan. */
    public static CollisionBox movement(
            CultPlayer player,
            int state,
            int x,
            int y,
            int z,
            double entityBottom,
            JavaCollisionState actor,
            boolean nativeOnly) {
        return nativeOnly
                ? NativeBlockCollisionHelper.getCollisionBox(player, state, x, y, z, entityBottom, actor)
                : movement(player, state, x, y, z, entityBottom, actor);
    }

    /** {@link #movement(CultPlayer, int, int, int, int, double, JavaCollisionState)} given its alternative. */
    public static CollisionBox movement(
            CultPlayer player,
            int state,
            int x,
            int y,
            int z,
            double entityBottom,
            JavaCollisionState actor,
            Optional<CollisionBox> alternative) {
        return selectIfDifferent(
                NativeBlockCollisionHelper.getCollisionBox(player, state, x, y, z, entityBottom, actor), alternative);
    }

    public static boolean hasStaticFullMovement(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, boolean nativeOnly) {
        return nativeOnly
                ? player.compensatedWorld.geometry().staticCollision(state) == Shapes.block()
                : hasStaticFullMovement(player, state, x, y, z, entityBottom);
    }

    /** False only when no Bedrock, Via or versioned Java movement override can apply to any state. */
    public static boolean hasMovementAlternatives(CultPlayer player) {
        return player.bedrockState != null
                || player.getClientVersion().isOlderThan(ClientVersion.V_26_3)
                || ViaClientBlockShapeMappings.replacesBlockStates(player);
    }

    /** Apply the shared Java version catalog to an already-observed local shape. */
    public static ac.cult.blocksim.engine.shapes.VoxelShape versionedMovementShape(
            CultPlayer player, int state, ac.cult.blocksim.engine.shapes.VoxelShape observed) {
        if (player == null || player.isBedrockMovement()) return observed;
        var alternative = VersionedJavaBlockShapes.movement(player, state, 0, 0, 0);
        if (alternative.isEmpty()) return observed;
        if (alternative.get() instanceof ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox voxel)
            return voxel.voxelShape();
        var parts = new ArrayList<SimpleCollisionBox>();
        alternative.get().downCast(parts);
        return ac.cult.blocksim.engine.shapes.Shapes.fromBoxes(parts.stream()
                .map(box -> new ac.cult.blocksim.data.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ))
                .toList());
    }

    public static CollisionBox visual(CultPlayer player, int state, int x, int y, int z) {
        int blockState = state;
        CollisionBox latestJava = NativeBlockCollisionHelper.getSelectionBox(player, blockState, x, y, z);
        return selectIfDifferent(latestJava, visualAlternative(player, state, x, y, z));
    }

    public static CollisionBox visual(CultPlayer player, int state, int blockData, int x, int y, int z) {
        CollisionBox latestJava = NativeBlockCollisionHelper.getSelectionBox(player, state, x, y, z);
        return selectIfDifferent(latestJava, visualAlternative(player, blockData, x, y, z));
    }

    /** Local outline geometry for vanilla VoxelShape.clip, using the same client selection as visual boxes. */
    public static ac.cult.blocksim.engine.shapes.VoxelShape visualShape(
            CultPlayer player, int state, int x, int y, int z) {
        var latest = NativeBlockCollisionHelper.getSelectionShape(player, state, x, y, z);
        var alternative = visualAlternative(player, state, x, y, z);
        if (alternative.isEmpty()
                || sameShape(NativeBlockCollisionHelper.fromShape(latest, x, y, z), alternative.get())) return latest;
        var parts = new ArrayList<SimpleCollisionBox>();
        alternative.get().downCast(parts);
        return ac.cult.blocksim.engine.shapes.Shapes.fromBoxes(parts.stream()
                .map(box -> new ac.cult.blocksim.data.Box(
                        box.minX - x, box.minY - y, box.minZ - z, box.maxX - x, box.maxY - y, box.maxZ - z))
                .toList());
    }

    public static Optional<CollisionBox> movementAlternative(
            CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        Optional<CollisionBox> bedrock = BedrockClientBlockShapeMappings.movement(player, state, x, y, z);
        if (bedrock.isPresent()) {
            return bedrock;
        }
        Optional<CollisionBox> viaReplacement =
                ViaClientBlockShapeMappings.movement(player, state, x, y, z, entityBottom);
        if (viaReplacement.isPresent()) {
            return viaReplacement;
        }
        return VersionedJavaBlockShapes.movement(player, state, x, y, z);
    }

    private static Optional<CollisionBox> visualAlternative(CultPlayer player, int state, int x, int y, int z) {
        Optional<CollisionBox> bedrock = BedrockClientBlockShapeMappings.visual(player, state, x, y, z);
        if (bedrock.isPresent()) {
            return bedrock;
        }
        Optional<CollisionBox> viaReplacement = ViaClientBlockShapeMappings.visual(player, state, x, y, z);
        if (viaReplacement.isPresent()) {
            return viaReplacement;
        }
        return VersionedJavaBlockShapes.visual(player, state, x, y, z);
    }

    public static CollisionBox selectIfDifferent(CollisionBox latestJava, Optional<CollisionBox> alternative) {
        if (alternative.isEmpty() || sameShape(latestJava, alternative.get())) {
            return latestJava;
        }
        return alternative.get();
    }

    static boolean sameShape(CollisionBox first, CollisionBox second) {
        if (first instanceof ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox a
                && second instanceof ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox b
                && !a.sameGrid(b)) return false;
        List<SimpleCollisionBox> firstBoxes = boxes(first);
        List<SimpleCollisionBox> secondBoxes = boxes(second);
        if (firstBoxes.size() != secondBoxes.size()) {
            return false;
        }
        for (int i = 0; i < firstBoxes.size(); i++) {
            if (!sameBox(firstBoxes.get(i), secondBoxes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<SimpleCollisionBox> boxes(CollisionBox box) {
        if (box == null || box.isNull()) {
            return List.of();
        }
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        box.downCast(boxes);
        boxes.removeIf(SimpleCollisionBox::isEmpty);
        boxes.sort(Comparator.comparingDouble((SimpleCollisionBox simple) -> simple.minX)
                .thenComparingDouble(simple -> simple.minY)
                .thenComparingDouble(simple -> simple.minZ)
                .thenComparingDouble(simple -> simple.maxX)
                .thenComparingDouble(simple -> simple.maxY)
                .thenComparingDouble(simple -> simple.maxZ));
        return boxes;
    }

    private static boolean sameBox(SimpleCollisionBox first, SimpleCollisionBox second) {
        return sameCoordinate(first.minX, second.minX)
                && sameCoordinate(first.minY, second.minY)
                && sameCoordinate(first.minZ, second.minZ)
                && sameCoordinate(first.maxX, second.maxX)
                && sameCoordinate(first.maxY, second.maxY)
                && sameCoordinate(first.maxZ, second.maxZ);
    }

    private static boolean sameCoordinate(double first, double second) {
        return Math.abs(first - second) <= SHAPE_EPSILON;
    }
}
