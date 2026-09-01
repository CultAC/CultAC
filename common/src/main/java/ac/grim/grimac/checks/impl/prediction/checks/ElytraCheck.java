package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.ElytraPseudo;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.NumFormatter;
import net.minecraft.world.phys.Vec3;

//@CheckData(name = "Elytra")
public class ElytraCheck implements EngineCheck {

    @Override
    public void handleResult(GrimPlayer player, PredictionResult result, PredictionResult lastResult) {
        if (result.getSimulationContext().getVehicle() != null) {
            return;
        }
        if (!result.getSimulationContext().usesFallFlyingMovement() || result.getSimulationContext().getWorldData().mustBeInLiquid()) {
            return;
        }

        Vec3 closest = result.getAcceptedClosestToTarget();
        double offset = closest.distanceTo(result.getTarget());

        // TODO: Make configurable threshold
        if (offset > 0.001) {
            final ElytraPseudo elytraPseudo = player.checkManager.getListener(ElytraPseudo.class);
            result.addFlag(elytraPseudo, () -> NumFormatter.formatNumberStandard(offset), offset);
        }
    }
}
