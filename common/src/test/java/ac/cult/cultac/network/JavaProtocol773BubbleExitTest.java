package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A protocol 773 player rising out of the top of a one-block upward bubble column, with air above it. 1.21.9's
 * BubbleColumnBlock#entityInside runs for every block Entity#checkInsideBlocks swept, so the move that leaves the
 * column still gets the surface push; 1.21.10 passes whether the end box still intersects the block and skips it.
 * The deltas are the captured vanilla 1.21.9 and 1.21.10 clients' (live run at Y 88, here at Y 64).
 */
class JavaProtocol773BubbleExitTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    /** Both releases while the end box stays in the column; the last move leaves it. */
    private static final double[] RISE = {
        0.095, 0.17100000113248826, 0.23180000294446948, 0.2804400051188469, 0.3193520074381829
    };

    private static final double[] AFTER_1_21_9 = {
        0.35048160975751896, 0.26507198272139587, 0.18137054659693588, 0.0993431375984869, 0.01895627521545823
    };
    private static final double[] AFTER_1_21_10 = {
        0.25048160975751893, 0.1670719808140472, 0.08533054285853249, 0.005223932103033875, -0.07328054796526712
    };

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1.21.9", "1.21.10"})
    void eitherReleasesExitIsExactAndProvesItsPatch(String release) throws Exception {
        boolean legacy = release.equals("1.21.9");
        try (var f = column()) {
            double y = rise(f);
            assertNull(f.player.provenMovementPatch, "the exit itself cannot tell the releases apart");
            for (double delta : legacy ? AFTER_1_21_9 : AFTER_1_21_10) {
                y += delta;
                var result = f.tick(new Vec3(0.5, y, 0.5), false);
                assertTrue(exact(result), release + " " + describe(result));
            }
            assertEquals(
                    legacy ? JavaMovementPatch.V_1_21_9 : JavaMovementPatch.V_1_21_10, f.player.provenMovementPatch);
        }
    }

    /** Once one rule is proven, the other release's exit is not accepted: the alternative is not a per-exit choice. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1.21.9", "1.21.10"})
    void aProvenPatchRejectsTheOtherReleasesExit(String release) throws Exception {
        boolean legacy = release.equals("1.21.9");
        try (var f = column()) {
            f.player.provenMovementPatch = legacy ? JavaMovementPatch.V_1_21_10 : JavaMovementPatch.V_1_21_9;
            double y = rise(f);
            y += (legacy ? AFTER_1_21_9 : AFTER_1_21_10)[0];
            var result = f.tick(new Vec3(0.5, y, 0.5), false);
            assertFalse(exact(result), release + " " + describe(result));
        }
    }

    private static JavaTickFixture column() throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_9);
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.SOUL_SAND.defaultState());
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y, 0), BLOCKS.with(BlockIds.BUBBLE_COLUMN.defaultState(), "drag", "false"));
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        // The column's surface callback already raised the stored velocity to -0.005 + 0.1.
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, RISE[0], 0));
        f.player.onGround = f.player.lastOnGround = false;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        f.input(NONE);
        return f;
    }

    /** The common rise, ending with the move whose end box leaves the column. */
    private static double rise(JavaTickFixture f) {
        double y = FLOOR_Y;
        for (double delta : RISE) {
            y += delta;
            var result = f.tick(new Vec3(0.5, y, 0.5), false);
            assertTrue(exact(result), "rise " + describe(result));
        }
        return y;
    }
}
