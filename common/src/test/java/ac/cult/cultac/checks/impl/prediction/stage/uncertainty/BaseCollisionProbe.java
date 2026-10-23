package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier.ProbeDimensions;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/** CollisionModifier#probeCollisions without the reuse of resolved axis steps. */
final class BaseCollisionProbe {
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
    private final SimpleCollisionBox probeEntityRegion = new SimpleCollisionBox();
    private final AxisResult positiveResult = new AxisResult(0, false);
    private final AxisResult negativeResult = new AxisResult(0, false);

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
        // Start with player bounding box
        SimpleCollisionBox grabBoxesBB = context.getFromMaximumExtent();

        Vec3 stuckSpeed = context.getLastStuckSpeed();

        double candidateY = target.y * stuckSpeed.y;
        target = context.getPacketMovement();
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
        SimpleCollisionBox blockQuery = horizontalInputReach > 0.0D
                ? grabBoxesBB
                        .copy()
                        .expand(horizontalInputReach * stuckSpeed.x, 0.0D, horizontalInputReach * stuckSpeed.z)
                : grabBoxesBB;

        List<SimpleCollisionBox> collisions = probeCollisions;
        collisions.clear();
        Collisions.getCollisionBoxes(player, blockQuery, collisions, false, playerPos.y, actor);

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
            if (worlds == null) {
                return new CollideAxisData(
                        new CollideAxisData.CollideResult(true, 0),
                        new CollideAxisData.CollideResult(true, 0),
                        new CollideAxisData.CollideResult(true, 0),
                        new CollideAxisData.CollideResult(true, 0),
                        List.copyOf(found));
            }
            if (!EntityPositionWorlds.noEntities(worlds)) {
                int blocks = collisions.size();
                List<CollideAxisData> results = new ArrayList<>(worlds.size());
                for (int w = 0; w < worlds.size(); w++) {
                    collisions.addAll(worlds.get(w));
                    results.add(probeAxes(
                            context,
                            minY,
                            candidateY,
                            target,
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

        // Brute force the current-client axis order possibilities exposed by the server-observable movement envelope.
        for (int orderIndex = 0; orderIndex < PROBE_ORDERS.length; orderIndex++) {
            if (orderIndex >= 2 && !groundedOrder) continue;
            var order = PROBE_ORDERS[orderIndex];
            double iterX = playerPos.x, iterY = playerPos.y, iterZ = playerPos.z;
            for (Collisions.Axis axis : order) {
                double movement = getAxisOfVector(target, axis);
                double positiveMovement =
                        getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, true);
                double negativeMovement =
                        getAttemptedAxisMovement(axis, movement, attemptedMovementExtents, stuckSpeed, false);
                double multiplier = 1 / getAxisOfVector(stuckSpeed, axis); // TODO: How do we stuck speed collision?

                AxisResult pos = testInAxisAndDirection(
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
                    double moved = moveBoundingBoxForAxis(
                            context, collisionsMinusUnknown, axis, movement, iterX, iterY, iterZ, dimensions);
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
        if (!unknown.isEmpty()) {
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
}
