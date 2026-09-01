package ac.grim.grimac.checks.impl.chat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;

// this can false from click events, but I doubt this would actually
// happen unless they're trying to flag, or if the server is set up badly
@CheckData(name = "ChatB", stableKey = "grim.exploit.spigot_antispam_bypass", description = "Invalid chat message")
public class ChatB extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("[message|command]={str}");

    public ChatB(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onChatMessage(PacketReceiveEvent event, GrimPlayer player, ServerboundChatPacket packet) {
        String message = packet.message();
        if (message.isEmpty() || !message.trim().equals(message)
                || message.startsWith("/") && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_19)) {
            if (flag(V.write(verbose()).bool(true).str(message)) && shouldModifyPackets()) {
                player.onPacketCancel();
                event.setCancelled(true);
            }
        }
    }

    @GrimPacketHandler
    public void onChatCommandUnsigned(PacketReceiveEvent event, GrimPlayer player, ServerboundChatCommandPacket packet) {
        String command = "/" + packet.command();
        if (!command.stripTrailing().equals(command) && flag(V.write(verbose()).bool(false).str(command))) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @GrimPacketHandler
    public void onChatCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundChatCommandSignedPacket packet) {
        String command = "/" + packet.command();
        if (!command.trim().equals(command) && flag(V.write(verbose()).bool(false).str(command))) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
