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
import java.util.List;

public final class Friction {
    private static final List<Boolean> NOT_SPRINTING = List.of(false);

    private Friction() {}

    /**
     * Appends the velocities after travel drag to {@code out}, in order. {@code shallowLava} is
     * LivingEntity#isInShallowFluid(LAVA) as travelInLava reads it after its Entity#move.
     */
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
            boolean shallowLava,
            List<Vec3> out) {
        applyTravelDrag(
                player,
                onGround,
                from,
                playerVelocity,
                context,
                falling,
                gravity,
                inWater,
                inLava,
                shallowLava,
                out,
                false);
    }

    static void applyTravelDrag(
            CultPlayer player,
            boolean onGround,
            Vec3 from,
            Vec3 playerVelocity,
            SimulationContext context,
            boolean falling,
            double gravity,
            boolean inWater,
            boolean inLava,
            boolean shallowLava,
            List<Vec3> out,
            boolean keepBranches) {
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
        boolean fluidTravel = (context.getVehicle() != null || !player.isFlying)
                && !context.getWorldData().isCanStandOnStartFluid();

        // Water friction - has highest priority
        if (inWater && fluidTravel) {
            int start = out.size();
            // LivingEntity#travelInWater reads one isSprinting for both its slowdown and
            // getFluidFallingAdjustedMovement, so each sprint state keeps its own pair.
            List<Boolean> sprinting = travellerSprinting(context);
            for (int i = 0, size = sprinting.size(); i < size; i++) {
                double friction = getSwimFriction(context, sprinting.get(i));
                if (!Double.isFinite(friction)) {
                    continue;
                }
                Vec3 withFriction = playerVelocity.multiply(friction, 0.8F, friction);
                addFluidFallingAdjustedMovement(
                        out, start, true, gravity, falling, sprinting.get(i), withFriction, keepBranches);
            }
            return;
        }

        // LivingEntity#travelInLava applies exactly one drag: shallow lava's fluid-falling one, or deep lava's 0.5.
        if (inLava && fluidTravel) {
            int start = out.size();
            if (shallowLava) {
                Vec3 withFriction = playerVelocity.multiply(0.5, 0.8F, 0.5);
                List<Boolean> sprinting = travellerSprinting(context);
                for (int i = 0, size = sprinting.size(); i < size; i++) {
                    addFluidFallingAdjustedMovement(
                            out, start, false, gravity, falling, sprinting.get(i), withFriction, keepBranches);
                }
            } else {
                out.add(playerVelocity.scale(0.5));
            }
            // travelInLava subtracts this after getFluidFallingAdjustedMovement.
            // Its near-zero falling branch makes the two operations noncommutative.
            for (int i = start; i < out.size(); i++) out.set(i, out.get(i).add(0, -gravity / 4, 0));
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
     * LivingEntity#isSprinting of the traveller during travel. A vehicle travels with its own flag, which only cats,
     * ocelots and players ever set, so a ridden mob never sprints. The player reads this tick's frozen
     * SimulationContext candidates; mutable CultPlayer sprint state may already reflect a later packet.
     */
    private static List<Boolean> travellerSprinting(SimulationContext context) {
        return context.getVehicle() != null
                ? NOT_SPRINTING
                : context.getIsSprinting().getStates();
    }

    /**
     * Appends {@code movement} after the fluid-falling adjustment of a traveller whose isSprinting is
     * {@code sprinting} to {@code results}. It skips a candidate equal to one already at or after {@code from} unless
     * {@code keepBranches} pairs push-range endpoints, and with {@code finiteOnly} one that is not finite.
     */
    private static void addFluidFallingAdjustedMovement(
            List<Vec3> results,
            int from,
            boolean finiteOnly,
            double gravity,
            boolean falling,
            boolean sprinting,
            Vec3 movement,
            boolean keepBranches) {
        // MCP-Reborn LivingEntity#getFluidFallingAdjustedMovement subtracts
        // gravity / 16 only when the entity is not sprinting.
        Vec3 candidate = gravity == 0.0D || sprinting ? movement : applyFluidGravity(gravity, falling, movement);
        if (finiteOnly && !isFinite(candidate)) return;
        if (keepBranches) results.add(candidate);
        else addDistinct(results, from, candidate);
    }

    public static Vec3 applyFluidGravity(double gravity, boolean falling, Vec3 movement) {
        double movementWithGravity = movement.y - gravity / 16.0D;
        double y = falling && Math.abs(movement.y - 0.005D) >= 0.003D && Math.abs(movementWithGravity) < 0.003D
                ? -0.003D
                : movementWithGravity;
        return new Vec3(movement.x, y, movement.z);
    }

    /** LivingEntity#travelInWater's slowdown for a traveller whose isSprinting is {@code sprinting}. */
    public static double getSwimFriction(SimulationContext context, boolean sprinting) {
        // LivingEntity#travelInWater: Dolphin's Grace replaces the traveller's slowdown after the efficiency lerp.
        if (context.getEntities().getPotionLevelForPlayer(MovementEffect.DOLPHINS_GRACE) != null) return 0.96F;
        // A sprinting traveller slows by 0.9F, any other by getWaterSlowDown: SkeletonHorse's 0.96F or 0.8F.
        boolean skeletonHorse =
                context.getVehicle() != null && context.getVehicle().type == EntityTypeIds.SKELETON_HORSE;
        float slowDown = sprinting ? 0.9F : skeletonHorse ? 0.96F : 0.8F;
        return applyWaterMovementEfficiency(context.getWaterMovementEfficiency(), slowDown);
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
