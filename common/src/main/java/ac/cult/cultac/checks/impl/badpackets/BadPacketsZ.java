package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;

@CheckData(name = "BadPacketsZ", stableKey = "cult.badpackets.duplicate_player_input", description = "Sent duplicate player input packets in the same client tick", experimental = true)
public class BadPacketsZ extends Check implements CheckListener {
    private boolean sent;

    public BadPacketsZ(CultPlayer player) {
        super(player);
    }

    // 26.x-only class: string form so the handler silently drops on servers without it
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        sent = false;
    }

    @CultPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerInputPacket packet) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) {
            return;
        }
        if (sent) {
            flag();
        }

        sent = true;
    }
}
