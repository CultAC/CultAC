package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.InvalidStep;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.RiptideOffset;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.VerticalOffset;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.NumFormatter;
import net.minecraft.world.phys.Vec3;

// Entity#collide uses collision-derived step heights, not a continuous range.
public class VerticalAnalyzer implements EngineCheck {
    private static final VanillaStepProof VANILLA_STEP_PROOF = new VanillaStepProof();

    public void handleResult(GrimPlayer player, PredictionResult result, PredictionResult lastResult) {
        if (player.packetStateData.invalidRiptideRelease) {
            String reason = player.packetStateData.invalidRiptideReleaseReason;
            result.addFlag(player.checkManager.getListener(RiptideOffset.class), () -> reason, 1);
            player.packetStateData.invalidRiptideRelease = false;
            player.packetStateData.invalidRiptideReleaseReason = "";
        }

        if (result.getSimulationContext().getVehicle() != null) {
            return;
        }

        Vec3 target = result.getTarget();
        Vec3 closest = result.getAcceptedClosestToTarget();

        if (VANILLA_STEP_PROOF.isInvalidStep(player, result)) {
            final InvalidStep invalidStep = player.checkManager.getListener(InvalidStep.class);
            result.addFlag(invalidStep, () -> "", 1);
        }

        if (result.getSimulationContext().usesFallFlyingMovement() && !result.getSimulationContext().getWorldData().mustBeInLiquid()) {
            return;
        }

        // Calculate offset between valid and target vertically, accounting for collisions
        double offset = closest.y - target.y;
        double absOffset = Math.abs(offset);
        if (absOffset > 0.001) {
            final VerticalOffset verticalOffset = player.checkManager.getListener(VerticalOffset.class);
            result.addFlag(verticalOffset, () -> NumFormatter.formatNumberStandard(offset), absOffset);
        }
    }
}
