package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaIdleEntityPushRegressionTest {
    /**
     * Native parking replay: the remote player's interpolation reaches the idle player after its travel.
     * Entity#push adds 0.03162277707289898 X; the next tick moves by it even though the contact tick sent no position.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void anIdleContactTickCarriesItsLateEntityPush(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.9, FLOOR_Y, 0.5);
            f.place(start, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            f.addRemotePlayer(100, new Vec3(6.5, FLOOR_Y, 0.5), GameMode.SURVIVAL);
            assertTrue(JavaTickFixture.exact(f.tick(start, true)));
            var remote = f.player.compensatedEntities.getEntity(100);
            remote.onBundledPositionSyncTransaction(0.5, FLOOR_Y, 0.5, f.player);
            for (int tick = 0; tick < (version.isNewerThanOrEquals(ClientVersion.V_26_3) ? 2 : 3); tick++) {
                var before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
                f.tickEnd();
                var idle = f.player.checkManager.getSimulationProcessor().getLastPrediction();
                assertNotSame(before, idle, "an idle contact tick must commit its own prediction");
                assertFalse(idle.isExempt());
                assertFalse(idle.hasEffectiveFlags(), JavaTickFixture.describe(idle));
            }
            assertEquals(0.5, remote.getClientMovementCollisionBox().minX + 0.3F, 1e-13);
            var last = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertEquals(1, last.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer());
            var result = f.tick(start.add(0.03162277707289898, 0, 0), true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            assertEquals(1, result.getSimulationContext().getWorldData().getNumCarriedPushing());
        }
    }
}
