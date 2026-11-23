package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;

@CheckData(name = "ChatE", stableKey = "cult.chat.inventory", description = "Chatting while inventory is open")
public class ChatE extends ChatMessageCheck {
    public ChatE(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onChat(PacketReceiveEvent<ServerboundChat> event, CultPlayer player, ServerboundChat packet) {
        if (true
                && player.openWindow.mustBeOpen()
                && !matchesExemptMessage(packet.message())
                && flag()
                && shouldModifyPackets()) {
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
                && !matchesExemptCommand(packet.command())
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
        if (true
                && player.openWindow.mustBeOpen()
                && !matchesExemptCommand(packet.command())
                && flag()
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
