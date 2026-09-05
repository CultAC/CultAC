package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.math.CultMath;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "BadPacketsV", stableKey = "cult.badpackets.slow_move", description = "Did not move far enough", experimental = true)
public class BadPacketsV extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("delta={f64}");

    private int noReminderTicks;

    private final LastInstance lastTeleportTicks;

    public BadPacketsV(CultPlayer player) {
        super(player);
        lastTeleportTicks = new LastInstance(player);
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
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
                final double deltaSq = CultMath.square(player.lastX - x)
                        + CultMath.square(player.lastY - y)
                        + CultMath.square(player.lastZ - z);
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
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        if (player.canSkipTicks()) return;
        if (!player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                || player.packetStateData.receivedMovementThisClientTick) return;

        noReminderTicks++;
    }
}
