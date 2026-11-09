package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ObserveOnlySwitchTest {
    @Test
    void aReloadIntoObserveOnlyDropsTheHeldMoveAndOneBackClearsViolations() throws Exception {
        try (var f = new JavaTickFixture()) {
            BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
            Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
            field.setAccessible(true);
            boolean original = field.getBoolean(manager);
            try {
                field.setBoolean(manager, false);
                f.player.reload();
                Check check = (Check) f.player.getChecks().stream()
                        .filter(Check.class::isInstance)
                        .findFirst()
                        .orElseThrow();

                check.violations = 5;
                f.player.reload();
                assertEquals(5, check.violations, "no switch, no reset");

                var setbacks = f.player.getSetbackTeleportUtil();
                setbacks.sender()
                        .setPendingServerMove(new ServerboundMovePlayer(0, 64, 0, 0, 0, true, false, true, false));
                field.setBoolean(manager, true);
                f.player.reload();
                assertNull(setbacks.sender().takePendingServerMove());
                assertEquals(5, check.violations, "observe-only keeps counting");

                field.setBoolean(manager, false);
                f.player.reload();
                assertEquals(0, check.violations);
            } finally {
                field.setBoolean(manager, original);
            }
        }
    }
}
