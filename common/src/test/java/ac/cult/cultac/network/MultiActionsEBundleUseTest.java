package ac.cult.cultac.network;

import static ac.cult.cultac.network.ItemUseTestSupport.fixture;
import static ac.cult.cultac.network.ItemUseTestSupport.setOffhand;
import static ac.cult.cultac.network.ItemUseTestSupport.use;
import static ac.cult.cultac.network.ItemUseTestSupport.useOffhand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsE;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.SwingKind;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import org.junit.jupiter.api.Test;

/**
 * BundleItem#use starts using the bundle and succeeds, so Minecraft#startUseItem swings the used hand right after the
 * use_item packet.
 */
class MultiActionsEBundleUseTest {
    @Test
    void theSwingAfterUsingABundleIsNotFlagged() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            var player = useBundle(f);
            var check = player.checkManager.getCheck(MultiActionsE.class);
            swing(player, check, Hand.OFF_HAND);
            assertEquals(0, check.violations);
        }
    }

    @Test
    void aSecondSwingWhileUsingTheBundleIsFlagged() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            var player = useBundle(f);
            var check = player.checkManager.getCheck(MultiActionsE.class);
            swing(player, check, Hand.OFF_HAND);
            swing(player, check, Hand.OFF_HAND);
            assertEquals(1, check.violations);
        }
    }

    @Test
    void aSwingAfterAnotherUseWhileUsingTheBundleIsFlagged() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            var player = useBundle(f);
            var check = player.checkManager.getCheck(MultiActionsE.class);
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack("minecraft:snowball", 16));
            use(player, Hand.MAIN_HAND, 2);
            swing(player, check, Hand.MAIN_HAND);
            assertEquals(1, check.violations);
        }
    }

    private static CultPlayer useBundle(RecordReceiveFixture f) {
        var player = f.player;
        player.gamemode = GameMode.SURVIVAL;
        player.setExperimentalChecks(true);
        setOffhand(player, ItemUtil.modelItems().stack("minecraft:bundle", 1));
        useOffhand(player, 1);
        assertTrue(player.isClientUsingItem());
        return player;
    }

    private static void swing(CultPlayer player, MultiActionsE check, Hand hand) {
        var packet = new ServerboundSwing(hand, SwingKind.SWING);
        check.onSwing(
                new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.SWING, packet),
                player,
                packet);
    }
}
