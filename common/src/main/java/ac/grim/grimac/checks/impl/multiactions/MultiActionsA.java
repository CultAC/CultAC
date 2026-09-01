package ac.grim.grimac.checks.impl.multiactions;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.DecodedPacketReliability;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.world.InteractionHand;

@CheckData(name = "MultiActionsA", stableKey = "grim.multiactions.attack_while_using", description = "Attacked while using an item", experimental = true)
public class MultiActionsA extends Check implements CheckListener {
    public MultiActionsA(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        if (NmsPacketUtil.readInteract(packet).action() == NmsPacketUtil.InteractAction.ATTACK) {
            check(event);
        }
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        check(event);
    }


    @GrimPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, GrimPlayer player, ServerboundSpectatorActionPacket packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        check(event);
    }


    @GrimPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerActionPacket packet) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.STAB) {
            check(event);
        }
    }

    private void check(PacketReceiveEvent event) {
        if (isActivelyUsingItem()) {
            if (flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    // Limit the active item to the in-use hand's current slot.
    private boolean isActivelyUsingItem() {
        return player.packetStateData.isSlowedByUsingItem()
                && (player.packetStateData.lastSlotSelected == player.packetStateData.getSlowedByUsingItemSlot()
                || player.packetStateData.itemInUseHand == InteractionHand.OFF_HAND);
    }
}
