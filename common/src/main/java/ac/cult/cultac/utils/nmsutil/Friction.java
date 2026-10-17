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

        // Water friction - has highest priority
        if (inWater) {
            int start = out.size();
            List<Double> frictions = getSwimFriction(context);
            for (int i = 0, size = frictions.size(); i < size; i++) {
                double friction = frictions.get(i);
                if (!Double.isFinite(friction)) {
                    continue;
                }
                boolean legacyWater = context.getVersion().isOlderThan(ClientVersion.V_1_13);
                Vec3 withFriction = playerVelocity.multiply(friction, legacyWater ? (double) 0.8F : 0.8D, friction);
                // EntityLivingBase#moveEntityWithHeading before Update Aquatic:
                // water always subtracts 0.02 after drag, including sprinting.
                if (legacyWater) {
                    addDistinct(out, start, withFriction.add(0, -0.02D, 0));
                    continue;
                }
                addFluidFallingAdjustedMovement(player, context, out, start, true, gravity, falling, withFriction);
            }
            return;
        }

        // Lava friction applies a stronger lateral slowdown before fluid falling is applied.
        if (inLava) {
            if (context.getVersion().isOlderThan(ClientVersion.V_1_16)) {
                out.add(playerVelocity.scale(0.5D).add(0, -0.02D, 0));
                return;
            }
            Vec3 withSomeFriction = playerVelocity.multiply(0.5, 0.8f, 0.5).add(0, -gravity / 4, 0);
            addFluidFallingAdjustedMovement(
                    player, context, out, out.size(), false, gravity, falling, withSomeFriction);
            out.add(playerVelocity.scale(0.5).add(0, -gravity / 4, 0));
            return;
        }

        // Normal friction is this well known formula, account for slow falling, has lowest priority
        boolean use26Dot2 = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        float frictionUnderPlayer =
                getPlayerFriction(player, from, context.getLastTickMainSupportingBlockData(), onGround, false, false);
        // Vec3 arithmetic as scalars: multiplying by 1 and adding -0 keep a component's bits.
        double x = playerVelocity.x * frictionUnderPlayer;
        double y = playerVelocity.y;
        double z = playerVelocity.z * frictionUnderPlayer;

        if (player.compensatedEntities.getLevitationAmplifier() != null) {
            // This supports both positive and negative levitation
            y += (0.05 * (player.compensatedEntities.getLevitationAmplifier() + 1) - y) * 0.2;
        } else if (!player.isBedrockMovement()
                && context.getVersion().isOlderThan(ClientVersion.V_1_9)
                && !player.compensatedWorld.isChunkLoaded(
                        (int) context.getEnd().x >> 4, (int) context.getEnd().z >> 4)) {
            // 1.8 EntityLivingBase#moveEntityWithHeading still ticks EmptyChunk.
            // After moving, it replaces motionY before drag until the column
            // loads. The chunk lookup casts toward zero, and the Y cutoff is
            // the legacy world's zero, independent of the modern server's minY.
            y = context.getEnd().y > 0 ? -0.1D : 0.0D;
        } else if (player.compensatedEntities.getEntityInControl().hasGravity) {
            y += -gravity;
        }

        // MCP-Reborn 26.2 LivingEntity#travelInAir applies the vertical air drag
        // through the air_drag_modifier attribute instead of the raw 0.98 constant.
        double verticalDrag = use26Dot2
                ? computeModifiedFriction(
                        0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                : 0.98F;
        out.add(new Vec3(x, y * verticalDrag, z));
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
        if (!player.compensatedEntities.getEntityInControl().hasGravity || gravity == 0.0D) {
            if (!finiteOnly || isFinite(movement)) addDistinct(results, from, movement);
            return;
        }

        // MCP-Reborn LivingEntity#getFluidFallingAdjustedMovement subtracts
        // gravity / 16 only when the entity is not sprinting. Use the frozen
        // SimulationContext sprint candidates for this simulated client tick;
        // mutable CultPlayer sprint state may already reflect a later packet.
        List<Boolean> sprintingStates = context.getIsSprinting().getStates();
        for (int i = 0, size = sprintingStates.size(); i < size; i++) {
            Vec3 candidate = sprintingStates.get(i) && context.getVehicle() == null
                    ? movement
                    : applyFluidGravity(gravity, falling, movement);
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
        if (context.getVersion().isOlderThan(ClientVersion.V_1_13)) {
            float friction = 0.8F;
            // Vanilla evaluates depth-strider interpolation in float precision.
            if (context.getDepthStriderLevel() > 0) {
                friction += (0.54600006F - friction) * context.getDepthStriderLevel() / 3.0F;
            }
            return Collections.singletonList((double) friction);
        }
        boolean skeletonHorse =
                context.getVehicle() != null && context.getVehicle().type == EntityTypeIds.SKELETON_HORSE;
        boolean dolphinsGrace = context.getEntities().getPotionLevelForPlayer(MovementEffect.DOLPHINS_GRACE) != null;
        return getSwimFriction(skeletonHorse, dolphinsGrace, context.getDepthStriderLevel());
    }

    public static List<Double> getSwimFriction(boolean skeletonHorse, boolean dolphinsGrace, float depthStriderLevel) {
        if (skeletonHorse || dolphinsGrace) {
            return Collections.singletonList(0.96);
        }

        // Keep the existing broad candidate set; narrowing this to DesyncStatus changes
        // movement prediction behavior under latency.
        return Arrays.asList(applyDepthStrider(depthStriderLevel, 0.9), applyDepthStrider(depthStriderLevel, 0.8));
    }

    public static double applyDepthStrider(float depthStriderLevel, double friction) {
        return friction + ((0.54600006F - friction) * depthStriderLevel / 3.0F);
    }

    public static float getPlayerFriction(
            CultPlayer player,
            Vec3 from,
            MainSupportingBlockData mainSupportingBlockData,
            boolean onGround,
            boolean isGliding,
            boolean isFlying) {
        if (isGliding || isFlying) return 1.0f;

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
