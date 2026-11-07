package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(name = "CrashC", stableKey = "cult.crash.nan_position", description = "Sent non-finite position or rotation")
public class CrashC extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("xyzYP={f64}, {f64}, {f64}, {f32}, {f32}");

    public CrashC(CultPlayer player) {
        super(player);
    }

    /** CheckManagerListener calls this before teleport matching or last-sent tick state can consume the values. */
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (packet.hasPosition() || packet.hasRotation()) {
            double x = packet.xOr(0);
            double y = packet.yOr(0);
            double z = packet.zOr(0);
            float yaw = packet.yawOr(0);
            float pitch = packet.pitchOr(0);
            if (packet.hasPosition() && (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z))
                    || packet.hasRotation() && (!Float.isFinite(yaw) || !Float.isFinite(pitch))) {
                flag(V.write(verbose()).f64(x).f64(y).f64(z).f32(yaw).f32(pitch));
                executeViolationSetback();
                event.cancelFinally();
                player.onPacketCancel();
            }
        }
    }

    /** MoveVehicle is checked before its coordinates or rotation reach vehicle prediction. */
    public boolean rejectMoveVehicle(PacketReceiveEvent<ServerboundMoveVehicle> event, ServerboundMoveVehicle packet) {
        var position = packet.position();
        double x = position.x(), y = position.y(), z = position.z();
        float yaw = packet.yaw(), pitch = packet.pitch();
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yaw) && Float.isFinite(pitch)) return false;
        flag(V.write(verbose()).f64(x).f64(y).f64(z).f32(yaw).f32(pitch));
        executeViolationSetback();
        event.cancelFinally();
        player.onPacketCancel();
        return true;
    }
}
