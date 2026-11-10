package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.Friction;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.Collections;

/** The end of a tick a safe position and a correction both simulate. */
public final class SetbackPhysics {
    private SetbackPhysics() {}

    public static Vec3 simulateFriction(
            CultPlayer player,
            Vec3 input,
            boolean water,
            boolean lava,
            boolean gliding,
            boolean wasOnGround,
            boolean stuckSpeed,
            SimulationContext correctionContextForElytra) {
        double gravity = 0.08D;
        final boolean noGravity = !player.compensatedEntities.getEntityInControl().hasGravity;
        final boolean slowFalling = input.y < 0 && player.compensatedEntities.getSlowFallingAmplifier() != null;
        final boolean ridingBoat = player.compensatedEntities.getSelf().inVehicle()
                && player.compensatedEntities.getSelf().getRiding().isBoat();
        if (noGravity) {
            gravity = 0.0D;
        } else if (slowFalling) {
            gravity = 0.01D;
        } else if (ridingBoat) {
            gravity = 0.04D;
        }

        // We must always do this before simulating positions, as this is the last actual (safe) movement
        // We must not do this for knockback or explosions, as they are at the start of the tick
        Vec3 simulated;
        if (stuckSpeed) {
            simulated = new Vec3(0, -gravity, 0);
        } else if (water) {
            simulated = input.scale(0.8f).subtract(0.0D, gravity / 16.0f, 0.0D);
        } else if (lava) {
            simulated = input.scale(0.5D).subtract(0.0D, gravity / 4.0D, 0.0D);
        } else if (gliding && correctionContextForElytra != null) {
            // Route Java safe state capture already ran ElytraTransform, setbacks and bedrock must run it
            simulated = new ElytraTransform().transformElytra(player, correctionContextForElytra, input, gravity);
        } else if (gliding) {
            simulated = input.multiply(0.99F, 0.98F, 0.99F);
        } else { // Gliding doesn't have friction, we handle it differently
            final double horizFriction = wasOnGround ? 0.91F * 0.6F : 0.91F;
            simulated = new Vec3(input.x, Friction.applyAirGravity(player, input.y, gravity), input.z)
                    .multiply(horizFriction, 0.98f, horizFriction);
        }

        // stop 1.8 players from stepping onto 1.25 high blocks, because why not?
        return VelocityTransformer.applyMovementThreshold(new PredVector(simulated), player.getClientVersion(), true);
    }

    /** Whether one tick of {@code velocity} from {@code position} leaves the player where it is. */
    public static boolean barelyMoves(CultPlayer player, Vec3 position, Vec3 velocity) {
        SimpleCollisionBox oldBB = player.boundingBox;
        try {
            player.boundingBox = GetBoundingBox.getPlayerBoundingBox(player, position.x, position.y, position.z);
            Vec3 moved = VelocityTransformer.applyMovementThreshold(
                            Collections.singletonList(new PredVector(velocity)), player.getClientVersion())
                    .get(0);
            return Collisions.collide(player, moved.x, moved.y, moved.z).lengthSqr() < 0.001 * 0.001;
        } finally {
            player.boundingBox = oldBB;
        }
    }
}
