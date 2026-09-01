package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "CrashC", stableKey = "grim.crash.nan_position", description = "Sent non-finite position or rotation")
public class CrashC extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("xyzYP={f64}, {f64}, {f64}, {f32}, {f32}");

    public CrashC(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (packet.hasPosition()) {
            double x = packet.getX(0);
            double y = packet.getY(0);
            double z = packet.getZ(0);
            float yaw = packet.getYRot(0);
            float pitch = packet.getXRot(0);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                flag(V.write(verbose()).f64(x).f64(y).f64(z).f32(yaw).f32(pitch));
                executeViolationSetback();
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
