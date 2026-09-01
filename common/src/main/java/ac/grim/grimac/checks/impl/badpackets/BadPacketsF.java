package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "BadPacketsF", stableKey = "grim.badpackets.duplicate_sprint", description = "Sent duplicate sprinting status")
public class BadPacketsF extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("state={bool}");

    public boolean lastSprinting;
    public boolean exemptNext = true; // Support 1.14+ clients starting on either true or false sprinting, we don't know

    public BadPacketsF(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
            if (lastSprinting) {
                if (exemptNext) {
                    exemptNext = false;
                    return;
                }
                boolean state = true;
                if (flag(V.write(verbose()).bool(state)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }

            lastSprinting = true;
        } else if (packet.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
            if (!lastSprinting) {
                if (exemptNext) {
                    exemptNext = false;
                    return;
                }
                boolean state = false;
                if (flag(V.write(verbose()).bool(state)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }

            lastSprinting = false;
        }
    }
}
