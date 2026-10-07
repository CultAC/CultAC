package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;

@CheckData(
        name = "MultiActionsE",
        stableKey = "cult.multiactions.swing_while_using",
        description = "Swinging while using an item",
        experimental = true)
public class MultiActionsE extends Check implements CheckListener {
    private boolean dropping;

    public MultiActionsE(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        // A drop exempts only the one swing that follows it.
        boolean droppedBeforeSwing = dropping;
        dropping = false;

        if (!droppedBeforeSwing && isActivelyUsingItem()) {
            // This is possible to false on 1.7.
            if (!player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_7_10)
                    && flag()
                    && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        // 1.15+ Minecraft#handleKeybinds swings right after a successful drop,
        // which is the only way to swing while using an item.
        if (player.getClientVersion().getProtocolVersion() >= 573) {
            PlayerAction action = packet.action();
            dropping = action == PlayerAction.DROP_ITEM || action == PlayerAction.DROP_ALL_ITEMS;
        }
    }

    // The drop and its swing are sent before the tick's movement packet and tick end.
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        dropping = false;
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        dropping = false;
    }

    private boolean isActivelyUsingItem() {
        return player.packetStateData.isSlowedByUsingItem()
                && (player.packetStateData.lastSlotSelected == player.packetStateData.getSlowedByUsingItemSlot()
                        || player.packetStateData.itemInUseHand == Hand.OFF_HAND);
    }
}
