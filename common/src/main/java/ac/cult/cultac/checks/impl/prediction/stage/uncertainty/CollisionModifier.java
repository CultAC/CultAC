package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBoxPool;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.CompensatedBlockEntities;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

public class CollisionModifier implements UncertaintyHandler {
    // Fixed global iteration orders, preserving the existing candidate order without per-probe lists.
    private static final Collisions.Axis[][] PROBE_ORDERS = {
        {Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z},
        {Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X},
        {Collisions.Axis.X, Collisions.Axis.Z, Collisions.Axis.Y},
        {Collisions.Axis.Z, Collisions.Axis.X, Collisions.Axis.Y}
    };

    // Probe scratch: one instance probes on one thread at a time, and none of these escapes a probe.
    private final List<SimpleCollisionBox> probeCollisions = new ArrayList<>();
    private final List<SimpleCollisionBox> probeUnknown = new ArrayList<>();
    private final SimpleCollisionBox probeBox = new SimpleCollisionBox();
    private final SimpleCollisionBox probeEntityBox = new SimpleCollisionBox();
    private final SimpleCollisionBox probeBlockQuery = new SimpleCollisionBox();
    private final SimpleCollisionBox probeEntityScratch = new SimpleCollisionBox();
    private final SimpleCollisionBox probeEntityRegion = new SimpleCollisionBox();
    private final AxisResult positiveResult = new AxisResult(0, false);
    private final AxisResult negativeResult = new AxisResult(0, false);
    // An axis step's tests and move depend only on its axis, position and movements within one probe.
    private final ResolvedStep[] resolvedSteps = new ResolvedStep[PROBE_ORDERS.length * 3];
    private int resolvedStepCount;
    private final boolean javaTravelInput;

    public CollisionModifier() {
        this(true);
    }

