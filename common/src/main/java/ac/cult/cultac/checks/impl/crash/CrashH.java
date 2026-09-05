package ac.cult.cultac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

@CheckData(name = "CrashH", stableKey = "cult.crash.invalid_tab_complete", description = "Sent a tab complete request with invalid or excessive length")
public class CrashH extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("[(length)|(invalid)] length={sint}");

    public CrashH(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onCommandSuggestion(PacketReceiveEvent event, CultPlayer player, ServerboundCommandSuggestionPacket packet) {
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
