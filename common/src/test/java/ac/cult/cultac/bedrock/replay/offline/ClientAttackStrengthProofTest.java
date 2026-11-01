package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.*;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public final class ClientAttackStrengthProofTest {
    private static final float DELAY = 5.0F;

    @Test
    public void completionDoesNotIdentifyTheSelectedSlotOrTheActiveHand() throws Exception {
        CultPlayer player = player("minecraft:diamond_sword", 1);
        try {
            // The client can select this slot after the last game-mode sync; event 9 carries neither
            // the selected slot nor the active hand. A server-observed offhand use cannot rule it out.
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET + 1, ItemUtil.modelItems().stack("minecraft:honey_bottle", 1));
            player.packetStateData.itemInUseHand = Hand.OFF_HAND;
            var packet = new ClientboundEntityEvent(player.entityID, (byte) 9);
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
            player.packetEntityReplication.onEntityEvent(event, player, packet);
            var ping = (ClientboundPing) event.writesAfter().getFirst().packet();
            event.tasksAfter().forEach(Runnable::run);
            assertTrue(player.addTransactionResponse(ping.id()));
            assertTrue(player.attackStrength.isFullStrength(DELAY));
            tick(player);
            assertFalse(player.attackStrength.isFullStrength(DELAY));
            assertTrue(player.attackStrength.canBeFullStrength(DELAY));
            assertEquals(
                    "minecraft:diamond_sword",
                    player.getInventory().getHeldItem().itemKey());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void instabuildCanDifferFromGameModeDuringCompletion() throws Exception {
        for (boolean infiniteMaterials : List.of(false, true)) {
            CultPlayer player = player("minecraft:honey_bottle", 1);
            try {
                player.gamemode = infiniteMaterials ? GameMode.SURVIVAL : GameMode.CREATIVE;
                player.canInstabuild = infiniteMaterials;
                var packet = new ClientboundEntityEvent(player.entityID, (byte) 9);
                var event = new PacketSendEvent<>(
                        player.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
                player.packetEntityReplication.onEntityEvent(event, player, packet);
                var ping = (ClientboundPing) event.writesAfter().getFirst().packet();
                event.tasksAfter().forEach(Runnable::run);
                assertTrue(player.addTransactionResponse(ping.id()));
                tick(player);
                assertEquals(infiniteMaterials, player.attackStrength.isFullStrength(DELAY));
            } finally {
                OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            }
        }
    }

    @Test
    public void completionRequiresItsAtomicProofAndOnlyAffectsTheNextPlayerTick() throws Exception {
        for (boolean insideBundle : List.of(false, true)) {
            for (boolean creative : List.of(false, true)) {
                for (int count : List.of(1, 2)) {
                    CultPlayer player = player("minecraft:honey_bottle", count);
                    try {
                        player.gamemode = creative ? GameMode.CREATIVE : GameMode.SURVIVAL;
                        player.canInstabuild = creative;
                        var packet = new ClientboundEntityEvent(player.entityID, (byte) 9);
                        var event = new PacketSendEvent<>(
                                player.user,
                                ConnectionPhase.PLAY,
                                ClientboundPackets.ENTITY_EVENT,
                                packet,
                                insideBundle);
                        player.packetEntityReplication.onEntityEvent(event, player, packet);
                        assertEquals(!insideBundle, event.isBundleRequested());
                        assertEquals(1, event.writesAfter().size());
                        var ping =
                                (ClientboundPing) event.writesAfter().getFirst().packet();
                        event.tasksAfter().forEach(Runnable::run);
                        tick(player);
                        assertTrue(player.attackStrength.isFullStrength(DELAY));
                        assertTrue(player.addTransactionResponse(ping.id()));
                        // Incoming completion replaces the item but does not itself reset the counter.
                        assertTrue(player.attackStrength.isFullStrength(DELAY));
                        tick(player);
                        assertEquals(creative || count > 1, player.attackStrength.isFullStrength(DELAY));
                        assertTrue(player.attackStrength.canBeFullStrength(DELAY));
                    } finally {
                        OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
                    }
                }
            }
        }
    }

    @Test
    public void timedUseCancellationAndServerAuthoritativeHandSwapsDoNotReset() throws Exception {
        CultPlayer player = player("minecraft:honey_bottle", 1);
        try {
            var attack = new PacketPlayerAttack();
            var use = new ServerboundUseItem(Hand.MAIN_HAND, 0, 0, 0);
            attack.onUseItem(
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.USE_ITEM, use),
                    player,
                    use);
            for (int i = 0; i < 12; i++) tick(player);
            for (PlayerAction action : List.of(PlayerAction.RELEASE_USE_ITEM, PlayerAction.SWAP_ITEM_WITH_OFFHAND)) {
                var packet = new ServerboundPlayerAction(action, new BlockPos(0, 0, 0), Direction.DOWN, 0);
                attack.onPlayerAction(RecordReceiveTestEvents.playerAction(player, packet), player, packet);
                assertTrue(player.attackStrength.isFullStrength(DELAY));
            }
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void validatedUnrelatedClicksPreserveStrengthAndFailedPredictionsRetainTheReset() throws Exception {
        CultPlayer player = player("minecraft:golden_apple", 2);
        try {
            var diamond = ItemUtil.modelItems().stack("minecraft:diamond", 1);
            player.getInventory().inventory.getInventoryStorage().setItem(10, diamond);
            var click = new InventoryClick(0, 0, 10, 0, WindowClickType.PICKUP, Map.of(10, ItemUtil.empty()), diamond);
            @SuppressWarnings("unchecked")
            var type = (ac.cult.cultac.protocol.PacketType<InventoryClick>)
                    ac.cult.cultac.network.codec.ClientPacketCodecs.connectionCatalog().stream()
                            .filter(candidate -> candidate.recordClass() == InventoryClick.class)
                            .findFirst()
                            .orElseThrow();
            var event = new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, type, click);
            player.getInventory().onContainerClick(event, player, click);
            assertTrue(player.attackStrength.isFullStrength(DELAY));
            var failed = new InventoryClick(123, 0, 10, 0, WindowClickType.PICKUP, Map.of(), ItemUtil.empty());
            player.getInventory()
                    .onContainerClick(
                            new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, type, failed), player, failed);
            assertFalse(player.attackStrength.isFullStrength(DELAY));
            // The unknown local result can leave a last consumable even though the model still has two.
            for (int i = 0; i < 20; i++) tick(player);
            assertTrue(player.attackStrength.isFullStrength(DELAY));
            complete(player);
            tick(player);
            assertFalse(player.attackStrength.isFullStrength(DELAY));

            var items = player.getInventory().inventory.getSlots().stream()
                    .map(slot -> ItemUtil.copy(slot.getItem()))
                    .toList();
            var content = new ac.cult.cultac.network.packet.InventoryPackets.Content(0, 0, items, ItemUtil.empty());
            @SuppressWarnings("unchecked")
            var contentType =
                    (ac.cult.cultac.protocol.PacketType<ac.cult.cultac.network.packet.InventoryPackets.Content>)
                            ac.cult.cultac.network.codec.ClientPacketCodecs.connectionCatalog().stream()
                                    .filter(candidate -> candidate.recordClass() == content.getClass())
                                    .findFirst()
                                    .orElseThrow();
            var contentEvent = new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, contentType, content, false);
            player.getInventory().onContainerSetContent(contentEvent, player, content);
            assertTrue(player.attackStrength.hasUnconfirmedHotbarChange());
            var ping = (ClientboundPing) contentEvent.writesAfter().getFirst().packet();
            contentEvent.tasksAfter().forEach(Runnable::run);
            assertTrue(player.addTransactionResponse(ping.id()));
            assertFalse(player.attackStrength.hasUnconfirmedHotbarChange());
            for (int i = 0; i < 20; i++) tick(player);
            complete(player);
            tick(player);
            assertTrue(player.attackStrength.isFullStrength(DELAY));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void slotClaimedAndLeftInOneTickMayHaveResetInThePreviousTick() throws Exception {
        CultPlayer player = player("minecraft:diamond_sword", 1);
        try {
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET + 1, ItemUtil.modelItems().stack("minecraft:mace", 1));
            // A hotbar key held the mace through the previous Player#tick; this tick claims it, then the sword.
            for (int slot : List.of(1, 0)) {
                var packet = new ServerboundSetCarriedItem(slot);
                player.getInventory()
                        .onSetCarriedItem(
                                new PacketReceiveEvent<>(
                                        player.user, ConnectionPhase.PLAY, ServerboundPackets.SET_CARRIED_ITEM, packet),
                                player,
                                packet);
            }
            assertFalse(player.attackStrength.isFullStrength(DELAY));
            assertTrue(player.attackStrength.canBeFullStrength(DELAY));
            // This tick's Player#tick sees the sword after the mace and resets again.
            tick(player);
            for (int i = 0; i < DELAY - 1; i++) tick(player);
            assertFalse(player.attackStrength.isFullStrength(DELAY));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static CultPlayer player(String item, int count) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Strength_Proof"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        PlatformPlayer platform = mock(PlatformPlayer.class);
        user.bind(platform);
        // Live server item use must not be used as evidence of a client counter reset.
        when(platform.getItemUseState()).thenReturn(new ItemUseState(true, Hand.MAIN_HAND, false, 0.2F));
        player.entityID = 7;
        player.gamemode = GameMode.SURVIVAL;
        player.packetStateData.playerLoadedIntoLevel = true;
        player.getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack(item, count));
        tick(player);
        player.attackStrength.onReset();
        for (int i = 0; i < 20; i++) tick(player);
        assertTrue(player.attackStrength.isFullStrength(DELAY));
        return player;
    }

    private static void tick(CultPlayer player) {
        new CheckManagerListener().processClientTickEndReceive(RecordReceiveTestEvents.tickEnd(player), player);
    }

    private static void complete(CultPlayer player) {
        var packet = new ClientboundEntityEvent(player.entityID, (byte) 9);
        var event = new PacketSendEvent<>(
                player.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        player.packetEntityReplication.onEntityEvent(event, player, packet);
        var ping = (ClientboundPing) event.writesAfter().getFirst().packet();
        event.tasksAfter().forEach(Runnable::run);
        assertTrue(player.addTransactionResponse(ping.id()));
    }
}
