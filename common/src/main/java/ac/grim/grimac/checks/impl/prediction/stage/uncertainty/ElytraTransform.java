package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;
import org.bukkit.util.Vector;

public class ElytraTransform implements UncertaintyHandler {
    @Override
    public MovementTrace handleMovementTrace(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, MovementTrace trace, Vec3 end) {
        PredVector velocity = handleUncertainty(player, valid, result, context, lastResult, trace.position(), end);
        // LivingEntity#travelFallFlying stores this velocity before Entity#move.
        // Collision clipping changes displacement; restitution reads the stored velocity.
        return trace.withPosition(velocity).withPreCollisionVelocity(velocity);
    }

    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        if (!result.getSimulationContext().usesFallFlyingMovement()) return start;
        if (result.getSimulationContext().getWorldData().mustBeInLiquid()) return start;

        SimpleCollisionBox startingHorizontalUncertainty = valid.getCollisionIgnoredMaxStartingVelExtents();
        double uncertainty = startingHorizontalUncertainty == null ? 0 : Math.hypot(startingHorizontalUncertainty.maxX - startingHorizontalUncertainty.minX, startingHorizontalUncertainty.maxZ - startingHorizontalUncertainty.minZ);
        double yUncertainty = uncertainty * 0.04 * 3.2; // Exact values, we could also look at Y

        // LivingEntity#aiStep applies bubble callbacks after travel. Their existing
        // Y range is therefore input to the next tick's elytra transform, including X/Z.
        PredVector minStart = start, maxStart = start;
        if (BubbleColumn.beforeElytra(context) && !start.hasReason("Velocity")) {
            minStart = BubbleColumn.apply(context, lastResult, start, Double.NEGATIVE_INFINITY);
            maxStart = BubbleColumn.apply(context, lastResult, start, Double.POSITIVE_INFINITY);
        }
        SimpleCollisionBox allowed = null;

        // Don't require the player to have elytra if they MIGHT be in water
        if (context.getWorldData().maybeInLiquid()) {
            allowed = new SimpleCollisionBox(minStart, maxStart);
        }

        for (int loopFastmath = 0; loopFastmath < 2; loopFastmath++) {
            for (double gravity : result.getPossibleGravity()) {
                Vec3 elytraResult = transformElytra(player, context, minStart, gravity);
                Vec3 maxElytraResult = minStart.y == maxStart.y ? elytraResult : transformElytra(player, context, maxStart, gravity);
                if (allowed == null) {
                    allowed = new SimpleCollisionBox(elytraResult, maxElytraResult).sort();
                } else {
                    allowed = allowed.union(new SimpleCollisionBox(elytraResult, maxElytraResult).sort());
                }
            }
            player.trigHandler.toggleShitMath();
        }
        player.trigHandler.toggleShitMath();


        assert allowed != null;
        allowed.expand(0, yUncertainty, 0);

        Vec3 cut = VectorUtils.cutBoxToVector(end, allowed);
        return start.withXYZ(cut.x, cut.y, cut.z, "elytra");
    }

    public Vec3 transformElytra(GrimPlayer player, SimulationContext context, Vec3 start, double gravity) {
        // MCP-Reborn LivingEntity#updateFallFlyingMovement reads getLookAngle()
        // and getXRot() from the entity state being ticked. In Grim that state
        // is the packet-local SimulationContext, not mutable GrimPlayer fields
        // that may already have advanced while candidate vectors are replayed.
        Vector currentLook = ReachUtils.getLook(player, context.getXRot(), context.getYRot());
        return getElytraMovement(player, start, currentLook, context.getYRot(), gravity);
    }

    public static Vec3 getElytraMovement(GrimPlayer player, Vec3 vector, Vector lookVector, float pitch, double gravity) {
        float yRotRadians = pitch * 0.017453292F;
        double horizontalLookLength = Math.sqrt(lookVector.getX() * lookVector.getX() + lookVector.getZ() * lookVector.getZ());
        double velocityHorizLength = new Vec3(vector.x, 0, vector.z).length();
        double length = lookVector.length();

        // Mojang changed from using their math to using regular java math in 1.18.2 elytra movement
        double vertCosRotation = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_18_2) ? Math.cos(yRotRadians) : player.trigHandler.cos(yRotRadians);
        // Modern clients retain this intermediate as a double. Casting it back
        // to float here produces measurable 1.20.2+ glide drift.
        vertCosRotation = vertCosRotation * vertCosRotation * Math.min(1.0D, length / 0.4D);

        vector = vector.add(new Vec3(0.0D, gravity * (-1.0D + vertCosRotation * 0.75D), 0.0D));

        // Handle slowing the player down when falling
        if (vector.y < 0.0D && horizontalLookLength > 0.0D) {
            double d5 = vector.y * -0.1D * vertCosRotation;
            vector = vector.add(new Vec3(lookVector.getX() * d5 / horizontalLookLength, d5, lookVector.getZ() * d5 / horizontalLookLength));
        }

        // Handle accelerating the player when they are looking down
        if (yRotRadians < 0.0F && horizontalLookLength > 0.0D) {
            double d5 = velocityHorizLength * (double) (-player.trigHandler.sin(yRotRadians)) * 0.04D;
            vector = vector.add(new Vec3(-lookVector.getX() * d5 / horizontalLookLength, d5 * 3.2D, -lookVector.getZ() * d5 / horizontalLookLength));
        }

        // Handle accelerating the player sideways
        if (horizontalLookLength > 0) {
            vector = vector.add(new Vec3((lookVector.getX() / horizontalLookLength * velocityHorizLength - vector.x) * 0.1D, 0.0D, (lookVector.getZ() / horizontalLookLength * velocityHorizLength - vector.z) * 0.1D));
        }

        return vector.multiply(0.99F, 0.98F, 0.99F);
    }
}
