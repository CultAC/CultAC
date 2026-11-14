package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Friction {
    private Friction() {}

    /** Appends the velocities after travel drag to {@code out}, in order. */
    public static void applyTravelDrag(
            CultPlayer player,
            boolean onGround,
            Vec3 from,
            Vec3 playerVelocity,
            SimulationContext context,
            boolean falling,
            double gravity,
            boolean inWater,
            boolean inLava,
            List<Vec3> out) {
        if (context.getVehicle() instanceof PacketEntityHappyGhast) {
            if (inWater) {
                out.add(playerVelocity.scale(0.8F));
                return;
            }
            if (inLava) {
                out.add(playerVelocity.scale(0.5D));
                return;
            }
            out.add(playerVelocity.scale(0.91F));
            return;
        }

        // AbstractNautilus#travelInWater moves first and then scales the complete
        // stored velocity by 0.9. It does not apply the generic fluid gravity path.
        if (context.getVehicle() instanceof PacketEntityNautilus && inWater) {
            out.add(playerVelocity.scale(0.9D));
            return;
        }

        // Player#isAffectedByFluids is false in flight, so LivingEntity#travel takes travelInAir in water and lava.
        boolean fluidTravel = context.getVehicle() != null || !player.isFlying;

        // Water friction - has highest priority
        if (inWater && fluidTravel) {
            int start = out.size();
            List<Double> frictions = getSwimFriction(player, context);
            for (int i = 0, size = frictions.size(); i < size; i++) {
                double friction = frictions.get(i);
                if (!Double.isFinite(friction)) {
                    continue;
                }
                Vec3 withFriction = playerVelocity.multiply(friction, 0.8D, friction);
                addFluidFallingAdjustedMovement(player, context, out, start, true, gravity, falling, withFriction);
            }
            return;
        }

        // Lava friction applies a stronger lateral slowdown before fluid falling is applied.
        if (inLava && fluidTravel) {
            Vec3 withSomeFriction = playerVelocity.multiply(0.5, 0.8f, 0.5).add(0, -gravity / 4, 0);
            addFluidFallingAdjustedMovement(
                    player, context, out, out.size(), false, gravity, falling, withSomeFriction);
            out.add(playerVelocity.scale(0.5).add(0, -gravity / 4, 0));
            return;
        }

        // Normal friction is this well known formula, account for slow falling, has lowest priority
        boolean use26Dot2 = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        float frictionUnderPlayer =
                getPlayerFriction(player, from, context.getLastTickMainSupportingBlockData(), onGround);
        double x = playerVelocity.x * frictionUnderPlayer;
        double y = applyAirGravity(player, playerVelocity.y, gravity);
        double z = playerVelocity.z * frictionUnderPlayer;

        // MCP-Reborn 26.2 LivingEntity#travelInAir applies the vertical air drag
        // through the air_drag_modifier attribute instead of the raw 0.98 constant.
        double verticalDrag = use26Dot2
                ? computeModifiedFriction(
                        0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                : 0.98F;
        out.add(new Vec3(x, y * verticalDrag, z));
    }

    /** LivingEntity#travelInAir: the moved vertical velocity before vertical drag; levitation replaces gravity. */
    public static double applyAirGravity(CultPlayer player, double y, double gravity) {
        Integer levitation = player.compensatedEntities.getLevitationAmplifier();
        // This supports both positive and negative levitation
        return levitation != null ? y + (0.05 * (levitation + 1) - y) * 0.2 : y - gravity;
    }

    // MCP-Reborn 26.2 LivingEntity#computeModifiedFriction
    public static float computeModifiedFriction(float friction, float modifier) {
        return CultMath.clampFloat(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
    }

    /**
     * Appends the fluid-falling candidates of {@code movement} to {@code results}, skipping any equal to one already at
     * or after {@code from}, and with {@code finiteOnly} any that is not finite.
     */
    private static void addFluidFallingAdjustedMovement(
            CultPlayer player,
            SimulationContext context,
            List<Vec3> results,
            int from,
            boolean finiteOnly,
            double gravity,
            boolean falling,
            Vec3 movement) {
        if (gravity == 0.0D) {
            if (!finiteOnly || isFinite(movement)) addDistinct(results, from, movement);
            return;
        }

        // MCP-Reborn LivingEntity#getFluidFallingAdjustedMovement subtracts
        // gravity / 16 only when the entity is not sprinting. Use the frozen
        // SimulationContext sprint candidates for this simulated client tick;
        // mutable CultPlayer sprint state may already reflect a later packet.
        PacketEntity vehicle = context.getVehicle();
        if (vehicle != null) {
            // The travelling vehicle reads its own shared sprinting flag.
            boolean sprinting = !player.isBedrockMovement() && (vehicle.actionSharedFlags & 8) != 0;
            Vec3 candidate = sprinting ? movement : applyFluidGravity(gravity, falling, movement);
            if (!finiteOnly || isFinite(candidate)) addDistinct(results, from, candidate);
            return;
        }
        List<Boolean> sprintingStates = context.getIsSprinting().getStates();
        for (int i = 0, size = sprintingStates.size(); i < size; i++) {
            Vec3 candidate = sprintingStates.get(i) ? movement : applyFluidGravity(gravity, falling, movement);
            if (!finiteOnly || isFinite(candidate)) addDistinct(results, from, candidate);
        }
    }

    public static Vec3 applyFluidGravity(double gravity, boolean falling, Vec3 movement) {
        double movementWithGravity = movement.y - gravity / 16.0D;
        double y = falling && Math.abs(movement.y - 0.005D) >= 0.003D && Math.abs(movementWithGravity) < 0.003D
                ? -0.003D
                : movementWithGravity;
        return new Vec3(movement.x, y, movement.z);
    }

    public static List<Double> getSwimFriction(CultPlayer player, SimulationContext context) {
        PacketEntity vehicle = context.getVehicle();
        boolean skeletonHorse = vehicle != null && vehicle.type == EntityTypeIds.SKELETON_HORSE;
        boolean dolphinsGrace = context.getEntities().getPotionLevelForPlayer(MovementEffect.DOLPHINS_GRACE) != null;
        List<Double> frictions = getSwimFriction(skeletonHorse, dolphinsGrace, context.getWaterMovementEfficiency());
        if (frictions.size() != 2) return frictions;
        if (vehicle != null) {
            if (player.isBedrockMovement()) return frictions;
            // LivingEntity#travelInWater reads the travelling vehicle's own shared sprinting flag.
            return Collections.singletonList(frictions.get((vehicle.actionSharedFlags & 8) != 0 ? 0 : 1));
        }
        List<Boolean> sprinting = context.getIsSprinting().getStates();
        if (sprinting.size() != 1) return frictions;
        // LivingEntity#travelInWater slows a player sprinting at travel by 0.9F and any other by 0.8F.
        return Collections.singletonList(frictions.get(sprinting.getFirst() ? 0 : 1));
    }

    public static List<Double> getSwimFriction(
            boolean skeletonHorse, boolean dolphinsGrace, float waterMovementEfficiency) {
        // LivingEntity#travelInWater: Dolphin's Grace replaces the traveller's slowdown after the efficiency lerp.
        if (dolphinsGrace) {
            return Collections.singletonList(0.96);
        }
        // SkeletonHorse#getWaterSlowDown.
        if (skeletonHorse) {
            return Collections.singletonList(applyWaterMovementEfficiency(waterMovementEfficiency, 0.96));
        }

        // The sprinting and the non-sprinting slowdown, in that order.
        return Arrays.asList(
                applyWaterMovementEfficiency(waterMovementEfficiency, 0.9),
                applyWaterMovementEfficiency(waterMovementEfficiency, 0.8));
    }

    public static double applyWaterMovementEfficiency(float waterMovementEfficiency, double friction) {
        if (waterMovementEfficiency <= 0.0F) return friction;
        float slowDown = (float) friction;
        return slowDown + (0.54600006F - slowDown) * waterMovementEfficiency;
    }

    public static float getPlayerFriction(
            CultPlayer player, Vec3 from, MainSupportingBlockData mainSupportingBlockData, boolean onGround) {
        // MCP-Reborn 26.2 LivingEntity#travelInAir passes the block friction and the
        // 0.91 air drag through the friction_modifier / air_drag_modifier attributes.
        if (!onGround) {
            return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                    ? computeModifiedFriction(
                            0.91F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                    : 0.91f;
        }
        return getGroundFriction(player, BlockProperties.getFriction(player, mainSupportingBlockData, from));
    }

    /** {@link #getPlayerFriction} on the ground over a block of {@code blockFriction}. */
    public static float getGroundFriction(CultPlayer player, float blockFriction) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
            float airDrag = computeModifiedFriction(
                    0.91F, (float) player.compensatedEntities.getEntityInControl().airDragModifier);
            return computeModifiedFriction(
                            blockFriction, (float) player.compensatedEntities.getEntityInControl().frictionModifier)
                    * airDrag;
        }
        return 0.91f * blockFriction;
    }

    /** LivingEntity#getFrictionInfluencedSpeed's multiplier of the ground speed over a block of {@code friction}. */
    public static float getGroundSpeedFactor(CultPlayer player, float friction) {
        // MCP-Reborn 26.2 LivingEntity#getFrictionInfluencedSpeed receives the
        // friction_modifier-adjusted block friction and only boosts movement
        // speed on slippery blocks (friction > 0.6).
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
            float modifiedFriction = computeModifiedFriction(
                    friction, (float) player.compensatedEntities.getEntityInControl().frictionModifier);
            return modifiedFriction > 0.6f
                    ? 0.21600002f / (modifiedFriction * modifiedFriction * modifiedFriction)
                    : 1f;
        }
        return 0.21600002f / (friction * friction * friction);
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void addDistinct(List<Vec3> results, int from, Vec3 candidate) {
        for (int i = from, size = results.size(); i < size; i++) {
            if (results.get(i).equals(candidate)) {
                return;
            }
        }
        results.add(candidate);
    }
}
