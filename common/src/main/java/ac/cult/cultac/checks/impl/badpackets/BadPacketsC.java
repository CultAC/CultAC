package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "BadPacketsC", stableKey = "cult.badpackets.wake_not_sleeping", description = "Tried to wake up while not sleeping", experimental = true)
public class BadPacketsC extends Check implements CheckListener {
    public BadPacketsC(@NotNull CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {

        if (packet.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SLEEPING && !player.isInBed) {
            flag();
        }
    }
}
