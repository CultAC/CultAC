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
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorldPistons;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
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
     * the last Entity#move that set the collision flags, or null when none did. {@code recorded} is what the pass did
     * to Entity#movementThisTick.
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
            ReachInterpolationData oldInterpolation,
            JavaBlockEntityPath path,
            Recorded recorded) {
        public Result(
                JavaPassState state,
                boolean moved,
                boolean fixedX,
                boolean fixedY,
                boolean fixedZ,
                ReachInterpolationData interpolation,
                Double boatLastYd,
                ClientFluidHeights fluidHeights,
                LastMove lastMove,
                ReachInterpolationData oldInterpolation,
                JavaBlockEntityPath path) {
            this(
                    state,
                    moved,
                    fixedX,
                    fixedY,
                    fixedZ,
                    interpolation,
                    boatLastYd,
                    fluidHeights,
                    lastMove,
                    oldInterpolation,
                    path,
                    Recorded.NONE);
        }

        public Result(
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
            this(
                    state,
                    moved,
                    fixedX,
                    fixedY,
                    fixedZ,
                    interpolation,
                    boatLastYd,
                    fluidHeights,
                    lastMove,
                    oldInterpolation,
                    null);
        }

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
     * A pass's edits to the actor's Entity#movementThisTick, which from 1.21.5 the next Entity#applyEffectsFromBlocks
     * replays before its own movement: each SHULKER_BOX or SHULKER Entity#move that records its movement appends it,
     * and PistonMovingBlockEntity#moveEntityByPiston's removeLatestMovementRecording drops the last entry, which is
     * the piston's own when its Entity#move recorded one and otherwise an earlier one, {@code removedBefore} of them
     * from before the pass.
     */
    public record Recorded(int removedBefore, List<JavaInsideBlockEffects.RecordedMove> added) {
        public static final Recorded NONE = new Recorded(0, List.of());

        public Recorded {
            added = List.copyOf(added);
        }

        /** The actor's movementThisTick after the pass, from {@code before}. */
        public List<JavaInsideBlockEffects.RecordedMove> after(List<JavaInsideBlockEffects.RecordedMove> before) {
            if (removedBefore == 0 && added.isEmpty()) return before;
            int kept = Math.max(0, before.size() - removedBefore);
            if (kept == before.size() && added.isEmpty()) return before;
            var moves = new java.util.ArrayList<JavaInsideBlockEffects.RecordedMove>(kept + added.size());
            moves.addAll(before.subList(0, kept));
            moves.addAll(added);
            return List.copyOf(moves);
        }
    }

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
        return run(entityEnvironment(player, actor, pass, start.position(), false), pass, start);
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
        if (!env.authoritative()) {
            runMovers(env.player(), pass, null, List.of(replay));
            return replay.result(null);
        }
        boolean recordLocal = env.context().getVehicle() == null;
        List<Replay> remotes = recordLocal ? remoteColliders(env, pass, start) : List.of();
        List<JavaBlockEntityPath.Step> localMoves = runMovers(env.player(), pass, replay, remotes);
        return replay.result(
                !recordLocal || localMoves == null
                        ? null
                        : new JavaBlockEntityPath(pass, start.position(), List.copyOf(localMoves)));
    }

    /** A tracked remote root and the state it enters the pass with. */
    public record EntityStart(PacketEntity actor, JavaPassState start) {}

    /**
     * The committed replay of {@code pass} on tracked remote roots, after the selected travel: each mover moves them as
     * its Level#getEntities lists them, committing each Entity#move as it runs, so a later move collides with the roots
     * where the earlier moves left them. The local player stands where the selected travel's pass put it.
     */
    public static List<Result> runEntities(CultPlayer player, List<EntityStart> starts, ClientBlockEntityPass pass) {
        List<Replay> replays = new java.util.ArrayList<>(starts.size());
        for (EntityStart entry : starts) {
            PacketEntity actor = entry.actor();
            JavaPassState start = entry.start();
            if (actor.type == EntityTypeIds.SHULKER) start = start.withVelocity(Vec3.ZERO);
            replays.add(new Replay(entityEnvironment(player, actor, pass, start.position(), false), start));
        }
        runMovers(player, pass, null, replays);
        List<Result> results = new java.util.ArrayList<>(replays.size());
        for (Replay replay : replays) results.add(replay.result(null));
        return results;
    }

    private static JavaBlockEntityMove.Environment entityEnvironment(
            CultPlayer player, PacketEntity actor, ClientBlockEntityPass pass, Vec3 start, boolean detached) {
        return new JavaBlockEntityMove.Environment(
                player,
                null,
                null,
                actor.scale,
                (actor.actionSharedFlags & 2) != 0,
                start,
                pass.gameTime(),
                actor,
                actor.pistonMovementPatch(),
                detached);
    }

    /**
     * Level#tickBlockEntities over {@code pass}, after the leading Shulker#onPeekAmountChange moves: each mover in
     * ticker order moves the actors its Level#getEntities lists, in that list's order, each from where the moves before
     * it left it. {@code local} is the local player's replay, or null when the local player stands where the selected
     * travel's pass put it at each mover; {@code remotes} are the other actors. Once two actors move in one pass, each
     * reads the others at their in-pass boxes. Returns the local player's positions between movers, or null.
     */
    private static List<JavaBlockEntityPath.Step> runMovers(
            CultPlayer player, ClientBlockEntityPass pass, Replay local, List<Replay> remotes) {
        CompensatedWorld world = player.compensatedWorld;
        CompensatedWorldPistons pistons = world.pistons;
        CompensatedEntities entities = player.compensatedEntities;
        long tickedThrough = entities.passTickedThroughOrder();
        Vec3 previousLocal = entities.passLocalPlayerPosition();
        var previousBoxes = entities.passCollisionBoxes();
        List<ClientBlockEntityPass.Lid> lids = pass.lids();
        int actors = remotes.size() + (local == null ? 0 : 1);
        // Working space for the whole pass: each mover reuses the query order and the in-pass boxes.
        int[] order = actors > 1 ? new int[actors] : null;
        java.util.Map<PacketEntity, SimpleCollisionBox> boxes =
                actors > 1 ? new java.util.IdentityHashMap<>(remotes.size()) : null;
        List<JavaBlockEntityPath.Step> localMoves = null;
        int blockEntityMovers = 0, lid = 0, moverIndex = 0;
        try (var ignored = world.beginBlockEntityReplay(pass)) {
            for (ClientBlockEntityPass.Mover mover : pass.movers()) {
                // A move collides with the entities as the tick has left them by then: every entity has ticked
                // before the block entities, and a shulker's move follows its own tick and those before it.
                boolean peek = mover instanceof ClientBlockEntityPass.ShulkerPeek;
                entities.setPassTickedThroughOrder(
                        peek ? ((ClientBlockEntityPass.ShulkerPeek) mover).tickOrder() : Long.MAX_VALUE);
                if (!peek) {
                    // Every ShulkerBoxBlockEntity#updateAnimation ticking before this block entity has run, and a
                    // box's own update sets the lid its moveCollidedEntities collides with.
                    while (lid < lids.size() && lids.get(lid).beforeMover() <= blockEntityMovers)
                        world.advanceLidReplay(lids.get(lid++));
                    blockEntityMovers++;
                    if (boxes != null && entities.passCollisionBoxes() != boxes) {
                        // The block entities tick once every entity has; the actors start where the tick left them.
                        for (Replay remote : remotes) boxes.put(remote.env.actor(), remote.box());
                        entities.setPassCollisionBoxes(boxes);
                    }
                }
                Vec3 localBefore = local == null ? null : local.state.position();
                if (order == null) {
                    if (local != null) local.step(mover);
                    else stepEntity(entities, boxes, pass, mover, remotes.getFirst());
                } else {
                    queryOrder(entities, remotes, local, moverIndex, order);
                    for (int actor : order) {
                        if (actor == remotes.size()) {
                            entities.setPassLocalPlayerPosition(previousLocal);
                            local.step(mover);
                        } else if (local == null) {
                            stepEntity(entities, boxes, pass, mover, remotes.get(actor));
                        } else if (!peek) {
                            // Beside the local player's replay, a leading peek reaches none of its colliders.
                            Replay remote = remotes.get(actor);
                            entities.setPassLocalPlayerPosition(local.state.position());
                            remote.step(mover);
                            boxes.put(remote.env.actor(), remote.box());
                        }
                    }
                    entities.setPassLocalPlayerPosition(previousLocal);
                }
                if (mover instanceof ClientBlockEntityPass.Piston piston)
                    pistons.advanceReplay(piston.pos(), piston.storedProgress());
                if (local != null && !localBefore.equals(local.state.position())) {
                    if (localMoves == null) localMoves = new java.util.ArrayList<>();
                    localMoves.add(new JavaBlockEntityPath.Step(moverIndex, local.state.position()));
                }
                moverIndex++;
            }
        } finally {
            entities.setPassTickedThroughOrder(tickedThrough);
            entities.setPassLocalPlayerPosition(previousLocal);
            entities.setPassCollisionBoxes(previousBoxes);
        }
        return localMoves;
    }

    /** One actor's moves by {@code mover} while the local player stands where the selected travel's pass put it. */
    private static void stepEntity(
            CompensatedEntities entities,
            java.util.Map<PacketEntity, SimpleCollisionBox> boxes,
            ClientBlockEntityPass pass,
            ClientBlockEntityPass.Mover mover,
            Replay replay) {
        entities.setPassLocalPlayerPosition(localPlayerAtMover(replay.env, pass, mover, replay.state.position()));
        replay.step(mover);
        if (boxes != null && entities.passCollisionBoxes() == boxes) boxes.put(replay.env.actor(), replay.box());
    }

    /**
     * Level#getEntities's order for one mover, by where each actor starts it: EntitySectionStorage's section order,
     * then each section's insertion order. {@code order} receives the indexes of {@code remotes}, and
     * {@code remotes.size()} for {@code local} when it takes part. Insertion sort: a pass moves few actors.
     */
    private static void queryOrder(
            CompensatedEntities entities, List<Replay> remotes, Replay local, int moverIndex, int[] order) {
        for (int i = 0; i < order.length; i++) {
            int actor = i;
            int j = i - 1;
            while (j >= 0 && precedes(entities, remotes, local, moverIndex, actor, order[j])) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = actor;
        }
    }

    private static boolean precedes(
            CompensatedEntities entities, List<Replay> remotes, Replay local, int moverIndex, int a, int b) {
        int self = remotes.size();
        if (a == self || b == self) {
            Replay remote = remotes.get(a == self ? b : a);
            boolean localFirst = entities.localPlayerPrecedesInEntityQuery(
                    remote.env.actor(), local.state.position(), remote.state.position(), null, moverIndex);
            return a == self == localFirst;
        }
        Replay first = remotes.get(a), second = remotes.get(b);
        return entities.precedesInEntityQuery(
                first.env.actor(), first.state.position(), second.env.actor(), second.state.position());
    }

    /**
     * The tracked remote roots the local player can collide with that this pass's block entities may move, each
     * replayed beside the player without committing: JavaRemoteBlockEntityPass commits them after the selected travel.
     * From the first block entity on, their in-pass boxes stand in for their client-tick boxes while the player's moves
     * read entity collisions. A root a leading Shulker#onPeekAmountChange may move keeps its client-tick box.
     */
    private static List<Replay> remoteColliders(
            JavaBlockEntityMove.Environment env, ClientBlockEntityPass pass, JavaPassState start) {
        CultPlayer player = env.player();
        CompensatedEntities entities = player.compensatedEntities;
        PacketEntity self = entities.getSelf();
        PacketEntity selfRoot = entities.rootOfSelf();
        PacketEntity serverRoot = entities.serverVehicleRoot();
        var selection = new ac.cult.cultac.utils.nmsutil.EntityCollisionSelection(player, self, start.position());
        List<Replay> remotes = null;
        long tickedThrough = entities.passTickedThroughOrder();
        try {
            // Every entity has ticked before Level#tickBlockEntities.
            entities.setPassTickedThroughOrder(Long.MAX_VALUE);
            for (PacketEntity actor : entities.pushOrCollideCandidates()) {
                if (actor == self
                        || actor.getRiding() != null
                        || (actor == selfRoot || actor == serverRoot)
                                && entities.vehicles.isLocallyTickedVelocityRoot(actor)
                        || actor.newPacketLocation == null
                        || !actor.clientPhysicalPositionExact
                        || actor.oldPacketLocation != null
                        || actor.hasPistonPatchStates()
                        || !selection.mayCollide(actor)) continue;
                SimpleCollisionBox box = actor.getExactMovementCollisionBox();
                if (box == null || !pass.mayReach(box) || peekMayReach(pass, box)) continue;
                Vec3 at = actor.clientPhysicalPosition;
                SimpleCollisionBox held = GetBoundingBox.getPacketEntityBoundingBox(player, at.x, at.y, at.z, actor);
                Vec3 position = sameBox(held, box)
                        ? at
                        : new Vec3(
                                (box.maxX - box.minX) / 2 + box.minX, box.minY, (box.maxZ - box.minZ) / 2 + box.minZ);
                JavaPassState previous = actor.blockEntityState;
                var state = new JavaPassState(
                        position,
                        actor.type == EntityTypeIds.SHULKER ? Vec3.ZERO : actor.deltaMovement,
                        actor.onGround,
                        previous == null ? new MainSupportingBlockData(null, actor.onGround) : previous.support(),
                        previous == null ? null : previous.stuckSpeed(),
                        previous == null ? 0 : previous.fallDistance(),
                        previous == null ? ClientPistonDeltas.NONE : previous.pistonDeltas(),
                        false);
                if (remotes == null) remotes = new java.util.ArrayList<>(1);
                remotes.add(new Replay(entityEnvironment(player, actor, pass, position, true), state));
            }
        } finally {
            entities.setPassTickedThroughOrder(tickedThrough);
        }
        return remotes == null ? List.of() : remotes;
    }

    private static boolean peekMayReach(ClientBlockEntityPass pass, SimpleCollisionBox box) {
        for (ClientBlockEntityPass.Mover mover : pass.movers())
            if (mover instanceof ClientBlockEntityPass.ShulkerPeek peek
                    && peek.reach().isIntersected(box)) return true;
        return false;
    }

    private static boolean sameBox(SimpleCollisionBox a, SimpleCollisionBox b) {
        return a.minX == b.minX
                && a.minY == b.minY
                && a.minZ == b.minZ
                && a.maxX == b.maxX
                && a.maxY == b.maxY
                && a.maxZ == b.maxZ;
    }

    private static Vec3 localPlayerAtMover(
            JavaBlockEntityMove.Environment env,
            ClientBlockEntityPass pass,
            ClientBlockEntityPass.Mover mover,
            Vec3 actorPosition) {
        var player = env.player();
        var prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        var selected = prediction == null ? null : JavaMovementEngine.passAfterTravel(prediction);
        var path = selected == null ? null : selected.result().path();
        if (path == null
                || path.pass().gameTime() != pass.gameTime()
                || prediction.getSimulationContext().getItemUse().tick() != player.packetStateData.acceptedClientTick)
            return null;
        int index = path.pass().movers().indexOf(mover);
        if (index < 0) return null;
        Vec3 before = path.position(index, false);
        // Level#getEntities captures its section-ordered list before moving any of its entries.
        boolean localFirst = player.compensatedEntities.localPlayerPrecedesInEntityQuery(
                env.actor(), before, actorPosition, path, index);
        return path.position(index, localFirst);
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
        private List<JavaInsideBlockEffects.RecordedMove> recordedMoves;
        private int removedBefore;

        private Replay(JavaBlockEntityMove.Environment env, JavaPassState state) {
            this.env = env;
            this.state = state;
            PacketEntity actor = env.actor();
            // Entity#move(PISTON) adjusts a rider's running path through recordMovement only from 26.3. An older
            // rider's next interpolation step meets the shove from where it left the rider.
            boolean recorded = env.detached() && env.version().isNewerThanOrEquals(ClientVersion.V_26_3);
            ReachInterpolationData riderPath = recorded ? actor.riderInterpolationPath(false) : null;
            ReachInterpolationData oldRiderPath = recorded ? actor.riderInterpolationPath(true) : null;
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

        private Result result(JavaBlockEntityPath path) {
            return new Result(
                    state,
                    moved,
                    fixed[0],
                    fixed[1],
                    fixed[2],
                    interpolation,
                    boatLastYd,
                    fluidHeights,
                    lastMove,
                    oldInterpolation,
                    path,
                    recorded());
        }

        private Recorded recorded() {
            return recordedMoves == null && removedBefore == 0
                    ? Recorded.NONE
                    : new Recorded(removedBefore, recordedMoves == null ? List.of() : recordedMoves);
        }

        /** Entity#move's addMovementThisTick, when the move recorded its movement. */
        private void addMovementThisTick(Vec3 from, JavaBlockEntityMove.Moved result) {
            if (!result.recordedMovement()) return;
            if (recordedMoves == null) recordedMoves = new java.util.ArrayList<>();
            recordedMoves.add(
                    new JavaInsideBlockEffects.RecordedMove(from, result.state().position(), result.delta()));
        }

        /** Entity#removeLatestMovementRecording. */
        private void removeLatestMovementRecording() {
            if (recordedMoves != null && !recordedMoves.isEmpty()) recordedMoves.removeLast();
            else removedBefore++;
        }

        /** Whatever {@code mover} does to this actor. */
        private void step(ClientBlockEntityPass.Mover mover) {
            if (mover instanceof ClientBlockEntityPass.Piston piston) tick(piston);
            else if (mover instanceof ClientBlockEntityPass.ShulkerBox shulkerBox) shulkerBox(shulkerBox);
            else if (mover instanceof ClientBlockEntityPass.ShulkerPeek peek) shulkerPeek(peek);
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
                addMovementThisTick(previous, result);
                state = JavaBlockEntityMove.applyEffectsFromBlocks(env, result.state(), previous);
                removeLatestMovementRecording();
            } finally {
                pistons.setReplayNoClip(null);
            }
        }

        /** ShulkerBoxBlockEntity#moveCollidedEntities: every entity whose box meets the area moves. */
        private void shulkerBox(ClientBlockEntityPass.ShulkerBox shulkerBox) {
            if (!intersects(box(), shulkerBox.area())) return;
            Vec3 movement = shulkerBox.movement();
            Vec3 from = state.position();
            JavaBlockEntityMove.Moved result =
                    JavaBlockEntityMove.move(env, state, JavaBlockEntityMove.Mover.SHULKER_BOX, movement);
            record(result, movement.x != 0 ? 0 : movement.y != 0 ? 1 : 2, false);
            addMovementThisTick(from, result);
            state = result.state();
        }

        /**
         * Shulker#onPeekAmountChange: Level#getEntities finds each entity whose box meets the opened part, and
         * Entity#move moves it with MoverType.SHULKER.
         */
        private void shulkerPeek(ClientBlockEntityPass.ShulkerPeek peek) {
            if (!intersects(box(), peek.area())) return;
            Vec3 movement = peek.movement();
            Vec3 from = state.position();
            JavaBlockEntityMove.Moved result =
                    JavaBlockEntityMove.move(env, state, JavaBlockEntityMove.Mover.SHULKER, movement);
            record(result, movement.x != 0 ? 0 : movement.y != 0 ? 1 : 2, false);
            addMovementThisTick(from, result);
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
