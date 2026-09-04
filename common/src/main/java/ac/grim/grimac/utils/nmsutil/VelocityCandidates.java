package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHappyGhast;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

public final class VelocityCandidates {
    private VelocityCandidates() {
    }

    public static List<PredictionResult> nextTickVelocityRealities(GrimPlayer player, PredictionResult result) {
        if (shouldRetainItemControlledZeroMoveRealities(player, result)) {
            List<PredictionResult> realities = new ArrayList<>();
            realities.add(result);
            realities.addAll(result.getAnyReality());
            return realities;
        }

        // Do not carry current-tick alternatives into the next tick's velocity set.
        return List.of(result);
    }

    public static Candidates derive(PredictionResult result, PredictionResult lastPrediction, Vec3 diff) {
        Set<Vec3> validPlayerStartingVels = new HashSet<>();

        // Preserve the existing prediction candidate space for legacy soul-sand uncertainty.
        int soulSandCount = result.getSimulationContext().getWorldData().getSoulSandCount();
        double multiplier = Math.pow(0.4, soulSandCount);
        diff = diff.multiply(multiplier, 1, multiplier);
        Vec3 positionOnlyDelta = result.getValidMovements().getPositionOnlyDelta();
        if (positionOnlyDelta.lengthSqr() > 1.0E-14) {
            // MCP-Reborn has a few packet-visible Entity#move paths that do not
            // write their displacement to Entity#deltaMovement, such as piston,
            // shulker, shulker-box, and grounded riptide lift movement. Remove
            // only that per-reality displacement before deriving carried
            // next-tick velocity.
            diff = diff.subtract(positionOnlyDelta);
        }
        // Entity#restituteMovementAfterCollisions receives the movement which
        // survived collision resolution. Keep it separate from subsequent
        // transformations of deltaMovement and from the restitution result.
        double collisionMovementY = diff.y;

        GrimPlayer player = result.getPlayer();
        if (player != null) {
            diff = applyPostMoveLivingFluidCurrent(player, result, diff);
        }

        boolean isX = result.getCollideAxisData().getX().isLikelyCollide();
        boolean isY = result.getCollideAxisData().getYPos() != null && result.getCollideAxisData().getYPos().isLikelyCollide() ||
                result.getCollideAxisData().getYNeg() != null && result.getCollideAxisData().getYNeg().isLikelyCollide()
                || result.getValidMovements().isCanStep();
        boolean isZ = result.getCollideAxisData().getZ().isLikelyCollide();

        OptionalDouble requiredPostCollisionY = requiredPacketVisibleSlimeBounceY(result, collisionMovementY);
        if (requiredPostCollisionY.isPresent()) {
            diff = new Vec3(diff.x, requiredPostCollisionY.getAsDouble(), diff.z);
            isY = false;
        }

        addVelocityCandidates(validPlayerStartingVels, diff, isX, isY, isZ);
        addPostMoveDeltaMovementCandidates(validPlayerStartingVels, result, isX, isY, isZ, collisionMovementY);

        if (lastPrediction != null && lastPrediction.getSimulationContext().getWorldData().getStuckSpeed().getUnknownStuckSpeedMultiplier() != null) {
            validPlayerStartingVels.add(Vec3.ZERO);
        }

        if (consumedRequiredStuckSpeedThisMove(result)) {
            // MCP-Reborn Entity#move applies Entity#stuckSpeedMultiplier to the
            // current move, then immediately clears both stuckSpeedMultiplier and
            // deltaMovement before collision resolution. Any next-tick carried
            // velocity must therefore be rebuilt from that zero base by later
            // vanilla logic such as LivingEntity#travelInWater,
            // LivingEntity#floatInWaterWhileRidden, or jumpOutOfFluid; it cannot
            // be derived directly from the full accepted packet delta.
            validPlayerStartingVels.clear();
            validPlayerStartingVels.add(Vec3.ZERO);
        }

        return new Candidates(validPlayerStartingVels);
    }

