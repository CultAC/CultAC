package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import org.bukkit.GameMode;

@CheckData(name = "BadPacketsK", stableKey = "cult.badpackets.invalid_spectate", description = "Sent spectate packets while not in spectator mode")
public class BadPacketsK extends Check implements CheckListener {
    public BadPacketsK(CultPlayer player) {
        super(player);
    }


    @CultPacketHandler
    public void onTeleportToEntity(PacketReceiveEvent event, CultPlayer player, ServerboundTeleportToEntityPacket packet) {
        if (player.gamemode != GameMode.SPECTATOR && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
