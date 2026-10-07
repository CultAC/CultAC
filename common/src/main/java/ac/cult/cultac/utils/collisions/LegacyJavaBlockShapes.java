package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.Optional;

/** Pre-flattening shapes from Entity/Block sources, selected after Via replacement. */
final class LegacyJavaBlockShapes {
    private LegacyJavaBlockShapes() {}

    static Optional<CollisionBox> movement(CultPlayer player, int state, int x, int y, int z) {
        if (player == null
                || state < 0
                || player.isBedrockMovement()
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            return Optional.empty();
        }
        var material = DataTables.defaults().registry().block(state);
        CollisionBox shape;
        if (material == BlockIds.FARMLAND) {
            shape = box(0, 0, 0, 1, 1, 1);
        } else if (material == BlockIds.LADDER) {
            shape = ladder(ac.cult.cultac.utils.nmsutil.ClientBlockProperties.facing(state));
        } else if (material == BlockIds.LILY_PAD) {
            shape = box(0, 0, 0, 1, 1.0 / 64, 1);
        } else if (material == BlockIds.SNOW) {
            shape = box(0, 0, 0, 1, (BlockProps.LAYERS.value(state) - 1) / 8.0, 1);
        } else if (material == BlockIds.ANVIL
                || material == BlockIds.CHIPPED_ANVIL
                || material == BlockIds.DAMAGED_ANVIL) {
            Direction face = ac.cult.cultac.utils.nmsutil.ClientBlockProperties.facing(state);
            shape = face == Direction.EAST || face == Direction.WEST
                    ? box(0, 0, 0.125, 1, 1, 0.875)
                    : box(0.125, 0, 0, 0.875, 1, 1);
        } else {
            shape = material == BlockIds.IRON_BARS
                            || material == BlockIds.GLASS_PANE
                            || material.key().endsWith("_stained_glass_pane")
                    ? pane(player, x, y, z)
                    : null;
        }
        return shape == null ? Optional.empty() : Optional.of(shape.offset(x, y, z));
    }

    private static CollisionBox ladder(Direction facing) {
        return switch (facing) {
            case NORTH -> box(0, 0, 0.875, 1, 1, 1);
            case SOUTH -> box(0, 0, 0, 1, 1, 0.125);
            case WEST -> box(0.875, 0, 0, 1, 1, 1);
            default -> box(0, 0, 0, 0.125, 1, 1);
        };
    }

    private static CollisionBox pane(CultPlayer player, int x, int y, int z) {
        // BlockPane#addCollisionBoxesToList makes an isolated 1.8 pane a cross.
        boolean n = connects(player, x, y, z - 1);
        boolean s = connects(player, x, y, z + 1);
        boolean w = connects(player, x - 1, y, z);
        boolean e = connects(player, x + 1, y, z);
        boolean isolated = !n && !s && !w && !e;
        ComplexCollisionBox shape = new ComplexCollisionBox();
        if (w || e || isolated) shape.add(box(w || isolated ? 0 : 0.5, 0, 0.4375, e || isolated ? 1 : 0.5, 1, 0.5625));
        if (n || s || isolated) shape.add(box(0.4375, 0, n || isolated ? 0 : 0.5, 0.5625, 1, s || isolated ? 1 : 0.5));
        return shape;
    }

    private static boolean connects(CultPlayer player, int x, int y, int z) {
        int state = player.compensatedWorld.getBlockStateIdAt(x, y, z);
        var material = DataTables.defaults().registry().block(state);
        // 1.8 Block#isFullBlock caches isOpaqueCube during construction. Leaves
        // cache true before fancy graphics is enabled; modern leaves never occlude.
        return BlockFamilies.LEAVES.test(state)
                || DataTables.defaults().registry().facts(state).has(StateFacts.SOLID_RENDER)
                || material == BlockIds.GLASS
                || material.key().endsWith("_stained_glass")
                || material == BlockIds.IRON_BARS
                || material == BlockIds.GLASS_PANE
                || material.key().endsWith("_stained_glass_pane");
    }

    private static SimpleCollisionBox box(double x, double y, double z, double xx, double yy, double zz) {
        return new SimpleCollisionBox(x, y, z, xx, yy, zz);
    }
}
