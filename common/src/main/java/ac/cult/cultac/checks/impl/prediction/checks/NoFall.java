package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassAfterTravel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRootGround;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;

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

        if (result.getSimulationContext().getVehicle() != null) {
            handleVehicleResult(player, result);
            return;
        }

        boolean flaggedNoFall = false;
        final NoFallPseudo noFallPseudo = player.checkManager.getListener(NoFallPseudo.class);

        // From 26.3 the block-entity pass after the travel can hold the packet's last Entity#move, which sets onGround.
        JavaPassAfterTravel pass = JavaMovementEngine.passAfterTravel(result);
        boolean passEndsMove = pass != null && pass.result().moved();

        // A forward move's Entity#move set onGround to its own verticalCollisionBelow (1.21.11 Entity.java:739-741),
        // and sendPosition reports it. Before 1.21.2 the packet can close several unsent ticks.
        JavaMoveCollision forward = result.getCollideAxisData() instanceof JavaMoveCollision move
                        && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                ? move
                : null;

        SimpleCollisionBox valid = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        double stuckSpeedY = result.getSimulationContext().getLastStuckSpeed().y;
        // Every Entity#move sets onGround from its own collision, so the travel lands by its own displacement:
        // the reported movement minus the position-only shove.
        double travelY = result.getTarget().y - result.getPositionOnlyDelta().y / stuckSpeedY;
        boolean desiredOnGround = canCollideDown;

        if (passEndsMove) {
            boolean reported = result.getSimulationContext().isOnGround();
            if (reported != pass.result().state().onGround()) {
                result.addFlag(noFallPseudo, () -> String.valueOf(reported), 0.1);
                flaggedNoFall = true;
            }
        } else if (forward != null) {
            boolean reported = result.getSimulationContext().isOnGround();
            if (reported != forward.verticalCollisionBelow()) {
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
                && !passEndsMove
                && forward == null
                && canCollideDown
                && valid.maxY + 0.001 < travelY // And their valid Y is below their actual movement
                && (downCollide.inMovementSpace(stuckSpeedY) + 0.001 >= travelY // And they recovered by colliding
                        || stepsUpFromDownwardMove(result, valid))) {
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
            result.setDesiredOnGround(passEndsMove
                    ? pass.result().state().onGround()
                    : forward != null ? forward.verticalCollisionBelow() : desiredOnGround);
        }
    }

    /**
     * From 1.21.4 ServerboundMoveVehiclePacket#fromEntity reports the root's Entity#onGround after its tick, and
     * ServerGamePacketListenerImpl#handleMoveVehicle applies it to the root's ground and fall damage.
     */
    private static void handleVehicleResult(CultPlayer player, PredictionResult result) {
        if (!player.packetStateData.vehicleMovementOnGroundPresent) return;
        Boolean moved = JavaRootGround.after(player, result);
        if (moved == null) return;
        boolean reported = player.packetStateData.vehicleMovementReportedOnGround;
        if (reported == moved) return;
        result.addFlag(player.checkManager.getListener(NoFallPseudo.class), () -> String.valueOf(reported), 0.1);
        result.setDesiredOnGround(moved);
    }

    /**
     * A travel above the floor it could land on, and above every Y the player requested, that only Entity#collide's
     * step-up explains. Every requested Y is downward, so the step follows a collision that stopped the downward move:
     * Entity#move sets verticalCollisionBelow, then onGround, and Entity#checkFallDamage lands the fall. A client that
     * reports this move airborne skips that landing on the server, which then resets the fall for the upward move.
     */
    private static boolean stepsUpFromDownwardMove(PredictionResult result, SimpleCollisionBox valid) {
        return result.getValidMovements().isCanStep() && valid.maxY < 0.0D;
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
