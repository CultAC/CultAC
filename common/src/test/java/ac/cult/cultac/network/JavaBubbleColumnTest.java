package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * BubbleColumnBlock#entityInside on a player rising through an upward column, for every Entity#checkInsideBlocks
 * traversal. A short move calls back each column block the end box overlaps; 1.21.9 also calls back blocks the move
 * only swept, which protocol 773 shares with 1.21.10's precise gate.
 */
class JavaBubbleColumnTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int COLUMN_BOTTOM = 64;
    private static final int COLUMN_TOP = 74;
    private static final double START_Y = 65.0;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_7", "V_1_21_9", "V_1_21_11", "V_26_3"})
    void theColumnPushIsCarriedExactly(ClientVersion version) throws Exception {
        try (var f = column(version)) {
            double y = START_Y;
            double velocity = 0;
            for (int tick = 0; tick < 6; tick++) {
                velocity = threshold(velocity);
                y += velocity;
                PredictionResult result = f.tick(new Vec3(0.5, y, 0.5), false);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                velocity = afterTick(velocity, y);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_7", "V_1_21_9", "V_1_21_11", "V_26_3"})
    void aPlayerCannotResistTheColumn(ClientVersion version) throws Exception {
        try (var f = column(version)) {
            double y = START_Y;
            double velocity = 0;
            for (int tick = 0; tick < 6; tick++) {
                // Water travel alone, as if no column block called onInsideBubbleColumn.
                velocity = threshold(velocity);
                y += velocity;
                PredictionResult result = f.tick(new Vec3(0.5, y, 0.5), false);
                if (tick > 0) {
                    assertFalse(exact(result), "tick " + tick + ": " + describe(result));
                    return;
                }
                velocity = waterTravel(velocity);
            }
        }
    }

    private static JavaTickFixture column(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        int upward = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), false);
        for (int y = COLUMN_BOTTOM; y <= COLUMN_TOP; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), upward);
        }
        // Water above the column keeps every column block an inside, not a surface, block.
        f.player.compensatedWorld.updateBlock(new BlockPos(0, COLUMN_TOP + 1, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(new Vec3(0.5, START_Y, 0.5), Vec3.ZERO);
        f.input(NONE);
        return f;
    }

    /** LivingEntity#travelInWater, then one Entity#onInsideBubbleColumn per column block the box ends in. */
    private static double afterTick(double velocity, double y) {
        velocity = waterTravel(velocity);
        // Entity#checkInsideBlocks: precise callbacks for the blocks the 1.0E-5-deflated end box intersects.
        int blocks = (int) Math.floor(y + 1.8 - 1.0E-5) - (int) Math.floor(y + 1.0E-5) + 1;
        for (int block = 0; block < blocks; block++) velocity = Math.min(0.7, velocity + 0.06);
        return velocity;
    }

    /** LivingEntity#travelInWater without input: 0.8F vertical drag, then the gravity / 16 fluid fall. */
    private static double waterTravel(double velocity) {
        return velocity * 0.8F - 0.08 / 16.0;
    }

    /** LivingEntity#aiStep's threshold before travel. */
    private static double threshold(double velocity) {
        return Math.abs(velocity) < 0.003 ? 0 : velocity;
    }
}
