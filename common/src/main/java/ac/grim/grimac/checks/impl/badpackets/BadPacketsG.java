package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.DecodedPacketReliability;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "BadPacketsG", stableKey = "grim.badpackets.duplicate_sneak", description = "Sent duplicate sneaking status")
public class BadPacketsG extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("state={bool}");

    private boolean lastSneaking, respawn;

    public BadPacketsG(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {

        NmsPacketUtil.PlayerCommandAction action = NmsPacketUtil.readPlayerCommand(packet).action();

        if ((action == NmsPacketUtil.PlayerCommandAction.PRESS_SHIFT_KEY
                || action == NmsPacketUtil.PlayerCommandAction.RELEASE_SHIFT_KEY)
                && !DecodedPacketReliability.nativeInputFamilyReliable(player.getClientVersion())) {
            return;
        }

        if (action == NmsPacketUtil.PlayerCommandAction.PRESS_SHIFT_KEY) {
            if (handleLegacySneak(true)) {
                event.setCancelled(true);
            }
        } else if (action == NmsPacketUtil.PlayerCommandAction.RELEASE_SHIFT_KEY) {
            if (handleLegacySneak(false)) {
                event.setCancelled(true);
            }
        }
    }

    public boolean handleLegacySneak(boolean sneaking) {
        boolean rejected = false;
        // The player may send two START_SNEAKING packets if they respawned.
        if (lastSneaking == sneaking && !respawn) {
            if (flag(V.write(verbose()).bool(sneaking)) && shouldModifyPackets()) {
                player.onPacketCancel();
                rejected = true;
            }
        } else {
            lastSneaking = sneaking;
        }
        respawn = false;
        return rejected;
    }

    public void handleRespawn() {
        // Clients could potentially not send a STOP_SNEAKING packet when they die, so we need to track it
        respawn = true;
    }
}
