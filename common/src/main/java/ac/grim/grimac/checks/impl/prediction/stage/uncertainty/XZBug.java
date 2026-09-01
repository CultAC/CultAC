package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;

public class XZBug implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_14) || player.getClientVersion().isNewerThan(ClientVersion.V_1_18_2) || lastResult == null) {
            return start;
        }
        if (lastResult.getCollideAxisData().getX().isLikelyCollide() && lastResult.getCollideAxisData().getZ().isLikelyCollide()) {
            // We add velocity mostly when on ground, not off ground
            DesyncStatus lastOnGround = result.getSimulationContext().getLastOnGround();
            result.getSimulationContext().getWorldData().setLastOnGround(DesyncStatus.UNKNOWN);
            double sprintingMovementHidden = result.getSimulationContext().getMaxSpeed(player) * 3;
            result.getSimulationContext().getWorldData().setLastOnGround(lastOnGround);

            start = start.withX(GrimMath.clamp(end.x, start.x - sprintingMovementHidden, start.x + sprintingMovementHidden), "XZ bug");
        }
        return start;
    }
}
