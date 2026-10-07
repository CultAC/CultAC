package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCommandSuggestion;

@CheckData(
        name = "ChatA",
        stableKey = "cult.exploit.blank_tab_complete",
        description = "Sent a tab complete packet with no command or input text",
        experimental = true)
public class ChatA extends Check implements CheckListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    public ChatA(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {

        return SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_13);
    }

    @CultPacketHandler
    public void onCommandSuggestion(
            PacketReceiveEvent<ServerboundCommandSuggestion> event,
            CultPlayer player,
            ServerboundCommandSuggestion packet) {
        if (!isApplicable()) return;
        String text = packet.command();
        if (text.equals("/") || text.trim().isEmpty()) {
            if (flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
