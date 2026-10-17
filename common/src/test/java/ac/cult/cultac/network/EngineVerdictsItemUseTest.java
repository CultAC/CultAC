package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.cloud.capture.EngineVerdicts;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import org.junit.jupiter.api.Test;

/** The verdict's using-item bit follows the client item use a modern Java client is predicted to have. */
class EngineVerdictsItemUseTest {
    @Test
    void aModernClientsPredictedItemUseReachesTheVerdict() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            var player = fixture.player;
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            player.gamemode = GameMode.SURVIVAL;
            player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            var verdicts = new EngineVerdicts(player);
            assertTrue(player.usesModernJavaItemUse());
            assertFalse(verdicts.read().usingItem());

            player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);

            assertFalse(player.packetStateData.isSlowedByUsingItem());
            assertTrue(verdicts.read().usingItem());
        }
    }
}
