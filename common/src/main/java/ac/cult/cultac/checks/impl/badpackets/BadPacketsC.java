package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import org.jetbrains.annotations.NotNull;

@CheckData(
        name = "BadPacketsC",
        stableKey = "cult.badpackets.wake_not_sleeping",
        description = "Tried to wake up while not sleeping",
        experimental = true)
public class BadPacketsC extends Check implements CheckListener {
    // Gui#tick closes InBedChatScreen, whose Leave Bed button sends STOP_SLEEPING, only in the tick after the wake.
    private boolean inBedAtLastTick;

    public BadPacketsC(@NotNull CultPlayer player) {
        super(player);
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        inBedAtLastTick = player.isInBed;
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {

        if (packet.action() == PlayerCommandAction.STOP_SLEEPING && !player.isInBed && !inBedAtLastTick) {
            flag();
        }
    }
}
