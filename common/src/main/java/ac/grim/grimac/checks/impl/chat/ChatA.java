package ac.grim.grimac.checks.impl.chat;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

@CheckData(name = "ChatA", stableKey = "grim.exploit.blank_tab_complete", description = "Sent a tab complete packet with no command or input text", experimental = true)
public class ChatA extends Check implements CheckListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    public ChatA(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {

        return SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_13);
    }

    @GrimPacketHandler
    public void onCommandSuggestion(PacketReceiveEvent event, GrimPlayer player, ServerboundCommandSuggestionPacket packet) {
        if (!isApplicable()) return;
        String text = packet.getCommand();
        if (text.equals("/") || text.trim().isEmpty()) {
            if (flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
