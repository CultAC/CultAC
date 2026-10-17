package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsC;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommandSigned;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@CheckData(
        name = "ChatC",
        stableKey = "cult.chat.moving_while_chatting",
        description = "Moving while chatting",
        experimental = true)
public class ChatC extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    public ChatC(CultPlayer player) {
        super(player);
    }

    // optionally allow cheats like autogg
    private @Nullable Predicate<String> exemptRegex;

    @CultPacketHandler
    public void onChatMessage(PacketReceiveEvent<ServerboundChat> event, CultPlayer player, ServerboundChat packet) {
        check(packet.message(), event);
    }

    @CultPacketHandler
    public void onChatCommandUnsigned(
            PacketReceiveEvent<ServerboundChatCommandSigned> event,
            CultPlayer player,
            ServerboundChatCommandSigned packet) {
        check("/" + packet.command(), event);
    }

    @CultPacketHandler
    public void onChatCommand(
            PacketReceiveEvent<ServerboundChatCommand> event, CultPlayer player, ServerboundChatCommand packet) {
        check("/" + packet.command(), event);
    }

    private void check(String message, PacketReceiveEvent event) {
        if (exemptRegex != null && exemptRegex.test(message)) {
            return;
        }

        boolean sprinting = MultiActionsC.isVerboseSprinting(player);
        boolean input = MultiActionsC.isVerboseInput(player);
        if ((sprinting || input)
                && flag(V.write(verbose()).bool(sprinting).bool(false).bool(input))
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        String regexString = config.getStringElse(getConfigName() + ".exempt-regex", null);
        exemptRegex = regexString == null ? null : Pattern.compile(regexString).asMatchPredicate();
    }
}
