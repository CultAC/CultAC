package ac.cult.cultac.checks.impl.vehicle;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.storage.verbose.Verbose;

/**
 * The onGround bit ServerboundMoveVehiclePacket#fromEntity copies from the locally controlled root. The engine carries
 * the collision result in place of a claim it disproves, and the packet reaches the server with that result, so this
 * only flags.
 */
@CheckData(
        name = "VehicleGround",
        stableKey = "cult.vehicle.ground_spoof",
        description = "Reported an impossible vehicle ground state",
        setback = 10,
        decay = 0.01)
public final class VehicleGround extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("reported={bool}, predicted=[true|false]");

    public VehicleGround(CultPlayer player) {
        super(player);
    }

    /** Whether a definite collision result disproves the reported onGround. */
    public static boolean disproves(boolean reported, DesyncStatus predicted) {
        return !predicted.isDesync() && !predicted.is(reported);
    }

    public void onGroundClaim(boolean reported, DesyncStatus predicted) {
        if (predicted.isDesync()) return;
        if (!disproves(reported, predicted)) {
            reward();
            return;
        }
        if (flag(V.write(verbose()).bool(reported).bool(predicted == DesyncStatus.TRUE))) {
            setbackIfAboveSetbackVL();
        }
    }
}
