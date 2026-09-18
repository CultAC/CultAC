package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CollisionModifier implements UncertaintyHandler {
    @Override
    public MovementTrace handleMovementTrace(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, MovementTrace trace, Vec3 end) {
        if (valid != null && valid.isTestingMaxStartingVelExtents(end)) {
            return trace;
        }

        // Entity#move records its attempted delta, after stuck scaling, for
        // checkInsideBlocks' axis order. Clipping can reverse the X/Z ordering.
        Vec3 stuckSpeed = trace.position().collisionStuckSpeedMultiplier(context);
        trace = trace.withPreCollisionMovement(stuckSpeed == null
                ? trace.position() : trace.position().multiply(stuckSpeed));
        Vec3 collisionBaseOffset = trace.collisionBaseOffset();
        if (collisionBaseOffset.lengthSqr() <= 1.0E-14) {
            return trace.withPosition(transformWithCollisions(context, result.getCollideAxisData(), trace.position(), end));
        }

        // Piston/shulker shoves are separate position-only Entity#move calls.
        // Collision clipping applies to the player's movement vector, then the
        // already-proven external base displacement is re-applied once.
        Vec3 movementOnlyTarget = end.subtract(collisionBaseOffset);
        PredVector clippedMovement = transformWithCollisions(context, result.getCollideAxisData(), trace.position(), movementOnlyTarget);
        return trace.withPosition(clippedMovement.add(collisionBaseOffset, "external movement collision base"));
    }

    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        if (valid != null && valid.isTestingMaxStartingVelExtents(end)) return start;

        return transformWithCollisions(context, result.getCollideAxisData(), start, end);
    }

    public static PredVector transformWithCollisions(SimulationContext context, CollideAxisData collide, PredVector start, Vec3 end) {
        PredVector attempted = start;
        // Collisions mean that movement from the start of the collision to the max extent is possible
        if (isValidCollision(collide.getX(), start.x, end.x)) {
            double clamped = CultMath.clamp(end.x, start.x, collide.getX().getResult());
            start = start.withX(clamped, "collide x");
        }

        if (isValidCollision(collide.getZ(), start.z, end.z)) {
            double clamped = CultMath.clamp(end.z, start.z, collide.getZ().getResult());
            start = start.withZ(clamped, "collide z");
        }

        if (collide.getYNeg() != null
                && collide.getYNeg().isLikelyCollide()
                && start.y < collide.getYNeg().getResult()
                && end.y <= 0 && end.y > start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYNeg().getResult());
            start = start.withY(clamped, "collide -y");
        }

        if (collide.getYPos() != null
                && collide.getYPos().isLikelyCollide()
                && start.y > collide.getYPos().getResult()
                && end.y >= 0 && end.y < start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYPos().getResult());
            start = start.withY(clamped, "collide +y");
        }

        // Legacy Entity#moveEntity always commits the clipped bounding box.
        if (context != null && context.getVersion() != null && context.getVersion().isOlderThan(ClientVersion.V_1_14)) return start;
        Vec3 packetVisibleMovement = applyEntityMovePositionCommitGuard(
                attempted,
                start,
                attempted.collisionStuckSpeedMultiplier(context));
        return packetVisibleMovement == start ? start : start.with(packetVisibleMovement, "Entity#move setPos guard");
    }

    private static Vec3 applyEntityMovePositionCommitGuard(Vec3 attemptedMovement, Vec3 clippedMovement, Vec3 stuckSpeed) {
        Vec3 multiplier = stuckSpeed == null ? new Vec3(1.0D, 1.0D, 1.0D) : stuckSpeed;
        Vec3 vanillaAttempted = attemptedMovement.multiply(multiplier.x, multiplier.y, multiplier.z);
        Vec3 vanillaClipped = clippedMovement.multiply(multiplier.x, multiplier.y, multiplier.z);

        double clippedLengthSqr = vanillaClipped.lengthSqr();
        if (clippedLengthSqr <= 1.0E-7D && vanillaAttempted.lengthSqr() - clippedLengthSqr >= 1.0E-7D) {
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
    public CollideAxisData probeCollisions(CultPlayer player, SimulationContext context, double minY, Vec3 target, Vec3 playerPos, SimpleCollisionBox attemptedMovementExtents) {
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
            ProbeDimensions dimensions
    ) {
        return probeCollisions(player, context, minY, target, playerPos, attemptedMovementExtents, dimensions,
                JavaCollisionState.current(player));
    }

    public CollideAxisData probeCollisions(CultPlayer player, SimulationContext context, double minY, Vec3 target,
            Vec3 playerPos, SimpleCollisionBox attemptedMovementExtents, ProbeDimensions dimensions, JavaCollisionState actor) {
        return probeCollisions(player, context, minY, target, playerPos, attemptedMovementExtents, dimensions, actor, false);
    }

    public CollideAxisData probeCollisions(CultPlayer player, SimulationContext context, double minY, Vec3 target,
            Vec3 playerPos, SimpleCollisionBox attemptedMovementExtents, ProbeDimensions dimensions, JavaCollisionState actor,
            boolean followCandidateVerticalMovement) {
        CollideAxisData result = new CollideAxisData();

        // Start with player bounding box
        SimpleCollisionBox grabBoxesBB = context.getFromMaximumExtent().copy();

        Vec3 stuckSpeed = context.getLastStuckSpeed();

        double candidateY = target.y * stuckSpeed.y;
        target = context.getEnd().subtract(context.getStart());
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

        List<SimpleCollisionBox> collisions = new ArrayList<>();
        Collisions.getCollisionBoxes(player, grabBoxesBB, collisions, false, playerPos.y, actor);

        List<SimpleCollisionBox> unknown = UnknownCollisionProvider.movementUnknownCollisions(
                player,
                context,
                playerPos,
                grabBoxesBB);
        result.setUnknown(unknown);


        // slight optimization
        unknown.removeIf(unknownBox -> !unknownBox.isIntersected(grabBoxesBB));

        if (!unknown.isEmpty()) {
            return new CollideAxisData(new CollideAxisData.CollideResult(true, 0),
                    new CollideAxisData.CollideResult(true, 0),
                    new CollideAxisData.CollideResult(true, 0),
                    new CollideAxisData.CollideResult(true, 0),
                    unknown);
        }

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

        List<List<Collisions.Axis>> orderPossibilities = new ArrayList<>();
        orderPossibilities.add(Arrays.asList(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z));
        if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_14)) {
            orderPossibilities.add(Arrays.asList(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X));
        }

        if (context.getLastOnGround().determineOptimistically()) {
            orderPossibilities.add(Arrays.asList(Collisions.Axis.X, Collisions.Axis.Z, Collisions.Axis.Y));
            if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_14)) {
                orderPossibilities.add(Arrays.asList(Collisions.Axis.Z, Collisions.Axis.X, Collisions.Axis.Y));
            }
        }

        // Brute force the current-client axis order possibilities exposed by the server-observable movement envelope.
        for (List<Collisions.Axis> order : orderPossibilities) {
            Vec3 iterPos = playerPos;
            for (Collisions.Axis axis : order) {
                double movement = getAxisOfVector(target, axis);
                double positiveMovement = getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, true);
                double negativeMovement = getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, false);
                double multiplier = 1 / getAxisOfVector(stuckSpeed, axis); // TODO: How do we stuck speed collision?

                AxisResult pos = testInAxisAndDirection(context, collisions, unknown, iterPos, axis, positiveMovement, true, dimensions);
                // Keep the downward reach for landing/step discovery separate
                // from the position used for the following horizontal axes.
                if (axis == Collisions.Axis.Y) {
                    negativeMovement = getAttemptedAxisMovement(axis, minY, attemptedMovementExtents, stuckSpeed, false);
                    movement = followCandidateVerticalMovement ? candidateY : minY;
                }
                AxisResult neg = testInAxisAndDirection(context, collisions, unknown, iterPos, axis, negativeMovement, false, dimensions);

                if (axis == Collisions.Axis.Y && followCandidateVerticalMovement && movement != 0.0D) {
                    AxisResult vertical = movement < 0 ? neg : pos;
                    if (vertical != null && vertical.isCollide()) {
                        movement = movement < 0 ? Math.max(movement, vertical.getResult())
                                : Math.min(movement, vertical.getResult());
                    }
                }

                // Entity#collideWithShapes advances by the resolved candidate
                // before the next axis. Neither the envelope's minY nor an
                // unvalidated packet Y proves the height of an X/Z contact.
                iterPos = moveBoundingBoxForAxis(context, collisionsMinusUnknown, axis, movement, iterPos, dimensions);

                switch (axis) {
                    case X:
                        CollideAxisData.CollideResult xResult = mergeHorizontalAxisResults(pos, neg, positiveMovement, negativeMovement, movement, multiplier);
                        if (xResult != null) result.setX(xResult);
                        break;
                    case Z:
                        CollideAxisData.CollideResult zResult = mergeHorizontalAxisResults(pos, neg, positiveMovement, negativeMovement, movement, multiplier);
                        if (zResult != null) result.setZ(zResult);
                        break;
                    // We care about both positive and negative for Y axis (for stepping) - special case
                    case Y:
                        if (pos != null) {
                            result.setYPos(new CollideAxisData.CollideResult(pos.isCollide(), pos.getResult() * multiplier));
                        }
                        if (neg != null) {
                            result.setYNeg(new CollideAxisData.CollideResult(neg.isCollide(), neg.getResult() * multiplier));
                        }
                        break;
                }
            }
        }

        return result;
    }

    private CollideAxisData.CollideResult mergeHorizontalAxisResults(AxisResult pos, AxisResult neg,
                                                                    double positiveMovement, double negativeMovement,
                                                                    double packetMovement, double multiplier) {
        AxisResult selected = selectHorizontalAxisResult(pos, neg, positiveMovement, negativeMovement, packetMovement);
        if (selected == null) {
            return null;
        }

        boolean didCollide = pos != null && pos.isCollide() || neg != null && neg.isCollide();
        return new CollideAxisData.CollideResult(didCollide, selected.getResult() * multiplier);
    }

    private AxisResult selectHorizontalAxisResult(AxisResult pos, AxisResult neg,
                                                 double positiveMovement, double negativeMovement,
                                                 double packetMovement) {
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

    private double getAttemptedAxisMovement(Collisions.Axis axis, double fallback, SimpleCollisionBox attemptedMovementExtents, Vec3 stuckSpeed, boolean positive) {
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

    private void expandToAttemptedMovement(SimpleCollisionBox sweepBox, SimpleCollisionBox attemptedMovementExtents, Vec3 stuckSpeed) {
        sweepBox.expandToCoordinate(attemptedMovementExtents.minX * stuckSpeed.x, 0, attemptedMovementExtents.minZ * stuckSpeed.z);
        sweepBox.expandToCoordinate(attemptedMovementExtents.maxX * stuckSpeed.x, 0, attemptedMovementExtents.maxZ * stuckSpeed.z);

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

    private Vec3 moveBoundingBoxForAxis(SimulationContext context, List<SimpleCollisionBox> collisions, Collisions.Axis axis, double amount, Vec3 playerPos, ProbeDimensions dimensions) {
        // Move the position for the next axis (with the smallest bounding box possible)
        // This is needed to stop people from slightly clipping into walls to avoid taking knockback (Clip AntiKB)
        if (amount == 0) return playerPos; // Nothing to do
        Vec3 movementVector = transformToOnlyHaveAxis(new Vec3(amount, amount, amount), axis);
        float width = context.getVehicle() == null ? dimensions.width() : context.getMaxWidth();
        float height = context.getVehicle() == null ? dimensions.initialHeight() : context.getMaxHeight();
        SimpleCollisionBox oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(playerPos.x, playerPos.y, playerPos.z, width, height);
        Vec3 result = Collisions.collideBoundingBoxLegacy(
                movementVector, oldBox, collisions, Collections.singletonList(axis), dimensions.epsilon());
        return playerPos.add(result);
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

    private AxisResult testInAxisAndDirection(SimulationContext context, List<SimpleCollisionBox> collisions, List<SimpleCollisionBox> unknown, Vec3 pos, Collisions.Axis axis, double amount, boolean isPositive, ProbeDimensions dimensions) {
        // Nothing to do (in opposite dir, not counting less than epsilon - floating point means you can move 1e16 upwards while colliding downwards
        double axisEpsilon = axisEpsilon(dimensions.epsilon(), axis);
        if (Math.abs(amount) > axisEpsilon && isPositive != (Math.signum(amount) == 1)) return null;

        boolean isVehicle = context.getVehicle() != null;

        // WHY CAN MOJANG NOT HANDLE THE SIMPLEST OF THINGS CORRECTLY
        // START HANDLING OF POTENTIALLY UNKNOWN CLIENT SIDED BLOCKS
        float maximumWidth = context.getVehicle() == null ? dimensions.width() : context.getMaxWidth();
        float maximumHeight = context.getVehicle() == null ? dimensions.maxHeight() : context.getMaxHeight();
        SimpleCollisionBox biggestBoxPossible = GetBoundingBox.getBoundingBoxFromPosAndSize(pos.x, pos.y, pos.z, maximumWidth, maximumHeight);
        biggestBoxPossible.expand(0.1); // For good measure.
        for (SimpleCollisionBox box : unknown) {
            if (box.isCollided(biggestBoxPossible)) {
                return new AxisResult(0, true);
            }
        }
        // We only care about finding the first collision
        if (!unknown.isEmpty()) {
            collisions = new ArrayList<>(collisions);
            collisions.addAll(unknown);
        }
        // END HANDLING OF POTENTIALLY UNKNOWN CLIENT SIDE BLOCKS

        // We should at least probe by collision epsilon...
        // This also means that 0 is transformed into the collision epsilon. This is intentional.
        double epsilon = isPositive ? axisEpsilon : -axisEpsilon;
        amount += epsilon;

        List<Collisions.Axis> axisAsList = Collections.singletonList(axis);

        // Restore Grim 3.0's collision-constrained reporting extent. Old clients
        // can be up to 0.03 from their last reported coordinates; collision
        // clipping, rather than proximity, bounds where that body can be.
        double reportingExtent = !isVehicle && context.getVersion().isOlderThan(ClientVersion.V_1_18_2) ? 0.03D : 0.0D;
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                pos.x, pos.y, pos.z, (float) (dimensions.width() - reportingExtent),
                (float) (dimensions.initialHeight() - reportingExtent));
        if (reportingExtent > 0) {
            List<Collisions.Axis> expandOrder = new ArrayList<>(Arrays.asList(Collisions.Axis.values()));
            expandOrder.remove(axis);
            expandOrder.add(axis);
            Vec3 extent = new Vec3(reportingExtent * 2, reportingExtent * 2, reportingExtent * 2);
            for (Collisions.Axis expandAxis : expandOrder) {
                if (expandAxis == axis) {
                    expandUpwards(playerBox, collisions, axis, epsilon,
                            dimensions.maxHeight() - dimensions.initialHeight(), dimensions.epsilon());
                }
                Vec3 expansion = transformToOnlyHaveAxis(extent, expandAxis);
                List<Collisions.Axis> oneAxis = Collections.singletonList(expandAxis);
                Vec3 positive = Collisions.collideBoundingBoxLegacy(expansion, playerBox, collisions, oneAxis, dimensions.epsilon());
                Vec3 negative = Collisions.collideBoundingBoxLegacy(expansion.scale(-1), playerBox, collisions, oneAxis, dimensions.epsilon());
                playerBox.expandToCoordinate(positive);
                playerBox.expandToCoordinate(negative);
            }
        } else if (!isVehicle) {
            expandUpwards(playerBox, collisions, axis, epsilon,
                    dimensions.maxHeight() - dimensions.initialHeight(), dimensions.epsilon());
        } else {
            // No desync's with vehicle bounding box (that we can control)
            playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(pos.x, pos.y, pos.z, context.getMaxWidth(), context.getMaxHeight());
        }

        // Now, after all of this bounding box fuckery is done, we can finally test for a collision!
        Vec3 amountAsVector = transformToOnlyHaveAxis(new Vec3(amount, amount, amount), axis);
        Vec3 result = Collisions.collideBoundingBoxLegacy(
                amountAsVector, playerBox, collisions, axisAsList, dimensions.epsilon());
        boolean collided = !result.equals(amountAsVector);

        if (!collided) { // We didn't collide in this axis, reduce by epsilon // TODO: DRY YOU FUCKING DONKEY
            Vec3 epsilonAsVector = transformToOnlyHaveAxis(new Vec3(epsilon, epsilon, epsilon), axis);
            result = result.subtract(epsilonAsVector);
        }

        return new AxisResult(getAxisOfVector(result, axis), collided);
    }

    private void expandUpwards(SimpleCollisionBox playerBox, List<SimpleCollisionBox> collisions,
                               Collisions.Axis axis, double epsilon, double amount,
                               SimpleCollisionBox.AxisEpsilon collisionEpsilon) {
        // TODO: Define minimum bounding box extent (for 1.8 players and such to not by 0.6)
        // Expand the player box upwards by their unknown hitbox amount
        Vec3 unknownPlayerBoxAmount = new Vec3(0, amount, 0);
        Vec3 thisAxisResult = Collisions.collideBoundingBoxLegacy(
                unknownPlayerBoxAmount, playerBox, collisions, Collections.singletonList(axis), collisionEpsilon);

        boolean collided = !thisAxisResult.equals(unknownPlayerBoxAmount);
        if (!collided) { // We didn't collide in this axis, reduce by epsilon
            Vec3 epsilonAsVector = transformToOnlyHaveAxis(new Vec3(epsilon, epsilon, epsilon), axis);
            thisAxisResult = thisAxisResult.subtract(epsilonAsVector);
        }
        playerBox.expandToCoordinate(thisAxisResult); // Height uncertainty for bounding box
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

    public record ProbeDimensions(float width, float initialHeight, float maxHeight,
                                  SimpleCollisionBox.AxisEpsilon epsilon) {
        public ProbeDimensions {
            if (width <= 0.0F || initialHeight <= 0.0F || maxHeight < initialHeight) {
                throw new IllegalArgumentException("invalid collision probe dimensions");
            }
            epsilon = java.util.Objects.requireNonNull(epsilon, "epsilon");
        }
    }

    private Vec3 transformToOnlyHaveAxis(Vec3 vector, Collisions.Axis axis) {
        switch (axis) {
            case X:
                return new Vec3(vector.x, 0, 0);
            case Y:
                return new Vec3(0, vector.y, 0);
            case Z:
                return new Vec3(0, 0, vector.z);
        }
        throw new IllegalStateException("Unknown axis " + axis);
    }
}
