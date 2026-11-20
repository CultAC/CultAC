package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ShulkerData;
import java.util.ArrayList;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ShulkerCollisionEnvelopeTest {
    private static final BlockPos BOX = new BlockPos(3, JavaTickFixture.FLOOR_Y + 3, 5);

    /** Vanilla Shulker#getProgressDeltaAabb sweeps toward the facing, never behind the closed box. */
    @ParameterizedTest
    @EnumSource(Direction.class)
    void collisionQueriesReachOnlyTheLidSide(Direction facing) throws Exception {
        try (var fixture = new JavaTickFixture()) {
            var world = fixture.player.compensatedWorld;
            var registry = DataTables.defaults().registry();
            int state = registry.with(
                    registry.block("minecraft:shulker_box").defaultState(),
                    "facing",
                    facing.name().toLowerCase(Locale.ROOT));
            world.updateBlock(BOX, state);
            world.openShulkerBoxes.add(new ShulkerData(BOX, 0, false));

            SimpleCollisionBox lidSide = query(facing, 1);
            SimpleCollisionBox oppositeSide = query(facing, -1);
            assertTrue(world.hasOpenShulkerCollision(lidSide), "lid sweep must reach the facing side");
            assertTrue(world.hasOpenShulkerCollision(query(facing, 0)), "retain the closed box footprint");
            assertFalse(world.hasOpenShulkerCollision(oppositeSide), "no sweep behind the box");

            var collisions = new ArrayList<SimpleCollisionBox>();
            world.addDynamicBlockCollisionUncertaintyBoxes(lidSide, collisions);
            assertEquals(1, collisions.size(), "collision collection must include the lid sweep");
            collisions.clear();
            world.addDynamicBlockCollisionUncertaintyBoxes(oppositeSide, collisions);
            assertTrue(collisions.isEmpty(), "collision collection must reject the opposite side");

            for (int tick = 0; tick < ShulkerData.BLOCK_ANIMATION_TICKS; tick++) {
                world.removeInvalidPistonLikeStuff(0);
            }
            assertFalse(world.hasOpenShulkerCollision(lidSide), "expired animation grants no lid sweep");
            world.addDynamicBlockCollisionUncertaintyBoxes(lidSide, collisions);
            assertTrue(collisions.isEmpty(), "expired animation contributes no dynamic collision");
        }
    }

    private static SimpleCollisionBox query(Direction facing, int side) {
        return new SimpleCollisionBox(0.4, 0.4, 0.4, 0.6, 0.6, 0.6)
                .offset(
                        BOX.x() + side * facing.getModX(),
                        BOX.y() + side * facing.getModY(),
                        BOX.z() + side * facing.getModZ());
    }
}
