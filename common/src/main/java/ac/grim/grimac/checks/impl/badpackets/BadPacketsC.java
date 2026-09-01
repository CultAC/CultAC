package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "BadPacketsC", stableKey = "grim.badpackets.wake_not_sleeping", description = "Tried to wake up while not sleeping", experimental = true)
public class BadPacketsC extends Check implements CheckListener {
    public BadPacketsC(@NotNull GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {

        if (packet.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SLEEPING && !player.isInBed) {
            flag();
        }
    }
}
