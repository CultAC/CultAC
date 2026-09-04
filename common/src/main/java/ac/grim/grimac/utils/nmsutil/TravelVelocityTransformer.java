package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TravelVelocityTransformer {
    private TravelVelocityTransformer() {
    }

    public static List<Vec3> transform(GrimPlayer player, boolean onGround, Vec3 from, Vec3 playerVelocity, PredictionResult result, float additionalBlockFriction) {
        // Boat friction is special, not handled here
        if (result.getSimulationContext().getVehicle() != null && EntityTypeUtil.isBoat(result.getSimulationContext().getVehicle().type)) {
            // edge case with teleporting to top of water
            if (player.boatData.nullifyNextY) {
                return Collections.singletonList(new Vec3(playerVelocity.x, 0, playerVelocity.z));
            }
            return Collections.singletonList(playerVelocity);
        }

        // There isn't friction or gravity when gliding
        List<Vec3> frictions = new ArrayList<>();

        for (boolean inWater : result.getSimulationContext().getWorldData().getInWater().getStates()) {
            for (boolean inLava : result.getSimulationContext().getWorldData().getInLava().getStates()) {
                for (Vec3 movedVelocity : preTravelRootVelocities(player, result, playerVelocity)) {
                    // Entity#move applies horizontal collision zeroing and then
                    // getBlockSpeedFactor before LivingEntity#travel applies air/water friction.
                    movedVelocity = movedVelocity.multiply(additionalBlockFriction, 1, additionalBlockFriction);

                    // Water/lava overrides gliding status
                    if (result.getSimulationContext().usesFallFlyingMovement() && !inWater && !inLava) {
                        frictions.add(movedVelocity);
                        continue;
                    }

                    // LivingEntity#getEffectiveGravity uses stored deltaMovement,
                    // not the collision-clipped packet displacement. This matters
                    // when a slow-falling player hits a ceiling.
                    boolean falling = movedVelocity.y <= 0.0D;
                    double grav = player.compensatedEntities.getEntityInControl().gravity;
                    if (falling && result.getSimulationContext().getEntities().getSlowFallingAmplifier() != null) {
                        grav = Math.min(grav, 0.01D);
                    }
                    if (!player.compensatedEntities.getEntityInControl().hasGravity) grav = 0;
                    frictions.addAll(Friction.applyTravelDrag(player, onGround, from, movedVelocity, result.getSimulationContext(), falling, grav, inWater, inLava));
                }
            }
        }

        // MCP-Reborn Strider#floatStrider only rewrites deltaMovement from the
        // post-travel Strider#tick path while the strider is actually in lava.
        if (!ac.grim.grimac.checks.impl.prediction.pipeline.java.JavaMovementEngine.contextUsesExactEffects(result.getSimulationContext())
                && result.getSimulationContext().getVehicle() != null
                && result.getSimulationContext().getVehicle().type == EntityTypesCompat.STRIDER
                && result.getSimulationContext().getWorldData().getInLava().getStates().contains(true)
                && !(Above.isAbove(player.y) && player.compensatedWorld.getLavaFluidLevelAt((int) Math.floor(player.x), (int) Math.floor(player.y + 1), (int) Math.floor(player.z)) == 0)) {
            int size = frictions.size();
            for (int i = 0; i < size; i++) {
                Vec3 friction = frictions.get(i);
                frictions.add(friction.scale(0.5).add(new Vec3(0, 0.05, 0)));
            }
        }

        return frictions;
    }

    static List<Vec3> preTravelRootVelocities(GrimPlayer player, PredictionResult result, Vec3 playerVelocity) {
        // Entity#baseTick current was already applied to the starting velocity
        // before collision/prediction. This method runs after the accepted move
        // to derive travel drag; applying current here would add it twice.
        return Collections.singletonList(playerVelocity);
    }

}
