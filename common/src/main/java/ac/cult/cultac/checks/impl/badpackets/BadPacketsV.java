package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.data.SentPositionCache;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsV",
        stableKey = "cult.badpackets.slow_move",
        description = "Sent a position update without moving, before the 20-tick position reminder was due",
        experimental = true)
public class BadPacketsV extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("delta={f64}");

    public BadPacketsV(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        // Neither the pre-26.3 PosRot echo nor the 26.3 acceptance writes LocalPlayer#xLast.
        if (event.answersTeleport() || !packet.hasPosition()) return;

        int positionAtLeastEveryNTicks = 19;
        SentPositionCache sent = player.packetStateData.sentPositionCache;
        if (sent.ticksWithoutPositionBeforeSent() < positionAtLeastEveryNTicks
                && sent.sentDistanceSquared() <= player.getMovementThreshold() * player.getMovementThreshold()) {
            double delta = Math.sqrt(sent.sentDistanceSquared());
            flag(V.write(verbose()).f64(delta));
        }
    }
}
