package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;

@CheckData(
        name = "PacketOrderN",
        stableKey = "cult.packetorder.place_use_order",
        description = "Sent use item and block place packets in an invalid order",
        experimental = true)
public class PacketOrderN extends BlockPlaceCheck {
    public PacketOrderN(final CultPlayer player) {
        super(player);
    }

    private boolean usingWithoutPlacing, placing;

    @Override
    public void onBlockPlace(BlockPlace place) {
        if (usingWithoutPlacing) {
            if (flag() && shouldModifyPackets() && shouldCancel()) {
                place.resync();
            }
        }
    }

    // Every use_item_on counts, whatever the hand holds: only block-placing items reach onBlockPlace.
    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        placing = true;
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!placing) {
            usingWithoutPlacing = true;
        }

        placing = false;

        if (!player.cameraEntity.isSelf()) {
            usingWithoutPlacing = placing = false;
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            usingWithoutPlacing = placing = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.receivedMovementThisClientTick) {
            usingWithoutPlacing = placing = false;
        }
    }
}
