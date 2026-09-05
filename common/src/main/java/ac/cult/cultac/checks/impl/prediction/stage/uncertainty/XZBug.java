package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;

public class XZBug implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_14) || player.getClientVersion().isNewerThan(ClientVersion.V_1_18_2) || lastResult == null) {
            return start;
        }
        if (lastResult.getCollideAxisData().getX().isLikelyCollide() && lastResult.getCollideAxisData().getZ().isLikelyCollide()) {
            // We add velocity mostly when on ground, not off ground
            DesyncStatus lastOnGround = result.getSimulationContext().getLastOnGround();
            result.getSimulationContext().getWorldData().setLastOnGround(DesyncStatus.UNKNOWN);
            double sprintingMovementHidden = result.getSimulationContext().getMaxSpeed(player) * 3;
            result.getSimulationContext().getWorldData().setLastOnGround(lastOnGround);

            start = start.withX(CultMath.clamp(end.x, start.x - sprintingMovementHidden, start.x + sprintingMovementHidden), "XZ bug");
        }
        return start;
    }
}
