package ac.grim.grimac.utils.collisions;

import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.NoCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

final class VersionedJavaBlockShapes {
    private static final List<ShapeOverride> MOVEMENT_OVERRIDES = List.of(
            movement(state -> state.getMaterial().name().equals("PALE_MOSS_CARPET"),
                    VersionedJavaBlockShapes::paleMossCarpetMovement)
    );

    private static final List<ShapeOverride> VISUAL_OVERRIDES = List.of();

    private VersionedJavaBlockShapes() {
    }

    static Optional<CollisionBox> movement(GrimPlayer player, BlockData state, int x, int y, int z) {
        if (!canUseVersionedJavaShape(player, state)) {
            return Optional.empty();
        }
        return firstMatch(MOVEMENT_OVERRIDES, player, state, x, y, z);
    }

    static Optional<CollisionBox> visual(GrimPlayer player, BlockData state, int x, int y, int z) {
        if (!canUseVersionedJavaShape(player, state)) {
            return Optional.empty();
        }
        return firstMatch(VISUAL_OVERRIDES, player, state, x, y, z);
    }

    private static boolean canUseVersionedJavaShape(GrimPlayer player, BlockData state) {
        return player != null
                && player.bedrockState == null
                && state != null
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && player.getClientVersion().isOlderThan(ClientVersion.V_26_2);
    }

    private static Optional<CollisionBox> firstMatch(List<ShapeOverride> overrides, GrimPlayer player, BlockData state, int x, int y, int z) {
        for (ShapeOverride override : overrides) {
            if (override.matches(state)) {
                return override.create(player, state, x, y, z);
            }
        }
        return Optional.empty();
    }

    private static Optional<CollisionBox> paleMossCarpetMovement(GrimPlayer player, BlockData state, int x, int y, int z) {
        if (isRaisedMossyCarpet(state)) {
            return Optional.of(NoCollisionBox.INSTANCE);
        }
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) {
            return Optional.of(box(x, y, z, 0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D));
        }
        return Optional.empty();
    }

    private static boolean isRaisedMossyCarpet(BlockData state) {
        try {
            Method isBottom = state.getClass().getMethod("isBottom");
            Object value = isBottom.invoke(state);
            if (value instanceof Boolean bottom) {
                return !bottom;
            }
        } catch (NoSuchMethodException ignored) {
            // Older Bukkit APIs do not expose the typed MossyCarpet interface.
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Unable to read pale moss carpet bottom state", exception);
        }

        String serialized = state.getAsString(false);
        return serialized != null && serialized.contains("bottom=false");
    }

    private static ShapeOverride movement(Material material, ShapeFactory factory) {
        return movement(state -> state.getMaterial() == material, factory);
    }

    private static ShapeOverride movement(Predicate<BlockData> matcher, ShapeFactory factory) {
        return new ShapeOverride(matcher, factory);
    }

    private static SimpleCollisionBox box(int x, int y, int z, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new SimpleCollisionBox(
                x + minX / 16.0D,
                y + minY / 16.0D,
                z + minZ / 16.0D,
                x + maxX / 16.0D,
                y + maxY / 16.0D,
                z + maxZ / 16.0D);
    }

    private record ShapeOverride(Predicate<BlockData> matcher, ShapeFactory factory) {
        boolean matches(BlockData state) {
            return matcher.test(state);
        }

        Optional<CollisionBox> create(GrimPlayer player, BlockData state, int x, int y, int z) {
            return factory.create(player, state, x, y, z);
        }
    }

    @FunctionalInterface
    private interface ShapeFactory {
        Optional<CollisionBox> create(GrimPlayer player, BlockData state, int x, int y, int z);
    }
}
