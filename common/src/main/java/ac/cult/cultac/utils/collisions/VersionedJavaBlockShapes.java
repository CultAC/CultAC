package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

final class VersionedJavaBlockShapes {
    private static final List<ShapeOverride> MOVEMENT_OVERRIDES = List.of(
            movement(
                    state -> BlockIds.is(state, BlockIds.PALE_MOSS_CARPET),
                    VersionedJavaBlockShapes::paleMossCarpetMovement),
            movement(BlockIds.PITCHER_CROP, VersionedJavaBlockShapes::pitcherCropMovement));

    private static final List<ShapeOverride> VISUAL_OVERRIDES = List.of();

    private VersionedJavaBlockShapes() {}

    static Optional<CollisionBox> movement(CultPlayer player, int state, int x, int y, int z) {
        Optional<CollisionBox> legacy = LegacyJavaBlockShapes.movement(player, state, x, y, z);
        if (legacy.isPresent()) return legacy;
        if (!canUseVersionedJavaShape(player, state)) {
            return Optional.empty();
        }
        return movement(player.getClientVersion(), state, x, y, z);
    }

    static Optional<CollisionBox> visual(CultPlayer player, int state, int x, int y, int z) {
        if (!canUseVersionedJavaShape(player, state)) {
            return Optional.empty();
        }
        return firstMatch(VISUAL_OVERRIDES, player.getClientVersion(), state, x, y, z);
    }

    // The same Java geometry rules serve Java clients and the sparse Bedrock catalog's baseline.
    // State remains in the server registry; only the requested shape version changes.
    static Optional<CollisionBox> movement(ClientVersion shapeVersion, int state, int x, int y, int z) {
        if (state < 0 || shapeVersion == null || shapeVersion.isOlderThan(ClientVersion.V_1_21_2)) {
            return Optional.empty();
        }
        return firstMatch(MOVEMENT_OVERRIDES, shapeVersion, state, x, y, z);
    }

    private static boolean canUseVersionedJavaShape(CultPlayer player, int state) {
        return player != null
                && player.bedrockState == null
                && state >= 0
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && player.getClientVersion().isOlderThan(ClientVersion.V_26_2);
    }

    private static Optional<CollisionBox> firstMatch(
            List<ShapeOverride> overrides, ClientVersion shapeVersion, int state, int x, int y, int z) {
        for (ShapeOverride override : overrides) {
            if (override.matches(state)) {
                return override.create(shapeVersion, state, x, y, z);
            }
        }
        return Optional.empty();
    }

    private static Optional<CollisionBox> paleMossCarpetMovement(
            ClientVersion shapeVersion, int state, int x, int y, int z) {
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

    private static Optional<CollisionBox> pitcherCropMovement(
            ClientVersion shapeVersion, int state, int x, int y, int z) {
        if (!BlockProps.AGE_4.has(state)
                || BlockProps.AGE_4.value(state) != 0
                || !BlockProps.DOUBLE_BLOCK_HALF.has(state)
                || BlockProps.DOUBLE_BLOCK_HALF.value(state) != 0) {
            return Optional.empty();
        }
        // Vanilla PitcherCropBlock#getCollisionShape checks AGE first through 1.21.4;
        // from 1.21.5 it checks HALF first, so an upper age-zero crop has no collision.
        return Optional.of(
                shapeVersion.isOlderThan(ClientVersion.V_1_21_5)
                        ? box(x, y, z, 5, -1, 5, 11, 3, 11)
                        : NoCollisionBox.INSTANCE);
    }

    private static boolean isRaisedMossyCarpet(int state) {
        // Vanilla MossyCarpetBlock.BASE is the shared "bottom" property.
        return BlockProps.BOTTOM.has(state) && !BlockProps.BOTTOM.booleanValue(state);
    }

    private static ShapeOverride movement(BlockDefinition material, ShapeFactory factory) {
        return movement(state -> BlockIds.is(state, material), factory);
    }

    private static ShapeOverride movement(IntPredicate matcher, ShapeFactory factory) {
        return new ShapeOverride(matcher, factory);
    }

    private static SimpleCollisionBox box(
            int x, int y, int z, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new SimpleCollisionBox(
                x + minX / 16.0D,
                y + minY / 16.0D,
                z + minZ / 16.0D,
                x + maxX / 16.0D,
                y + maxY / 16.0D,
                z + maxZ / 16.0D);
    }

    private record ShapeOverride(IntPredicate matcher, ShapeFactory factory) {
        boolean matches(int state) {
            return matcher.test(state);
        }

        Optional<CollisionBox> create(ClientVersion shapeVersion, int state, int x, int y, int z) {
            return factory.create(shapeVersion, state, x, y, z);
        }
    }

    @FunctionalInterface
    private interface ShapeFactory {
        Optional<CollisionBox> create(ClientVersion shapeVersion, int state, int x, int y, int z);
    }
}
