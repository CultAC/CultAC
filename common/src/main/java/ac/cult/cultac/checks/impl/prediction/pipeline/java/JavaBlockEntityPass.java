package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorldPistons;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.List;

/**
 * The local player's part of one client Level#tickBlockEntities: each PistonMovingBlockEntity#tick's
 * moveCollidedEntities and moveStuckEntities and each ShulkerBoxBlockEntity#moveCollidedEntities, in ticker order,
 * each from the state the one before left; after the Shulker#onPeekAmountChange moves of ClientLevel#tickEntities
 * that lead the pass.
 */
public final class JavaBlockEntityPass {
    private JavaBlockEntityPass() {}

    /**
     * What the pass left. {@code moved} is whether an Entity#move set onGround and the collision flags. A
     * {@code fixed} axis ends where the pass put it whatever the position it started from on that axis: a partial
     * shove ends 0.01 past the moving face and a collided move at the collider. {@code boatLastYd} is the latest
     * virtual fall callback's deltaMovement.y sample, or null when this pass ran no such callback. {@code lastMove} is
     * the last Entity#move that set the collision flags, or null when none did.
     */
    public record Result(
            JavaPassState state,
            boolean moved,
            boolean fixedX,
            boolean fixedY,
            boolean fixedZ,
            ReachInterpolationData interpolation,
            Double boatLastYd,
            ClientFluidHeights fluidHeights,
            LastMove lastMove,
            ReachInterpolationData oldInterpolation) {
        public Result(
                JavaPassState state,
                boolean moved,
                boolean fixedX,
                boolean fixedY,
                boolean fixedZ,
                ReachInterpolationData interpolation,
                Double boatLastYd,
                ClientFluidHeights fluidHeights,
                LastMove lastMove) {
            this(state, moved, fixedX, fixedY, fixedZ, interpolation, boatLastYd, fluidHeights, lastMove, null);
        }

        public Result(
                JavaPassState state,
                boolean moved,
                boolean fixedX,
                boolean fixedY,
                boolean fixedZ,
                ReachInterpolationData interpolation,
                Double boatLastYd,
                ClientFluidHeights fluidHeights) {
            this(state, moved, fixedX, fixedY, fixedZ, interpolation, boatLastYd, fluidHeights, null);
        }

        public Result(
                JavaPassState state,
                boolean moved,
                boolean fixedX,
                boolean fixedY,
                boolean fixedZ,
                ReachInterpolationData interpolation,
                Double boatLastYd) {
            this(state, moved, fixedX, fixedY, fixedZ, interpolation, boatLastYd, null);
        }

        public Result(JavaPassState state, boolean moved, boolean fixedX, boolean fixedY, boolean fixedZ) {
            this(state, moved, fixedX, fixedY, fixedZ, null, null);
        }

        public Vec3 movement(Vec3 start) {
            return state.position().subtract(start);
        }
    }

    /**
     * One Entity#move's delta after its stuck multiplier and its collided movement: Entity#move sets horizontalCollision
     * and minorHorizontalCollision from them, which LocalPlayer#aiStep reads before the next travel.
     */
    public record LastMove(Vec3 delta, Vec3 movement) {}

