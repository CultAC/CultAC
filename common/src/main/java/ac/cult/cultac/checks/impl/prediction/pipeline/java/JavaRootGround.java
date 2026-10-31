package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;

/**
 * The controlled root's Entity#onGround after its tick. The root's last Entity#move set it: its travel's, or from 26.3
 * the block-entity pass's after it. A 1.21.5 ridden travel that cannot simulate runs no Entity#move and keeps the
 * ground it started with.
 */
public final class JavaRootGround {
    private JavaRootGround() {}

    /** Null when the result holds no forward Entity#move. */
    public static Boolean after(CultPlayer player, PredictionResult result) {
        JavaPassAfterTravel pass = JavaMovementEngine.passAfterTravel(result);
        if (pass != null && pass.result().moved()) return pass.result().state().onGround();
        if (JavaRiddenTravel.clearsWithoutTravel(result.getSimulationContext()))
            return player.packetStateData.vehicleMovementStartOnGround;
        if (result.getCollideAxisData() instanceof JavaMoveCollision forward) return forward.verticalCollisionBelow();
        return null;
    }
}
