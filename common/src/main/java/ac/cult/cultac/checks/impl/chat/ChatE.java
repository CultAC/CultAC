package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;

@CheckData(name = "ChatE", stableKey = "cult.chat.inventory", description = "Chatting while inventory is open")
public class ChatE extends Check implements CheckListener {
    public ChatE(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onChat(PacketReceiveEvent<ServerboundChat> event, CultPlayer player, ServerboundChat packet) {
        if (player.openWindow.mustBeOpen() && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onCommand(
            PacketReceiveEvent<ServerboundChatCommand> event, CultPlayer player, ServerboundChatCommand packet) {
        if (player.openWindow.mustBeOpen()
                && !player.openWindow.mayShowBookDialog()
                && !player.openWindow.mayShowServerDialog()
                && flag()
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onSignedCommand(
            PacketReceiveEvent<ServerboundChatCommandSigned> event,
            CultPlayer player,
            ServerboundChatCommandSigned packet) {
        if (player.openWindow.mustBeOpen() && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
