package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaStuckStepTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /**
     * Entity#move scales the delta by the sweet berry bush's (0.8, 0.75, 0.8) stuck multiplier before
     * Entity#collide, so the step onto a bottom stair is the stair's full 0.5 height while the walk is scaled.
     */
    @Test
    void aBerryBushWalkStepsTheFullStairHeight() throws Exception {
        var registry = DataTables.defaults().registry();
        walkFromBerryBushOnto(
                registry.with(registry.block("minecraft:oak_stairs").defaultState(), "facing", "south"));
    }

    @Test
    void aBerryBushWalkStepsTheFullSlabHeight() throws Exception {
        walkFromBerryBushOnto(
                DataTables.defaults().registry().block("minecraft:oak_slab").defaultState());
    }

    private static void walkFromBerryBushOnto(int halfBlock) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0), BlockIds.SWEET_BERRY_BUSH.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 1), halfBlock);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5135);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            assertExact(f.tick(start, true));
            f.input(FORWARD);
            // LivingEntity#travel walks 0.98 * 0.1 from the zeroed delta; the bush keeps 0.8 of it.
            double walked = (double) 0.98F * 0.1F * 0.8F;
            Vec3 first = start.add(new Vec3(0, 0, walked));
            assertExact(f.tick(first, true));
            Vec3 second = first.add(new Vec3(0, 0, walked));
            assertExact(f.tick(second, true));
            // The box's front face is 0.0297 short of the block; Entity#collide steps onto its 0.5 bottom half.
            Vec3 stepped = second.add(new Vec3(0, 0.5, walked));
            assertExact(f.tick(stepped, true));
            // The box still overlaps the bush, so the walk along the bottom half stays scaled.
            assertExact(f.tick(stepped.add(new Vec3(0, 0, walked)), true));
        }
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
