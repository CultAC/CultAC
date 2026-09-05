package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "BadPacketsG", stableKey = "cult.badpackets.duplicate_sneak", description = "Sent duplicate sneaking status")
public class BadPacketsG extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("state={bool}");

    private boolean lastSneaking, respawn;

    public BadPacketsG(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {

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
