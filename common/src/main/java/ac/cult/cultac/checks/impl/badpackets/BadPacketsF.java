package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "BadPacketsF", stableKey = "cult.badpackets.duplicate_sprint", description = "Sent duplicate sprinting status")
public class BadPacketsF extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("state={bool}");

    public boolean lastSprinting;
    public boolean exemptNext = true; // Support 1.14+ clients starting on either true or false sprinting, we don't know

    public BadPacketsF(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
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
