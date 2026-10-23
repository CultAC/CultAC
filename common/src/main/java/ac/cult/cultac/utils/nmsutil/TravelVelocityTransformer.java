package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TravelVelocityTransformer {
    private TravelVelocityTransformer() {}

    public static List<Vec3> transform(
            CultPlayer player,
            boolean onGround,
            Vec3 from,
            Vec3 playerVelocity,
            PredictionResult result,
            float additionalBlockFriction) {
        // Boat friction is special, not handled here
        if (result.getSimulationContext().getVehicle() != null
                && EntityTypeUtil.isBoat(result.getSimulationContext().getVehicle().type)) {
            // edge case with teleporting to top of water
            if (player.boatData.nullifyNextY) {
                return Collections.singletonList(new Vec3(playerVelocity.x, 0, playerVelocity.z));
            }
            return Collections.singletonList(playerVelocity);
        }

        List<Vec3> frictions = new ArrayList<>(4);
        List<Boolean> inWaterStates =
                result.getSimulationContext().getWorldData().getInWater().getStates();
        List<Boolean> inLavaStates =
                result.getSimulationContext().getWorldData().getInLava().getStates();

        for (int waterIndex = 0, waterSize = inWaterStates.size(); waterIndex < waterSize; waterIndex++) {
            boolean inWater = inWaterStates.get(waterIndex);
            for (int lavaIndex = 0, lavaSize = inLavaStates.size(); lavaIndex < lavaSize; lavaIndex++) {
                boolean inLava = inLavaStates.get(lavaIndex);
                Vec3 movedVelocity = playerVelocity;
                // Entity#move applies horizontal collision zeroing and then
                // getBlockSpeedFactor before LivingEntity#travel applies air/water friction.
                // Multiplying by an exact 1 keeps every component's bits.
                if (additionalBlockFriction != 1.0F || movedVelocity.getClass() != Vec3.class) {
                    movedVelocity = movedVelocity.multiply(additionalBlockFriction, 1, additionalBlockFriction);
                }

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
                Friction.applyTravelDrag(
                        player,
                        onGround,
                        from,
                        movedVelocity,
                        result.getSimulationContext(),
                        falling,
                        grav,
                        inWater,
                        inLava,
                        frictions);
            }
        }

        // Player#travel replaces the travelled Y with 0.6 of the Y it travelled with, without gravity.
        if (result.getSimulationContext().getVehicle() == null
                && player.isFlying
                && result.getInitialStartingVel() != null) {
            double flightY = result.getInitialStartingVel().y * 0.6;
            frictions.replaceAll(velocity -> new Vec3(velocity.x, flightY, velocity.z));
        }

        // MCP-Reborn Strider#floatStrider only rewrites deltaMovement from the
        // post-travel Strider#tick path while the strider is actually in lava.
        if (!JavaMovementEngine.contextUsesExactEffects(result.getSimulationContext())
                && result.getSimulationContext().getVehicle() != null
                && result.getSimulationContext().getVehicle().type == EntityTypeIds.STRIDER
                && result.getSimulationContext()
                        .getWorldData()
                        .getInLava()
                        .getStates()
                        .contains(true)
                && !(Above.isAbove(player.y)
                        && player.compensatedWorld.getLavaFluidLevelAt(
                                        (int) Math.floor(player.x), (int) Math.floor(player.y + 1), (int)
                                                Math.floor(player.z))
                                == 0)) {
            int size = frictions.size();
            for (int i = 0; i < size; i++) {
                Vec3 friction = frictions.get(i);
                frictions.add(friction.scale(0.5).add(new Vec3(0, 0.05, 0)));
            }
        }

        return frictions;
    }
}
