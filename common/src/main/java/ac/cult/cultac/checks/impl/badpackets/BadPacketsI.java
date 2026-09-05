package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;

@CheckData(name = "BadPacketsI", stableKey = "cult.badpackets.spoofed_abilities", description = "Claimed to be flying while unable to fly")
public class BadPacketsI extends Check implements CheckListener {
    public BadPacketsI(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerAbilities(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        if (packet.isFlying() && !player.canFly && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
