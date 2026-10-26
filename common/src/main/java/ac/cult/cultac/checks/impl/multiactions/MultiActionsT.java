package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.utils.data.PacketStateData;

// Minecraft#handleKeybinds reads the hotbar keys and the scroll wheel only while no screen is open; a hotbar key in a
// container screen is a swap click, never a held slot change. Minecraft#tick runs MultiPlayerGameMode#tick before
// LocalPlayer#tick whatever the screen, so its ensureHasSentCarriedItem sends the slot chosen in the previous tick as
// the tick's first slot, ahead of everything LocalPlayer#tick sends, in the first tick a screen is open.
// ClientPacketListener#handleSetHeldSlot sets the selected slot but leaves carriedIndex, so the next tick sends the
// server's slot that way; with a screen open the client has no other way to change the selected slot.
@CheckData(
        name = "MultiActionsT",
        stableKey = "cult.multiactions.inventory_slot",
        description = "Changed the held slot while in an inventory",
        experimental = true)
public class MultiActionsT extends Check implements CheckListener {
    public MultiActionsT(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        if (player.openWindow.mustBeOpen() && !fromGameModeTick(packet.slot()) && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }

    /** The tick's first slot, ahead of LocalPlayer#tick: the previous tick's choice or a set_held_slot echo. */
    private boolean fromGameModeTick(int slot) {
        PacketStateData state = player.packetStateData;
        if (state.carriedItemClaimedThisClientTick || state.localPlayerTickSentThisClientTick()) return false;
        if (state.heldSlotEcho >= 0 && slot == state.heldSlotEcho) return true;
        return player.openWindow.getTicksOpen() == 0 && !state.heldSlotSetWithScreenOpen;
    }
}
