package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;

public class PacketPlayerWindow {

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (player.isBedrockMovement()) {
            handleMovePlayer(event, player);
        }
    }

    private void handleMovePlayer(PacketReceiveEvent event, CultPlayer player) {
        if (!event.isCancelled() && player.hasInventoryOpen && isDesynced(player)) {
            handleInventory(player, false);
        }
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        if (player.isBedrockMovement()) handleInventory(player, true);
        else if (packet.windowId() == 0 && !event.isCancelled()) player.openWindow.onClientOpenOrCloseWindow(true);
    }

    @CultPacketHandler
    public void onServerboundContainerClose(
            PacketReceiveEvent<ServerboundContainerClose> event, CultPlayer player, ServerboundContainerClose packet) {
        if (player.isBedrockMovement()) handleInventory(player, false);
        else player.openWindow.onClientOpenOrCloseWindow(false);
    }

    @CultPacketHandler
    public void onRespawn(PacketSendEvent<ClientboundRespawn> event, CultPlayer player, ClientboundRespawn packet) {
        if (!player.isBedrockMovement()) {
            CultPlayer.TrackedTransaction before =
                    player.createTrackedTransactionPacketForDeferredSend(CultPlayer.PING_LEADING);
            if (before != null) {
                event.getWritesBeforeSend().add(before.packet());
                event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(before));
                player.latencyUtils.addRealTimeTask(before.transaction(), player.openWindow::maybeClose);
            }
            return;
        }
        player.sendTransaction();
        final Runnable closeInventory = () -> handleInventory(player, false);
        player.latencyUtils.addRealTimeTaskNow(closeInventory);
    }

    @CultPacketHandler
    public void onOpenScreen(
            PacketSendEvent<ClientboundOpenScreen> event, CultPlayer player, ClientboundOpenScreen packet) {
        if (!player.isBedrockMovement()) {
            MenuType type = MenuType.fromRegistryKey(packet.menuType());
            if (type != MenuType.UNKNOWN) player.openWindow.onServerWindow(event, packet.containerId(), type);
            return;
        }
        player.sendTransaction();
        // Mark the tick only after the client can observe the screen.
        player.latencyUtils.addRealTimeTask(
                player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        MenuType type = MenuType.fromRegistryKey(packet.menuType());
        final Runnable applyScreenOpen = () -> handleInventory(player, type != MenuType.BEACON && !isDesynced(player));
        player.latencyUtils.addRealTimeTaskNow(applyScreenOpen);
    }

    @CultPacketHandler
    public void onMountScreenOpen(
            PacketSendEvent<ClientboundMountScreenOpen> event, CultPlayer player, ClientboundMountScreenOpen packet) {
        if (!player.isBedrockMovement()) {
            var entity = player.compensatedEntities.serverPositionsMap.get(packet.entityId());
            if (entity != null
                    && (EntityTypeUtil.isHorseFamily(entity.getEntityType())
                            || EntityTypeUtil.isType(entity.getEntityType(), "nautilus")
                            || EntityTypeUtil.isType(entity.getEntityType(), "zombie_nautilus"))) {
                player.openWindow.onServerWindow(event, packet.containerId(), MenuType.UNKNOWN);
            }
            return;
        }
        player.sendTransaction();
        player.latencyUtils.addRealTimeTask(
                player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        final Runnable openInventory = () -> handleInventory(player, true);
        player.latencyUtils.addRealTimeTaskNow(openInventory);
    }

    @CultPacketHandler
    public void onClientboundContainerClose(
            PacketSendEvent<ClientboundContainerClose> event, CultPlayer player, ClientboundContainerClose packet) {
        if (!player.isBedrockMovement()) {
            player.openWindow.onServerWindow(event, 0, null);
            return;
        }
        player.sendTransaction();
        final Runnable closeInventory = () -> handleInventory(player, false);
        player.latencyUtils.addRealTimeTaskNow(closeInventory);
    }

    // TODO: Is this still an issue?
    private boolean isDesynced(CultPlayer cultPlayer) {
        if (nearNetherPortal(cultPlayer)) {
            final PacketEntitySelf playerEntity = cultPlayer.compensatedEntities.getSelf();
            if (playerEntity.passengers.isEmpty() && !playerEntity.inVehicle()) {
                return true;
            }
        }

        return false;
    }

    // doesn't need to be accurate
    private boolean nearNetherPortal(CultPlayer cultPlayer) {
        return Collisions.hasState(
                cultPlayer,
                cultPlayer.boundingBox.copy().expand(0.1),
                (state, pos) -> BlockIds.is(state, BlockIds.NETHER_PORTAL));
    }

    public static void handleInventory(CultPlayer cultPlayer, boolean nowOpen) {

        if (!cultPlayer.hasInventoryOpen && nowOpen) {
            cultPlayer.lastOpenedInventory = System.currentTimeMillis();
        }

        cultPlayer.hasInventoryOpen = nowOpen;
    }
}
