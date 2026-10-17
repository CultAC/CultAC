package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;

@CheckData(
        name = "BadPacketsZ",
        stableKey = "cult.badpackets.duplicate_player_input",
        description = "Sent duplicate player input packets in the same client tick",
        experimental = true)
public class BadPacketsZ extends Check implements CheckListener {
    private boolean sent;

    public BadPacketsZ(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        sent = false;
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        if (sent) {
            flag();
        }

        sent = true;
    }
}
