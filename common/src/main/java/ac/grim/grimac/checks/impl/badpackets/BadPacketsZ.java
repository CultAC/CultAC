package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;

@CheckData(name = "BadPacketsZ", stableKey = "grim.badpackets.duplicate_player_input", description = "Sent duplicate player input packets in the same client tick", experimental = true)
public class BadPacketsZ extends Check implements CheckListener {
    private boolean sent;

    public BadPacketsZ(GrimPlayer player) {
        super(player);
    }

    // 26.x-only class: string form so the handler silently drops on servers without it
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        sent = false;
    }

    @GrimPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerInputPacket packet) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) {
            return;
        }
        if (sent) {
            flag();
        }

        sent = true;
    }
}
