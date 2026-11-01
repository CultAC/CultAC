package ac.cult.cultac.dialog;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomClickAction;

/** Hands clicks on CultAC's dialogs to {@link Dialogs} and keeps them from the server. */
public final class DialogClickListener {
    @CultPacketHandler
    public void onClick(
            PacketReceiveEvent<ServerboundCustomClickAction> event,
            CultPlayer player,
            ServerboundCustomClickAction click) {
        if (Dialogs.clicked(player, click)) event.setCancelled(true);
    }
}
