package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
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
            List<Double> frictions = getSwimFriction(context);
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
            // LivingEntity#travelInLava subtracts gravity / 4 after the shallow lava fluid-falling adjustment.
            int shallow = out.size();
            addFluidFallingAdjustedMovement(
                    player, context, out, shallow, false, gravity, falling, playerVelocity.multiply(0.5, 0.8f, 0.5));
            for (int i = shallow, size = out.size(); i < size; i++)
                out.set(i, out.get(i).add(0, -gravity / 4, 0));
            out.add(playerVelocity.scale(0.5).add(0, -gravity / 4, 0));
            return;
        }

        // Normal friction is this well known formula, account for slow falling, has lowest priority
        float frictionUnderPlayer =
                getPlayerFriction(player, from, context.getLastTickMainSupportingBlockData(), onGround);
        double x = playerVelocity.x * frictionUnderPlayer;
        double z = playerVelocity.z * frictionUnderPlayer;
        out.add(new Vec3(x, travelInAirY(player, playerVelocity.y, gravity), z));
    }

    /** LivingEntity#travelInAir's vertical end of tick: levitation, otherwise gravity, then the vertical air drag. */
    public static double travelInAirY(CultPlayer player, double y, double gravity) {
        y = applyAirGravity(player, y, gravity);
        // MCP-Reborn 26.2 LivingEntity#travelInAir applies the vertical air drag
        // through the air_drag_modifier attribute instead of the raw 0.98 constant.
        double verticalDrag = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                ? computeModifiedFriction(
                        0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                : 0.98F;
        return y * verticalDrag;
    }

    /** LivingEntity#travelInAir: the moved vertical velocity before vertical drag; levitation replaces gravity. */
    public static double applyAirGravity(CultPlayer player, double y, double gravity) {
        Integer levitation = player.compensatedEntities.getLevitationAmplifier();
        if (levitation != null) {
            // This supports both positive and negative levitation
            return y + (0.05 * (levitation + 1) - y) * 0.2;
        }
        return player.compensatedEntities.getEntityInControl().hasGravity ? y - gravity : y;
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
        // gravity / 16 only when the travelling entity, a ridden root included, is
        // not sprinting. Use the frozen SimulationContext sprint candidates for this
        // simulated client tick; mutable sprint state may reflect a later packet.
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

    public static List<Double> getSwimFriction(SimulationContext context) {
        boolean skeletonHorse =
                context.getVehicle() != null && context.getVehicle().type == EntityTypeIds.SKELETON_HORSE;
        boolean dolphinsGrace = context.getEntities().getPotionLevelForPlayer(MovementEffect.DOLPHINS_GRACE) != null;
        List<Boolean> sprinting = context.getIsSprinting().getStates();
        if (skeletonHorse && !dolphinsGrace) {
            // LivingEntity#travelInWater takes 0.9F over SkeletonHorse#getWaterSlowDown for a sprinting root.
            List<Double> frictions = new ArrayList<>(sprinting.size());
            for (boolean sprint : sprinting) {
                frictions.add(applyWaterMovementEfficiency(context.getWaterMovementEfficiency(), sprint ? 0.9 : 0.96));
            }
            return frictions;
        }
        List<Double> frictions = getSwimFriction(skeletonHorse, dolphinsGrace, context.getWaterMovementEfficiency());
        if (context.getVehicle() != null || frictions.size() != 2 || sprinting.size() != 1) return frictions;
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
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
            float airDrag = computeModifiedFriction(
                    0.91F, (float) player.compensatedEntities.getEntityInControl().airDragModifier);
            if (!onGround) return airDrag;
            float blockFriction =
                    computeModifiedFriction(BlockProperties.getFriction(player, mainSupportingBlockData, from), (float)
                            player.compensatedEntities.getEntityInControl().frictionModifier);
            return blockFriction * airDrag;
        }

        if (!onGround) return 0.91f;

        float frictionUnderPlayer = BlockProperties.getFriction(player, mainSupportingBlockData, from);
        return 0.91f * frictionUnderPlayer;
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
