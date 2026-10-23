package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(name = "BadPacketsA", stableKey = "cult.badpackets.duplicate_slot", description = "Sent duplicate slot id")
public class BadPacketsA extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("slot={sint}");

    private int lastSlot = -1;

    public BadPacketsA(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        final int slot = packet.slot();

        // TODO: codex claims that this flags on join sometimes
        if (slot == lastSlot && flag(V.write(verbose()).sint(slot)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }

        lastSlot = slot;
    }

    /** ClientPacketListener#handleLogin's new MultiPlayerGameMode starts its carriedIndex at 0. */
    public void resetCarriedIndex() {
        lastSlot = 0;
    }
}
