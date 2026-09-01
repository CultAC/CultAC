package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

@CheckData(name = "CrashH", stableKey = "grim.crash.invalid_tab_complete", description = "Sent a tab complete request with invalid or excessive length")
public class CrashH extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("[(length)|(invalid)] length={sint}");

    public CrashH(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onCommandSuggestion(PacketReceiveEvent event, GrimPlayer player, ServerboundCommandSuggestionPacket packet) {
        String text = packet.getCommand();
        final int length = text.length();
        // general length limit
        if (length > (!player.canUseGameMasterBlocks() ? 256 : 32500)) {
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            flag(V.write(verbose()).bool(true).sint(length));
            return;
        }
        // paper's patch
        final int index;
        if (length > 64 && ((index = text.indexOf(' ')) == -1 || index >= 64)) {
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            flag(V.write(verbose()).bool(false).sint(length));
        }
    }
}
