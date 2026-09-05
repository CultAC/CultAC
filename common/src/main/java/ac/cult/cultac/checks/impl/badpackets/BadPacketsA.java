package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

@CheckData(name = "BadPacketsA", stableKey = "cult.badpackets.duplicate_slot", description = "Sent duplicate slot id")
public class BadPacketsA extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("slot={sint}");

    private int lastSlot = -1;

    public BadPacketsA(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, CultPlayer player, ServerboundSetCarriedItemPacket packet) {
        final int slot = packet.getSlot();

        if (slot == lastSlot && flag(V.write(verbose()).sint(slot)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }

        lastSlot = slot;
    }
}
