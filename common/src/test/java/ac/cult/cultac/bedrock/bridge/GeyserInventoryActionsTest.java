package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.player.ActionManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.BeforeClass;
import org.junit.Test;

public class GeyserInventoryActionsTest {
    @BeforeClass
    public static void bootstrap() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var geyser = mock(org.geysermc.geyser.GeyserImpl.class, RETURNS_DEEP_STUBS);
        when(geyser.packDirectory()).thenReturn(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
        var instance = org.geysermc.geyser.GeyserImpl.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, geyser);
        try {
            Class.forName("org.geysermc.geyser.inventory.GeyserItemStack");
        } finally {
            instance.set(null, previous);
        }
    }

    private static CultPlayer player() throws Exception {
        var player = mock(CultPlayer.class);
        player.packetStateData = new PacketStateData();
        player.actionManager = mock(ActionManager.class);
        var transactions = CultPlayer.class.getDeclaredField("lastTransactionSent");
        transactions.setAccessible(true);
        transactions.set(player, new java.util.concurrent.atomic.AtomicInteger());
        player.compensatedEntities = new ac.cult.cultac.utils.latency.CompensatedEntities(player);
        when(player.getInventory()).thenReturn(new CompensatedInventory(player));
        return player;
    }

    private static GeyserSession session() {
        var session = mock(GeyserSession.class);
        var inventory = mock(PlayerInventory.class);
        when(session.getPlayerInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(46);
        when(inventory.getItem(anyInt())).thenReturn(GeyserItemStack.EMPTY);
        when(inventory.getCursor()).thenReturn(GeyserItemStack.EMPTY);
        return session;
    }

    @Test
    public void unchangedGeyserSlotsDoNotOverwriteCompensatedState() throws Exception {
        var player = player();
        var session = session();
        player.getInventory().inventory.getSlot(9).set(ItemUtil.modelItems().stack("minecraft:diamond_sword", 1));
        GeyserInventoryActions.translate(session, player, new MobEquipmentPacket(), () -> {});
        assertEquals(
                ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:diamond_sword"),
                player.getInventory().inventory.getSlot(9).getItem().getItem());
    }

    @Test
    public void nativeCloseUpdatesTheSharedModel() throws Exception {
        var player = player();
        player.hasInventoryOpen = true;
        player.getInventory().openWindowID = 3;
        player.getInventory().menu.setCarried(ItemUtil.modelItems().stack("minecraft:stone", 1));
        GeyserInventoryActions.translate(session(), player, new ContainerClosePacket(), () -> {});
        assertFalse(player.hasInventoryOpen);
        assertEquals(0, player.getInventory().openWindowID);
        assertTrue(player.getInventory().menu.getCarried().isEmpty());
    }

    @Test
    public void acceptedHotbarChangeUpdatesUseState() throws Exception {
        var player = player();
        var session = session();
        GeyserInventoryActions.translate(
                session,
                player,
                new MobEquipmentPacket(),
                () -> when(session.getPlayerInventory().getHeldItemSlot()).thenReturn(4));
        assertEquals(4, player.packetStateData.lastSlotSelected);
        assertEquals(4, player.getInventory().inventory.selected);
        verify(player.actionManager).selectHotbarSlot();
    }

    @Test
    public void serverMenuIdentityGuardsNativeSlotChanges() throws Exception {
        var player = player();
        var target = player.getInventory();
        target.openWindowID = 3;
        target.applyBedrockSlots(4, java.util.Map.of(9, ItemUtil.modelItems().stack("minecraft:stone", 1)));
        assertTrue(target.menu.getSlot(9).getItem().isEmpty());
        target.applyBedrockSlots(0, java.util.Map.of(9, ItemUtil.modelItems().stack("minecraft:stone", 1)));
        assertEquals(
                ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:stone"),
                target.inventory.getSlot(9).getItem().getItem());
    }
}
