package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;

/**
 * The controlled root's Entity#onGround after its tick. The root's travel set it with Entity#move, and a strider's
 * Strider#floatStrider can then set it with Entity#setOnGround(true). From 26.3 the block-entity pass after that starts
 * from this ground, and its own Entity#move replaces it. A 1.21.5 ridden travel that cannot simulate runs no
 * Entity#move and keeps the ground it started with, until floatStrider.
 */
public final class JavaRootGround {
    private JavaRootGround() {}

    /** Null when the result holds no forward Entity#move. */
    public static Boolean after(CultPlayer player, PredictionResult result) {
        JavaPassAfterTravel pass = JavaMovementEngine.passAfterTravel(result);
        if (pass != null) return pass.result().moved() ? pass.result().state().onGround() : pass.travel().onGround();
        boolean travel;
        if (JavaRiddenTravel.clearsWithoutTravel(result.getSimulationContext()))
            travel = player.packetStateData.vehicleMovementStartOnGround;
        else if (result.getCollideAxisData() instanceof JavaMoveCollision forward)
            travel = forward.verticalCollisionBelow();
        else return null;
        return travel || JavaStriderFloat.grounds(player, result);
    }
}
