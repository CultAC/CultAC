package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import net.minecraft.world.InteractionHand;

@CheckData(
        name = "MultiActionsA",
        stableKey = "cult.multiactions.attack_while_using",
        description = "Attacked while using an item",
        experimental = true)
public class MultiActionsA extends Check implements CheckListener {
    public MultiActionsA(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onInteractEntity(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(
                player.getClientVersion(), player.getObservedProtocol())) return;
        if (packet.action() == InteractAction.ATTACK) {
            check(event);
        }
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(
                player.getClientVersion(), player.getObservedProtocol())) return;
        check(event);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() == PlayerAction.STAB) {
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
