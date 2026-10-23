package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * WebBlock#entityInside's Entity#makeStuckInBlock, for every Entity#checkInsideBlocks traversal: the next Entity#move
 * scales its delta by the web's multiplier and zeroes the carried deltaMovement.
 */
class JavaCobwebTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    // LivingEntity#travel on stone: moveRelative(0.1F, 0.98F forward input).
    private static final double WALK = (double) 0.98F * (double) 0.1F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final double WEB_HORIZONTAL = 0.25;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_7", "V_1_21_9", "V_1_21_11", "V_26_3"})
    void aWebSlowsTheNextMoveAndDropsItsCarry(ClientVersion version) throws Exception {
        try (var f = web(version)) {
            // The first tick carries no web yet; the web's callback follows its move.
            double z = 0.75 + WALK;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
            // The carried delta plus the input, scaled by the web.
            z += (WALK * GROUND_FRICTION + WALK) * WEB_HORIZONTAL;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
            // Entity#move zeroed deltaMovement, so only the input remains.
            z += WALK * WEB_HORIZONTAL;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_7", "V_1_21_9", "V_1_21_11", "V_26_3"})
    void aPlayerCannotWalkThroughAWebAtFullSpeed(ClientVersion version) throws Exception {
        try (var f = web(version)) {
            double z = 0.75 + WALK;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, z), true));
            PredictionResult unslowed = f.tick(new Vec3(0.5, FLOOR_Y, z + WALK * GROUND_FRICTION + WALK), true);
            assertFalse(exact(unslowed), describe(unslowed));
        }
    }

    /** A player whose box already reaches into the web just south of it, facing it. */
    private static JavaTickFixture web(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 1), BlockIds.COBWEB.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        // The box ends at z = 1.05, inside the web block that starts at z = 1.
        f.place(new Vec3(0.5, FLOOR_Y, 0.75), new Vec3(0, -0.0784, 0));
        f.input(FORWARD);
        return f;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
