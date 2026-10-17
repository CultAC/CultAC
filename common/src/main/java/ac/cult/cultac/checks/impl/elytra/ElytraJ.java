package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;

@CheckData(
        name = "ElytraJ",
        stableKey = "cult.elytra.inventory",
        description = "Started gliding in an inventory",
        experimental = true)
public class ElytraJ extends Check implements ac.cult.cultac.checks.type.PostPredictionListener {
    public ElytraJ(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.START_FLYING_WITH_ELYTRA
                && player.openWindow.mustBeOpen()
                && flag()
                && shouldModifyPackets()) {
            player.closeInventorySafely();
        }
    }
}
