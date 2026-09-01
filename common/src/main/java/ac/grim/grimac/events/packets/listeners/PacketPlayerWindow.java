package ac.grim.grimac.events.packets.listeners;

import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.Packet;
import org.bukkit.Material;
import ac.grim.grimac.utils.inventory.inventory.MenuType;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

public class PacketPlayerWindow {

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(event, player);
    }

    private void handleMovePlayer(PacketReceiveEvent event, GrimPlayer player) {
        if (!event.isCancelled() && player.hasInventoryOpen && isDesynced(player)) { handleInventory(player, false); }
    }

    @GrimPacketHandler
    public void onContainerClick(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClickPacket packet) { handleInventory(player, true); }

    @GrimPacketHandler
    public void onServerboundContainerClose(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClosePacket packet) { handleInventory(player, false); }

    @GrimPacketHandler
    public void onRespawn(PacketSendEvent event, GrimPlayer player, ClientboundRespawnPacket packet) {
        player.sendTransaction();
        final Runnable closeInventory = () -> handleInventory(player, false);
        player.latencyUtils.addRealTimeTaskNow(closeInventory);
    }

    @GrimPacketHandler
    public void onOpenScreen(PacketSendEvent event, GrimPlayer player, ClientboundOpenScreenPacket packet) {
        player.sendTransaction();
        // Mark the tick only after the client can observe the screen.
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        MenuType type = MenuType.fromNms(packet.getType());
        final Runnable applyScreenOpen = () -> handleInventory(player, type != MenuType.BEACON && !isDesynced(player));
        player.latencyUtils.addRealTimeTaskNow(applyScreenOpen);
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket")
    public void onMountScreenOpen(PacketSendEvent event, GrimPlayer player, Packet<?> packet) {
        player.sendTransaction();
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        final Runnable openInventory = () -> handleInventory(player, true);
        player.latencyUtils.addRealTimeTaskNow(openInventory);
    }

    @GrimPacketHandler
    public void onClientboundContainerClose(PacketSendEvent event, GrimPlayer player, ClientboundContainerClosePacket packet) {
        player.sendTransaction();
        final Runnable closeInventory = () -> handleInventory(player, false);
        player.latencyUtils.addRealTimeTaskNow(closeInventory);
    }

    // TODO: Is this still an issue?
    private boolean isDesynced(GrimPlayer grimPlayer) {
        if (nearNetherPortal(grimPlayer)) {
            final PacketEntitySelf playerEntity = grimPlayer.compensatedEntities.getSelf();
            if (playerEntity.passengers.isEmpty() && !playerEntity.inVehicle()) {
                return true;
            }
        }

        return false;
    }

    //doesn't need to be accurate
    private boolean nearNetherPortal(GrimPlayer grimPlayer) {
        return Collisions.hasMaterial(grimPlayer, grimPlayer.boundingBox.copy().expand(0.1), pair -> pair.getFirst().getMaterial() == Material.NETHER_PORTAL);
    }


    private void handleInventory(GrimPlayer grimPlayer, boolean nowOpen) {

        if (!grimPlayer.hasInventoryOpen && nowOpen) {
            grimPlayer.lastOpenedInventory = System.currentTimeMillis();
        }

        grimPlayer.hasInventoryOpen = nowOpen;
    }

}
