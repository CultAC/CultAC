package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.LegacyPacketEventSemantics;
import ac.cult.cultac.checks.type.OrderedPacketReceiveListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.SwingPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;

@CheckData(name = "MultiActionsE", stableKey = "cult.multiactions.swing_while_using", description = "Swinging while using an item", experimental = true)
public class MultiActionsE extends Check implements OrderedPacketReceiveListener {
    private boolean dropping;

    public MultiActionsE(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getNmsPacket();

        if (SwingPacketUtil.isSwing(packet) && !dropping && isActivelyUsingItem()) {
            // This is possible to false on 1.7.
            if (!player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_7_10)
                    && flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }

        // PacketEvents cleared this one-packet exemption on every non-async
        // packet, then reopened it only for a drop action. Keeping that order is
        // what makes only the vanilla DROP -> SWING pair exempt.
        if (!LegacyPacketEventSemantics.isAsync(packet)) {
            dropping = false;
        }

        if (packet instanceof ServerboundPlayerActionPacket actionPacket
                && player.getClientVersion().getProtocolVersion() >= 573
                // 26.3 drops animate locally; they do not authorize a PUNCH.
                && player.getClientVersion().isOlderThan(ClientVersion.V_26_3_RC_1)) {
            ServerboundPlayerActionPacket.Action action = actionPacket.getAction();
            dropping = action == ServerboundPlayerActionPacket.Action.DROP_ITEM
                    || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS;
        }
    }

    private boolean isActivelyUsingItem() {
        return player.packetStateData.isSlowedByUsingItem()
                && (player.packetStateData.lastSlotSelected == player.packetStateData.getSlowedByUsingItemSlot()
                || player.packetStateData.itemInUseHand == InteractionHand.OFF_HAND);
    }
}
