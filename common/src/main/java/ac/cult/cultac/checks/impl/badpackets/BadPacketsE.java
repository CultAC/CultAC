package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsE",
        stableKey = "cult.badpackets.invalid_position",
        description = "Sent too many movement packets without updating position")
public class BadPacketsE extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("ticks={uint}");
    private static final int MAX_NO_REMINDER_TICKS = 19;

    private int noReminderTicks;

    public BadPacketsE(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (packet.hasPosition() || player.inVehicle()) {
            noReminderTicks = 0;
            return;
        }

        if (!player.packetStateData.lastPacketWasTeleport && ++noReminderTicks > MAX_NO_REMINDER_TICKS) {
            flag(V.write(verbose()).uint(noReminderTicks));
        }
    }

    public void handleRespawn() {
        noReminderTicks = 0;
    }
}
