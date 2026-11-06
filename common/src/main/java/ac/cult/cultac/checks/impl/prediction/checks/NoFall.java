package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;

public class NoFall implements EngineCheck {
    private static final VanillaStepProof STEP_PROOF = new VanillaStepProof();

    @Override
    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
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

        PistonPushes pushes = result.getSimulationContext().getWorldData().getPistonPushes();
        // From 26.3 the shulker box block shoves are the packet's last Entity#move calls, and the last sets onGround.
        boolean shoveMayEndMove =
                pushes != null && !pushes.getShulkerBoxPush().isEmpty() && !pushes.isShulkerBoxShoveBeforeTravel();
        ShulkerBoxShoveResult shoveEndsMove = null;
        if (shoveMayEndMove && pushes.getShulkerBoxShovesAfterTravel() != null) {
            // The prediction's trace holds what the shoves left, or nothing when none reached the player.
            MovementTrace trace = result.getValidMovements().getClosestTrace();
            shoveEndsMove = trace == null ? null : trace.shulkerBoxShove();
            shoveMayEndMove = shoveEndsMove != null;
        }

        SimpleCollisionBox valid = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        double stuckSpeedY = result.getSimulationContext().getLastStuckSpeed().y;
        // Every Entity#move sets onGround from its own collision, so the travel lands by its own displacement:
        // the reported movement minus the position-only shove.
        double travelY = result.getTarget().y - result.getPositionOnlyDelta().y / stuckSpeedY;
        boolean desiredOnGround = canCollideDown;

        if (shoveEndsMove != null) {
            boolean reported = result.getSimulationContext().isOnGround();
            if (reported != shoveEndsMove.onGround()) {
                result.addFlag(noFallPseudo, () -> String.valueOf(reported), 0.1);
                flaggedNoFall = true;
            }
        } else if (result.getSimulationContext().isOnGround()
                && !endsOnFloor(downCollide, travelY, stuckSpeedY)
                && !STEP_PROOF.canStandAtDestination(player, result)) {
            result.addFlag(noFallPseudo, () -> "true", 0.1);
            flaggedNoFall = true;
            desiredOnGround = false;
        }

        // The player is off the ground and can collide to the ground
        if (!result.getSimulationContext().isOnGround()
                && !shoveMayEndMove
                && canCollideDown
                && valid.maxY + 0.001 < travelY // And their valid Y is below their actual movement
                && downCollide.inMovementSpace(stuckSpeedY) + 0.001 >= travelY) { // And they recovered by colliding
            result.addFlag(noFallPseudo, () -> "false", 0.1);
            flaggedNoFall = true;
        }

        double validY = result.getAcceptedClosestToTarget().y;
        double targetY = result.getTarget().y;
        // > 0 resets fall damage, we must protect against this
        if (validY <= 0 && targetY > 0 && !result.getSimulationContext().isOnGround()) {
            result.addFlag(
                    noFallPseudo,
                    () -> NumFormatter.formatNumberStandard(targetY) + " > "
                            + NumFormatter.formatNumberStandard(validY),
                    0.1);
            flaggedNoFall = true;
        }

        if (flaggedNoFall) {
            result.setDesiredOnGround(desiredOnGround);
        }
    }

    /**
     * Entity#move sets onGround only when collision stops a downward move, which leaves the travel on the floor it hit.
     * A travel that ends above the nearest floor the move could reach hit none, however far the move could have gone.
     * The 0.001 is the recovered-fall check's: it holds Entity#move's setPos guard, which keeps the player where it was
     * for a clip within sqrt(1.0E-7), and a position LocalPlayer#sendPosition withheld within 2.0E-4.
     */
    private static boolean endsOnFloor(CollideAxisData.CollideResult downCollide, double travelY, double stuckSpeedY) {
        // The clip and the target are both stored divided by Entity#stuckSpeedMultiplier.
        return downCollide != null
                && downCollide.isLikelyCollide()
                && travelY * stuckSpeedY <= downCollide.inMovementSpace(stuckSpeedY) + 0.001;
    }

    private static void handleBedrockResult(
            CultPlayer player, PredictionResult result, BedrockVerticalCollisionVerdict verdict) {
        if (verdict == BedrockVerticalCollisionVerdict.MANUFACTURED_COLLISION) {
            result.addFlag(player.checkManager.getListener(NoFallPseudo.class), () -> "manufactured_collision", 0.1D);
        } else if (verdict == BedrockVerticalCollisionVerdict.MANUFACTURED_NON_COLLISION) {
            result.addFlag(
                    player.checkManager.getListener(NoFallPseudo.class), () -> "manufactured_non_collision", 0.1D);
        }
    }
}
