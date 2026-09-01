package ac.grim.grimac.checks.impl.multiactions;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.LegacyPacketEventSemantics;
import ac.grim.grimac.checks.type.OrderedPacketReceiveListener;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;

@CheckData(name = "MultiActionsE", stableKey = "grim.multiactions.swing_while_using", description = "Swinging while using an item", experimental = true)
public class MultiActionsE extends Check implements OrderedPacketReceiveListener {
    private boolean dropping;

    public MultiActionsE(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getNmsPacket();

        if (packet instanceof ServerboundSwingPacket && !dropping && isActivelyUsingItem()) {
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
                && player.getClientVersion().getProtocolVersion() >= 573) {
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
