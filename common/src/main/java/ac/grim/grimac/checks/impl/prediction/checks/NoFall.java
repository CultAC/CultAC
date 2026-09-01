package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.bedrock.prediction.BedrockPredictionResult;
import ac.grim.grimac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.NumFormatter;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.CollideAxisData;

//@CheckData(name = "NoFall")
public class NoFall implements EngineCheck {
    private static final VanillaStepProof STEP_PROOF = new VanillaStepProof();

    @Override
    public void handleResult(GrimPlayer player, PredictionResult result, PredictionResult lastResult) {
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult != null) {
            handleBedrockResult(player, result, bedrockResult.verticalCollisionVerdict());
            return;
        }

        CollideAxisData.CollideResult downCollide = result.getCollideAxisData().getYNeg();
        boolean canCollideDown = downCollide != null && downCollide.isLikelyCollide();

        if (result.getSimulationContext().getVehicle() != null) return;

        boolean flaggedNoFall = false;
        final NoFallPseudo noFallPseudo = player.checkManager.getListener(NoFallPseudo.class);

        if (!canCollideDown
                && result.getSimulationContext().isOnGround()
                && !STEP_PROOF.canStandAtDestination(player, result)) {
            result.addFlag(player.checkManager.getListener(NoFallPseudo.class), () -> "true", 0.1);
            flaggedNoFall = true;
        }

        SimpleCollisionBox valid = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        // The player is off the ground and can collide to the ground
        if (!result.getSimulationContext().isOnGround() && downCollide != null && downCollide.isLikelyCollide()
                && valid.maxY + 0.001 < result.getTarget().y // And their valid Y is below their actual movement
                && downCollide.getResult() + 0.001 >= result.getTarget().y) { // And they recovered by colliding
            result.addFlag(noFallPseudo, () -> "false", 0.1);
            flaggedNoFall = true;
        }

        double validY = result.getAcceptedClosestToTarget().y;
        double targetY = result.getTarget().y;
        // > 0 resets fall damage, we must protect against this
        if (validY <= 0 && targetY > 0 && !result.getSimulationContext().isOnGround()) {
            result.addFlag(noFallPseudo, () -> NumFormatter.formatNumberStandard(targetY) + " > " + NumFormatter.formatNumberStandard(validY), 0.1);
            flaggedNoFall = true;
        }

        if (flaggedNoFall) {
            result.setDesiredOnGround(canCollideDown);
        }
    }

    private static void handleBedrockResult(
            GrimPlayer player,
            PredictionResult result,
            BedrockVerticalCollisionVerdict verdict
    ) {
        if (verdict == BedrockVerticalCollisionVerdict.MANUFACTURED_COLLISION) {
            result.addFlag(
                    player.checkManager.getListener(NoFallPseudo.class),
                    () -> "manufactured_collision",
                    0.1D);
        } else if (verdict == BedrockVerticalCollisionVerdict.MANUFACTURED_NON_COLLISION) {
            result.addFlag(
                    player.checkManager.getListener(NoFallPseudo.class),
                    () -> "manufactured_non_collision",
                    0.1D);
        }
    }
}
