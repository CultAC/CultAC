package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** The pushes of a tick with no push source read as fresh empty pushes, before and after a piston shoves the player. */
class PistonIdlePushesTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double FALL = 0.0784000015258789;

    @Test
    void idlePushesStayEmptyAcrossAPistonShove() throws Exception {
        SimpleCollisionBox empty = new SimpleCollisionBox();
        String fresh = new PistonPushes(empty, empty, empty, Collections.emptySet()).toString();
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.2);
            f.place(position, new Vec3(0, -FALL, 0));
            PistonPushes idle = pushes(f.tick(position, true));
            assertEquals(fresh, idle.toString());
            for (int tick = 0; tick < 3; tick++) assertSame(idle, pushes(f.tick(position, true)));

            BlockPos piston = new BlockPos(0, FLOOR_Y + 1, 2);
            int stickyPiston =
                    REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "north");
            f.player.compensatedWorld.updateBlock(piston, stickyPiston);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y + 1, 1),
                    REGISTRY.block("minecraft:slime_block").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            JavaPistonSlimeLaunchTest.blockEvent(f, piston, 0, 2, stickyPiston);

            boolean pushed = false;
            for (int tick = 0; tick < 4; tick++) {
                PistonPushes pushes = pushes(f.tick(position, true));
                pushed |= pushes != idle;
            }
            assertTrue(pushed, "the piston pass never reached the push path");

            PistonPushes after = null;
            for (int tick = 0; tick < 20; tick++) after = pushes(f.tick(position, true));
            assertSame(idle, after);
            assertEquals(fresh, idle.toString());
        }
    }

    private static PistonPushes pushes(PredictionResult result) {
        return result.getSimulationContext().getWorldData().getPistonPushes();
    }
}
