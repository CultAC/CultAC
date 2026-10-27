package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** BlockCollisions uses strict AABB overlap for Shapes.block(), and voxel epsilon for other shapes. */
class JavaSupportingBlockBoundaryTest {
    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_2", "V_1_21_5", "V_26_3"})
    void aFullBlockSupportsASubEpsilonHorizontalOverlap(ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var support = f.player.checkManager.getSimulationProcessor().mainSupportingBlockAfter(
                    null, box(1.0 - 1.0E-8), Vec3.ZERO, true);
            assertEquals(new BlockPos(0, FLOOR_Y - 1, 0), support.getBlockPos());
            // Native honey-edge stepping can keep this neighboring stone support into the next jump.
            assertFalse(BlockProperties.onHoneyBlock(f.player, support, new Vec3(1.3, FLOOR_Y, .5)));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_2", "V_1_21_5", "V_26_3"})
    void anExactlyTouchingFullBlockDoesNotSupportThePlayer(ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var support = f.player.checkManager.getSimulationProcessor().mainSupportingBlockAfter(
                    null, box(1.0), Vec3.ZERO, true);
            assertNull(support.getBlockPos());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_2", "V_1_21_5", "V_26_3"})
    void aNonFullShapeRetainsTheVoxelOverlapEpsilon(ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var blocks = DataTables.defaults().registry();
            int slab = blocks.with(blocks.block("minecraft:stone_slab").defaultState(), "type", "top");
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), slab);
            var support = f.player.checkManager.getSimulationProcessor().mainSupportingBlockAfter(
                    null, box(1.0 - 1.0E-8), Vec3.ZERO, true);
            assertNull(support.getBlockPos());
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 0), BlockIds.HONEY_BLOCK.defaultState());
        return f;
    }

    private static SimpleCollisionBox box(double minX) {
        return new SimpleCollisionBox(minX, FLOOR_Y, .2, 1.6, FLOOR_Y + 1.8, .8);
    }
}
