package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommandSigned;
import ac.grim.grimac.api.storage.verbose.Verbose;

// this can false from click events, but I doubt this would actually
// happen unless they're trying to flag, or if the server is set up badly
@CheckData(name = "ChatB", stableKey = "cult.exploit.spigot_antispam_bypass", description = "Invalid chat message")
public class ChatB extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("[message|command]={str}");

    public ChatB(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onChatMessage(PacketReceiveEvent<ServerboundChat> event, CultPlayer player, ServerboundChat packet) {
        String message = packet.message();
        if (message.isEmpty() || !message.trim().equals(message) || message.startsWith("/")) {
            if (flag(V.write(verbose()).bool(true).str(message)) && shouldModifyPackets()) {
                player.onPacketCancel();
                event.setCancelled(true);
            }
        }
    }

    @CultPacketHandler
    public void onChatCommandUnsigned(
            PacketReceiveEvent<ServerboundChatCommand> event, CultPlayer player, ServerboundChatCommand packet) {
        String command = "/" + packet.command();
        if (!command.stripTrailing().equals(command)
                && flag(V.write(verbose()).bool(false).str(command))
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onChatCommand(
            PacketReceiveEvent<ServerboundChatCommandSigned> event,
            CultPlayer player,
            ServerboundChatCommandSigned packet) {
        String command = "/" + packet.command();
        if (!command.trim().equals(command)
                && flag(V.write(verbose()).bool(false).str(command))
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
