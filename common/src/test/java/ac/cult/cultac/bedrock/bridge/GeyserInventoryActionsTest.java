package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.player.ActionManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftCreativeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftResultsDeprecatedAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
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

    private static ItemStackRequestSlotData slot(ContainerSlotType container, int slot) {
        return new ItemStackRequestSlotData(container, slot, 0, new FullContainerName(container, null));
    }

    // CraftCreative(elytra), CraftResultsDeprecated, Place(CREATED_OUTPUT -> chest armor slot)
    private static ItemStackRequest creativeElytraRequest(int id) {
        return new ItemStackRequest(id, new ItemStackRequestAction[]{
                new CraftCreativeAction(1, 1),
                new CraftResultsDeprecatedAction(new org.cloudburstmc.protocol.bedrock.data.inventory.ItemData[0], 1),
                new PlaceAction(1, slot(ContainerSlotType.CREATED_OUTPUT, 50), slot(ContainerSlotType.ARMOR, 1))}, new String[0]);
    }

    private static ItemStackRequestPacket requests(ItemStackRequest... requests) {
        var packet = new ItemStackRequestPacket();
        packet.getRequests().addAll(java.util.List.of(requests));
        return packet;
    }

    @Test
    public void survivalCreativeRequestsReachGeyserWithoutActions() throws Exception {
        var player = player();
        player.gamemode = GameMode.SURVIVAL;
        var session = session();
        var take = new ItemStackRequest(8, new ItemStackRequestAction[]{
                new TakeAction(1, slot(ContainerSlotType.HOTBAR, 0), slot(ContainerSlotType.CURSOR, 0))}, new String[0]);
        var packet = requests(creativeElytraRequest(7), take);
        GeyserInventoryActions.translate(session, player, packet, () -> {
            assertEquals(7, packet.getRequests().get(0).getRequestId());
            assertEquals(0, packet.getRequests().get(0).getActions().length);
            assertSame(take, packet.getRequests().get(1));
        });
    }

    @Test
    public void authInputCreativeRequestIsStrippedButStillPerformed() throws Exception {
        var player = player();
        player.gamemode = GameMode.ADVENTURE;
        var packet = new PlayerAuthInputPacket();
        packet.getInputData().add(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST);
        packet.setItemStackRequest(creativeElytraRequest(3));
        GeyserInventoryActions.translate(session(), player, packet, () -> {
            assertEquals(3, packet.getItemStackRequest().getRequestId());
            assertEquals(0, packet.getItemStackRequest().getActions().length);
            assertTrue(packet.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST));
        });
    }

    @Test
    public void creativeRequestsNeedBothModelsInCreative() throws Exception {
        var player = player();
        var session = session();
        var request = creativeElytraRequest(5);

        player.gamemode = GameMode.CREATIVE;
        var stale = requests(request);
        GeyserInventoryActions.translate(session, player, stale, () -> {});
        assertEquals(0, stale.getRequests().get(0).getActions().length);

        when(session.getGameMode()).thenReturn(org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode.CREATIVE);
        var creative = requests(request);
        GeyserInventoryActions.translate(session, player, creative, () -> {});
        assertSame(request, creative.getRequests().get(0));

        var unbound = requests(request);
        GeyserInventoryActions.rejectCreativeRequests(session, null, unbound);
        assertEquals(0, unbound.getRequests().get(0).getActions().length);
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
