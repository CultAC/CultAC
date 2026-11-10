package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Ladder;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import java.util.ArrayList;
import java.util.List;

/**
 * From 26.3 the block-entity pass between a travel and its report can leave an axis where it leaves it whatever the
 * travel did on it (a {@link JavaBlockEntityPass.Result} fixed axis), so the report does not show that axis of the
 * travel's LivingEntity#moveRelative input. LocalPlayer#sendChanges sent the keys that input came from in the
 * PLAYER_INPUT before the position, so the travel carries the input those keys give on that axis, with the collision
 * the travel's Entity#move meets there.
 */
public final class JavaHiddenTravelInput {
    private JavaHiddenTravelInput() {}

    private static final double SAME_INPUT = 1.0E-7D;

    /** A travel input and whether Entity#collide clipped the move on X and on Z. */
    public record Variant(Vec3 input, boolean fixedX, boolean collidedX, boolean fixedZ, boolean collidedZ) {}

    /**
     * The inputs the selected travel may have taken given {@code accepted} on the axes the report shows, or null when
     * the pass after it hides no horizontal axis or the keys' input is undetermined. Of the speeds the keys may have
     * moved at, those whose input on a shown axis is nearest the accepted input remain.
     */
    public static List<Variant> variants(CultPlayer player, PredictionResult result, Vec3 accepted) {
        JavaPassAfterTravel pass = JavaMovementEngine.passAfterTravel(result);
        if (pass == null || player == null) return null;
        // A candidate holding its exact input travelled by it; the pass hides nothing the engine still has to choose.
        // A ridden root's LivingEntity#travelRidden ran its one moveRelative with the ridden input that
        // VelocityTransformer/BoatTransform already added, as in JavaPassAfterTravel#select.
        if (JavaTravelInputs.isExact(result.getInitialStartingVel())
                || result.getSimulationContext().getVehicle() != null) return null;
        boolean fixedX = pass.result().fixedX() || pass.travelBackedOffX();
        boolean fixedZ = pass.result().fixedZ() || pass.travelBackedOffZ();
        if (!fixedX && !fixedZ) return null;
        SimulationContext context = result.getSimulationContext();
        List<Vec3> keys = ClientKeyInput.travelInputs(player, context, result.getInitialStartingVel());
        if (keys.isEmpty()) return null;

        double nearest = Double.POSITIVE_INFINITY;
        for (Vec3 key : keys) nearest = Math.min(nearest, shownDistance(key, accepted, fixedX, fixedZ));
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        List<Variant> variants = new ArrayList<>(keys.size());
        for (Vec3 key : keys) {
            if (shownDistance(key, accepted, fixedX, fixedZ) > nearest + SAME_INPUT) continue;
            Vec3 input = new Vec3(fixedX ? key.x : accepted.x, 0.0D, fixedZ ? key.z : accepted.z);
            // Entity#move scales the input-inclusive delta by the stuck multiplier before Entity#collide.
            for (boolean climbing : Ladder.clampsDeltaMovement(context).getStates()) {
                Vec3 delta = JavaPassAfterTravel.travelDelta(player, result, trace, input, climbing);
                var move = JavaPassAfterTravel.collideTravel(player, result, delta);
                var variant = new Variant(
                        input,
                        fixedX,
                        !CultMath.equal(move.attempted().x, move.movement().x),
                        fixedZ,
                        !CultMath.equal(move.attempted().z, move.movement().z));
                if (!variants.contains(variant)) variants.add(variant);
            }
        }
        return variants;
    }

    /** How far {@code key} is from {@code accepted} on the axes the report shows. */
    private static double shownDistance(Vec3 key, Vec3 accepted, boolean fixedX, boolean fixedZ) {
        double x = fixedX ? 0.0D : key.x - accepted.x;
        double z = fixedZ ? 0.0D : key.z - accepted.z;
        return Math.hypot(x, z);
    }
}
