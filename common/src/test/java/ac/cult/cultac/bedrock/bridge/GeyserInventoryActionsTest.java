package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.manager.player.ActionManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GeyserInventoryActionsTest {
    @BeforeClass public static void bootstrap() throws Exception { GeyserItemComponentsTest.bootstrap(); }

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

    @Test public void unchangedGeyserSlotsDoNotOverwriteCompensatedState() throws Exception {
        var player = player();
        var session = session();
        player.getInventory().inventory.getSlot(9).set(new ItemStack(Material.DIAMOND_SWORD));
        GeyserInventoryActions.translate(session, player, new MobEquipmentPacket(), () -> {});
        assertEquals(Material.DIAMOND_SWORD, player.getInventory().inventory.getSlot(9).getItem().getType());
    }

    @Test public void nativeCloseUpdatesTheSharedModel() throws Exception {
        var player = player();
        player.hasInventoryOpen = true;
        player.getInventory().openWindowID = 3;
        player.getInventory().menu.setCarried(new ItemStack(Material.STONE));
        GeyserInventoryActions.translate(session(), player, new ContainerClosePacket(), () -> {});
        assertFalse(player.hasInventoryOpen);
        assertEquals(0, player.getInventory().openWindowID);
        assertTrue(player.getInventory().menu.getCarried().isEmpty());
    }

    @Test public void acceptedHotbarChangeUpdatesUseState() throws Exception {
        var player = player();
        var session = session();
        GeyserInventoryActions.translate(session, player, new MobEquipmentPacket(),
                () -> when(session.getPlayerInventory().getHeldItemSlot()).thenReturn(4));
        assertEquals(4, player.packetStateData.lastSlotSelected);
        assertEquals(4, player.getInventory().inventory.selected);
        verify(player.actionManager).selectHotbarSlot();
    }

    @Test public void serverMenuIdentityGuardsNativeSlotChanges() throws Exception {
        var player = player();
        var target = player.getInventory();
        target.openWindowID = 3;
        target.applyBedrockSlots(4, java.util.Map.of(9, new ItemStack(Material.STONE)));
        assertTrue(target.menu.getSlot(9).getItem().isEmpty());
        target.applyBedrockSlots(0, java.util.Map.of(9, new ItemStack(Material.STONE)));
        assertEquals(Material.STONE, target.inventory.getSlot(9).getItem().getType());
    }
}
