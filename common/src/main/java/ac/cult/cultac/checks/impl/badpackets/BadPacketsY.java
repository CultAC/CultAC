package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

@CheckData(name = "BadPacketsY", stableKey = "cult.badpackets.oob_slot", description = "Sent out of bounds slot id")
public class BadPacketsY extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("slot={sint}");

    public BadPacketsY(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, CultPlayer player, ServerboundSetCarriedItemPacket packet) {
        final int slot = packet.getSlot();
        if (slot > 8 || slot < 0) { // ban
            if (flag(V.write(verbose()).sint(slot)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
