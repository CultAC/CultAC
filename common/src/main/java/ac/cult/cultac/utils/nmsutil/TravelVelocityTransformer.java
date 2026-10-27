package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TravelVelocityTransformer {
    private TravelVelocityTransformer() {}

    /**
     * The travel operations after Entity#move left {@code playerVelocity}, with the horizontalCollision that move set.
     * {@code keepBranches} retains equal branch outputs while pairing push-range endpoints.
     */
    static List<Vec3> transform(
            CultPlayer player,
            boolean onGround,
            Vec3 from,
            Vec3 playerVelocity,
            boolean horizontalCollision,
            PredictionResult result,
            float additionalBlockFriction,
            boolean keepBranches) {
        if (ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRiddenTravel.clearsWithoutTravel(result.getSimulationContext()))
            return Collections.singletonList(Vec3.ZERO);
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
        boolean inWater = result.getSimulationContext().getWorldData().isInWater();
        boolean inLava = result.getSimulationContext().getWorldData().isInLava();
        // The deltaMovement LivingEntity#travelInFluid starts with: moveRelative adds no vertical input.
        double travelStartY = VelocityCandidates.hasEngineTrace(result)
                ? VelocityCandidates.preCollisionVelocity(result).y
                : playerVelocity.y;
        Boolean shallowLava = null;

        int branchStart = frictions.size();
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
        } else {
            var context = result.getSimulationContext();
            boolean fluidTravel = context.getVehicle() != null || !player.isFlying;
            boolean waterTravel = inWater && fluidTravel;
            boolean lavaTravel = !waterTravel && inLava && fluidTravel;
            // LivingEntity#travelInFluid reads isFalling and getEffectiveGravity before its Entity#move.
            // travelInAir reads getEffectiveGravity after its move, from the stored deltaMovement rather than
            // the collision-clipped packet displacement. Both matter when a slow-falling player hits a ceiling.
            boolean falling = (waterTravel || lavaTravel ? travelStartY : movedVelocity.y) <= 0.0D;
            double grav = player.compensatedEntities.getEntityInControl().gravity;
            if (falling && result.getSimulationContext().getEntities().getSlowFallingAmplifier() != null) {
                grav = Math.min(grav, 0.01D);
            }
            if (!player.compensatedEntities.getEntityInControl().hasGravity) grav = 0;
            if (lavaTravel && shallowLava == null) shallowLava = JavaShallowLava.test(player, result);
            var pass = JavaMovementEngine.passAfterTravel(result);
            var climbing = pass == null
                    ? context.getWorldData().getClimbing()
                    : WorldStageBuilder.climbingAtPosition(
                            player, context, pass.travel().position());
            boolean powderBoost = !waterTravel
                    && !lavaTravel
                    && JavaInsideBlockEffects.canClimbPowderSnow(
                            player,
                            context,
                            pass == null ? context.getEnd() : pass.travel().position());
            boolean climbBoost = VelocityCandidates.carriesClimbBoost(result)
                    && !lavaTravel
                    && (climbing.determineOptimistically() || powderBoost)
                    && (horizontalCollision
                            || !waterTravel
                                    && context.getVehicle() == null
                                    && player.packetStateData.knownInput.jump());
            // LivingEntity raises the returned movement after Entity#move, before travel drag.
            // getEffectiveGravity still reads the entity's stored deltaMovement, not that returned Y.
            if (climbBoost) {
                if (climbing.isDesync() && !powderBoost) {
                    Friction.applyTravelDrag(
                            player,
                            onGround,
                            from,
                            movedVelocity,
                            context,
                            falling,
                            grav,
                            inWater,
                            inLava,
                            lavaTravel && shallowLava,
                            frictions,
                            keepBranches);
                }
                movedVelocity = new Vec3(movedVelocity.x, 0.2, movedVelocity.z);
            }
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
                    lavaTravel && shallowLava,
                    frictions,
                    keepBranches);
            if ((waterTravel || lavaTravel) && !(context.getVehicle() instanceof PacketEntityHappyGhast)) {
                // HappyGhast#travel uses travelFlying; AbstractNautilus#travelInWater replaces the water path.
                if (horizontalCollision && !(waterTravel && context.getVehicle() instanceof PacketEntityNautilus))
                    for (int i = branchStart, size = frictions.size(); i < size; i++) {
                        Vec3 velocity = frictions.get(i);
                        var free = JavaFluidHopVelocity.isFree(player, result, velocity);
                        if (free == DesyncStatus.TRUE) frictions.set(i, JavaFluidHopVelocity.hop(velocity));
                        // The entity positions the client may hold disagree on isFree, so both outcomes carry.
                        else if (free == DesyncStatus.UNKNOWN) frictions.add(JavaFluidHopVelocity.hop(velocity));
                    }
                if (waterTravel || context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_3))
                    RiddenWaterFloatVelocity.apply(result, frictions, branchStart);
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
                && result.getSimulationContext().getWorldData().mayBeInLava()
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
