package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class BedrockCollisionClipper {
    private static final float NO_LOWER = Float.NEGATIVE_INFINITY;
    private static final float NO_UPPER = Float.POSITIVE_INFINITY;
    static final float DEFAULT_MAX_DEPENETRATION = 1.0F;

    /**
     * The deepest full overlap, on each axis, the swept move resolves through the nearest face. A ridden vehicle is
     * moved out of a block it overlaps by the move itself, until two moves in a row have overlapped a shape: from then
     * on it keeps no limit of its own until a move overlaps nothing. A player is pushed out by the end-of-tick block
     * push instead. Each solid actor among {@code obstacles} raises the limit of the whole move to its own.
     */
    static BedrockDepenetrationLimit maximumDepenetration(
            ac.cult.cultac.bedrock.prediction.state.BedrockMovementState state, List<BlockCollision> obstacles) {
        float own = !state.isVehicle() ? 0.01F : state.penetratingMoves() < 2 ? DEFAULT_MAX_DEPENETRATION : 0.0F;
        BedrockDepenetrationLimit limit = BedrockDepenetrationLimit.uniform(own);
        for (int index = obstacles.size() - 1; index >= 0; index--) {
            Optional<PlacedBlockCollision> block = obstacles.get(index).block();
            if (block.isEmpty()) continue;
            BedrockDepenetrationLimit actor = BedrockDepenetrationLimit.ofSolidActor(block.get());
            if (actor != null) limit = limit.atLeast(actor);
        }
        return limit;
    }

    private BedrockCollisionClipper() {}

    static ClipResult clip(WorldCollisionBox moving, Vec3d requestedMove, List<BlockCollision> obstacles) {
        return clip(moving, requestedMove, obstacles, BedrockCoordinateFrame.IDENTITY);
    }

    static ClipResult clip(
            WorldCollisionBox moving,
            Vec3d requestedMove,
            List<BlockCollision> obstacles,
            BedrockCoordinateFrame frame) {
        return clip(
                moving, requestedMove, obstacles, frame, BedrockDepenetrationLimit.uniform(DEFAULT_MAX_DEPENETRATION));
    }

    static ClipResult clip(
            WorldCollisionBox moving,
            Vec3d requestedMove,
            List<BlockCollision> obstacles,
            BedrockCoordinateFrame frame,
            BedrockDepenetrationLimit maximumDepenetration) {
        FloatMove move = FloatMove.from(requestedMove);
        FloatMove requested = move;
        Optional<PlacedBlockCollision> yCollisionBlock = Optional.empty();
        // The buffer of the actor that ended each axis, while no block ended it after.
        float bufferX = 0.0F, bufferY = 0.0F, bufferZ = 0.0F;
        float penetration = 0.0F;
        // The two highest distinct lower bounds and two lowest distinct upper bounds solid actors put on each axis.
        float lx1 = NO_LOWER, lx2 = NO_LOWER, ly1 = NO_LOWER, ly2 = NO_LOWER, lz1 = NO_LOWER, lz2 = NO_LOWER;
        float ux1 = NO_UPPER, ux2 = NO_UPPER, uy1 = NO_UPPER, uy2 = NO_UPPER, uz1 = NO_UPPER, uz2 = NO_UPPER;
        // The solid actors come last in the list, so the move meets them first; then the blocks.
        boolean actorsMet = false;
        int crossed = 0;
        // Along each crossed axis, the move had the actors bounding it from below been met last, and from above.
        float belowLastX = 0.0F, aboveLastX = 0.0F, belowLastY = 0.0F, aboveLastY = 0.0F;
        float belowLastZ = 0.0F, aboveLastZ = 0.0F;
        FloatMove afterActors = move;
        // The vanilla swept-move consumes shapes in reverse insertion
        // order and moves the actor only after the phase.
        for (int index = obstacles.size() - 1; index >= 0; index--) {
            BlockCollision obstacle = obstacles.get(index);
            if (!actorsMet && !isSolidActor(obstacle)) {
                actorsMet = true;
                afterActors = move;
                // Bounds of one axis commute unless a lower one lies above an upper one: then the actor met last
                // decides the axis, between the highest lower bound and the lowest upper bound.
                crossed = (lx1 > ux1 ? 1 : 0) | (ly1 > uy1 ? 2 : 0) | (lz1 > uz1 ? 4 : 0);
                belowLastX = lx1;
                aboveLastX = ux1;
                belowLastY = ly1;
                aboveLastY = uy1;
                belowLastZ = lz1;
                aboveLastZ = uz1;
            }
            AabbClip clip = clipCollide(obstacle.box(), moving, frame);
            // Every full overlap counts, whether the move leaves the shape or passes through it.
            penetration += clip.penetration();
            // An overlap deeper than the limit leaves the shape out of the move.
            if (clip.bound() == Bound.NONE || clip.penetration() > clip.axis().component(maximumDepenetration)) {
                continue;
            }
            if (!actorsMet) {
                boolean lower = clip.bound() == Bound.AT_LEAST;
                float edge = clip.edge();
                switch (clip.axis()) {
                    case X -> {
                        if (lower) {
                            if (edge > lx1) {
                                lx2 = lx1;
                                lx1 = edge;
                            } else if (edge < lx1 && edge > lx2) lx2 = edge;
                        } else if (edge < ux1) {
                            ux2 = ux1;
                            ux1 = edge;
                        } else if (edge > ux1 && edge < ux2) ux2 = edge;
                    }
                    case Y -> {
                        if (lower) {
                            if (edge > ly1) {
                                ly2 = ly1;
                                ly1 = edge;
                            } else if (edge < ly1 && edge > ly2) ly2 = edge;
                        } else if (edge < uy1) {
                            uy2 = uy1;
                            uy1 = edge;
                        } else if (edge > uy1 && edge < uy2) uy2 = edge;
                    }
                    case Z -> {
                        if (lower) {
                            if (edge > lz1) {
                                lz2 = lz1;
                                lz1 = edge;
                            } else if (edge < lz1 && edge > lz2) lz2 = edge;
                        } else if (edge < uz1) {
                            uz2 = uz1;
                            uz1 = edge;
                        } else if (edge > uz1 && edge < uz2) uz2 = edge;
                    }
                }
            } else if ((crossed & 1 << clip.axis().ordinal()) != 0) {
                // The blocks bound both ends of a crossed axis as they bound the move.
                switch (clip.axis()) {
                    case X -> {
                        belowLastX = clip.apply(belowLastX);
                        aboveLastX = clip.apply(aboveLastX);
                    }
                    case Y -> {
                        belowLastY = clip.apply(belowLastY);
                        aboveLastY = clip.apply(aboveLastY);
                    }
                    case Z -> {
                        belowLastZ = clip.apply(belowLastZ);
                        aboveLastZ = clip.apply(aboveLastZ);
                    }
                }
            }
            FloatMove clippedMove = clip.apply(move);
            if (clippedMove.x() != move.x()) bufferX = actorsMet ? 0.0F : buffer(obstacle);
            if (clippedMove.y() != move.y()) bufferY = actorsMet ? 0.0F : buffer(obstacle);
            if (clippedMove.z() != move.z()) bufferZ = actorsMet ? 0.0F : buffer(obstacle);
            if (clippedMove.y() != move.y()) {
                yCollisionBlock = obstacle.block();
            }
            move = clippedMove;
        }
        if (!actorsMet) {
            afterActors = move;
            crossed = (lx1 > ux1 ? 1 : 0) | (ly1 > uy1 ? 2 : 0) | (lz1 > uz1 ? 4 : 0);
            belowLastX = lx1;
            aboveLastX = ux1;
            belowLastY = ly1;
            aboveLastY = uy1;
            belowLastZ = lz1;
            aboveLastZ = uz1;
        }
        BufferedEnds bufferedX = bufferX > 0.0F
                ? bufferedEnds(
                        SweepAxis.X, move.x(), requested.x(), bufferX, obstacles, moving, frame, maximumDepenetration)
                : BufferedEnds.NONE;
        BufferedEnds bufferedY = bufferY > 0.0F
                ? bufferedEnds(
                        SweepAxis.Y, move.y(), requested.y(), bufferY, obstacles, moving, frame, maximumDepenetration)
                : BufferedEnds.NONE;
        BufferedEnds bufferedZ = bufferZ > 0.0F
                ? bufferedEnds(
                        SweepAxis.Z, move.z(), requested.z(), bufferZ, obstacles, moving, frame, maximumDepenetration)
                : BufferedEnds.NONE;
        int bufferedAxes = (bufferedX.widens() ? 1 : 0) | (bufferedY.widens() ? 2 : 0) | (bufferedZ.widens() ? 4 : 0);
        if (crossed == 0) {
            return new ClipResult(
                    move.toVec3d(),
                    yCollisionBlock,
                    penetration,
                    BedrockActorOrderCrossing.buffered(
                            null,
                            bufferedAxes,
                            bufferedX,
                            bufferedY,
                            bufferedZ,
                            Math.max(bufferX, Math.max(bufferY, bufferZ))));
        }
        // An axis with one actor reaching across from each side ends at one of its two ends whatever the order; a
        // second distinct bound inside the crossing gives orders that end between them.
        int exact = (!(lx2 > ux1) && !(ux2 < lx1) ? 1 : 0)
                | (!(ly2 > uy1) && !(uy2 < ly1) ? 2 : 0)
                | (!(lz2 > uz1) && !(uz2 < lz1) ? 4 : 0);
        int belowWon =
                (afterActors.x() == lx1 ? 1 : 0) | (afterActors.y() == ly1 ? 2 : 0) | (afterActors.z() == lz1 ? 4 : 0);
        BedrockActorOrderCrossing crossing = new BedrockActorOrderCrossing(
                crossed,
                exact & crossed,
                belowWon & crossed,
                (crossed & 1) == 0 ? 0.0F : Math.min(belowLastX, aboveLastX) - move.x(),
                (crossed & 2) == 0 ? 0.0F : Math.min(belowLastY, aboveLastY) - move.y(),
                (crossed & 4) == 0 ? 0.0F : Math.min(belowLastZ, aboveLastZ) - move.z(),
                (crossed & 1) == 0 ? 0.0F : Math.max(belowLastX, aboveLastX) - move.x(),
                (crossed & 2) == 0 ? 0.0F : Math.max(belowLastY, aboveLastY) - move.y(),
                (crossed & 4) == 0 ? 0.0F : Math.max(belowLastZ, aboveLastZ) - move.z(),
                0);
        return new ClipResult(
                move.toVec3d(),
                yCollisionBlock,
                penetration,
                BedrockActorOrderCrossing.buffered(
                        crossing,
                        bufferedAxes,
                        bufferedX,
                        bufferedY,
                        bufferedZ,
                        Math.max(bufferX, Math.max(bufferY, bufferZ))));
    }

    /** The buffer of a moving boat's or happy ghast's shape; 0 for a block or any other actor. */
    private static float buffer(BlockCollision obstacle) {
        return obstacle.block().map(BedrockDepenetrationLimit::buffer).orElse(0.0F);
    }

    /**
     * Where a move an actor with {@code buffer} ended along {@code axis} at {@code end} may end instead, as offsets
     * from that end: up to the buffer either way, neither back past the move's start nor past the requested move, and
     * within the bounds the blocks put on the axis.
     */
    private static BufferedEnds bufferedEnds(
            SweepAxis axis,
            float end,
            float requested,
            float buffer,
            List<BlockCollision> obstacles,
            WorldCollisionBox moving,
            BedrockCoordinateFrame frame,
            BedrockDepenetrationLimit maximumDepenetration) {
        float low = Math.max(end - buffer, Math.min(0.0F, requested));
        float high = Math.min(end + buffer, Math.max(0.0F, requested));
        for (BlockCollision obstacle : obstacles) {
            if (isSolidActor(obstacle)) continue;
            AabbClip clip = clipCollide(obstacle.box(), moving, frame);
            if (clip.axis() != axis
                    || clip.bound() == Bound.NONE
                    || clip.penetration() > axis.component(maximumDepenetration)) continue;
            low = clip.apply(low);
            high = clip.apply(high);
        }
        return new BufferedEnds(Math.min(0.0F, low - end), Math.max(0.0F, high - end));
    }

    /** The offsets from a move's end along one axis that a moving actor's buffer allows. */
    record BufferedEnds(float min, float max) {
        static final BufferedEnds NONE = new BufferedEnds(0.0F, 0.0F);

        boolean widens() {
            return min < 0.0F || max > 0.0F;
        }
    }

    /**
     * How {@code obstacle} bounds a phase of a move from {@code moving}: bit 2 * axis, +1 when it bounds the axis from
     * below. -1 when it does not bound the move.
     */
    static int bound(
            WorldCollisionBox obstacle,
            WorldCollisionBox moving,
            BedrockCoordinateFrame frame,
            BedrockDepenetrationLimit maximumDepenetration) {
        AabbClip clip = clipCollide(obstacle, moving, frame);
        if (clip.bound() == Bound.NONE || clip.penetration() > clip.axis().component(maximumDepenetration)) {
            return -1;
        }
        return 2 * clip.axis().ordinal() + (clip.bound() == Bound.AT_LEAST ? 1 : 0);
    }

    static boolean isSolidActor(BlockCollision obstacle) {
        return obstacle.block().isPresent() && obstacle.block().get().isEntityCollision();
    }

    /**
     * A shape's clip of one phase, fixed by the boxes alone: it bounds one axis of the move from above or below. Two
     * bounds of one axis give the same move in either order unless the lower lies above the upper.
     */
    private static AabbClip clipCollide(
            WorldCollisionBox obstacle, WorldCollisionBox moving, BedrockCoordinateFrame frame) {
        if (frame.localX(obstacle.maxX()) <= frame.localX(obstacle.minX())
                || f(obstacle.maxY()) <= f(obstacle.minY())
                || frame.localZ(obstacle.maxZ()) <= frame.localZ(obstacle.minZ())) {
            return AabbClip.UNCHANGED;
        }

        AxisOverlap x = axisOverlap(
                frame.localX(obstacle.minX()),
                frame.localX(obstacle.maxX()),
                frame.localX(moving.minX()),
                frame.localX(moving.maxX()));
        AxisOverlap y = axisOverlap(f(obstacle.minY()), f(obstacle.maxY()), f(moving.minY()), f(moving.maxY()));
        AxisOverlap z = axisOverlap(
                frame.localZ(obstacle.minZ()),
                frame.localZ(obstacle.maxZ()),
                frame.localZ(moving.minZ()),
                frame.localZ(moving.maxZ()));
        int separatedAxes = (x.overlapping() ? 0 : 1) + (y.overlapping() ? 0 : 1) + (z.overlapping() ? 0 : 1);
        if (separatedAxes >= 2) {
            return AabbClip.UNCHANGED;
        }

        if (separatedAxes == 1) {
            // The move stops at the face across the gap: the gap bounds the move towards the shape.
            SweepAxis axis = !x.overlapping() ? SweepAxis.X : !y.overlapping() ? SweepAxis.Y : SweepAxis.Z;
            AxisOverlap separation = overlap(axis, x, y, z);
            return new AabbClip(
                    0.0F,
                    axis,
                    separation.sign() < 0.0F ? Bound.AT_MOST : Bound.AT_LEAST,
                    separation.depth() * separation.sign());
        }

        // Full overlaps resolve through the nearest face, with X/Y/Z tie priority.
        SweepAxis axis = SweepAxis.X;
        AxisOverlap nearest = x;
        if (y.depth() < nearest.depth()) {
            axis = SweepAxis.Y;
            nearest = y;
        }
        if (z.depth() < nearest.depth()) {
            axis = SweepAxis.Z;
            nearest = z;
        }

        float signedExit = nearest.depth() * nearest.sign();
        return new AabbClip(nearest.depth(), axis, signedExit > 0.0F ? Bound.AT_LEAST : Bound.AT_MOST, signedExit);
    }

    private static AxisOverlap axisOverlap(float obstacleMin, float obstacleMax, float movingMin, float movingMax) {
        float positiveExit = clean(obstacleMax - movingMin, obstacleMax);
        float negativeExit = clean(movingMax - obstacleMin, obstacleMin);
        if (negativeExit <= 0.0F) {
            return new AxisOverlap(negativeExit, -1.0F, false);
        }
        if (positiveExit <= 0.0F) {
            return new AxisOverlap(positiveExit, 1.0F, false);
        }
        return positiveExit <= negativeExit
                ? new AxisOverlap(positiveExit, 1.0F, true)
                : new AxisOverlap(negativeExit, -1.0F, true);
    }

    private static AxisOverlap overlap(SweepAxis axis, AxisOverlap x, AxisOverlap y, AxisOverlap z) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    /**
     * A box the client left flush with a face can come back from its float feet up to one float step past that face;
     * an overlap that shallow is the contact it was, not one to leave.
     */
    private static float clean(float value, float face) {
        if (Math.abs(value) <= (float) BedrockCollisionSweep.CONTACT_EPSILON) {
            return 0.0F;
        }
        return value > 0.0F && value <= Math.ulp(face) ? 0.0F : value;
    }

    private static float f(double value) {
        return (float) value;
    }

    /** {@code actorOrder}: how the order of the phase's solid actors decides it, or null when it cannot. */
    record ClipResult(
            Vec3d move,
            Optional<PlacedBlockCollision> yCollisionBlock,
            float penetration,
            BedrockActorOrderCrossing actorOrder) {
        ClipResult {
            move = Objects.requireNonNull(move, "move");
            yCollisionBlock = Objects.requireNonNull(yCollisionBlock, "yCollisionBlock");
        }
    }

    /** How a shape's clip bounds its axis of the move. */
    private enum Bound {
        NONE,
        AT_MOST,
        AT_LEAST
    }

    private record AabbClip(float penetration, SweepAxis axis, Bound bound, float edge) {
        private static final AabbClip UNCHANGED = new AabbClip(0.0F, SweepAxis.X, Bound.NONE, 0.0F);

        private AabbClip {
            axis = Objects.requireNonNull(axis, "axis");
            bound = Objects.requireNonNull(bound, "bound");
        }

        private FloatMove apply(FloatMove move) {
            float current = move.component(axis);
            float clipped = apply(current);
            return clipped != current ? move.with(axis, clipped) : move;
        }

        private float apply(float current) {
            boolean bounded = bound == Bound.AT_MOST ? current > edge : current < edge;
            return bounded ? edge : current;
        }
    }

    private record AxisOverlap(float depth, float sign, boolean overlapping) {}

    private record FloatMove(float x, float y, float z) {
        private static FloatMove from(Vec3d move) {
            return new FloatMove(f(move.x()), f(move.y()), f(move.z()));
        }

        private float component(SweepAxis axis) {
            return switch (axis) {
                case X -> x;
                case Y -> y;
                case Z -> z;
            };
        }

        private FloatMove with(SweepAxis axis, float value) {
            return switch (axis) {
                case X -> new FloatMove(value, y, z);
                case Y -> new FloatMove(x, value, z);
                case Z -> new FloatMove(x, y, value);
            };
        }

        private Vec3d toVec3d() {
            return new Vec3d(x, y, z);
        }
    }

    private enum SweepAxis {
        X,
        Y,
        Z;

        private float component(BedrockDepenetrationLimit limit) {
            return switch (this) {
                case X -> limit.x();
                case Y -> limit.y();
                case Z -> limit.z();
            };
        }
    }
}