    /**
     * Whether {@code version}'s LocalPlayer reports its position after Level#tickBlockEntities: from 26.3
     * Minecraft#tick sends it in LocalPlayer#sendChanges after the pass, before that in LocalPlayer#tick before it.
     */
    public static boolean reportsAfterPass(ClientVersion version) {
        return version.isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    /**
     * Replays {@code pass} on the player in {@code pose} from {@code start}. {@code context} supplies the actor,
     * version and attributes, {@code sneaking} the shift key and {@code tickStart} the position the client tick began
     * at.
     */
    public static Result run(
            CultPlayer player,
            SimulationContext context,
            ClientBlockEntityPass pass,
            JavaPassState start,
            Pose pose,
            boolean sneaking,
            Vec3 tickStart) {
        return run(player, context, pass, start, pose, sneaking, tickStart, context.getMovementPatch());
    }

    public static Result run(
            CultPlayer player,
            SimulationContext context,
            ClientBlockEntityPass pass,
            JavaPassState start,
            Pose pose,
            boolean sneaking,
            Vec3 tickStart,
            JavaMovementPatch patch) {
        if (context.getVehicle() != null) sneaking = (context.getVehicle().actionSharedFlags & 2) != 0;
        var env = new JavaBlockEntityMove.Environment(
                player, context, pose, context.getScale(), sneaking, tickStart, pass.gameTime(), null, patch);
        return run(env, pass, start);
    }

    /** The same block-entity moves on a tracked remote root; it has no local travel authority. */
    public static Result runEntity(
            CultPlayer player, PacketEntity actor, ClientBlockEntityPass pass, JavaPassState start) {
        if (actor.type == EntityTypeIds.SHULKER) start = start.withVelocity(Vec3.ZERO);
        var env = new JavaBlockEntityMove.Environment(
                player,
                null,
                null,
                actor.scale,
                (actor.actionSharedFlags & 2) != 0,
                start.position(),
                pass.gameTime(),
                actor,
                actor.pistonMovementPatch());
        return run(env, pass, start);
    }

    /**
     * {@link #runEntity} for an entity whose position is not tracked, such as a rider outside the player's riding
     * trees: the result is where the pass leaves it, and the entity itself is left as it was.
     */
    public static Result runDetachedEntity(
            CultPlayer player,
            PacketEntity actor,
            ClientBlockEntityPass pass,
            JavaPassState start,
            JavaMovementPatch patch) {
        var env = new JavaBlockEntityMove.Environment(
                player,
                null,
                null,
                actor.scale,
                (actor.actionSharedFlags & 2) != 0,
                start.position(),
                pass.gameTime(),
                actor,
                patch,
                true);
        return run(env, pass, start);
    }

    private static Result run(JavaBlockEntityMove.Environment env, ClientBlockEntityPass pass, JavaPassState start) {
        var replay = new Replay(env, start);
        CompensatedWorld world = env.player().compensatedWorld;
        CompensatedWorldPistons pistons = world.pistons;
        CompensatedEntities entities = env.player().compensatedEntities;
        long tickedThrough = entities.passTickedThroughOrder();
        List<ClientBlockEntityPass.Lid> lids = pass.lids();
        int blockEntityMovers = 0, lid = 0;
        try (var ignored = world.beginBlockEntityReplay(pass)) {
            for (ClientBlockEntityPass.Mover mover : pass.movers()) {
                // A move collides with the entities as the tick has left them by then: every entity has ticked
                // before the block entities, and a shulker's move follows its own tick and those before it.
                entities.setPassTickedThroughOrder(
                        mover instanceof ClientBlockEntityPass.ShulkerPeek peek ? peek.tickOrder() : Long.MAX_VALUE);
                if (!(mover instanceof ClientBlockEntityPass.ShulkerPeek)) {
                    // Every ShulkerBoxBlockEntity#updateAnimation ticking before this block entity has run, and a
                    // box's own update sets the lid its moveCollidedEntities collides with.
                    while (lid < lids.size() && lids.get(lid).beforeMover() <= blockEntityMovers)
                        world.advanceLidReplay(lids.get(lid++));
                    blockEntityMovers++;
                }
                if (mover instanceof ClientBlockEntityPass.Piston piston) {
                    replay.tick(piston);
                    pistons.advanceReplay(piston.pos(), piston.storedProgress());
                } else if (mover instanceof ClientBlockEntityPass.ShulkerBox shulkerBox) {
                    replay.shulkerBox(shulkerBox);
                } else if (mover instanceof ClientBlockEntityPass.ShulkerPeek peek) {
                    replay.shulkerPeek(peek);
                }
            }
        } finally {
            entities.setPassTickedThroughOrder(tickedThrough);
        }
        return new Result(
                replay.state,
                replay.moved,
                replay.fixed[0],
                replay.fixed[1],
                replay.fixed[2],
                replay.interpolation,
                replay.boatLastYd,
                replay.fluidHeights,
                replay.lastMove,
                replay.oldInterpolation);
    }

    private static final class Replay {
        private final JavaBlockEntityMove.Environment env;
        private JavaPassState state;
        private boolean moved;
        private Double boatLastYd;
        private ClientFluidHeights fluidHeights;
        private LastMove lastMove;
        private final ReachInterpolationData interpolation;
        private final ReachInterpolationData oldInterpolation;
        private final boolean[] fixed = new boolean[3];

        private Replay(JavaBlockEntityMove.Environment env, JavaPassState state) {
            this.env = env;
            this.state = state;
            PacketEntity actor = env.actor();
            ReachInterpolationData riderPath = env.detached() ? actor.riderInterpolationPath(false) : null;
            ReachInterpolationData oldRiderPath = env.detached() ? actor.riderInterpolationPath(true) : null;
            this.oldInterpolation = oldRiderPath == null ? null : oldRiderPath.copy();
            this.interpolation = riderPath != null
                    ? riderPath.copy()
                    : env.authoritative()
                                    && env.context().getVehicle() != null
                                    && env.version().isNewerThanOrEquals(ClientVersion.V_26_3)
                                    && actor.newPacketLocation.hasActiveInterpolationTarget()
                            ? actor.newPacketLocation.copy()
                            : null;
        }

        private SimpleCollisionBox box() {
            return env.box(state.position());
        }

        /** PistonMovingBlockEntity#tick of a block entity whose progress is below 1. */
        private void tick(ClientBlockEntityPass.Piston piston) {
            Direction movement = piston.movementDirection();
            double deltaProgress = piston.newProgress() - piston.progress();
            moveCollidedEntities(piston, movement, deltaProgress);
            moveStuckEntities(piston, movement, deltaProgress);
        }

        private void moveCollidedEntities(
                ClientBlockEntityPass.Piston piston, Direction movement, double deltaProgress) {
            BlockPos pos = piston.pos();
            VoxelShape shape = env.player().compensatedWorld.geometry().collision(piston.collisionRelatedState(), pos);
            if (shape.isEmpty()) return;
            List<SimpleCollisionBox> boxes = NativeBlockCollisionHelper.toBoxes(shape);
            SimpleCollisionBox bounds = moveByPositionAndProgress(pos, bounds(boxes), piston);
            SimpleCollisionBox query =
                    movementArea(bounds, movement, deltaProgress).union(bounds);
            // Level#getEntities matches the entities whose box intersects the movement area.
            if (!intersects(box(), query)) return;

            if ((env.authoritative()
                            || env.version().isOlderThan(ClientVersion.V_26_3)
                            || env.actor().type == EntityTypeIds.FISHING_BOBBER)
                    && BlockIds.is(piston.movedState(), BlockIds.SLIME_BLOCK)) {
                // Through 26.2 only ServerPlayer is excluded, and no client-side entity is a ServerPlayer.
                // From 26.3 the callback instead requires Entity#canSimulateMovement.
                // Projectile#getMoveSimulationType is SERVER_AND_CLIENT, including remote fishing hooks.
                Vec3 velocity = state.velocity();
                state = state.withVelocity(
                        switch (movement.getAxis()) {
                            case X -> new Vec3(movement.getStepX(), velocity.y, velocity.z);
                            case Y -> new Vec3(velocity.x, movement.getStepY(), velocity.z);
                            case Z -> new Vec3(velocity.x, velocity.y, movement.getStepZ());
                        });
            }

            double delta = 0.0;
            for (SimpleCollisionBox local : boxes) {
                SimpleCollisionBox moving =
                        movementArea(moveByPositionAndProgress(pos, local, piston), movement, deltaProgress);
                SimpleCollisionBox entity = box();
                if (intersects(moving, entity)) {
                    delta = Math.max(delta, getMovement(moving, movement, entity));
                    if (delta >= deltaProgress) break;
                }
            }
            if (delta <= 0.0) return;
            boolean partial = delta < deltaProgress;
            delta = Math.min(delta, deltaProgress) + 0.01;
            moveEntityByPiston(movement, delta, movement, partial);
            if (!piston.extending() && piston.source()) fixEntityWithinPistonBase(pos, movement, deltaProgress);
        }

        /** PistonMovingBlockEntity#moveStuckEntities: a honey block carries the grounded entities on it sideways. */
        private void moveStuckEntities(ClientBlockEntityPass.Piston piston, Direction movement, double deltaProgress) {
            if (!BlockIds.is(piston.movedState(), BlockIds.HONEY_BLOCK) || movement.getAxis() == Direction.Axis.Y)
                return;
            BlockPos pos = piston.pos();
            List<SimpleCollisionBox> moved = NativeBlockCollisionHelper.toBoxes(
                    env.player().compensatedWorld.geometry().collision(piston.movedState(), pos));
            double stickyTop = moved.isEmpty() ? Double.NEGATIVE_INFINITY : bounds(moved).maxY;
            SimpleCollisionBox aabb = moveByPositionAndProgress(
                    pos, new SimpleCollisionBox(0.0, stickyTop, 0.0, 1.0, 1.5000010000000001, 1.0), piston);
            if (!intersects(box(), aabb) || !matchesStickyCriteria(aabb, pos)) return;
            moveEntityByPiston(movement, deltaProgress, movement, false);
        }

        // PistonMovingBlockEntity#matchesStickyCritera for the local player, whose push reaction is the default.
        private boolean matchesStickyCriteria(SimpleCollisionBox aabb, BlockPos pos) {
            Vec3 position = state.position();
            BlockPos supporting =
                    state.support() == null ? null : state.support().getBlockPos();
            return state.onGround()
                    && (pos.equals(supporting)
                            || position.x >= aabb.minX
                                    && position.x <= aabb.maxX
                                    && position.z >= aabb.minZ
                                    && position.z <= aabb.maxZ);
        }

        /**
         * PistonMovingBlockEntity#fixEntityWithinPistonBase: a retracting source pushes an entity still inside its
         * base block back out, against the movement.
         */
        private void fixEntityWithinPistonBase(BlockPos pos, Direction direction, double deltaProgress) {
            SimpleCollisionBox entity = box();
            SimpleCollisionBox base = new SimpleCollisionBox(pos);
            if (!intersects(entity, base)) return;
            Direction opposite = direction.getOppositeFace();
            double delta = getMovement(base, opposite, entity) + 0.01;
            double deltaIntersected = getMovement(base, opposite, intersection(entity, base)) + 0.01;
            if (Math.abs(delta - deltaIntersected) < 0.01) {
                boolean partial = delta < deltaProgress;
                delta = Math.min(delta, deltaProgress) + 0.01;
                moveEntityByPiston(direction, delta, opposite, partial);
            }
        }

        /**
         * PistonMovingBlockEntity#moveEntityByPiston: NOCLIP lets the move pass the blocks moving the
         * {@code noClip} way; the piston's Entity#move and its block pass.
         */
        private void moveEntityByPiston(Direction noClip, double delta, Direction movement, boolean partial) {
            CompensatedWorldPistons pistons = env.player().compensatedWorld.pistons;
            pistons.setReplayNoClip(noClip);
            try {
                Vec3 previous = state.position();
                Vec3 shove =
                        new Vec3(delta * movement.getStepX(), delta * movement.getStepY(), delta * movement.getStepZ());
                JavaBlockEntityMove.Moved result =
                        JavaBlockEntityMove.move(env, state, JavaBlockEntityMove.Mover.PISTON, shove);
                // Entity#limitPistonMovement or a stuck multiplier makes the move a fixed distance instead.
                record(result, axis(movement), partial && result.delta().equals(shove));
                state = JavaBlockEntityMove.applyEffectsFromBlocks(env, result.state(), previous);
            } finally {
                pistons.setReplayNoClip(null);
            }
        }

        /** ShulkerBoxBlockEntity#moveCollidedEntities: every entity whose box meets the area moves. */
        private void shulkerBox(ClientBlockEntityPass.ShulkerBox shulkerBox) {
            if (!intersects(box(), shulkerBox.area())) return;
            Vec3 movement = shulkerBox.movement();
            JavaBlockEntityMove.Moved result =
                    JavaBlockEntityMove.move(env, state, JavaBlockEntityMove.Mover.SHULKER_BOX, movement);
            record(result, movement.x != 0 ? 0 : movement.y != 0 ? 1 : 2, false);
            state = result.state();
        }

        /**
         * Shulker#onPeekAmountChange: Level#getEntities finds each entity whose box meets the opened part, and
         * Entity#move moves it with MoverType.SHULKER.
         */
        private void shulkerPeek(ClientBlockEntityPass.ShulkerPeek peek) {
            if (!intersects(box(), peek.area())) return;
            Vec3 movement = peek.movement();
            JavaBlockEntityMove.Moved result =
                    JavaBlockEntityMove.move(env, state, JavaBlockEntityMove.Mover.SHULKER, movement);
            record(result, movement.x != 0 ? 0 : movement.y != 0 ? 1 : 2, false);
            state = result.state();
        }

        private void record(JavaBlockEntityMove.Moved result, int axis, boolean partial) {
            if (!result.moved()) return;
            lastMove = new LastMove(result.delta(), result.movement());
            if (result.boatLastYd() != null) boatLastYd = result.boatLastYd();
            if (result.fluidHeights() != null) fluidHeights = result.fluidHeights();
            if (interpolation != null && result.recordedMovement())
                interpolation.applyPredictedMovement(
                        env.player(),
                        env.actor(),
                        result.movement(),
                        result.state().position());
            if (oldInterpolation != null && result.recordedMovement())
                oldInterpolation.applyPredictedMovement(
                        env.player(),
                        env.actor(),
                        result.movement(),
                        result.state().position());
            moved = true;
            if (partial || result.clipped(axis)) fixed[axis] = true;
            // Entity#collide's step raises a horizontal move to whatever height the obstacle needs.
            if (axis != 1 && result.clipped(1)) fixed[1] = true;
        }
    }

    private static int axis(Direction direction) {
        return switch (direction.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
    }

    /** VoxelShape#bounds of a shape's boxes. */
    private static SimpleCollisionBox bounds(List<SimpleCollisionBox> boxes) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (SimpleCollisionBox box : boxes) {
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** PistonMovingBlockEntity#moveByPositionAndProgress: {@code box} at the block entity's current offset. */
    private static SimpleCollisionBox moveByPositionAndProgress(
            BlockPos pos, SimpleCollisionBox box, ClientBlockEntityPass.Piston piston) {
        double currentPosition = piston.extendedProgress();
        Direction direction = piston.direction();
        return new SimpleCollisionBox(
                box.minX + (pos.getX() + currentPosition * direction.getStepX()),
                box.minY + (pos.getY() + currentPosition * direction.getStepY()),
                box.minZ + (pos.getZ() + currentPosition * direction.getStepZ()),
                box.maxX + (pos.getX() + currentPosition * direction.getStepX()),
                box.maxY + (pos.getY() + currentPosition * direction.getStepY()),
                box.maxZ + (pos.getZ() + currentPosition * direction.getStepZ()));
    }

    /** PistonMath#getMovementArea. */
    private static SimpleCollisionBox movementArea(SimpleCollisionBox box, Direction direction, double amount) {
        double step = amount * direction.getAxisDirection().getStep();
        double min = Math.min(step, 0.0D);
        double max = Math.max(step, 0.0D);
        return switch (direction) {
            case WEST -> new SimpleCollisionBox(box.minX + min, box.minY, box.minZ, box.minX + max, box.maxY, box.maxZ);
            case EAST -> new SimpleCollisionBox(box.maxX + min, box.minY, box.minZ, box.maxX + max, box.maxY, box.maxZ);
            case DOWN -> new SimpleCollisionBox(box.minX, box.minY + min, box.minZ, box.maxX, box.minY + max, box.maxZ);
            case UP -> new SimpleCollisionBox(box.minX, box.maxY + min, box.minZ, box.maxX, box.maxY + max, box.maxZ);
            case NORTH ->
                new SimpleCollisionBox(box.minX, box.minY, box.minZ + min, box.maxX, box.maxY, box.minZ + max);
            case SOUTH ->
                new SimpleCollisionBox(box.minX, box.minY, box.maxZ + min, box.maxX, box.maxY, box.maxZ + max);
        };
    }

    /** PistonMovingBlockEntity#getMovement: how far {@code box} must move along {@code movement} to leave the area. */
    private static double getMovement(SimpleCollisionBox area, Direction movement, SimpleCollisionBox box) {
        return switch (movement) {
            case EAST -> area.maxX - box.minX;
            case WEST -> box.maxX - area.minX;
            case DOWN -> box.maxY - area.minY;
            case SOUTH -> area.maxZ - box.minZ;
            case NORTH -> box.maxZ - area.minZ;
            default -> area.maxY - box.minY;
        };
    }

    // AABB#intersects
    private static boolean intersects(SimpleCollisionBox a, SimpleCollisionBox b) {
        return a.minX < b.maxX
                && a.maxX > b.minX
                && a.minY < b.maxY
                && a.maxY > b.minY
                && a.minZ < b.maxZ
                && a.maxZ > b.minZ;
    }

    // AABB#intersect
    private static SimpleCollisionBox intersection(SimpleCollisionBox a, SimpleCollisionBox b) {
        return new SimpleCollisionBox(
                Math.max(a.minX, b.minX),
                Math.max(a.minY, b.minY),
                Math.max(a.minZ, b.minZ),
                Math.min(a.maxX, b.maxX),
                Math.min(a.maxY, b.maxY),
                Math.min(a.maxZ, b.maxZ));
    }
}
