package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

@CheckData(
        name = "BreakVisibility",
        stableKey = "cult.break.visibility",
        description = "Broke a block through blocks or on an inaccessible face")
public final class BreakVisibility extends VisibilityCheck {
    public BreakVisibility(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility);
    }

    @CultPacketHandler
    public void onDig(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        // ABORT releases attack or switches targets without a fresh hit on the old block.
        if (packet.action() != PlayerAction.START_DESTROY_BLOCK && packet.action() != PlayerAction.STOP_DESTROY_BLOCK)
            return;
        if (visibility.blockFaceHidden(packet.position(), packet.direction()))
            reject(event, "action=" + packet.action() + ", " + block(packet.position(), packet.direction()));
    }
}
