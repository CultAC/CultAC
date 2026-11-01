package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * LivingEntity#travelInAir sets deltaMovement.y to 0.2 after a move that collides horizontally on a climbable, then
 * applies gravity and drag. Velocity and explosion packets handled before the next tick act on that deltaMovement.
 */
class JavaLadderPacketVelocityTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final double CLIMB = (0.2 - 0.08) * 0.98F;
    // A north-facing ladder's shape starts 13/16 into its block; the 0.6 wide box touches it from here.
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 1, 0.8125 - 0.3);

    /** ClientPacketListener#handleExplosion adds the knockback to the climb boost the last tick left. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void anExplosionAddsToTheClimbBoost(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 at = climb(f, version);
            double knockback = 0.5;
            f.explosion(new Vec3(0, knockback, 0));
            at = at.add(new Vec3(0, CLIMB + knockback, 0));
            assertExact(climbTick(f, at));
            assertExact(climbTick(f, at.add(new Vec3(0, CLIMB, 0))));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /** ClientPacketListener#handleSetEntityMotion replaces the deltaMovement, climb boost included. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aVelocityPacketReplacesTheClimbBoost(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 at = climb(f, version);
            double motion = 0.05;
            f.pongThrough(f.velocity(new Vec3(0, motion, 0)));
            at = at.add(new Vec3(0, motion, 0));
            assertExact(climbTick(f, at));
            assertEquals(0, f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    private static Vec3 climb(JavaTickFixture f, ClientVersion version) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        for (int y = FLOOR_Y; y <= FLOOR_Y + 6; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.LADDER.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.yRot = 0;
        f.place(START, new Vec3(0, CLIMB, 0));
        f.input(FORWARD);
        Vec3 at = START;
        for (int tick = 0; tick < 2; tick++) {
            at = at.add(new Vec3(0, CLIMB, 0));
            assertExact(climbTick(f, at));
        }
        return at;
    }

    private static PredictionResult climbTick(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var packet = new ServerboundMovePlayer(to.x, to.y, to.z, f.player.xRot, f.player.yRot, false, true, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != before, "the tick committed no prediction");
        return result;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
