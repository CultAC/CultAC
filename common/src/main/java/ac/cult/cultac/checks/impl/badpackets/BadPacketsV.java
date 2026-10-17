package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.math.CultMath;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsV",
        stableKey = "cult.badpackets.slow_move",
        description = "Did not move far enough",
        experimental = true)
public class BadPacketsV extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("delta={f64}");

    private int noReminderTicks;
    // LocalPlayer#sendPosition's cache is independent of its position after teleport packets.
    // A new LocalPlayer starts at zero; ClientPacketListener#handleMovePlayer does not write it.
    private double sentX;
    private double sentY;
    private double sentZ;

    public void onNewLocalPlayer() {
        sentX = sentY = sentZ = 0.0;
        noReminderTicks = 0;
    }

    private final LastInstance lastTeleportTicks;

    public BadPacketsV(CultPlayer player) {
        super(player);
        lastTeleportTicks = new LastInstance(player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        // isTickPacket: movement packets count unless they answered a teleport
        if (player.packetStateData.lastPacketWasTeleport) {
            lastTeleportTicks.reset();
            return;
        }

        if (packet.hasPosition()) {
            int positionAtLeastEveryNTicks = 19;

            if (noReminderTicks < positionAtLeastEveryNTicks && !lastTeleportTicks.hasOccurredSince(1)) {
                final double x = packet.xOr(player.x);
                final double y = packet.yOr(player.y);
                final double z = packet.zOr(player.z);
                final double deltaSq =
                        CultMath.square(sentX - x) + CultMath.square(sentY - y) + CultMath.square(sentZ - z);
                if (deltaSq <= player.getMovementThreshold() * player.getMovementThreshold()) {
                    double delta = Math.sqrt(deltaSq);
                    flag(V.write(verbose()).f64(delta));
                }
            }

            sentX = packet.xOr(player.x);
            sentY = packet.yOr(player.y);
            sentZ = packet.zOr(player.z);
            noReminderTicks = 0;
        } else {
            noReminderTicks++;
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (player.packetStateData.receivedMovementThisClientTick) return;

        noReminderTicks++;
    }
}