    private static Vec3 applyPostMoveLivingFluidCurrent(GrimPlayer player, PredictionResult result, Vec3 postMoveDelta) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity vehicle = context == null ? null : context.getVehicle();
        if (context == null
                || vehicle != null && (EntityTypeUtil.isBoat(vehicle.type) || vehicle instanceof PacketEntityHappyGhast)
                || player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_15_2)
                || player.getClientVersion() == ClientVersion.V_1_21_4
                || context.getWorldData().getInWater().determineOptimistically()
                || !context.getWorldData().getInLava().determinePessimistically()) {
            return postMoveDelta;
        }

        // Modern 2.0 PlayerBaseTick/MovementTicker already modeled this exact
        // LivingEntity#checkFallDamage path. Entity#move updates the position,
        // then LivingEntity#checkFallDamage calls updateFluidInteraction again
        // when the entity is not in water. That second lava current does not
        // affect the packet-visible move which just completed; it does affect
        // deltaMovement before block speed and travel drag derive the next tick.
        Vec3 current = WaterCurrent.calculateLavaCurrent(player, context, context.getEnd(), postMoveDelta);
        return current != null && Double.isFinite(current.x) && Double.isFinite(current.y) && Double.isFinite(current.z)
                ? postMoveDelta.add(current)
                : postMoveDelta;
    }

    private static OptionalDouble requiredPacketVisibleSlimeBounceY(PredictionResult result, double clippedY) {
        if (result.getInitialStartingVel() == null
                || result.getCollideAxisData().getYNeg() == null
                || !result.getCollideAxisData().getYNeg().isLikelyCollide()) {
            return OptionalDouble.empty();
        }

        SimulationContext context = result.getSimulationContext();
        if (context == null
                || context.getVehicle() != null
                || context.isSneaking()
                || context.getWorldData() == null) {
            return OptionalDouble.empty();
        }

        Material onBlock = context.getWorldData().getOnBlock();
        GrimPlayer player = result.getPlayer();
        boolean use26Dot2 = player != null && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        if (use26Dot2 ? !isBouncyBlock(onBlock) : onBlock != Material.SLIME_BLOCK) {
            return OptionalDouble.empty();
        }

        double deltaY = result.getInitialStartingVel().y;
        if (deltaY >= 0.0D) {
            return OptionalDouble.empty();
        }

        double bounceY;
        if (use26Dot2) {
            // MCP-Reborn 26.2 Entity#restituteMovementAfterCollisions (Entity.java:803-844)
            double gravity = effectiveGravity(player);
            if (-deltaY < gravity || onBlock == Material.HONEY_BLOCK) {
                // Below the effective-gravity impact speed (or inside
                // #suppresses_bounce) the 26.2 client does not bounce at all.
                return OptionalDouble.empty();
            }
            bounceY = restitutedBounceY(player, deltaY, clippedY, gravity, blockBounciness(result, onBlock));
        } else {
            bounceY = -deltaY;
        }
        double packetVisibleThreshold = player == null ? 2.0E-4D : player.getMovementThreshold();
        return bounceY > packetVisibleThreshold ? OptionalDouble.of(bounceY) : OptionalDouble.empty();
    }

    private static boolean isBouncyBlock(Material onBlock) {
        // Blocks registered with a bounceRestitution in the 26.2 client
        // registry (MCP-Reborn Blocks.java): slime (1.0) and beds (0.75).
        return onBlock == Material.SLIME_BLOCK || NmsBlockTags.isBed(onBlock);
    }

    private static double blockBounciness(PredictionResult result, Material onBlock) {
        // MCP-Reborn 26.2 Entity#getBlockBounciness: non-living entities (such as
        // boats and minecarts) only receive 80% of the block's restitution.
        double restitution = onBlock == Material.SLIME_BLOCK ? 1.0D : 0.75D;
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        boolean living = vehicle == null || vehicle.isLivingEntity();
        return living ? restitution : restitution * 0.8D;
    }

    private static double restitutedBounceY(GrimPlayer player, double deltaY, double clippedY, double gravity, double blockRestitution) {
        double restitution = Math.max(player.compensatedEntities.getEntityInControl().bounciness, blockRestitution);
        double airDrag = Friction.computeModifiedFriction(0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier);
        return restitutedBounceY(deltaY, clippedY, gravity, airDrag, restitution);
    }

    static double restitutedBounceY(double deltaY, double clippedY, double gravity, double airDrag, double restitution) {
        // MCP-Reborn 26.2 Entity#restituteMovementAfterCollisions uses this raw
        // ratio. It deliberately does not clamp it: collision resolution can
        // return movement opposite to deltaMovement when resolving an overlap.
        double portionWithMovement = deltaY == 0.0D ? 0.0D : clippedY / deltaY;
        double effectiveDrag = Mth.lerp(portionWithMovement, 1.0D, airDrag);
        return (portionWithMovement * gravity - deltaY) * effectiveDrag * restitution;
    }

    private static double effectiveGravity(GrimPlayer player) {
        // MCP-Reborn LivingEntity#getEffectiveGravity: slow falling while falling
        // caps gravity at 0.01; the base value is the gravity attribute (0.08).
        double gravity = player.compensatedEntities.getEntityInControl().gravity;
        if (player.compensatedEntities.getSlowFallingAmplifier() != null) {
            gravity = Math.min(gravity, 0.01D);
        }
        return gravity;
    }

    private static boolean shouldRetainItemControlledZeroMoveRealities(GrimPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity vehicle = context == null ? null : context.getVehicle();
        if (vehicle == null
                || !isItemControlledRideable(vehicle)
                || context.getTarget().lengthSqr() > 1.0E-14D
                || result.getValidMovements() == null
                || result.getValidMovements().getClosestTrace() == null) {
            return false;
        }

        // Entity#move can skip Entity#setPos for tiny/collided movement while
        // LivingEntity#travel still carries the post-input deltaMovement into the
        // next tick. A zero MoveVehicle packet therefore does not distinguish the
        // sibling item-controlled starting velocities that collapse to the same
        // root position through this guard.
        return result.getValidMovements().getClosestTrace().position().hasReason("Entity#move setPos guard");
    }

    private static boolean isItemControlledRideable(PacketEntity vehicle) {
        return vehicle.type == EntityTypesCompat.PIG
                || vehicle.type == EntityTypesCompat.STRIDER;
    }

    private static boolean consumedRequiredStuckSpeedThisMove(PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        if (context == null) {
            return false;
        }

        Vec3 lastStuckSpeed = context.getLastStuckSpeed();
        return lastStuckSpeed != null
                && lastStuckSpeed.distanceToSqr(new Vec3(1.0D, 1.0D, 1.0D)) > 1.0E-14;
    }

    private static void addVelocityCandidates(Set<Vec3> candidates, Vec3 diff, boolean canZeroX, boolean canZeroY, boolean canZeroZ) {
        for (int mask = 0; mask < 8; mask++) {
            if (((mask & 1) != 0 && !canZeroX)
                    || ((mask & 2) != 0 && !canZeroY)
                    || ((mask & 4) != 0 && !canZeroZ)) {
                continue;
            }

            candidates.add(new Vec3(
                    (mask & 1) != 0 ? 0 : diff.x,
                    (mask & 2) != 0 ? 0 : diff.y,
                    (mask & 4) != 0 ? 0 : diff.z
            ));
        }
    }

    private static void addPostMoveDeltaMovementCandidates(Set<Vec3> candidates, PredictionResult result, boolean canZeroX, boolean canZeroY, boolean canZeroZ, double clippedY) {
        if (result.getInitialStartingVel() == null) {
            return;
        }

        // MCP-Reborn Entity#move does not generally replace deltaMovement with
        // the packet-visible displacement. It zeros collided horizontal axes
        // after collision, lets the landed block update vertical motion, applies
        // block speed factor, then LivingEntity#travelInWater/#travelInAir apply
        // drag/gravity to that stored deltaMovement for the next tick. A fully
        // collided or movement-thresholded packet can therefore have a zero
        // displacement while still carrying a non-zero next-tick root velocity.
        Vec3 postMoveDelta = result.getInitialStartingVel();
        GrimPlayer player = result.getPlayer();
        boolean use26Dot2 = player != null && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        if (use26Dot2) {
            // MCP-Reborn 26.2 Entity#restituteMovementAfterCollisions reflects
            // collided horizontal axes as -v * bounciness instead of zeroing them
            // (zero at the default bounciness attribute of 0.0).
            SimulationContext context = result.getSimulationContext();
            boolean suppressingBounce = context.getVehicle() == null && context.isSneaking();
            double bounciness = suppressingBounce ? 0.0D : player.compensatedEntities.getEntityInControl().bounciness;
            if (canZeroX) {
                postMoveDelta = new Vec3(-postMoveDelta.x * bounciness, postMoveDelta.y, postMoveDelta.z);
            }
            if (canZeroZ) {
                postMoveDelta = new Vec3(postMoveDelta.x, postMoveDelta.y, -postMoveDelta.z * bounciness);
            }
        }
        boolean verticalCollision = (result.getCollideAxisData().getYPos() != null && result.getCollideAxisData().getYPos().isLikelyCollide())
                || (result.getCollideAxisData().getYNeg() != null && result.getCollideAxisData().getYNeg().isLikelyCollide());
        if (verticalCollision) {
            postMoveDelta = new Vec3(postMoveDelta.x, applyPostVerticalCollisionBlockVelocity(result, postMoveDelta.y, clippedY), postMoveDelta.z);
            canZeroY = false;
        }
        addVelocityCandidates(candidates, postMoveDelta, canZeroX, canZeroY, canZeroZ);
    }

    private static double applyPostVerticalCollisionBlockVelocity(PredictionResult result, double deltaY, double clippedY) {
        Material onBlock = result.getSimulationContext().getWorldData().getOnBlock();
        boolean suppressingBounce = result.getSimulationContext().getVehicle() == null && result.getSimulationContext().isSneaking();
        GrimPlayer player = result.getPlayer();

        if (player != null && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
            // MCP-Reborn 26.2 Entity#restituteMovementAfterCollisions (Entity.java:803-844):
            // sneaking zeroes restitution; otherwise a downward collision bounces
            // with the higher of the bounciness attribute and the block restitution,
            // while a ceiling collision restitutes with the bounciness attribute.
            if (suppressingBounce) {
                return 0.0D;
            }

            double gravity = effectiveGravity(player);
            double bounciness = player.compensatedEntities.getEntityInControl().bounciness;
            double restitution = bounciness;
            if (deltaY < 0.0D) {
                // #suppresses_bounce (vanilla: honey block) and the impact-speed
                // gate both force restitution to zero.
                if (-deltaY < gravity || onBlock == Material.HONEY_BLOCK) {
                    restitution = 0.0D;
                } else {
                    restitution = Math.max(restitution, blockBounciness(result, onBlock));
                }
            }
            if (restitution <= 0.0D) {
                return 0.0D;
            }
            return restitutedBounceY(player, deltaY, clippedY, gravity, restitution);
        }

        if (suppressingBounce) {
            return 0.0D;
        }

        if (deltaY < 0.0D) {
            if (onBlock == Material.SLIME_BLOCK) {
                return -deltaY;
            }

            if (NmsBlockTags.isBed(onBlock)) {
                return -deltaY * 0.66F;
            }
        }

        if (onBlock == Material.SLIME_BLOCK || NmsBlockTags.isBed(onBlock)) {
            return deltaY;
        }

        return 0.0D;
    }

    public record Candidates(Set<Vec3> velocities) {
    }
}
