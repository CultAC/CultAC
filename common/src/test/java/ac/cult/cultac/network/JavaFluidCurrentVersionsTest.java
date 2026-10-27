package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.FluidCurrent;
import ac.cult.cultac.utils.nmsutil.FluidTypeFlowing;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The same current primitive is used before travel and by LivingEntity's post-move fluid callback. */
class JavaFluidCurrentVersionsTest {
    private static final BlockPos WATER = new BlockPos(8, 64, 8);

    @ParameterizedTest(name = "{0}: current height uses the release's arithmetic")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void shallowCurrentUsesFloatOrDoubleWorldHeight(String release, ClientVersion version) throws Exception {
        try (var f = flowing(version)) {
            double feet = 64.87;
            boolean modern = version.isNewerThanOrEquals(ClientVersion.V_26_1);
            // Entity sums int Y + float height through 1.21.11; EntityFluidInteraction sums double Y + float height.
            double height = modern ? 64.0D + 8 / 9.0F - feet : (double) (64 + 8 / 9.0F) - (feet + .001D);
            var current = new FluidCurrent();
            assertEquals(true, current.compute(f.player, box(feet), true, 1, 1, FluidTags.WATER, .014D));
            assertEquals(height * .014D, current.x, 1e-13);
            assertEquals(0, current.y, 0);
            assertEquals(0, current.z, 0);
        }
    }

    @ParameterizedTest(name = "{0}: the weak accumulated-current cutoff was added in 26.1")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void oldWaterUpdateRetainsAWeakCurrentBeforeTheMinimumPush(String release, ClientVersion version) throws Exception {
        try (var f = flowing(version)) {
            // One eastward unit flow is scaled to about .001 by the shallow contact. Older Entity code
            // retains it and raises its .000014 impulse to .0045; the newer accumulator discards it first.
            double feet = (double) (64 + 8 / 9.0F) - .002D;
            boolean modern = version.isNewerThanOrEquals(ClientVersion.V_26_1);
            var current = new FluidCurrent();
            boolean applied = current.compute(f.player, box(feet), true, 0, 0, FluidTags.WATER, .014D);
            assertEquals(!modern, applied);
            assertEquals(modern ? 0 : .0045000000000000005D, applied ? current.x : 0, 1e-13);
            assertEquals(0, current.y, 0);
            assertEquals(0, current.z, 0);
        }
    }

    private static SimpleCollisionBox box(double feet) {
        return new SimpleCollisionBox(8.2, feet, 8.2, 8.8, feet + 1.8, 8.8);
    }

    private static JavaTickFixture flowing(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.updateBlock(WATER, BlockIds.WATER.defaultState());
        f.player.compensatedWorld.updateBlock(WATER.east().below(), BlockIds.WATER.defaultState());
        assertEquals(
                new Vec3(1, 0, 0),
                FluidTypeFlowing.flow(f.player, WATER, ClientFluidQueries.fluidAt(f.player.compensatedWorld, WATER)));
        return f;
    }
}
