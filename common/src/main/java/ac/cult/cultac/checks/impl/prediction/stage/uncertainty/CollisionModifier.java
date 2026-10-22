package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final SimpleCollisionBox probeEntityScratch = new SimpleCollisionBox();
    private final AxisResult positiveResult = new AxisResult(0, false);
    private final AxisResult negativeResult = new AxisResult(0, false);
    private final boolean javaTravelInput;

    // Prediction-pass reuse, owned by one player's SimulationProcessor. Between beginPredictionPass and
    // endPredictionPass the world, entities and player state are fixed, so a gather with identical inputs
    // returns identical boxes in identical order, and an entity whose widest possible unknown box misses
    // the prefilter bounds contributes nothing to any query inside them.
    private static final double ENTITY_PREFILTER_MARGIN = 4.0D;
    private CultPlayer passPlayer;
    private final Map<GatherKey, List<SimpleCollisionBox>> passGathered = new HashMap<>();
    private final List<PacketEntity> passNearbyEntities = new ArrayList<>();
    private final SimpleCollisionBox passNearbyBounds = new SimpleCollisionBox();
    private boolean passNearbyValid;

    public CollisionModifier() {
        this(true);
    }

    /** {@code javaTravelInput}: the setPos guard measures Java LivingEntity#travel's horizontal input. */
    public CollisionModifier(boolean javaTravelInput) {
        this.javaTravelInput = javaTravelInput;
    }

    /**
     * Starts a prediction pass for {@code player}: probes until {@link #endPredictionPass} may reuse block
     * collision gathers and the entity prefilter. Only call this while the world, compensated entities and
     * player state cannot change until the pass ends.
     */
    public void beginPredictionPass(CultPlayer player) {
        endPredictionPass();
        passPlayer = player;
    }

    public void endPredictionPass() {
        passPlayer = null;
        passGathered.clear();
        passNearbyEntities.clear();
        passNearbyValid = false;
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
        return clipMovementTrace(valid, result, context, trace, end, javaTravelInput);
    }

    /**
     * {@link #handleMovementTrace} without an instance: it reads no per-instance state other than
     * {@code javaTravelInput}, so callers can clip a trace without allocating a modifier.
     */
    static MovementTrace clipMovementTrace(
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            MovementTrace trace,
            Vec3 end,
            boolean javaTravelInput) {
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
        PredictionResult inputSource = javaTravelInput ? result : null;
        Vec3 collisionBaseOffset = trace.collisionBaseOffset();
        if (collisionBaseOffset.lengthSqr() <= 1.0E-14) {
            return trace.withPosition(
                    transformWithCollisions(context, result.getCollideAxisData(), trace.position(), end, inputSource));
        }

        // Piston/shulker shoves are separate position-only Entity#move calls.
        // Collision clipping applies to the player's movement vector, then the
        // already-proven external base displacement is re-applied once.
        Vec3 movementOnlyTarget = end.subtract(collisionBaseOffset);
        PredVector clippedMovement = transformWithCollisions(
                context, result.getCollideAxisData(), trace.position(), movementOnlyTarget, inputSource);
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
        PredVector attempted = start;
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
                && start.y < collide.getYNeg().getResult()
                && end.y <= 0
                && end.y > start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYNeg().getResult());
            start = start.withY(clamped, "collide -y");
        }

        if (collide.getYPos() != null
                && collide.getYPos().isLikelyCollide()
                && start.y > collide.getYPos().getResult()
                && end.y >= 0
                && end.y < start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYPos().getResult());
            start = start.withY(clamped, "collide +y");
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

    private static Vec3 horizontalInputTowards(Vec3 movement, Vec3 end, PredictionResult inputSource) {
        if (inputSource == null || inputSource.getSimulationContext() == null) return Vec3.ZERO;
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
                player, context, minY, target, playerPos, attemptedMovementExtents, dimensions, actor, false);
    }

    public CollideAxisData probeCollisions(
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents,
            ProbeDimensions dimensions,
            JavaCollisionState actor,
            boolean followCandidateVerticalMovement) {
        // Start with player bounding box
        SimpleCollisionBox grabBoxesBB = context.getFromMaximumExtent();

        Vec3 stuckSpeed = context.getLastStuckSpeed();

        double candidateY = target.y * stuckSpeed.y;
        target = context.getPacketMovement();
        minY *= stuckSpeed.y;

        if (attemptedMovementExtents != null) {
            expandToAttemptedMovement(grabBoxesBB, attemptedMovementExtents, stuckSpeed);
            expandToPacketMovement(grabBoxesBB, target);
        } else {
            grabBoxesBB.expandToCoordinate(target.x, 0, target.z);
            if (minY < 0 || target.y < 0) grabBoxesBB.minY += Math.min(target.y, minY / stuckSpeed.y);
            if (target.y > 0) grabBoxesBB.maxY += target.y;
        }
        grabBoxesBB.expand(maxEpsilon(dimensions.epsilon()));

        boolean inPass = passPlayer != null && passPlayer == player;
        List<SimpleCollisionBox> collisions = inPass
                ? passGatheredCollisions(player, grabBoxesBB, playerPos.y, actor)
                : gatherCollisions(player, grabBoxesBB, playerPos.y, actor, probeCollisions);

        List<SimpleCollisionBox> found = probeUnknown;
        found.clear();
        UnknownCollisionProvider.addMovementUnknownCollisions(
                player,
                context,
                playerPos,
                grabBoxesBB,
                found,
                probeEntityBox,
                probeEntityScratch,
                inPass ? passEntitiesFor(player, grabBoxesBB) : null);

        // slight optimization
        if (!found.isEmpty()) found.removeIf(unknownBox -> !unknownBox.isIntersected(grabBoxesBB));

        if (!found.isEmpty()) {
            return new CollideAxisData(
                    new CollideAxisData.CollideResult(true, 0),
                    new CollideAxisData.CollideResult(true, 0),
                    new CollideAxisData.CollideResult(true, 0),
                    new CollideAxisData.CollideResult(true, 0),
                    List.copyOf(found));
        }
        // Any unknown box returned above, so every axis below probes the block collisions alone.
        CollideAxisData result = new CollideAxisData(null, null, null, null, List.of());

        // Orders 0 and 1 both begin with the same Y step from playerPos over the same inputs; order 1
        // replays order 0's results rather than recomputing them.
        boolean firstYPosPresent = false, firstYPosCollide = false;
        boolean firstYNegPresent = false, firstYNegCollide = false;
        double firstYPosResult = 0.0D, firstYNegResult = 0.0D;
        double firstYIterX = 0.0D, firstYIterY = 0.0D, firstYIterZ = 0.0D;

        boolean groundedOrder = context.getLastOnGround().determineOptimistically();

        // Brute force the current-client axis order possibilities exposed by the server-observable movement envelope.
        for (int orderIndex = 0; orderIndex < PROBE_ORDERS.length; orderIndex++) {
            if (orderIndex >= 2 && !groundedOrder) continue;
            var order = PROBE_ORDERS[orderIndex];
            double iterX = playerPos.x, iterY = playerPos.y, iterZ = playerPos.z;
            for (int axisIndex = 0; axisIndex < order.length; axisIndex++) {
                Collisions.Axis axis = order[axisIndex];
                if (orderIndex == 1 && axisIndex == 0) {
                    // PROBE_ORDERS[0][0] == PROBE_ORDERS[1][0] == Y, from the same playerPos.
                    double yMultiplier = 1 / getAxisOfVector(stuckSpeed, axis);
                    // Includes the += 0 that the Y step applies to X and Z (it normalizes -0.0).
                    iterX = firstYIterX;
                    iterY = firstYIterY;
                    iterZ = firstYIterZ;
                    if (firstYPosPresent) result.combineYPos(firstYPosCollide, firstYPosResult * yMultiplier);
                    if (firstYNegPresent) result.combineYNeg(firstYNegCollide, firstYNegResult * yMultiplier);
                    continue;
                }
                double movement = getAxisOfVector(target, axis);
                double positiveMovement =
                        getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, true);
                double negativeMovement =
                        getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, false);
                double multiplier = 1 / getAxisOfVector(stuckSpeed, axis); // TODO: How do we stuck speed collision?

                AxisResult pos = testInAxisAndDirection(
                        context,
                        collisions,
                        iterX,
                        iterY,
                        iterZ,
                        axis,
                        positiveMovement,
                        true,
                        dimensions,
                        positiveResult);
                // Keep the downward reach for landing/step discovery separate
                // from the position used for the following horizontal axes.
                if (axis == Collisions.Axis.Y) {
                    negativeMovement =
                            getAttemptedAxisMovement(axis, minY, attemptedMovementExtents, stuckSpeed, false);
                    movement = followCandidateVerticalMovement ? candidateY : minY;
                }
                AxisResult neg = testInAxisAndDirection(
                        context,
                        collisions,
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
                    double moved = moveBoundingBoxForAxis(
                            context, collisions, axis, movement, iterX, iterY, iterZ, dimensions);
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

                if (orderIndex == 0 && axisIndex == 0) {
                    // pos/neg are reused scratch; keep their values for order 1's identical Y step.
                    firstYPosPresent = pos != null;
                    firstYPosCollide = pos != null && pos.isCollide();
                    firstYPosResult = pos == null ? 0.0D : pos.getResult();
                    firstYNegPresent = neg != null;
                    firstYNegCollide = neg != null && neg.isCollide();
                    firstYNegResult = neg == null ? 0.0D : neg.getResult();
                    firstYIterX = iterX;
                    firstYIterY = iterY;
                    firstYIterZ = iterZ;
                }
            }
        }

        return result;
    }

    private static List<SimpleCollisionBox> gatherCollisions(
            CultPlayer player,
            SimpleCollisionBox queryBox,
            double entityBottom,
            JavaCollisionState actor,
            List<SimpleCollisionBox> out) {
        out.clear();
        Collisions.getCollisionBoxes(player, queryBox, out, false, entityBottom, actor);
        return out;
    }

    /** The pass's read-only gather for these exact getCollisionBoxes inputs. */
    private List<SimpleCollisionBox> passGatheredCollisions(
            CultPlayer player, SimpleCollisionBox queryBox, double entityBottom, JavaCollisionState actor) {
        GatherKey key = new GatherKey(
                queryBox.minX,
                queryBox.minY,
                queryBox.minZ,
                queryBox.maxX,
                queryBox.maxY,
                queryBox.maxZ,
                entityBottom,
                actor);
        List<SimpleCollisionBox> gathered = passGathered.get(key);
        if (gathered == null) {
            gathered = gatherCollisions(player, queryBox, entityBottom, actor, new ArrayList<>());
            passGathered.put(key, gathered);
        }
        return gathered;
    }

    /**
     * The entities, in entity map order, that can add an unknown box intersecting {@code queryBox}, or null to
     * scan the whole map. Every box UnknownCollisionProvider can add lies within the entity's possible box
     * expanded by 2, or is a happy ghast's movement box, and isIntersected is monotone in both boxes, so an
     * excluded entity adds nothing for any query inside the prefilter bounds.
     */
    private List<PacketEntity> passEntitiesFor(CultPlayer player, SimpleCollisionBox queryBox) {
        if (passNearbyValid && contains(passNearbyBounds, queryBox)) return passNearbyEntities;
        if (passNearbyValid) {
            passNearbyBounds.minX = Math.min(passNearbyBounds.minX, queryBox.minX);
            passNearbyBounds.minY = Math.min(passNearbyBounds.minY, queryBox.minY);
            passNearbyBounds.minZ = Math.min(passNearbyBounds.minZ, queryBox.minZ);
            passNearbyBounds.maxX = Math.max(passNearbyBounds.maxX, queryBox.maxX);
            passNearbyBounds.maxY = Math.max(passNearbyBounds.maxY, queryBox.maxY);
            passNearbyBounds.maxZ = Math.max(passNearbyBounds.maxZ, queryBox.maxZ);
        } else {
            passNearbyBounds.minX = queryBox.minX;
            passNearbyBounds.minY = queryBox.minY;
            passNearbyBounds.minZ = queryBox.minZ;
            passNearbyBounds.maxX = queryBox.maxX;
            passNearbyBounds.maxY = queryBox.maxY;
            passNearbyBounds.maxZ = queryBox.maxZ;
        }
        passNearbyBounds.expand(ENTITY_PREFILTER_MARGIN);
        passNearbyEntities.clear();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity instanceof PacketEntityHappyGhast
                    || entity.getPossibleCollisionBoxes(probeEntityBox, probeEntityScratch)
                            .expand(2)
                            .isIntersected(passNearbyBounds)) {
                passNearbyEntities.add(entity);
            }
        }
        passNearbyValid = true;
        // NaN bounds contain nothing: scan the whole map as before.
        return contains(passNearbyBounds, queryBox) ? passNearbyEntities : null;
    }

    private static boolean contains(SimpleCollisionBox outer, SimpleCollisionBox inner) {
        return outer.minX <= inner.minX
                && outer.minY <= inner.minY
                && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX
                && outer.maxY >= inner.maxY
                && outer.maxZ >= inner.maxZ;
    }

    /** Every input of the probe's getCollisionBoxes call other than the pass-fixed player and world. */
    private record GatherKey(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            double entityBottom,
            JavaCollisionState actor) {}

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
