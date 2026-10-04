package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsG",
        stableKey = "cult.badpackets.duplicate_sneak",
        description = "Sent duplicate sneaking status")
public class BadPacketsG extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("state={bool}");

    private boolean lastSneaking, respawn;

    public BadPacketsG(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        PlayerCommandAction action = packet.action();

        if ((action == PlayerCommandAction.PRESS_SHIFT_KEY || action == PlayerCommandAction.RELEASE_SHIFT_KEY)
                && !DecodedPacketReliability.nativeInputFamilyReliable(
                        player.getClientVersion(), player.getObservedProtocol())) {
            return;
        }

        if (action == PlayerCommandAction.PRESS_SHIFT_KEY) {
            if (handleLegacySneak(true)) {
                event.setCancelled(true);
            }
        } else if (action == PlayerCommandAction.RELEASE_SHIFT_KEY) {
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
