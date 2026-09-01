package ac.grim.grimac.utils.collisions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.NativeBlockCollisionHelper;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ClientBlockShapes {
    private static final double SHAPE_EPSILON = 1.0E-7D;

    private ClientBlockShapes() {
    }

    public static CollisionBox movement(
            GrimPlayer player,
            BlockData state,
            int x,
            int y,
            int z
    ) {
        return movement(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static CollisionBox movement(
            GrimPlayer player,
            BlockData state,
            int x,
            int y,
            int z,
            double entityBottom
    ) {
        CollisionBox latestJava = NativeBlockCollisionHelper.getCollisionBox(player, state, x, y, z, entityBottom);
        return selectIfDifferent(latestJava, movementAlternative(player, state, x, y, z, entityBottom));
    }

    public static CollisionBox movement(
            GrimPlayer player,
            BlockState state,
            int x,
            int y,
            int z
    ) {
        return movement(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static CollisionBox movement(
            GrimPlayer player,
            BlockState state,
            int x,
            int y,
            int z,
            double entityBottom
    ) {
        CollisionBox latestJava = NativeBlockCollisionHelper.getCollisionBox(player, state, x, y, z, entityBottom);
        return selectIfDifferent(latestJava, movementAlternative(player, state == null ? null : ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(state), x, y, z, entityBottom));
    }

    public static CollisionBox visual(
            GrimPlayer player,
            BlockData state,
            int x,
            int y,
            int z
    ) {
        BlockState blockState = state instanceof org.bukkit.craftbukkit.block.data.CraftBlockData craftBlockData
                ? craftBlockData.getState()
                : player == null || player.compensatedWorld == null
                ? null
                : player.compensatedWorld.getBlockStateAt(x, y, z);
        CollisionBox latestJava = NativeBlockCollisionHelper.getSelectionBox(player, blockState, x, y, z);
        return selectIfDifferent(latestJava, visualAlternative(player, state, x, y, z));
    }

    public static CollisionBox visual(
            GrimPlayer player,
            BlockState state,
            BlockData blockData,
            int x,
            int y,
            int z
    ) {
        CollisionBox latestJava = NativeBlockCollisionHelper.getSelectionBox(player, state, x, y, z);
        return selectIfDifferent(latestJava, visualAlternative(player, blockData, x, y, z));
    }

    private static Optional<CollisionBox> movementAlternative(GrimPlayer player, BlockData state, int x, int y, int z, double entityBottom) {
        Optional<CollisionBox> bedrock = BedrockClientBlockShapeMappings.movement(player, state, x, y, z);
        if (bedrock.isPresent()) {
            return bedrock;
        }
        Optional<CollisionBox> viaReplacement = ViaClientBlockShapeMappings.movement(player, state, x, y, z, entityBottom);
        if (viaReplacement.isPresent()) {
            return viaReplacement;
        }
        return VersionedJavaBlockShapes.movement(player, state, x, y, z);
    }

    private static Optional<CollisionBox> visualAlternative(GrimPlayer player, BlockData state, int x, int y, int z) {
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

    private static CollisionBox selectIfDifferent(CollisionBox latestJava, Optional<CollisionBox> alternative) {
        if (alternative.isEmpty() || sameShape(latestJava, alternative.get())) {
            return latestJava;
        }
        return alternative.get();
    }

    static boolean sameShape(CollisionBox first, CollisionBox second) {
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
        boxes.sort(Comparator
                .comparingDouble((SimpleCollisionBox simple) -> simple.minX)
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
