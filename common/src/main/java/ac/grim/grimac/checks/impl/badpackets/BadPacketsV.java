package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.LastInstance;
import ac.grim.grimac.utils.math.GrimMath;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "BadPacketsV", stableKey = "grim.badpackets.slow_move", description = "Did not move far enough", experimental = true)
public class BadPacketsV extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("delta={f64}");

    private int noReminderTicks;

    private final LastInstance lastTeleportTicks;

    public BadPacketsV(GrimPlayer player) {
        super(player);
        lastTeleportTicks = new LastInstance(player);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.canSkipTicks()) return;

        // isTickPacket: movement packets count unless they answered a teleport
        if (player.packetStateData.lastPacketWasTeleport) {
            lastTeleportTicks.reset();
            return;
        }

        if (packet instanceof ServerboundMovePlayerPacket.Pos || packet instanceof ServerboundMovePlayerPacket.PosRot) {
            int positionAtLeastEveryNTicks = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8) ? 20 : 19;

            if (noReminderTicks < positionAtLeastEveryNTicks && !lastTeleportTicks.hasOccurredSince(1)) {
                final double x = packet.getX(player.x);
                final double y = packet.getY(player.y);
                final double z = packet.getZ(player.z);
                final double deltaSq = GrimMath.square(player.lastX - x)
                        + GrimMath.square(player.lastY - y)
                        + GrimMath.square(player.lastZ - z);
                if (deltaSq <= player.getMovementThreshold() * player.getMovementThreshold()) {
                    double delta = Math.sqrt(deltaSq);
                    flag(V.write(verbose()).f64(delta));
                }
            }

            noReminderTicks = 0;
        } else {
            noReminderTicks++;
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (player.canSkipTicks()) return;
        if (!player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                || player.packetStateData.receivedMovementThisClientTick) return;

        noReminderTicks++;
    }
}
