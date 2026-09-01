package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

@CheckData(name = "BadPacketsY", stableKey = "grim.badpackets.oob_slot", description = "Sent out of bounds slot id")
public class BadPacketsY extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("slot={sint}");

    public BadPacketsY(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, GrimPlayer player, ServerboundSetCarriedItemPacket packet) {
        final int slot = packet.getSlot();
        if (slot > 8 || slot < 0) { // ban
            if (flag(V.write(verbose()).sint(slot)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
