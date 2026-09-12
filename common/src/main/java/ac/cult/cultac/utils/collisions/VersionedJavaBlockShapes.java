package ac.cult.cultac.utils.collisions;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

final class VersionedJavaBlockShapes {
    private static final List<ShapeOverride> MOVEMENT_OVERRIDES = List.of(
            movement(state -> state.getMaterial().name().equals("PALE_MOSS_CARPET"),
                    VersionedJavaBlockShapes::paleMossCarpetMovement),
            movement(Material.PITCHER_CROP, VersionedJavaBlockShapes::pitcherCropMovement)
    );

    private static final List<ShapeOverride> VISUAL_OVERRIDES = List.of();

    private VersionedJavaBlockShapes() {
    }

    static Optional<CollisionBox> movement(CultPlayer player, BlockData state, int x, int y, int z) {
        Optional<CollisionBox> legacy = LegacyJavaBlockShapes.movement(player, state, x, y, z);
        if (legacy.isPresent()) return legacy;
        if (!canUseVersionedJavaShape(player, state)) {
            return Optional.empty();
        }
        return movement(player.getClientVersion(), state, x, y, z);
    }

    static Optional<CollisionBox> visual(CultPlayer player, BlockData state, int x, int y, int z) {
        if (!canUseVersionedJavaShape(player, state)) {
            return Optional.empty();
        }
        return firstMatch(VISUAL_OVERRIDES, player.getClientVersion(), state, x, y, z);
    }

    // The same Java geometry rules serve Java clients and the sparse Bedrock catalog's baseline.
    // State remains in the server registry; only the requested shape version changes.
    static Optional<CollisionBox> movement(ClientVersion shapeVersion, BlockData state, int x, int y, int z) {
        if (state == null || shapeVersion == null || shapeVersion.isOlderThan(ClientVersion.V_1_21_2)) {
            return Optional.empty();
        }
        return firstMatch(MOVEMENT_OVERRIDES, shapeVersion, state, x, y, z);
    }

    private static boolean canUseVersionedJavaShape(CultPlayer player, BlockData state) {
        return player != null
                && player.bedrockState == null
                && state != null
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && player.getClientVersion().isOlderThan(ClientVersion.V_26_2);
    }

    private static Optional<CollisionBox> firstMatch(List<ShapeOverride> overrides, ClientVersion shapeVersion, BlockData state, int x, int y, int z) {
        for (ShapeOverride override : overrides) {
            if (override.matches(state)) {
                return override.create(shapeVersion, state, x, y, z);
            }
        }
        return Optional.empty();
    }

    private static Optional<CollisionBox> paleMossCarpetMovement(ClientVersion shapeVersion, BlockData state, int x, int y, int z) {
        if (shapeVersion.isNewerThanOrEquals(ClientVersion.V_26_2)) {
            return Optional.empty();
        }
        if (isRaisedMossyCarpet(state)) {
            return Optional.of(NoCollisionBox.INSTANCE);
        }
        if (shapeVersion.isOlderThan(ClientVersion.V_1_21_2)) {
            return Optional.of(box(x, y, z, 0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D));
        }
        return Optional.empty();
    }

    private static Optional<CollisionBox> pitcherCropMovement(ClientVersion shapeVersion, BlockData state, int x, int y, int z) {
        if (!(state instanceof Ageable crop) || crop.getAge() != 0
                || !(state instanceof Bisected bisected) || bisected.getHalf() != Bisected.Half.TOP) {
            return Optional.empty();
        }
        // Vanilla PitcherCropBlock#getCollisionShape checks AGE first through 1.21.4;
        // from 1.21.5 it checks HALF first, so an upper age-zero crop has no collision.
        return Optional.of(shapeVersion.isOlderThan(ClientVersion.V_1_21_5)
                ? box(x, y, z, 5, -1, 5, 11, 3, 11)
                : NoCollisionBox.INSTANCE);
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

        Optional<CollisionBox> create(ClientVersion shapeVersion, BlockData state, int x, int y, int z) {
            return factory.create(shapeVersion, state, x, y, z);
        }
    }

    @FunctionalInterface
    private interface ShapeFactory {
        Optional<CollisionBox> create(ClientVersion shapeVersion, BlockData state, int x, int y, int z);
    }
}
