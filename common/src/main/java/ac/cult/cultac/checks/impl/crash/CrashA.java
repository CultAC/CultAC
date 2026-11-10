package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;

@CheckData(
        name = "CrashA",
        stableKey = "cult.crash.large_position",
        description = "Sent a position outside the valid world bounds")
public class CrashA extends Check implements CheckListener {
    private static final double HARD_CODED_BORDER = 2.9999999E7D;

    public CrashA(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (event.answersTeleport()) return;
        if (!packet.hasPosition()) return;

        // Y technically is uncapped, but no player will reach these values legit
        if (Math.abs(packet.xOr(0)) > HARD_CODED_BORDER
                || Math.abs(packet.zOr(0)) > HARD_CODED_BORDER
                || Math.abs(packet.yOr(0)) > Integer.MAX_VALUE) {
            flag(); // Ban
            executeViolationSetback();
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
