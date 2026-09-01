package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;

@CheckData(name = "BadPacketsI", stableKey = "grim.badpackets.spoofed_abilities", description = "Claimed to be flying while unable to fly")
public class BadPacketsI extends Check implements CheckListener {
    public BadPacketsI(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerAbilities(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        if (packet.isFlying() && !player.canFly && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
