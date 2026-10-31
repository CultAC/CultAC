package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(name = "CrashC", stableKey = "cult.crash.nan_position", description = "Sent non-finite position or rotation")
public class CrashC extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("xyzYP={f64}, {f64}, {f64}, {f32}, {f32}");

    public CrashC(CultPlayer player) {
        super(player);
    }

    // Registered as an early receive check: a rejected packet must not reach CheckManagerListener,
    // which writes the rotation and starts the client tick before pre-prediction checks run.
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        boolean finitePosition = !packet.hasPosition()
                || (Double.isFinite(packet.x()) && Double.isFinite(packet.y()) && Double.isFinite(packet.z()));
        boolean finiteRotation =
                !packet.hasRotation() || (Float.isFinite(packet.yaw()) && Float.isFinite(packet.pitch()));
        if (finitePosition && finiteRotation) return;
        flag(V.write(verbose())
                .f64(packet.xOr(0))
                .f64(packet.yOr(0))
                .f64(packet.zOr(0))
                .f32(packet.yawOr(0))
                .f32(packet.pitchOr(0)));
        executeViolationSetback();
        event.setCancelled(true);
        player.onPacketCancel();
    }
}
