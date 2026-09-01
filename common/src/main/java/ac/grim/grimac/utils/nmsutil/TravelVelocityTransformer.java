package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
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

                    DesyncStatus isFalling = result.getIsFalling();
                    for (boolean falling : isFalling.getStates()) {
                        double grav = 0.08;
                        // Gravity is 0.01 when a player has slow falling and is moving downwards
                        if (falling && result.getSimulationContext().getEntities().getSlowFallingAmplifier() != null)
                            grav = 0.01;
                        if (!player.compensatedEntities.getEntityInControl().hasGravity) grav = 0;
                        frictions.addAll(Friction.applyTravelDrag(player, onGround, from, movedVelocity, result.getSimulationContext(), falling, grav, inWater, inLava));
                    }
                }
            }
        }

        // MCP-Reborn Strider#floatStrider only rewrites deltaMovement from the
        // post-travel Strider#tick path while the strider is actually in lava.
        if (result.getSimulationContext().getVehicle() != null
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
        SimulationContext context = result.getSimulationContext();
        PacketEntity vehicle = context == null ? null : context.getVehicle();
        if (vehicle == null
                || EntityTypeUtil.isBoat(vehicle.type)
                || !player.packetStateData.isVehicleMovementFromClientTick()) {
            return Collections.singletonList(playerVelocity);
        }

        List<Vec3> currents = mountedRootWaterCurrents(player, result);
        if (currents.isEmpty()) {
            return Collections.singletonList(playerVelocity);
        }

        // Entity#baseTick applies fluid current before LivingEntity#travel chooses
        // water, lava, or air travel. A mounted root can be pushed by flowing
        // water even when its block-position fluid state makes travelInAir run.
        List<Vec3> velocities = new ArrayList<>();
        velocities.add(playerVelocity);
        for (Vec3 current : currents) {
            Vec3 withCurrent = playerVelocity.add(current);
            if (!velocities.contains(withCurrent)) {
                velocities.add(withCurrent);
            }
        }
        return velocities;
    }

    private static List<Vec3> mountedRootWaterCurrents(GrimPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity vehicle = context == null ? null : context.getVehicle();
        if (vehicle == null || EntityTypeUtil.isBoat(vehicle.type)) {
            return Collections.emptyList();
        }

        List<Vec3> currents = new ArrayList<>(2);
        addMountedRootWaterCurrent(player, context, currents, context.getStart());
        if (context.getEnd().distanceToSqr(context.getStart()) > 1.0E-14D) {
            addMountedRootWaterCurrent(player, context, currents, context.getEnd());
        }
        return currents;
    }

    private static void addMountedRootWaterCurrent(GrimPlayer player, SimulationContext context, List<Vec3> currents, Vec3 position) {
        Vec3 current = WaterCurrent.calculateWaterCurrent(player, context, position, Vec3.ZERO);
        if (current == null) {
            current = WaterCurrent.calculateTouchedNonPlayerWaterCurrent(player, WaterCurrent.getWaterCurrentInteractionBox(player, context, position));
        }
        if (current == null || !isFinite(current) || currents.contains(current)) {
            return;
        }
        currents.add(current);
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
