package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;

@CheckData(
        name = "PacketOrderH",
        stableKey = "cult.packetorder.sneak_sprint_order",
        description = "Sent sprinting and sneaking state changes in an invalid packet order",
        experimental = true)
public class PacketOrderH extends Check implements CheckListener {
    public PacketOrderH(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        switch (packet.action()) {
            case PRESS_SHIFT_KEY, RELEASE_SHIFT_KEY -> {
                if (player.packetOrderProcessor.isSprinting()) {
                    flag();
                }
            }
            default -> {}
        }
    }
}
