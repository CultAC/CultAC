package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Ladder;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

public final class VelocityCandidates {
    private static final Vec3 NO_STUCK_SPEED = new Vec3(1.0D, 1.0D, 1.0D);

    private VelocityCandidates() {}

    public static List<PredictionResult> nextTickVelocityRealities(CultPlayer player, PredictionResult result) {
        if (shouldRetainItemControlledZeroMoveRealities(player, result)) {
            List<PredictionResult> realities = new ArrayList<>();
            realities.add(result);
            realities.addAll(result.getAnyReality());
            return realities;
        }

        // Do not carry current-tick alternatives into the next tick's velocity set.
        return List.of(result);
    }

    /** Adds the candidates to the empty {@code validPlayerStartingVels} and returns it. */
    static Set<Vec3> derive(
            PredictionResult result, PredictionResult lastPrediction, Vec3 diff, Set<Vec3> validPlayerStartingVels) {
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

        CultPlayer player = result.getPlayer();
        if (player != null) {
            Vec3 moved = diff;
            diff = applyPostMoveLivingFluidCurrent(player, result, diff);
            Vec3 edge = moved.equals(diff)
                            && !hasUnknownStuckSpeed(lastPrediction)
                            && !consumedRequiredStuckSpeedThisMove(result)
                    ? SneakEdgeBackoff.deltaMovement(result, diff)
                    : null;
            if (edge != null) {
                validPlayerStartingVels.add(edge);
                return validPlayerStartingVels;
            }
        }

        boolean isX = result.getCollideAxisData().getX().isLikelyCollide();
        boolean isY = result.getCollideAxisData().getYPos() != null
                        && result.getCollideAxisData().getYPos().isLikelyCollide()
                || result.getCollideAxisData().getYNeg() != null
                        && result.getCollideAxisData().getYNeg().isLikelyCollide()
                || result.getValidMovements().isCanStep();
        boolean isZ = result.getCollideAxisData().getZ().isLikelyCollide();

        // Before 1.21.2, BouncyBlock handles vertical bounce uncertainty. Do not
        // replace its falling/zero carry with a mandatory bounce from a probe.
        OptionalDouble requiredPostCollisionY =
                player != null ? requiredPacketVisibleSlimeBounceY(result, collisionMovementY) : OptionalDouble.empty();
        if (requiredPostCollisionY.isPresent()) {
            diff = new Vec3(diff.x, requiredPostCollisionY.getAsDouble(), diff.z);
            isY = false;
        }
        boolean stepped = requiredPostCollisionY.isEmpty()
                && result.getValidMovements().isCanStep()
                && result.getInitialStartingVel() != null
                && result.getRealities().stream()
                        .allMatch(r -> r.getValidMovements().isCanStep());
        if (stepped) {
            diff = new Vec3(
                    diff.x,
                    applyPostVerticalCollisionBlockVelocity(result, preCollisionVelocity(result).y, collisionMovementY),
                    diff.z);
            isY = false;
        }

        addVelocityCandidates(validPlayerStartingVels, diff, isX, isY, isZ);
        addPostMoveDeltaMovementCandidates(validPlayerStartingVels, result, isX, isY, isZ, collisionMovementY, stepped);

        if (hasUnknownStuckSpeed(lastPrediction)) {
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

        return validPlayerStartingVels;
    }

    private static boolean hasUnknownStuckSpeed(PredictionResult lastPrediction) {
        return lastPrediction != null
                && lastPrediction
                                .getSimulationContext()
                                .getWorldData()
                                .getStuckSpeed()
                                .getUnknownStuckSpeedMultiplier()
                        != null;
    }

    private static Vec3 applyPostMoveLivingFluidCurrent(
            CultPlayer player, PredictionResult result, Vec3 postMoveDelta) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity vehicle = context == null ? null : context.getVehicle();
        if (context == null
                || vehicle != null && (EntityTypeUtil.isBoat(vehicle.type) || vehicle instanceof PacketEntityHappyGhast)
                || player.getClientVersion() == ClientVersion.V_1_21_4
                || context.getWorldData().getInWater().determineOptimistically()) {
            return postMoveDelta;
        }

        // Entity#move updates the position, then LivingEntity#checkFallDamage
        // pushes the entity by the water current at its post-move box when it
        // was not in water before the move. The current does not affect the
        // packet-visible move which just completed; it does affect deltaMovement
        // before block speed and travel drag derive the next tick.
        // The current's 0.003 minimum-push test reads deltaMovement before Entity#move zeroes collided axes.
        Vec3 moving = preCollisionDeltaMovement(result, postMoveDelta);
        Vec3 delta = postMoveDelta;
        if (vehicle == null || vehicle.isLivingEntity()) {
            Vec3 water = WaterCurrent.calculateWaterCurrent(player, context, context.getEnd(), moving);
            delta = addFinite(delta, water);
            moving = addFinite(moving, water);
        }
        // Before 26.1 checkFallDamage runs updateInWaterStateAndDoWaterCurrentPushing, which pushes water only.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                && context.getWorldData().getInLava().determinePessimistically()) {
            delta = addFinite(delta, WaterCurrent.calculateLavaCurrent(player, context, context.getEnd(), moving));
        }
        return delta;
    }

    /**
     * Entity#deltaMovement while Entity#move runs checkFallDamage: the selected velocity plus the accepted
     * LivingEntity#moveRelative input, or zero when the move consumed a stuck-speed multiplier.
     */
    private static Vec3 preCollisionDeltaMovement(PredictionResult result, Vec3 fallback) {
        if (consumedRequiredStuckSpeedThisMove(result)) {
            return Vec3.ZERO;
        }
        if (result.getValidMovements() == null
                || result.getValidMovements().getClosestTrace() == null
                || result.getAcceptedClosestToTarget() == null) {
            return fallback;
        }
        return result.getValidMovements()
                .getClosestTrace()
                .preCollisionVelocity()
                .add(acceptedInput(result));
    }

    /** The accepted LivingEntity#moveRelative input: the circular allowance the closest movement used. */
    private static Vec3 acceptedInput(PredictionResult result) {
        return result.getLegacyLikePredictionVector().subtract(result.getAcceptedClosestToTarget());
    }

    private static Vec3 addFinite(Vec3 delta, Vec3 current) {
        return current != null && Double.isFinite(current.x) && Double.isFinite(current.y) && Double.isFinite(current.z)
                ? delta.add(current)
                : delta;
    }

    private static OptionalDouble requiredPacketVisibleSlimeBounceY(PredictionResult result, double clippedY) {
        if (result.getInitialStartingVel() == null
                || result.getCollideAxisData().getYNeg() == null
                || !result.getCollideAxisData().getYNeg().isLikelyCollide()) {
            return OptionalDouble.empty();
        }

        SimulationContext context = result.getSimulationContext();
        if (context == null || context.getVehicle() != null || context.isSneaking() || context.getWorldData() == null) {
            return OptionalDouble.empty();
        }

        int onBlock = context.getWorldData().getOnBlock();
        CultPlayer player = result.getPlayer();
        boolean use26Dot2 = player != null && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        if (use26Dot2 ? !isBouncyBlock(onBlock) : !BlockIds.is(onBlock, BlockIds.SLIME_BLOCK)) {
            return OptionalDouble.empty();
        }

        double deltaY = preCollisionVelocity(result).y;
        if (deltaY >= 0.0D) {
            return OptionalDouble.empty();
        }

        double bounceY;
        if (use26Dot2) {
            // MCP-Reborn 26.2 Entity#restituteMovementAfterCollisions (Entity.java:803-844)
            double gravity = effectiveGravity(player);
            if (suppressesBounceAtGravity(player.getClientVersion(), deltaY, gravity)
                    || BlockIds.is(onBlock, BlockIds.HONEY_BLOCK)) {
                // Below the effective-gravity impact speed (or inside
                // #suppresses_bounce) the 26.2 client does not bounce at all.
                return OptionalDouble.empty();
            }
            bounceY = restitutedBounceY(
                    player, deltaY, clippedY, gravity, blockBounciness(onBlock, livingMover(context)));
        } else {
            bounceY = -deltaY;
        }
        double packetVisibleThreshold = player == null ? 2.0E-4D : player.getMovementThreshold();
        return bounceY > packetVisibleThreshold && mayHaveClippedVertically(result)
                ? OptionalDouble.of(bounceY)
                : OptionalDouble.empty();
    }

    /**
     * Whether Entity#collide may have changed the accepted move's vertical movement. Entity#move sets
     * verticalCollision only when it did (Entity.java:800, 770 in 26.2, {@code delta.y != movement.y}), and
     * Entity#restituteMovementAfterCollisions bounces only on a vertical collision.
     */
    private static boolean mayHaveClippedVertically(PredictionResult result) {
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        if (trace == null
                || trace.preCollisionMovement() == null
                || trace.collisionBaseOffset().lengthSqr() > 1.0E-14) {
            return true;
        }
        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
        if (stuck != null && stuck.y != 1.0D) {
            return true;
        }
        return trace.position().y != trace.preCollisionMovement().y && restsOnCollider(result, trace);
    }

    /**
     * Whether the box that {@code trace}'s move ends in rests on a block or a possible entity box. Entity#collide
     * clips a downward move only onto a collider's top, so after that clip the box cannot move down at all.
     */
    private static boolean restsOnCollider(PredictionResult result, MovementTrace trace) {
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        if (player == null || context == null) {
            return true;
        }
        SimpleCollisionBox end = context.getFromActualPose().copy().offset(trace.position());
        double probe = -SimpleCollisionBox.COLLISION_EPSILON;
        return Collisions.collideWithAdditionalCollisionBoxes(
                                player, end, 0.0D, probe, 0.0D, player.entityCollisionBoxes())
                        .y
                != probe;
    }

    /**
     * The deltaMovement Entity#move received. LivingEntity#travelFallFlying and AbstractBoat#tick replace it before
     * the move in every version (1.21.11 LivingEntity.java:2357, AbstractBoat.java:233-239), so a gliding or boat
     * tick never carries the velocity it started from.
     */
    public static Vec3 preCollisionVelocity(PredictionResult result) {
        return result.getValidMovements().getClosestTrace().preCollisionVelocity();
    }

    private static boolean isBouncyBlock(int onBlock) {
        if (onBlock < 0) return false;
        // Blocks registered with a bounceRestitution in the 26.2 client
        // registry (MCP-Reborn Blocks.java): slime (1.0) and beds (0.75).
        return BlockIds.is(onBlock, BlockIds.SLIME_BLOCK)
                || BlockIds.is(onBlock, BlockIds.SHELF_MUSHROOM)
                || (ClientBlockProperties.isBed(onBlock) && !BlockIds.is(onBlock, BlockIds.STRAW_BED));
    }

    private static double blockBounciness(int onBlock, boolean living) {
        // MCP-Reborn 26.2 Entity#getBlockBounciness: non-living entities (such as
        // boats and minecarts) only receive 80% of the block's restitution.
        double restitution = BlockIds.is(onBlock, BlockIds.SLIME_BLOCK) ? 1.0D : isBouncyBlock(onBlock) ? 0.75D : 0.0D;
        return living ? restitution : restitution * 0.8D;
    }

    private static boolean livingMover(SimulationContext context) {
        PacketEntity vehicle = context.getVehicle();
        return vehicle == null || vehicle.isLivingEntity();
    }

    private static double restitutedBounceY(
            CultPlayer player, double deltaY, double clippedY, double gravity, double blockRestitution) {
        double restitution = Math.max(player.compensatedEntities.getEntityInControl().bounciness, blockRestitution);
        double airDrag = Friction.computeModifiedFriction(
                0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier);
        return restitutedBounceY(deltaY, clippedY, gravity, airDrag, restitution);
    }

    static double restitutedBounceY(
            double deltaY, double clippedY, double gravity, double airDrag, double restitution) {
        // MCP-Reborn 26.2 Entity#restituteMovementAfterCollisions uses this raw
        // ratio. It deliberately does not clamp it: collision resolution can
        // return movement opposite to deltaMovement when resolving an overlap.
        double portionWithMovement = deltaY == 0.0D ? 0.0D : clippedY / deltaY;
        double effectiveDrag = CultMath.lerp(portionWithMovement, 1.0D, airDrag);
        return (portionWithMovement * gravity - deltaY) * effectiveDrag * restitution;
    }

    static boolean suppressesBounceAtGravity(ClientVersion version, double deltaY, double gravity) {
        // 26.3 Entity#restituteMovementAfterCollisions includes exact equality.
        return version.isNewerThanOrEquals(ClientVersion.V_26_3) ? -deltaY <= gravity : -deltaY < gravity;
    }

    private static double effectiveGravity(CultPlayer player) {
        // MCP-Reborn LivingEntity#getEffectiveGravity: slow falling while falling
        // caps gravity at 0.01; the base value is the gravity attribute (0.08).
        double gravity = player.compensatedEntities.getEntityInControl().gravity;
        if (player.compensatedEntities.getSlowFallingAmplifier() != null) {
            gravity = Math.min(gravity, 0.01D);
        }
        return gravity;
    }

    private static boolean shouldRetainItemControlledZeroMoveRealities(CultPlayer player, PredictionResult result) {
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
        return vehicle.type == EntityTypeIds.PIG || vehicle.type == EntityTypeIds.STRIDER;
    }

    private static boolean consumedRequiredStuckSpeedThisMove(PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        if (context == null) {
            return false;
        }

        Vec3 lastStuckSpeed = context.getLastStuckSpeed();
        return lastStuckSpeed != null && lastStuckSpeed.distanceToSqr(NO_STUCK_SPEED) > 1.0E-14;
    }

    private static void addVelocityCandidates(
            Set<Vec3> candidates, Vec3 diff, boolean canZeroX, boolean canZeroY, boolean canZeroZ) {
        for (int mask = 0; mask < 8; mask++) {
            if (((mask & 1) != 0 && !canZeroX) || ((mask & 2) != 0 && !canZeroY) || ((mask & 4) != 0 && !canZeroZ)) {
                continue;
            }
            // Zeroing an axis that is already +0.0 repeats the vector of the smaller mask added before it.
            if (((mask & 1) != 0 && Double.compare(diff.x, 0.0D) == 0)
                    || ((mask & 2) != 0 && Double.compare(diff.y, 0.0D) == 0)
                    || ((mask & 4) != 0 && Double.compare(diff.z, 0.0D) == 0)) {
                continue;
            }

            candidates.add(
                    mask == 0
                            ? (diff.getClass() == Vec3.class ? diff : new Vec3(diff.x, diff.y, diff.z))
                            : new Vec3(
                                    (mask & 1) != 0 ? 0 : diff.x,
                                    (mask & 2) != 0 ? 0 : diff.y,
                                    (mask & 4) != 0 ? 0 : diff.z));
        }
    }

    private static void addPostMoveDeltaMovementCandidates(
            Set<Vec3> candidates,
            PredictionResult result,
            boolean canZeroX,
            boolean canZeroY,
            boolean canZeroZ,
            double clippedY,
            boolean stepped) {
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
        // LivingEntity#travelFallFlying has already replaced deltaMovement
        // before Entity#move. Carry that velocity on uncollided axes as well;
        // carrying the initial vector would retain a stale pre-elytra candidate.
        // In LivingEntity#handleRelativeFrictionAndCalculateMovement,
        // LivingEntity#moveRelative adds the input to deltaMovement and
        // LivingEntity#handleOnClimbable then clamps it on a climbable, both
        // before Entity#move, so the carried velocity holds the accepted input,
        // clamped while climbing. The travelFallFlying glide adds no input; its
        // carried velocity is the accepted glide movement.
        Vec3 carried = preCollisionVelocity(result);
        Vec3 postMoveDelta = carried;
        Vec3 input = null;
        if (result.getValidMovements().getClosestTrace() != null && result.getAcceptedClosestToTarget() != null) {
            input = acceptedInput(result);
            postMoveDelta = postMoveDelta.add(input);
        }
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        DesyncStatus climbing = player == null ? DesyncStatus.FALSE : Ladder.clampsDeltaMovement(context);
        if (climbing != DesyncStatus.TRUE) {
            addPostMoveDeltaMovementCandidates(
                    candidates, result, postMoveDelta, canZeroX, canZeroY, canZeroZ, clippedY, stepped);
        }
        if (climbing != DesyncStatus.FALSE) {
            // On a climbable the accepted input extends Ladder's clamp of the carried velocity.
            Vec3 climbingDelta = Ladder.handleOnClimbable(player, context, carried);
            if (input != null) climbingDelta = climbingDelta.add(input);
            addPostMoveDeltaMovementCandidates(
                    candidates,
                    result,
                    Ladder.handleOnClimbable(player, context, climbingDelta),
                    canZeroX,
                    canZeroY,
                    canZeroZ,
                    clippedY,
                    stepped);
        }
    }

    /** Adds what Entity#move leaves of the deltaMovement {@code postMoveDelta} it moved by. */
    private static void addPostMoveDeltaMovementCandidates(
            Set<Vec3> candidates,
            PredictionResult result,
            Vec3 postMoveDelta,
            boolean canZeroX,
            boolean canZeroY,
            boolean canZeroZ,
            double clippedY,
            boolean stepped) {
        CultPlayer player = result.getPlayer();
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
        if (stepped || collidedVertically(result)) {
            postMoveDelta = new Vec3(
                    postMoveDelta.x,
                    applyPostVerticalCollisionBlockVelocity(result, postMoveDelta.y, clippedY),
                    postMoveDelta.z);
            canZeroY = false;
        }
        addVelocityCandidates(candidates, postMoveDelta, canZeroX, canZeroY, canZeroZ);
    }

    /** Whether {@code result}'s move likely collided vertically. */
    public static boolean collidedVertically(PredictionResult result) {
        return result.getCollideAxisData().getYPos() != null
                        && result.getCollideAxisData().getYPos().isLikelyCollide()
                || result.getCollideAxisData().getYNeg() != null
                        && result.getCollideAxisData().getYNeg().isLikelyCollide();
    }

    /** Entity#move's vertical deltaMovement once {@code result}'s vertical collision clipped it to {@code movedY}. */
    public static double afterVerticalCollision(PredictionResult result, double movedY) {
        return applyPostVerticalCollisionBlockVelocity(result, preCollisionVelocity(result).y, movedY);
    }

    private static double applyPostVerticalCollisionBlockVelocity(
            PredictionResult result, double deltaY, double clippedY) {
        SimulationContext context = result.getSimulationContext();
        return afterVerticalCollision(
                result.getPlayer(),
                context.getWorldData().getOnBlock(),
                context.getVehicle() == null && context.isSneaking(),
                livingMover(context),
                deltaY,
                clippedY);
    }

    /**
     * Entity#move's vertical deltaMovement after a vertical collision clipped {@code deltaY} to {@code clippedY} with
     * {@code onBlock} at Entity#getOnPosLegacy.
     */
    public static double afterVerticalCollision(
            CultPlayer player, int onBlock, boolean suppressingBounce, boolean living, double deltaY, double clippedY) {
        return afterVerticalCollision(player, onBlock, suppressingBounce, living, deltaY < 0.0D, deltaY, clippedY);
    }

    /**
     * Entity#move's vertical deltaMovement {@code deltaY} after a vertical collision clipped the move to
     * {@code clippedY}; {@code below} is Entity#verticalCollisionBelow, whether the move went down.
     */
    public static double afterVerticalCollision(
            CultPlayer player,
            int onBlock,
            boolean suppressingBounce,
            boolean living,
            boolean below,
            double deltaY,
            double clippedY) {
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
            if (below) {
                // #suppresses_bounce (vanilla: honey block) and the impact-speed
                // gate both force restitution to zero.
                if (suppressesBounceAtGravity(player.getClientVersion(), deltaY, gravity)
                        || BlockIds.is(onBlock, BlockIds.HONEY_BLOCK)) {
                    restitution = 0.0D;
                } else {
                    restitution = Math.max(restitution, blockBounciness(onBlock, living));
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
            if (BlockIds.is(onBlock, BlockIds.SLIME_BLOCK)) {
                return -deltaY;
            }

            if (ClientBlockProperties.isBed(onBlock)) {
                return -deltaY * 0.66F;
            }
        }

        if (BlockIds.is(onBlock, BlockIds.SLIME_BLOCK) || ClientBlockProperties.isBed(onBlock)) {
            return deltaY;
        }

        return 0.0D;
    }
}