    /** {@code javaTravelInput}: the setPos guard measures Java LivingEntity#travel's horizontal input. */
    public CollisionModifier(boolean javaTravelInput) {
        this.javaTravelInput = javaTravelInput;
    }

    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            MovementTrace trace,
            Vec3 end) {
        if (valid != null && valid.isTestingMaxStartingVelExtents(end)) {
            return trace;
        }

        // Entity#move records its attempted delta, after stuck scaling, for
        // checkInsideBlocks' axis order. Clipping can reverse the X/Z ordering.
        Vec3 stuckSpeed = trace.position().collisionStuckSpeedMultiplier(context);
        trace = trace.withPreCollisionMovement(
                stuckSpeed == null || stuckSpeed.x == 1.0D && stuckSpeed.y == 1.0D && stuckSpeed.z == 1.0D
                        ? trace.position()
                        : trace.position().multiply(stuckSpeed));
        List<CompensatedBlockEntities.ShulkerShove> shoves = shulkerBoxShovesAfterTravel(context);
        if (shoves == null || trace.shulkerBoxShove() != null) return collide(result, context, trace, end);
        return collide(result, context, shoveAfterTravel(player, context, trace, shoves), end);
    }

    /**
     * {@code trace} moved by the shulker box block shoves after its travel: Entity#collide clips the travel from the
     * packet's start, which Entity#onGround as the travel starts lets step up, and the shoves start from its end and
     * from the onGround its vertical collision below sets.
     */
    private static MovementTrace shoveAfterTravel(
            CultPlayer player,
            SimulationContext context,
            MovementTrace trace,
            List<CompensatedBlockEntities.ShulkerShove> shoves) {
        Vec3 stuckSpeed = context.getLastStuckSpeed();
        Vec3 delta = trace.preCollisionMovement();
        Vec3 travel = Collisions.collide(
                player,
                context.getFromActualPose(),
                delta.x,
                delta.y,
                delta.z,
                context.getLastOnGround().determineOptimistically());
        double travelLength = travel.lengthSqr();
        Vec3 end = travelLength > 1.0E-7D || delta.lengthSqr() - travelLength < 1.0E-7D
                ? context.getStart().add(travel)
                : context.getStart();
        boolean onGround = delta.y < 0.0D && delta.y != travel.y;
        // Player#tick ends with updatePlayerPose (26.3 :284), at the travel's end, before Level#tickBlockEntities.
        Pose pose = player.updatedPose(context.getWorldData().isSwimming(trace.position()), end.x, end.y, end.z);
        // LivingEntity#aiStep's applyEffectsFromBlocks sets the multiplier the first shove meets from the travel.
        SimpleCollisionBox travelFrom = context.getFromActualPose().copy();
        SimpleCollisionBox travelTo = travelFrom.copy().offset(end.subtract(context.getStart()));
        Vec3 travelStuckSpeed = WorldStageBuilder.stuckSpeedAlong(player, context, travelFrom, travelTo, end);
        ShulkerBoxShoveResult shove = player.compensatedWorld.shoveByShulkerBoxes(
                context, shoves, end, pose, onGround, true, travelStuckSpeed);
        if (shove == null) return trace;
        Vec3 movement = shove.movement();
        return new MovementTrace(
                trace.position(),
                trace.positionOnlyDelta().add(movement),
                trace.collisionBaseOffset()
                        .add(movement.multiply(1.0D / stuckSpeed.x, 1.0D / stuckSpeed.y, 1.0D / stuckSpeed.z)),
                trace.preCollisionVelocity(),
                trace.preCollisionMovement(),
                shove);
    }

    private MovementTrace collide(PredictionResult result, SimulationContext context, MovementTrace trace, Vec3 end) {
        Vec3 collisionBaseOffset = trace.collisionBaseOffset();
        if (collisionBaseOffset.lengthSqr() <= 1.0E-14) {
            return trace.withPosition(transformWithCollisions(
                    context, result.getCollideAxisData(), trace.position(), end, inputSource(result)));
        }

        // Piston/shulker shoves are separate position-only Entity#move calls.
        // Collision clipping applies to the player's movement vector, then the
        // already-proven external base displacement is re-applied once.
        Vec3 movementOnlyTarget = withoutPositionRounding(end.subtract(collisionBaseOffset), context.getEnd());
        PredVector clippedMovement = transformWithCollisions(
                context, result.getCollideAxisData(), trace.position(), movementOnlyTarget, inputSource(result));
        return trace.withPosition(clippedMovement.add(collisionBaseOffset, "external movement collision base"));
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            PredVector start,
            Vec3 end) {
        if (valid != null && valid.isTestingMaxStartingVelExtents(end)) return start;

        return transformWithCollisions(context, result.getCollideAxisData(), start, end, inputSource(result));
    }

    /**
     * Zeroes each axis of {@code movement} smaller than one ulp of the reported position there: Entity#setPos adds the
     * shove to the travel's end, and that rounding is all a stationary travel leaves after the shove is subtracted.
     */
    private static Vec3 withoutPositionRounding(Vec3 movement, Vec3 position) {
        if (position == null) return movement;
        boolean x = Math.abs(movement.x) < Math.ulp(position.x);
        boolean y = Math.abs(movement.y) < Math.ulp(position.y);
        boolean z = Math.abs(movement.z) < Math.ulp(position.z);
        return x || y || z ? new Vec3(x ? 0.0D : movement.x, y ? 0.0D : movement.y, z ? 0.0D : movement.z) : movement;
    }

    private PredictionResult inputSource(PredictionResult result) {
        return javaTravelInput ? result : null;
    }

    /**
     * Clips {@code start} like Entity#collide, then applies Entity#move's setPos guard. The guard sees the
     * horizontal input that {@code inputSource}'s circular input allowance adds towards {@code end}; a null
     * {@code inputSource} means no input.
     */
    public static PredVector transformWithCollisions(
            SimulationContext context,
            CollideAxisData collide,
            PredVector start,
            Vec3 end,
            PredictionResult inputSource) {
        if (collide instanceof EntityWorldCollisions entityWorlds) {
            // The client holds each entity at one position, so one world clips the whole move.
            PredVector nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (CollideAxisData world : entityWorlds.worlds()) {
                PredVector moved = transformWithCollisions(context, world, start, end, inputSource);
                double distance = moved.distanceToSqr(end);
                if (nearest == null || distance < nearestDistance) {
                    nearest = moved;
                    nearestDistance = distance;
                }
            }
            return nearest;
        }
        PredVector attempted = start;
        if (!isJavaVehicleMove(context)) {
            start = clampWithinCollisions(collide, start, end);
        } else if (collide.getUnknown().isEmpty()) {
            start = clipExactly(collide, start);
        } else {
            start = clipWithinBlocks(collide, start, end);
        }
        Vec3 stuckSpeed = attempted.collisionStuckSpeedMultiplier(context);
        if (applyEntityMovePositionCommitGuard(attempted, start, stuckSpeed) == start) return start;
        // LivingEntity#travel adds moveRelative's input before Entity#move; the guard measures the full delta.
        Vec3 input = horizontalInputTowards(start, end, inputSource);
        if (input != Vec3.ZERO
                && applyEntityMovePositionCommitGuard(attempted.add(input), start.add(input), stuckSpeed)
                        != Vec3.ZERO) {
            return start;
        }
        return start.with(Vec3.ZERO, "Entity#move setPos guard");
    }

    /** Whether {@code context} moves a vehicle a Java client controls, which Entity#collide clips axis by axis. */
    public static boolean isJavaVehicleMove(SimulationContext context) {
        return context.getVehicle() != null && context.getBedrockInput() == null;
    }

    private static Vec3 horizontalInputTowards(Vec3 movement, Vec3 end, PredictionResult inputSource) {
        if (inputSource == null || inputSource.getSimulationContext() == null) return Vec3.ZERO;
        if (movement instanceof PredVector vector && vector.isInputApplied()) return Vec3.ZERO;
        double dx = end.x - movement.x;
        double dz = end.z - movement.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(distance) || distance < 1.0E-5F) return Vec3.ZERO;
        double radius = inputSource.getHorizontalInputRadius();
        if (!(radius > 0.0D)) return Vec3.ZERO;
        double scale = Math.min(radius, distance) / distance;
        return new Vec3(dx * scale, 0.0D, dz * scale);
    }

    private static Vec3 applyEntityMovePositionCommitGuard(
            Vec3 attemptedMovement, Vec3 clippedMovement, Vec3 stuckSpeed) {
        double mx = stuckSpeed == null ? 1.0D : stuckSpeed.x;
        double my = stuckSpeed == null ? 1.0D : stuckSpeed.y;
        double mz = stuckSpeed == null ? 1.0D : stuckSpeed.z;
        double ax = attemptedMovement.x * mx, ay = attemptedMovement.y * my, az = attemptedMovement.z * mz;
        double cx = clippedMovement.x * mx, cy = clippedMovement.y * my, cz = clippedMovement.z * mz;
        double clippedLengthSqr = cx * cx + cy * cy + cz * cz;
        if (clippedLengthSqr <= 1.0E-7D && ax * ax + ay * ay + az * az - clippedLengthSqr >= 1.0E-7D) {
            return Vec3.ZERO;
        }

        return clippedMovement;
    }

    private static PredVector clampWithinCollisions(CollideAxisData collide, PredVector start, Vec3 end) {
        // Collisions mean that movement from the start of the collision to the max extent is possible
        if (isValidCollision(collide.getX(), start.x, end.x)) {
            double clamped = CultMath.clamp(end.x, start.x, collide.getX().getResult());
            start = start.withXYZOrSelf(clamped, start.y, start.z, "collide x");
        }

        if (isValidCollision(collide.getZ(), start.z, end.z)) {
            double clamped = CultMath.clamp(end.z, start.z, collide.getZ().getResult());
            start = start.withXYZOrSelf(start.x, start.y, clamped, "collide z");
        }

        if (collide.getYNeg() != null
                && collide.getYNeg().isLikelyCollide()
                && start.y < 0
                && start.y < collide.getYNeg().getResult()
                // VoxelShape#collideX keeps a floor up to 1e-7 above the feet, so a fall can clip slightly upward.
                && end.y <= Math.max(0.0D, collide.getYNeg().getResult())
                && end.y > start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYNeg().getResult());
            start = start.withY(clamped, "collide -y");
        }

        if (collide.getYPos() != null
                && collide.getYPos().isLikelyCollide()
                && start.y > 0
                && start.y > collide.getYPos().getResult()
                // Mirrored: a ceiling up to 1e-7 below the head can clip a rise slightly downward.
                && end.y >= Math.min(0.0D, collide.getYPos().getResult())
                && end.y < start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYPos().getResult());
            start = start.withY(clamped, "collide +y");
        }
        return start;
    }

    /** Entity#collide: the move along each axis stops at the first shape in its way. */
    private static PredVector clipExactly(CollideAxisData collide, PredVector movement) {
        movement = movement.withXYZOrSelf(movement.x, clipY(collide, movement.y), movement.z, "collide y");
        movement = movement.withXYZOrSelf(clip(collide.getX(), movement.x), movement.y, movement.z, "collide x");
        return movement.withXYZOrSelf(movement.x, movement.y, clip(collide.getZ(), movement.z), "collide z");
    }

    /**
     * {@link #clipExactly} against the blocks, where {@code collide} leaves out entity shapes Entity#collide may also
     * meet: those only stop an axis sooner, so each axis ends between its start and where the blocks stop it.
     */
    private static PredVector clipWithinBlocks(CollideAxisData collide, PredVector movement, Vec3 end) {
        PredVector blocks = clipExactly(collide, movement);
        return blocks.withXYZOrSelf(
                towardsStart(end.x, blocks.x),
                towardsStart(end.y, blocks.y),
                towardsStart(end.z, blocks.z),
                "collide unknown entities");
    }

    private static double towardsStart(double end, double limit) {
        return CultMath.clamp(end, Math.min(0.0D, limit), Math.max(0.0D, limit));
    }

    private static double clipY(CollideAxisData collide, double movement) {
        CollideAxisData.CollideResult floor = collide.getYNeg();
        if (movement < 0.0D && floor != null && floor.isLikelyCollide() && movement < floor.getResult()) {
            return floor.getResult();
        }
        CollideAxisData.CollideResult ceiling = collide.getYPos();
        if (movement > 0.0D && ceiling != null && ceiling.isLikelyCollide() && movement > ceiling.getResult()) {
            return ceiling.getResult();
        }
        return movement;
    }

    /** {@code movement} along one horizontal axis, stopped at the shape {@code data} found on its side. */
    private static double clip(CollideAxisData.CollideResult data, double movement) {
        if (!data.isLikelyCollide() || Math.signum(movement) != data.getSide()) return movement;
        return Math.abs(movement) < Math.abs(data.getResult()) ? movement : data.getResult();
    }

    private static boolean isValidCollision(CollideAxisData.CollideResult data, double start, double end) {
        if (!data.isLikelyCollide()) return false;
        // The player wouldn't hit the collision yet with their regular velocity
        if (Math.abs(start) < Math.abs(data.getResult())) return false;
        // The signs are correct for a collision to occur in this direction
        // TODO: Differentiate between positive and negative collisions
        return true;
    }

    // Let's rely on the player onGround
    // When moving in a direction, try to expand the bounding box by the movement amount by running collisions
    // This expansion is done before EACH time we run an axis collision
    // If we can expand in that direction, then do so.
    // We then take this expanded bounding box then run collisions again and note the minimum collision amount
    // - To test collisions in the next direction, we take the minimum extent of the bounding box and move it.
    //
    // We should probe collisions in all directions that we care about, really.
    // - When the player isn't moving in a direction, we should probe collision epsilon in both pos and negative
    // - When the player could technically move in both pos and negative Y, probe both! (for stepping)
    public CollideAxisData probeCollisions(
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents) {
        return probeCollisions(
                player,
                context,
                minY,
                target,
                playerPos,
                attemptedMovementExtents,
                new ProbeDimensions(0.6F, 0.6F, 1.8F, SimpleCollisionBox.AxisEpsilon.JAVA));
    }

    public CollideAxisData probeCollisions(
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents,
            ProbeDimensions dimensions) {
        return probeCollisions(
                player,
                context,
                minY,
                target,
                playerPos,
                attemptedMovementExtents,
                dimensions,
                JavaCollisionState.current(player));
    }

    public CollideAxisData probeCollisions(
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents,
            ProbeDimensions dimensions,
            JavaCollisionState actor) {
        return probeCollisions(
                player, context, minY, target, playerPos, attemptedMovementExtents, dimensions, actor, false, 0.0D);
    }

    /**
     * Probes the collisions around the attempted movement. {@code horizontalInputReach} is how far input the
     * attempted extents leave out can carry the movement on X and Z.
     */
    public CollideAxisData probeCollisions(
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents,
            ProbeDimensions dimensions,
            JavaCollisionState actor,
            boolean followCandidateVerticalMovement,
            double horizontalInputReach) {
        CollisionBoxPool pool = CollisionBoxPool.current();
        int mark = pool.mark();
        try {
            return probeCollisions(
                    player,
                    context,
                    minY,
                    target,
                    playerPos,
                    attemptedMovementExtents,
                    dimensions,
                    actor,
                    followCandidateVerticalMovement,
                    horizontalInputReach,
                    pool);
        } finally {
            probeCollisions.clear();
            pool.release(mark);
        }
    }

    /** The shulker box block shoves a client from 26.3 applies after this travel: PistonPushes's, when set. */
    private static List<CompensatedBlockEntities.ShulkerShove> shulkerBoxShovesAfterTravel(SimulationContext context) {
        WorldData worldData = context.getWorldData();
        PistonPushes pushes = worldData == null ? null : worldData.getPistonPushes();
        return pushes == null ? null : pushes.getShulkerBoxShovesAfterTravel();
    }

    /**
     * The shulker box shove a client before 26.3 applies between its reported position and this travel: PistonPushes's,
     * when set and moving the player.
     */
    private static Vec3 shoveBeforeTravel(SimulationContext context) {
        WorldData worldData = context.getWorldData();
        PistonPushes pushes = worldData == null ? null : worldData.getPistonPushes();
        ShulkerBoxShoveResult shove = pushes == null ? null : pushes.getShulkerBoxShoveResult();
        return shove == null || shove.movement().lengthSqr() == 0.0D ? null : shove.movement();
    }

    /** Native colliders come from {@code pool}; none of them outlives this call. */
    private CollideAxisData probeCollisions(
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents,
            ProbeDimensions dimensions,
            JavaCollisionState actor,
            boolean followCandidateVerticalMovement,
            double horizontalInputReach,
            CollisionBoxPool pool) {
        // Start with player bounding box
        SimpleCollisionBox grabBoxesBB = context.getFromMaximumExtent();

        Vec3 stuckSpeed = context.getLastStuckSpeed();

        Vec3 shove = shoveBeforeTravel(context);
        Vec3 clientShove = shove == null ? null : shove.multiply(stuckSpeed.x, stuckSpeed.y, stuckSpeed.z);
        if (shove != null) {
            // The travel's Entity#move starts where the shulker box shove left the player.
            playerPos = playerPos.add(clientShove);
            grabBoxesBB.offset(clientShove);
            target = target.subtract(shove);
            minY -= shove.y;
            if (attemptedMovementExtents != null)
                attemptedMovementExtents = attemptedMovementExtents.copy().offset(-shove.x, -shove.y, -shove.z);
        }

        double candidateY = target.y * stuckSpeed.y;
        Vec3 beforeCollision = target;
        target = context.getPacketMovement();
        if (clientShove != null) target = target.subtract(clientShove);
        minY *= stuckSpeed.y;

        if (attemptedMovementExtents != null) {
            expandToAttemptedMovement(grabBoxesBB, attemptedMovementExtents, stuckSpeed);
            // Entity#collide gathers entities along the attempted movement, which a step does not raise.
            probeEntityRegion.set(grabBoxesBB);
            expandToPacketMovement(grabBoxesBB, target);
        } else {
            grabBoxesBB.expandToCoordinate(target.x, 0, target.z);
            if (minY < 0 || target.y < 0) grabBoxesBB.minY += Math.min(target.y, minY / stuckSpeed.y);
            if (target.y > 0) grabBoxesBB.maxY += target.y;
        }
        grabBoxesBB.expand(maxEpsilon(dimensions.epsilon()));
        // Entity#collide gathers block shapes through expandTowards(movement), and that movement carries the input.
        SimpleCollisionBox blockQuery = horizontalInputReach > 0.0D
                ? probeBlockQuery
                        .set(grabBoxesBB)
                        .expand(horizontalInputReach * stuckSpeed.x, 0.0D, horizontalInputReach * stuckSpeed.z)
                : grabBoxesBB;

        List<SimpleCollisionBox> collisions = probeCollisions;
        collisions.clear();
        Collisions.getCollisionBoxes(player, blockQuery, collisions, false, playerPos.y, actor, pool);

        List<SimpleCollisionBox> found = probeUnknown;
        found.clear();
        UnknownCollisionProvider.addMovementUnknownCollisions(
                player, context, playerPos, grabBoxesBB, found, probeEntityBox, probeEntityScratch);

        // slight optimization
        if (!found.isEmpty()) found.removeIf(unknownBox -> !unknownBox.isIntersected(grabBoxesBB));

        if (!found.isEmpty()) {
            SimpleCollisionBox region = attemptedMovementExtents == null
                    ? probeEntityRegion.set(blockQuery)
                    : probeEntityRegion.expand(maxEpsilon(dimensions.epsilon()));
            if (attemptedMovementExtents != null && horizontalInputReach > 0.0D) {
                region.expand(horizontalInputReach * stuckSpeed.x, 0.0D, horizontalInputReach * stuckSpeed.z);
            }
            EntityPositionWorlds.withStepEntities(player, region);
            List<List<SimpleCollisionBox>> worlds = EntityPositionWorlds.of(player, context, playerPos, region);
            if (worlds == null && isJavaVehicleMove(context)) {
                CollideAxisData blocks = probeAxes(
                        context,
                        minY,
                        candidateY,
                        target,
                        beforeCollision,
                        playerPos,
                        attemptedMovementExtents,
                        dimensions,
                        followCandidateVerticalMovement,
                        collisions);
                blocks.setUnknown(List.copyOf(found));
                return blocks;
            }
            if (worlds == null) {
                return new CollideAxisData(
                        new CollideAxisData.CollideResult(true, 0),
                        new CollideAxisData.CollideResult(true, 0),
                        new CollideAxisData.CollideResult(true, 0),
                        new CollideAxisData.CollideResult(true, 0),
                        List.copyOf(found));
            }
            if (!EntityPositionWorlds.noEntities(worlds)) {
                // Entity#collide stops the move against the blocks and each entity's box at once.
                int blocks = collisions.size();
                List<CollideAxisData> results = new ArrayList<>(worlds.size());
                for (int w = 0; w < worlds.size(); w++) {
                    collisions.addAll(worlds.get(w));
                    results.add(probeAxes(
                            context,
                            minY,
                            candidateY,
                            target,
                            beforeCollision,
                            playerPos,
                            attemptedMovementExtents,
                            dimensions,
                            followCandidateVerticalMovement,
                            collisions));
                    collisions.subList(blocks, collisions.size()).clear();
                }
                return EntityWorldCollisions.of(results, worlds);
            }
        }
        return probeAxes(
                context,
                minY,
                candidateY,
                target,
                beforeCollision,
                playerPos,
                attemptedMovementExtents,
                dimensions,
                followCandidateVerticalMovement,
                collisions);
    }

    private CollideAxisData probeAxes(
            SimulationContext context,
            double minY,
            double candidateY,
            Vec3 target,
            Vec3 beforeCollision,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents,
            ProbeDimensions dimensions,
            boolean followCandidateVerticalMovement,
            List<SimpleCollisionBox> collisions) {
        Vec3 stuckSpeed = context.getLastStuckSpeed();
        List<SimpleCollisionBox> unknown = List.of();
        CollideAxisData result = new CollideAxisData(null, null, null, null, unknown);

        // TODO: This is stupid, DO WE CARE ABOUT COLLISIONS WHEN UNKNOWN?
        List<SimpleCollisionBox> collisionsMinusUnknown;
        if (unknown.isEmpty()) {
            collisionsMinusUnknown = collisions;
        } else {
            collisionsMinusUnknown = new ArrayList<>(collisions);
            collisionsMinusUnknown.removeIf(box -> {
                for (SimpleCollisionBox e : unknown) {
                    if (e.isIntersected(box)) return true;
                }
                return false;
            });
        }

        boolean groundedOrder = context.getLastOnGround().determineOptimistically();
        boolean vehicle = isJavaVehicleMove(context);
        // Entity#collide moves along Y, then along X and Z in Direction#axisStepOrder of the move before collision.
        int stepOrder = Math.abs(beforeCollision.x) < Math.abs(beforeCollision.z) ? 1 : 0;
        resolvedStepCount = 0;

        // Brute force the current-client axis order possibilities exposed by the server-observable movement envelope.
        for (int orderIndex = 0; orderIndex < PROBE_ORDERS.length; orderIndex++) {
            if (vehicle ? orderIndex != stepOrder : orderIndex >= 2 && !groundedOrder) continue;
            var order = PROBE_ORDERS[orderIndex];
            double iterX = playerPos.x, iterY = playerPos.y, iterZ = playerPos.z;
            for (Collisions.Axis axis : order) {
                double movement = getAxisOfVector(target, axis);
                double positiveMovement =
                        getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, true);
                double negativeMovement =
                        getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, false);
                double multiplier = 1 / getAxisOfVector(stuckSpeed, axis); // TODO: How do we stuck speed collision?

                // Keep the downward reach for landing/step discovery separate
                // from the position used for the following horizontal axes.
                if (axis == Collisions.Axis.Y) {
                    negativeMovement =
                            getAttemptedAxisMovement(axis, minY, attemptedMovementExtents, stuckSpeed, false);
                    movement = followCandidateVerticalMovement ? candidateY : minY;
                }
                AxisResult pos;
                AxisResult neg;
                double moved = 0;
                ResolvedStep step =
                        findResolvedStep(axis, iterX, iterY, iterZ, positiveMovement, negativeMovement, movement);
                if (step != null) {
                    pos = step.positive(positiveResult);
                    neg = step.negative(negativeResult);
                    movement = step.movement;
                    moved = step.moved;
                } else {
                    double requested = movement;
                    pos = testInAxisAndDirection(
                            context,
                            collisions,
                            unknown,
                            iterX,
                            iterY,
                            iterZ,
                            axis,
                            positiveMovement,
                            true,
                            dimensions,
                            positiveResult);
                    neg = testInAxisAndDirection(
                            context,
                            collisions,
                            unknown,
                            iterX,
                            iterY,
                            iterZ,
                            axis,
                            negativeMovement,
                            false,
                            dimensions,
                            negativeResult);

                    if (axis == Collisions.Axis.Y && followCandidateVerticalMovement && movement != 0.0D) {
                        AxisResult vertical = movement < 0 ? neg : pos;
                        if (vertical != null && vertical.isCollide()) {
                            movement = movement < 0
                                    ? Math.max(movement, vertical.getResult())
                                    : Math.min(movement, vertical.getResult());
                        }
                    }

                    // Entity#collideWithShapes advances by the resolved candidate
                    // before the next axis. Neither the envelope's minY nor an
                    // unvalidated packet Y proves the height of an X/Z contact.
                    if (movement != 0) {
                        moved = moveBoundingBoxForAxis(
                                context, collisionsMinusUnknown, axis, movement, iterX, iterY, iterZ, dimensions);
                    }
                    recordResolvedStep(
                            axis,
                            iterX,
                            iterY,
                            iterZ,
                            positiveMovement,
                            negativeMovement,
                            requested,
                            pos,
                            neg,
                            movement,
                            moved);
                }

                if (movement != 0) {
                    iterX += axis == Collisions.Axis.X ? moved : 0;
                    iterY += axis == Collisions.Axis.Y ? moved : 0;
                    iterZ += axis == Collisions.Axis.Z ? moved : 0;
                }

                switch (axis) {
                    case X:
                        AxisResult x =
                                selectHorizontalAxisResult(pos, neg, positiveMovement, negativeMovement, movement);
                        if (x != null) result.combineX(didCollide(pos, neg), x.getResult() * multiplier, side(x, pos));
                        break;
                    case Z:
                        AxisResult z =
                                selectHorizontalAxisResult(pos, neg, positiveMovement, negativeMovement, movement);
                        if (z != null) result.combineZ(didCollide(pos, neg), z.getResult() * multiplier, side(z, pos));
                        break;
                    // We care about both positive and negative for Y axis (for stepping) - special case
                    case Y:
                        if (pos != null) result.combineYPos(pos.isCollide(), pos.getResult() * multiplier);
                        if (neg != null) result.combineYNeg(neg.isCollide(), neg.getResult() * multiplier);
                        break;
                }
            }
        }

        return result;
    }

    private ResolvedStep findResolvedStep(
            Collisions.Axis axis,
            double x,
            double y,
            double z,
            double positiveMovement,
            double negativeMovement,
            double movement) {
        long xBits = Double.doubleToRawLongBits(x);
        long yBits = Double.doubleToRawLongBits(y);
        long zBits = Double.doubleToRawLongBits(z);
        long positiveBits = Double.doubleToRawLongBits(positiveMovement);
        long negativeBits = Double.doubleToRawLongBits(negativeMovement);
        long movementBits = Double.doubleToRawLongBits(movement);
        for (int i = 0; i < resolvedStepCount; i++) {
            ResolvedStep step = resolvedSteps[i];
            if (step.axis == axis
                    && step.x == xBits
                    && step.y == yBits
                    && step.z == zBits
                    && step.positiveMovement == positiveBits
                    && step.negativeMovement == negativeBits
                    && step.requestedMovement == movementBits) {
                return step;
            }
        }
        return null;
    }

    private void recordResolvedStep(
            Collisions.Axis axis,
            double x,
            double y,
            double z,
            double positiveMovement,
            double negativeMovement,
            double requestedMovement,
            AxisResult pos,
            AxisResult neg,
            double movement,
            double moved) {
        if (resolvedStepCount == resolvedSteps.length) return;
        ResolvedStep step = resolvedSteps[resolvedStepCount];
        if (step == null) {
            step = new ResolvedStep();
            resolvedSteps[resolvedStepCount] = step;
        }
        resolvedStepCount++;
        step.axis = axis;
        step.x = Double.doubleToRawLongBits(x);
        step.y = Double.doubleToRawLongBits(y);
        step.z = Double.doubleToRawLongBits(z);
        step.positiveMovement = Double.doubleToRawLongBits(positiveMovement);
        step.negativeMovement = Double.doubleToRawLongBits(negativeMovement);
        step.requestedMovement = Double.doubleToRawLongBits(requestedMovement);
        step.hasPositive = pos != null;
        step.positiveCollide = pos != null && pos.collide;
        step.positiveResult = pos == null ? 0 : pos.result;
        step.hasNegative = neg != null;
        step.negativeCollide = neg != null && neg.collide;
        step.negativeResult = neg == null ? 0 : neg.result;
        step.movement = movement;
        step.moved = moved;
    }

    private static final class ResolvedStep {
        private Collisions.Axis axis;
        private long x, y, z;
        private long positiveMovement, negativeMovement, requestedMovement;
        private boolean hasPositive, positiveCollide, hasNegative, negativeCollide;
        private double positiveResult, negativeResult;
        private double movement, moved;

        private AxisResult positive(AxisResult out) {
            return restore(out, hasPositive, positiveCollide, positiveResult);
        }

        private AxisResult negative(AxisResult out) {
            return restore(out, hasNegative, negativeCollide, negativeResult);
        }

        private static AxisResult restore(AxisResult out, boolean present, boolean collide, double result) {
            if (!present) return null;
            out.result = result;
            out.collide = collide;
            return out;
        }
    }

    private static boolean didCollide(AxisResult pos, AxisResult neg) {
        return pos != null && pos.isCollide() || neg != null && neg.isCollide();
    }

    private static int side(AxisResult selected, AxisResult pos) {
        return selected.isCollide() ? (selected == pos ? 1 : -1) : 0;
    }

    private AxisResult selectHorizontalAxisResult(
            AxisResult pos, AxisResult neg, double positiveMovement, double negativeMovement, double packetMovement) {
        if (pos == null) return neg;
        if (neg == null) return pos;

        if (pos.isCollide() != neg.isCollide()) {
            return pos.isCollide() ? pos : neg;
        }

        if (packetMovement > SimpleCollisionBox.COLLISION_EPSILON) return pos;
        if (packetMovement < -SimpleCollisionBox.COLLISION_EPSILON) return neg;

        double positiveReach = Math.max(0.0D, positiveMovement);
        double negativeReach = Math.max(0.0D, -negativeMovement);
        if (positiveReach > negativeReach + SimpleCollisionBox.COLLISION_EPSILON) return pos;
        if (negativeReach > positiveReach + SimpleCollisionBox.COLLISION_EPSILON) return neg;

        return Math.abs(pos.getResult()) <= Math.abs(neg.getResult()) ? pos : neg;
    }

    private double getAttemptedAxisMovement(
            Collisions.Axis axis,
            double fallback,
            SimpleCollisionBox attemptedMovementExtents,
            Vec3 stuckSpeed,
            boolean positive) {
        if (attemptedMovementExtents == null) {
            return fallback;
        }

        double attempted = switch (axis) {
            case X -> positive ? attemptedMovementExtents.maxX : attemptedMovementExtents.minX;
            case Y -> positive ? attemptedMovementExtents.maxY : attemptedMovementExtents.minY;
            case Z -> positive ? attemptedMovementExtents.maxZ : attemptedMovementExtents.minZ;
        };
        attempted *= getAxisOfVector(stuckSpeed, axis);

        if (positive) {
            return Math.max(fallback, attempted);
        }
        return Math.min(fallback, attempted);
    }

    private void expandToAttemptedMovement(
            SimpleCollisionBox sweepBox, SimpleCollisionBox attemptedMovementExtents, Vec3 stuckSpeed) {
        sweepBox.expandToCoordinate(
                attemptedMovementExtents.minX * stuckSpeed.x, 0, attemptedMovementExtents.minZ * stuckSpeed.z);
        sweepBox.expandToCoordinate(
                attemptedMovementExtents.maxX * stuckSpeed.x, 0, attemptedMovementExtents.maxZ * stuckSpeed.z);

        if (attemptedMovementExtents.minY < 0) {
            sweepBox.minY += attemptedMovementExtents.minY * stuckSpeed.y;
        }
        if (attemptedMovementExtents.maxY > 0) {
            sweepBox.maxY += attemptedMovementExtents.maxY * stuckSpeed.y;
        }
    }

    private void expandToPacketMovement(SimpleCollisionBox sweepBox, Vec3 target) {
        sweepBox.expandToCoordinate(target.x, 0, target.z);
        if (target.y < 0) {
            sweepBox.minY += target.y;
        }
        if (target.y > 0) {
            sweepBox.maxY += target.y;
        }
    }

    private double moveBoundingBoxForAxis(
            SimulationContext context,
            List<SimpleCollisionBox> collisions,
            Collisions.Axis axis,
            double amount,
            double x,
            double y,
            double z,
            ProbeDimensions dimensions) {
        // Move the position for the next axis (with the smallest bounding box possible)
        // This is needed to stop people from slightly clipping into walls to avoid taking knockback (Clip AntiKB)
        float width = context.getVehicle() == null ? dimensions.width() : context.getMaxWidth();
        float height = context.getVehicle() == null ? dimensions.initialHeight() : context.getMaxHeight();
        SimpleCollisionBox oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(probeBox, x, y, z, width, height);
        return Collisions.collideAxis(amount, oldBox, collisions, axis, dimensions.epsilon());
    }

    private double getAxisOfVector(Vec3 vec, Collisions.Axis axis) {
        switch (axis) {
            case X:
                return vec.x;
            case Y:
                return vec.y;
            case Z:
                return vec.z;
        }
        throw new IllegalStateException("Unknown axis " + axis);
    }

    private AxisResult testInAxisAndDirection(
            SimulationContext context,
            List<SimpleCollisionBox> collisions,
            List<SimpleCollisionBox> unknown,
            double posX,
            double posY,
            double posZ,
            Collisions.Axis axis,
            double amount,
            boolean isPositive,
            ProbeDimensions dimensions,
            AxisResult out) {
        // Nothing to do (in opposite dir, not counting less than epsilon - floating point means you can move 1e16
        // upwards while colliding downwards
        double axisEpsilon = axisEpsilon(dimensions.epsilon(), axis);
        if (Math.abs(amount) > axisEpsilon && isPositive != (Math.signum(amount) == 1)) return null;

        boolean isVehicle = context.getVehicle() != null;

        // WHY CAN MOJANG NOT HANDLE THE SIMPLEST OF THINGS CORRECTLY
        // START HANDLING OF POTENTIALLY UNKNOWN CLIENT SIDED BLOCKS
        if (!unknown.isEmpty()) {
            float maximumWidth = context.getVehicle() == null ? dimensions.width() : context.getMaxWidth();
            float maximumHeight = context.getVehicle() == null ? dimensions.maxHeight() : context.getMaxHeight();
            SimpleCollisionBox biggestBoxPossible =
                    GetBoundingBox.getBoundingBoxFromPosAndSize(posX, posY, posZ, maximumWidth, maximumHeight);
            biggestBoxPossible.expand(0.1); // For good measure.
            for (SimpleCollisionBox box : unknown) {
                if (box.isCollided(biggestBoxPossible)) {
                    return new AxisResult(0, true);
                }
            }
            // We only care about finding the first collision
            collisions = new ArrayList<>(collisions);
            collisions.addAll(unknown);
        }
        // END HANDLING OF POTENTIALLY UNKNOWN CLIENT SIDE BLOCKS

        // We should at least probe by collision epsilon...
        // This also means that 0 is transformed into the collision epsilon. This is intentional.
        double epsilon = isPositive ? axisEpsilon : -axisEpsilon;
        amount += epsilon;

        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                probeBox, posX, posY, posZ, dimensions.width(), dimensions.initialHeight());
        if (!isVehicle) {
            expandUpwards(
                    playerBox,
                    collisions,
                    axis,
                    epsilon,
                    dimensions.maxHeight() - dimensions.initialHeight(),
                    dimensions.epsilon());
        } else {
            // No desync's with vehicle bounding box (that we can control)
            playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    probeBox, posX, posY, posZ, context.getMaxWidth(), context.getMaxHeight());
        }

        // Now, after all of this bounding box fuckery is done, we can finally test for a collision!
        double result = Collisions.collideAxis(amount, playerBox, collisions, axis, dimensions.epsilon());
        boolean collided = Double.compare(result, amount) != 0;

        if (!collided) { // We didn't collide in this axis, reduce by epsilon // TODO: DRY YOU FUCKING DONKEY
            result -= epsilon;
        }

        out.result = result;
        out.collide = collided;
        return out;
    }

    private void expandUpwards(
            SimpleCollisionBox playerBox,
            List<SimpleCollisionBox> collisions,
            Collisions.Axis axis,
            double epsilon,
            double amount,
            SimpleCollisionBox.AxisEpsilon collisionEpsilon) {
        // TODO: Define minimum bounding box extent (for 1.8 players and such to not by 0.6)
        // Expand the player box upwards by their unknown hitbox amount
        double axisAmount = axis == Collisions.Axis.Y ? amount : 0;
        double result = Collisions.collideAxis(axisAmount, playerBox, collisions, axis, collisionEpsilon);
        boolean collided = Double.compare(result, axisAmount) != 0;
        double x = axis == Collisions.Axis.X ? result : 0;
        double y = axis == Collisions.Axis.Y ? result : amount;
        double z = axis == Collisions.Axis.Z ? result : 0;
        if (!collided) { // We didn't collide in this axis, reduce by epsilon
            switch (axis) {
                case X -> x -= epsilon;
                case Y -> y -= epsilon;
                case Z -> z -= epsilon;
            }
        }
        playerBox.expandToCoordinate(x, y, z); // Height uncertainty for bounding box
    }

    @AllArgsConstructor
    @Data
    static class AxisResult {
        double result;
        boolean collide;
    }

    private static double axisEpsilon(SimpleCollisionBox.AxisEpsilon epsilon, Collisions.Axis axis) {
        return switch (axis) {
            case X -> epsilon.x();
            case Y -> epsilon.y();
            case Z -> epsilon.z();
        };
    }

    private static double maxEpsilon(SimpleCollisionBox.AxisEpsilon epsilon) {
        return Math.max(epsilon.x(), Math.max(epsilon.y(), epsilon.z()));
    }

    public record ProbeDimensions(
            float width, float initialHeight, float maxHeight, SimpleCollisionBox.AxisEpsilon epsilon) {
        public ProbeDimensions {
            if (width <= 0.0F || initialHeight <= 0.0F || maxHeight < initialHeight) {
                throw new IllegalArgumentException("invalid collision probe dimensions");
            }
            epsilon = java.util.Objects.requireNonNull(epsilon, "epsilon");
        }
    }
}
