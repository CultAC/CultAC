package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.Packet;
import org.bukkit.Material;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

public class PacketPlayerWindow {

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(event, player);
    }

    private void handleMovePlayer(PacketReceiveEvent event, CultPlayer player) {
        if (!event.isCancelled() && player.hasInventoryOpen && isDesynced(player)) { handleInventory(player, false); }
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) { handleInventory(player, true); }

    @CultPacketHandler
    public void onServerboundContainerClose(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClosePacket packet) { handleInventory(player, false); }

    @CultPacketHandler
    public void onRespawn(PacketSendEvent event, CultPlayer player, ClientboundRespawnPacket packet) {
        player.sendTransaction();
        final Runnable closeInventory = () -> handleInventory(player, false);
        player.latencyUtils.addRealTimeTaskNow(closeInventory);
    }

    @CultPacketHandler
    public void onOpenScreen(PacketSendEvent event, CultPlayer player, ClientboundOpenScreenPacket packet) {
        player.sendTransaction();
        // Mark the tick only after the client can observe the screen.
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        MenuType type = MenuType.fromNms(packet.getType());
        final Runnable applyScreenOpen = () -> handleInventory(player, type != MenuType.BEACON && !isDesynced(player));
        player.latencyUtils.addRealTimeTaskNow(applyScreenOpen);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundHorseScreenOpenPacket")
    public void onHorseScreenOpen(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
        onMountScreenOpen(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket")
    public void onMountScreenOpen(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
        player.sendTransaction();
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        final Runnable openInventory = () -> handleInventory(player, true);
        player.latencyUtils.addRealTimeTaskNow(openInventory);
    }

    @CultPacketHandler
    public void onClientboundContainerClose(PacketSendEvent event, CultPlayer player, ClientboundContainerClosePacket packet) {
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

    //doesn't need to be accurate
    private boolean nearNetherPortal(CultPlayer cultPlayer) {
        return Collisions.hasMaterial(cultPlayer, cultPlayer.boundingBox.copy().expand(0.1), pair -> pair.getFirst().getMaterial() == Material.NETHER_PORTAL);
    }


    private void handleInventory(CultPlayer cultPlayer, boolean nowOpen) {

        if (!cultPlayer.hasInventoryOpen && nowOpen) {
            cultPlayer.lastOpenedInventory = System.currentTimeMillis();
        }

        cultPlayer.hasInventoryOpen = nowOpen;
    }

}
