package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

@CheckData(name = "ChatA", stableKey = "cult.exploit.blank_tab_complete", description = "Sent a tab complete packet with no command or input text", experimental = true)
public class ChatA extends Check implements CheckListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    public ChatA(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {

        return SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_13);
    }

    @CultPacketHandler
    public void onCommandSuggestion(PacketReceiveEvent event, CultPlayer player, ServerboundCommandSuggestionPacket packet) {
        if (!isApplicable()) return;
        String text = (String) ac.cult.cultac.network.packet.NmsPacketUtil.invokeNoArg(packet, "command", "getCommand");
        if (text.equals("/") || text.trim().isEmpty()) {
            if (flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
