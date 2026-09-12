package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;

@CheckData(name = "MultiActionsA", stableKey = "cult.multiactions.attack_while_using", description = "Attacked while using an item", experimental = true)
public class MultiActionsA extends Check implements CheckListener {
    public MultiActionsA(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        if (NmsPacketUtil.readInteract(packet).action() == NmsPacketUtil.InteractAction.ATTACK) {
            check(event);
        }
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        check(event);
    }


    // 26.1 uses a required entity id; 26.2 also permits a spectator action without a target.
    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket")
    public void onSpectateEntity(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSpectatorAction(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket")
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        check(event);
    }


    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        if (packet.getAction().name().equals("STAB")) {
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
