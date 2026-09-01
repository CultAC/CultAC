package ac.grim.grimac.checks.impl.chat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.multiactions.MultiActionsC;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;
import java.util.regex.Pattern;

@CheckData(name = "ChatC", stableKey = "grim.chat.moving_while_chatting", description = "Moving while chatting", experimental = true)
public class ChatC extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    public ChatC(GrimPlayer player) {
        super(player);
    }

    // optionally allow cheats like autogg
    private @Nullable Predicate<String> exemptRegex;


    @GrimPacketHandler
    public void onChatMessage(PacketReceiveEvent event, GrimPlayer player, ServerboundChatPacket packet) {
        check(packet.message(), event);
    }

    @GrimPacketHandler
    public void onChatCommandUnsigned(PacketReceiveEvent event, GrimPlayer player, ServerboundChatCommandSignedPacket packet) {
        check("/" + packet.command(), event);
    }

    @GrimPacketHandler
    public void onChatCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundChatCommandPacket packet) {
        check("/" + packet.command(), event);
    }

    private void check(String message, PacketReceiveEvent event) {
        if (exemptRegex != null && exemptRegex.test(message)) {
            return;
        }

        boolean sprinting = MultiActionsC.isVerboseSprinting(player);
        boolean sneaking = MultiActionsC.isVerboseSneaking(player);
        boolean input = MultiActionsC.isVerboseInput(player);
        if ((sprinting || sneaking || input)
                && flag(V.write(verbose()).bool(sprinting).bool(sneaking).bool(input))
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
