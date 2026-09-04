package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.MainSupportingBlockData;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.grim.grimac.utils.data.packetentity.PacketEntityNautilus;
import ac.grim.grimac.utils.math.GrimMath;
import net.minecraft.world.phys.Vec3;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Friction {
    private Friction() {
    }

    public static List<Vec3> applyTravelDrag(GrimPlayer player, boolean onGround, Vec3 from, Vec3 playerVelocity,
                                             SimulationContext context, boolean falling, double gravity,
                                             boolean inWater, boolean inLava) {
        if (context.getVehicle() instanceof PacketEntityHappyGhast) {
            if (inWater) {
                return Collections.singletonList(playerVelocity.scale(0.8F));
            }
            if (inLava) {
                return Collections.singletonList(playerVelocity.scale(0.5D));
            }
            return Collections.singletonList(playerVelocity.scale(0.91F));
        }

        // AbstractNautilus#travelInWater moves first and then scales the complete
        // stored velocity by 0.9. It does not apply the generic fluid gravity path.
        if (context.getVehicle() instanceof PacketEntityNautilus && inWater) {
            return Collections.singletonList(playerVelocity.scale(0.9D));
        }

        // Water friction - has highest priority
        if (inWater) {
            List<Vec3> results = new ArrayList<>();
            for (double friction : getSwimFriction(context)) {
                if (!Double.isFinite(friction)) {
                    continue;
                }
                Vec3 withFriction = playerVelocity.multiply(friction, 0.8, friction);
                List<Vec3> adjusted = new ArrayList<>();
                addFluidFallingAdjustedMovement(player, context, adjusted, gravity, falling, withFriction);
                for (Vec3 candidate : adjusted) {
                    if (isFinite(candidate)) {
                        addDistinct(results, candidate);
                    }
                }
            }
            return results;
        }

        // Lava friction applies a stronger lateral slowdown before fluid falling is applied.
        if (inLava) {
            List<Vec3> velocities = new ArrayList<>();
            Vec3 withSomeFriction = playerVelocity.multiply(0.5, 0.8f, 0.5).add(0, -gravity / 4, 0);
            addFluidFallingAdjustedMovement(player, context, velocities, gravity, falling, withSomeFriction);
            velocities.add(playerVelocity.scale(0.5).add(0, -gravity / 4, 0));
            return velocities;
        }

        // Normal friction is this well known formula, account for slow falling, has lowest priority
        boolean use26Dot2 = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        float frictionUnderPlayer = getPlayerFriction(player, from, context.getLastTickMainSupportingBlockData(), onGround, false, false);
        playerVelocity = playerVelocity.multiply(frictionUnderPlayer, 1.0, frictionUnderPlayer);

        if (player.compensatedEntities.getLevitationAmplifier() != null) {
            // This supports both positive and negative levitation
            double y = playerVelocity.y;
            y += (0.05 * (player.compensatedEntities.getLevitationAmplifier() + 1) - y) * 0.2;
            playerVelocity = new Vec3(playerVelocity.x, y, playerVelocity.z);
        } else if (player.compensatedEntities.getEntityInControl().hasGravity) {
            playerVelocity = playerVelocity.subtract(0, gravity, 0);
        }

        // MCP-Reborn 26.2 LivingEntity#travelInAir applies the vertical air drag
        // through the air_drag_modifier attribute instead of the raw 0.98 constant.
        double verticalDrag = use26Dot2
                ? computeModifiedFriction(0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                : 0.98F;
        playerVelocity = playerVelocity.multiply(1, verticalDrag, 1);
        return Collections.singletonList(playerVelocity);
    }

    // MCP-Reborn 26.2 LivingEntity#computeModifiedFriction
    public static float computeModifiedFriction(float friction, float modifier) {
        return GrimMath.clampFloat(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
    }

    private static void addFluidFallingAdjustedMovement(GrimPlayer player, SimulationContext context, List<Vec3> results, double gravity, boolean falling, Vec3 movement) {
        if (!player.compensatedEntities.getEntityInControl().hasGravity || gravity == 0.0D) {
            addDistinct(results, movement);
            return;
        }

        // MCP-Reborn LivingEntity#getFluidFallingAdjustedMovement subtracts
        // gravity / 16 only when the entity is not sprinting. Use the frozen
        // SimulationContext sprint candidates for this simulated client tick;
        // mutable GrimPlayer sprint state may already reflect a later packet.
        for (boolean sprinting : context.getIsSprinting().getStates()) {
            Vec3 candidate = sprinting && context.getVehicle() == null ? movement : applyFluidGravity(gravity, falling, movement);
            addDistinct(results, candidate);
        }
    }

    public static Vec3 applyFluidGravity(double gravity, boolean falling, Vec3 movement) {
        double movementWithGravity = movement.y - gravity / 16.0D;
        double y = falling && Math.abs(movement.y - 0.005D) >= 0.003D && Math.abs(movementWithGravity) < 0.003D ? -0.003D : movementWithGravity;
        return new Vec3(movement.x, y, movement.z);
    }

    public static List<Double> getSwimFriction(SimulationContext context) {
        boolean skeletonHorse = context.getVehicle() != null && context.getVehicle().type == EntityTypesCompat.SKELETON_HORSE;
        boolean dolphinsGrace = context.getEntities().getPotionLevelForPlayer(PotionEffectType.DOLPHINS_GRACE) != null;
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

    public static float getPlayerFriction(GrimPlayer player, Vec3 from, MainSupportingBlockData mainSupportingBlockData,
                                          boolean onGround, boolean isGliding, boolean isFlying) {
        if (isGliding || isFlying) return 1.0f;

        // MCP-Reborn 26.2 LivingEntity#travelInAir passes the block friction and the
        // 0.91 air drag through the friction_modifier / air_drag_modifier attributes.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
            float airDrag = computeModifiedFriction(0.91F, (float) player.compensatedEntities.getEntityInControl().airDragModifier);
            if (!onGround) return airDrag;
            float blockFriction = computeModifiedFriction(
                    BlockProperties.getFriction(player, mainSupportingBlockData, from),
                    (float) player.compensatedEntities.getEntityInControl().frictionModifier);
            return blockFriction * airDrag;
        }

        if (!onGround) return 0.91f;

        float frictionUnderPlayer = BlockProperties.getFriction(player, mainSupportingBlockData, from);
        return 0.91f * frictionUnderPlayer;
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void addDistinct(List<Vec3> results, Vec3 candidate) {
        for (Vec3 existing : results) {
            if (existing.equals(candidate)) {
                return;
            }
        }
        results.add(candidate);
    }
}
