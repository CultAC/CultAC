package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.nmsutil.Collisions;

public class PacketPlayerWindow {

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        handleMovePlayer(event, player);
    }

    private void handleMovePlayer(PacketReceiveEvent event, CultPlayer player) {
        if (!event.isCancelled() && player.hasInventoryOpen && isDesynced(player)) {
            handleInventory(player, false);
        }
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        handleInventory(player, true);
    }

    @CultPacketHandler("serverbound.container_close")
    public void onServerboundContainerClose(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        handleInventory(player, false);
    }

    @CultPacketHandler
    public void onRespawn(PacketSendEvent<ClientboundRespawn> event, CultPlayer player, ClientboundRespawn packet) {
        player.sendTransaction();
        final Runnable closeInventory = () -> handleInventory(player, false);
        player.latencyUtils.addRealTimeTaskNow(closeInventory);
    }

    @CultPacketHandler
    public void onOpenScreen(
            PacketSendEvent<ClientboundOpenScreen> event, CultPlayer player, ClientboundOpenScreen packet) {
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
        player.sendTransaction();
        player.latencyUtils.addRealTimeTask(
                player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        final Runnable openInventory = () -> handleInventory(player, true);
        player.latencyUtils.addRealTimeTaskNow(openInventory);
    }

    @CultPacketHandler("clientbound.container_close")
    public void onClientboundContainerClose(PacketSendEvent<Opaque> event, CultPlayer player, Opaque packet) {
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
