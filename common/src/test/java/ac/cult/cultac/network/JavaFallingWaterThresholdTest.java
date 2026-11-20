package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 26.2 client ending a swim up a one-block falling-water column, its x and z pinned against the column's walls. The
 * column's current pushes it down by about 0.0139 in Entity#baseTick, so the -0.0025 deltaMovement at the top of the
 * rise clears LivingEntity#aiStep's 0.003 threshold. The terrain and moves are a real client's, moved by (-18000, 0,
 * -19000).
 */
class JavaFallingWaterThresholdTest {
    private static final ServerboundPlayerInput SPRINT_KEY =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final double X = 4.30000001192;
    private static final double Z = 4.69999998808;

    @Test
    void theCurrentKeepsTheTopOfTheRiseOverTheThreshold() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_2, ClientVersion.V_26_2)) {
            column(f);
            // The rise's last held-jump move, 0.05798046077468655, through travelInWater's drag and
            // getFluidFallingAdjustedMovement.
            f.place(at(65.52999027690474), new Vec3(0.0, 0.05798046077468655 * 0.8F - 0.005, 0.0));
            f.input(SPRINT_KEY);
            rotate(f, 500.24014F, -31.609037F);
            f.tick(at(65.5575016362599), false);
            rotate(f, 501.1232F, -40.108295F);
            assertExact(f.tick(at(65.56063771411623), false));

            rotate(f, 501.56473F, -47.062225F);
            assertExact(f.tick(at(65.5442735664829), false));
            rotate(f, 501.56473F, -48.166016F);
            assertExact(f.tick(at(65.5123092382254), false));
            rotate(f, 501.34396F, -50.48398F);
            assertExact(f.tick(at(65.46786476528258), false));
            rotate(f, 501.23358F, -50.594357F);
            assertExact(f.tick(at(65.41343617644274), false));
        }
    }

    /** Still water has no current, so the same deltaMovement is zeroed and keeping it is not a vanilla move. */
    @Test
    void stillWaterStillZeroesTheTopOfTheRise() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_2, ClientVersion.V_26_2)) {
            for (int y = 64; y <= 69; y++) {
                for (int x = 3; x <= 5; x++) {
                    for (int z = 3; z <= 5; z++) set(f, x, y, z, BlockIds.WATER.defaultState());
                }
            }
            settle(f);
            f.place(at(65.56063771411623), new Vec3(0.0, 0.0031360778563254144 * 0.8F - 0.005, 0.0));
            f.input(SPRINT_KEY);
            rotate(f, 501.56473F, -47.062225F);
            PredictionResult kept = f.tick(at(65.56063771411623 + 0.0031360778563254144 * 0.8F - 0.005), false);
            assertFalse(kept.getFlags().isEmpty(), "kept the thresholded deltaMovement: " + describe(kept));
        }
    }

    /** The blocks around the column at seq 14525, x 3..5 by z 3..5, rows of z with x across. */
    private static void column(JavaTickFixture f) {
        String[][] layers = {
            {"planks cobble air", "planks cobble water8", "water8 water8 air"},
            {"planks cobble air", "planks water8 water1", "water8 water1 air"},
            {"planks cobble air", "planks water8 air", "water8 air air"},
            {"planks cobble air", "planks water8 air", "water8 air air"},
            {"water1 cobble air", "water2 water3 air", "water3 air air"},
            {"planks cobble air", "planks planks air", "air air air"},
        };
        BlockRegistry registry = DataTables.defaults().registry();
        for (int layer = 0; layer < layers.length; layer++) {
            for (int row = 0; row < 3; row++) {
                String[] names = layers[layer][row].split(" ");
                for (int column = 0; column < 3; column++) {
                    set(f, 3 + column, 64 + layer, 3 + row, state(registry, names[column]));
                }
            }
        }
        settle(f);
    }

    private static int state(BlockRegistry registry, String name) {
        return switch (name) {
            case "air" -> BlockIds.AIR.defaultState();
            case "planks" -> registry.block("minecraft:oak_planks").defaultState();
            case "cobble" -> registry.block("minecraft:cobblestone").defaultState();
            default -> registry.with(BlockIds.WATER.defaultState(), "level", name.substring("water".length()));
        };
    }

    private static void set(JavaTickFixture f, int x, int y, int z, int state) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static Vec3 at(double y) {
        return new Vec3(X, y, Z);
    }

    private static void rotate(JavaTickFixture f, float yaw, float pitch) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
