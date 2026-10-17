package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "MultiActionsM",
        stableKey = "cult.multiactions.inventory_sneak",
        description = "Sneaking while in an inventory",
        experimental = true)
public class MultiActionsM extends Check implements CheckListener {
    public MultiActionsM(CultPlayer player) {
        super(player);
    }

    private static final Verbose V = Verbose.of("tick").or("start").or("stop");
    private boolean wasInputSneaking;

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.PRESS_SHIFT_KEY) onChange(true);
        else if (packet.action() == PlayerCommandAction.RELEASE_SHIFT_KEY) onChange(false);
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        if (packet.shift() != wasInputSneaking) onChange(packet.shift());
        wasInputSneaking = packet.shift();
    }

    private void onChange(boolean sneaking) {
        if ((sneaking || player.openWindow.getTicksOpen() != 0)
                && player.openWindow.mustBeOpen()
                && flag(V.write(verbose(), sneaking ? 1 : 2))
                && shouldModifyPackets()) player.closeInventorySafely();
    }
}
