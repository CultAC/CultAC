package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import org.bukkit.GameMode;

@CheckData(name = "BadPacketsK", stableKey = "grim.badpackets.invalid_spectate", description = "Sent spectate packets while not in spectator mode")
public class BadPacketsK extends Check implements CheckListener {
    public BadPacketsK(GrimPlayer player) {
        super(player);
    }


    @GrimPacketHandler
    public void onTeleportToEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundTeleportToEntityPacket packet) {
        if (player.gamemode != GameMode.SPECTATOR && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
