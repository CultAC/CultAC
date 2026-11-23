package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsC;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommandSigned;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "ChatC",
        stableKey = "cult.chat.moving_while_chatting",
        description = "Moving while chatting",
        experimental = true)
public class ChatC extends ChatMessageCheck {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    public ChatC(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onChatMessage(PacketReceiveEvent<ServerboundChat> event, CultPlayer player, ServerboundChat packet) {
        check(packet.message(), false, event);
    }

    @CultPacketHandler
    public void onChatCommandUnsigned(
            PacketReceiveEvent<ServerboundChatCommandSigned> event,
            CultPlayer player,
            ServerboundChatCommandSigned packet) {
        check(packet.command(), true, event);
    }

    @CultPacketHandler
    public void onChatCommand(
            PacketReceiveEvent<ServerboundChatCommand> event, CultPlayer player, ServerboundChatCommand packet) {
        check(packet.command(), true, event);
    }

    private void check(String text, boolean command, PacketReceiveEvent event) {
        boolean sprinting = MultiActionsC.isVerboseSprinting(player);
        boolean input = MultiActionsC.isVerboseInput(player);
        if ((sprinting || input)
                && !(command ? matchesExemptCommand(text) : matchesExemptMessage(text))
                && flag(V.write(verbose()).bool(sprinting).bool(false).bool(input))
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
