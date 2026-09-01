package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

@CheckData(name = "BadPacketsA", stableKey = "grim.badpackets.duplicate_slot", description = "Sent duplicate slot id")
public class BadPacketsA extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("slot={sint}");

    private int lastSlot = -1;

    public BadPacketsA(final GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, GrimPlayer player, ServerboundSetCarriedItemPacket packet) {
        final int slot = packet.getSlot();

        if (slot == lastSlot && flag(V.write(verbose()).sint(slot)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }

        lastSlot = slot;
    }
}
