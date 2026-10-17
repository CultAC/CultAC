package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCommandSuggestion;

@CheckData(
        name = "ChatA",
        stableKey = "cult.exploit.blank_tab_complete",
        description = "Sent a tab complete packet with no command or input text",
        experimental = true)
public class ChatA extends Check implements CheckListener {
    public ChatA(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onCommandSuggestion(
            PacketReceiveEvent<ServerboundCommandSuggestion> event,
            CultPlayer player,
            ServerboundCommandSuggestion packet) {
        String text = packet.command();
        if (text.equals("/") || text.trim().isEmpty()) {
            if (flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
