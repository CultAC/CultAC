package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaHiddenTravelInput;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Ladder;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class VelocityCandidates {
    private static final Vec3 NO_STUCK_SPEED = new Vec3(1.0D, 1.0D, 1.0D);

    private VelocityCandidates() {}

    /** Entity#move's stored deltaMovement, and whether that move set Entity#horizontalCollision. */
    record Moved(Vec3 velocity, boolean horizontalCollision) {}

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

    /**
     * Adds the candidates to the empty sets: {@code collided} gets those whose Entity#move set horizontalCollision,
     * {@code validPlayerStartingVels} the rest, and it is returned. A velocity both kinds of move leave is in both.
     */
    static Set<Vec3> derive(
            PredictionResult result,
            PredictionResult lastPrediction,
            Set<Vec3> validPlayerStartingVels,
            Set<Vec3> collided) {
        // Every committed Java travel ran Entity#move, exempt or not. A teleport replaces deltaMovement in
        // ClientPacketListener#setValuesFromPositionPacket instead, which SimulationProcessor#handleTeleport applies.
        if (!hasEngineTrace(result)) throw new IllegalStateException("No simulated Entity#move to carry");
        for (Moved moved : engineCandidates(result, lastPrediction)) {
            (moved.horizontalCollision() ? collided : validPlayerStartingVels).add(moved.velocity());
        }
        return validPlayerStartingVels;
    }

    /**
     * Whether {@code result} holds a simulated Entity#move. An exemption only skips the checks on a travel the engine
     * still simulated, so it carries that travel's state like any other.
     */
    public static boolean hasEngineTrace(PredictionResult result) {
        return !result.isTeleport()
                && result.getProfilePredictionVector() == null
                && result.getInitialStartingVel() != null
                && result.getValidMovements() != null
                && result.getValidMovements().getClosestTrace() != null
                && result.getAcceptedClosestToTarget() != null;
    }

    /** Entity#move's stored deltaMovement, before block speed and travel drag, in the climbable branch order. */
    static List<Moved> engineCandidates(PredictionResult result, PredictionResult lastPrediction) {
        if (result.getSimulationContext().isNoPhysics()) {
            // Entity#move's early noPhysics branch changes position only; it never clips or clears deltaMovement.
            return List.of(new Moved(
                    preCollisionVelocity(result).add(acceptedInput(result)),
                    result.getSimulationContext().isNoPhysicsHorizontalCollision()));
        }
        if (consumedRequiredStuckSpeedThisMove(result, lastPrediction)) {
            // Entity#move clears deltaMovement when it consumes stuck speed, before checkFallDamage can
            // push
            // the entity by a newly entered fluid and before collision restitution. Those effects still
            // run.
            return afterMove(result, Vec3.ZERO, acceptedInput(result), true, null);
        }
        Vec3 carried = preCollisionVelocity(result);
        Vec3 input = acceptedInput(result);
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        // From 26.3 a block-entity pass may hide an axis of the travel's input; the reported keys give it.
        List<JavaHiddenTravelInput.Variant> hidden = JavaHiddenTravelInput.variants(player, result, input);
        if (hidden != null) {
            List<Moved> candidates = new ArrayList<>(hidden.size());
            for (JavaHiddenTravelInput.Variant variant : hidden)
                candidates.addAll(travelCandidates(result, lastPrediction, carried, variant.input(), variant));
            return candidates;
        }
        // A forward move already carries the deltaMovement Player#maybeBackOffFromEdge hid from the position.
        if (player != null && !hasUnknownStuckSpeed(lastPrediction) && forwardMove(result) == null) {
            // Player#maybeBackOffFromEdge hides deltaMovement, including the actual input and fluid
            // current.
            // Reconstruct that engine velocity from the client keys when one source-valid move proves it.
            // The accepted ENGINE movement selects the branch; the packet displacement is never carried.
            Vec3 edge = SneakEdgeBackoff.deltaMovement(result, result.getLegacyLikePredictionVector());
            if (edge != null && edge.equals(applyPostMoveLivingFluidCurrent(player, result, edge)))
                return List.of(new Moved(edge, collidedHorizontally(result)));
        }
        return travelCandidates(result, lastPrediction, carried, input, null);
    }

    /** The climbable branches of the travel that added {@code input} to {@code carried}. */
    private static List<Moved> travelCandidates(
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 carried,
            Vec3 input,
            JavaHiddenTravelInput.Variant hidden) {
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        DesyncStatus climbing = player == null ? DesyncStatus.FALSE : Ladder.clampsDeltaMovement(context);
        Boolean replayedClimbing = result.getValidMovements().getReplayedClimbing();
        if (replayedClimbing != null) climbing = DesyncStatus.fromBoolean(replayedClimbing);
        List<Moved> candidates = new ArrayList<>(3);
        if (climbing != DesyncStatus.TRUE)
            candidates.addAll(afterMove(result, carried.add(input), input, false, hidden));
        if (climbing != DesyncStatus.FALSE) {
            // LivingEntity#handleRelativeFrictionAndCalculateMovement clamps the input-inclusive
            // deltaMovement.
            Vec3 climbingDelta = replayedClimbing != null
                    ? carried.add(input)
                    : Ladder.handleOnClimbable(player, context, carried).add(input);
            candidates.addAll(
                    afterMove(result, Ladder.handleOnClimbable(player, context, climbingDelta), input, false, hidden));
        }
        if (hasUnknownStuckSpeed(lastPrediction)) candidates.addAll(afterMove(result, Vec3.ZERO, input, true, hidden));
        return candidates;
    }

    /**
     * Entity#move: checkFallDamage's fluid current, collided-axis response, then (in the caller) block speed.
     * Entity#move zeroes only an axis its collision changed. An axis whose clip is uncertain carries both the
     * collision response and the deltaMovement that reaches the move unclipped. The variants depend only on the trace,
     * so the first is the trace's own outcome. A move that consumed stuck speed ({@code cleared}) zeroed deltaMovement
     * before colliding, so it carries no alternative. Each variant sets horizontalCollision from its own X and Z
     * clips, as Entity#move does. On an axis {@code hidden} fixes, its own travel's clip decides.
     */
    private static List<Moved> afterMove(
            PredictionResult result, Vec3 velocity, Vec3 input, boolean cleared, JavaHiddenTravelInput.Variant hidden) {
        CultPlayer player = result.getPlayer();
        Vec3 beforeCurrent = velocity;
        if (player != null) velocity = applyPostMoveLivingFluidCurrent(player, result, velocity);
        Vec3 current = velocity.subtract(beforeCurrent);
        JavaMoveCollision forward = forwardMove(result);
        if (forward != null) return List.of(afterForwardMove(result, forward, velocity));
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        Vec3 attempted = trace.preCollisionMovement();
        Vec3 moved = collisionMovement(result);
        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
        Vec3 scale = stuck == null ? NO_STUCK_SPEED : stuck;
        attempted = attempted.add(input.multiply(scale));
        if (result.getValidMovements().getReplayedClimbing() != null)
            attempted = result.getValidMovements().getReplayedTravelMovement();
        moved = moved.add(result.getLegacyLikePredictionVector()
                .subtract(result.getAcceptedClosestToTarget())
                .multiply(scale));
        // Mth#equal uses 1e-5 for X/Z; verticalCollision uses exact inequality.
        boolean hiddenX = hidden != null && hidden.fixedX();
        boolean hiddenZ = hidden != null && hidden.fixedZ();
        boolean x = hiddenX ? hidden.collidedX() : !CultMath.equal(attempted.x, moved.x);
        boolean z = hiddenZ ? hidden.collidedZ() : !CultMath.equal(attempted.z, moved.z);
        boolean y = collidedVertically(result);
        SimulationContext context = result.getSimulationContext();
        boolean modern = player != null && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        double restitution = modern && !(context.getVehicle() == null && context.isSneaking())
                ? player.compensatedEntities.getEntityInControl().bounciness
                : 0.0D;
        double clippedX = restitution == 0.0D ? 0.0D : -velocity.x * restitution;
        double clippedY = applyPostVerticalCollisionBlockVelocity(result, velocity.y, moved.y);
        double clippedZ = restitution == 0.0D ? 0.0D : -velocity.z * restitution;
        Vec3 collided = new Vec3(x ? clippedX : velocity.x, y ? clippedY : velocity.y, z ? clippedZ : velocity.z);
        if (cleared) return List.of(new Moved(collided, x || z));
        // A clipped axis may also have been free; a free one may also have been clipped.
        boolean otherX =
                !hiddenX && (x ? scale.x != 0.0D && !certainlyClipped(result, true) : mayHaveClipped(result, true));
        boolean otherY = y ? scale.y != 0.0D && !certainlyClippedVertically(result) : mayHaveClippedVertically(result);
        boolean otherZ =
                !hiddenZ && (z ? scale.z != 0.0D && !certainlyClipped(result, false) : mayHaveClipped(result, false));
        if (!otherX && !otherY && !otherZ) return List.of(new Moved(collided, x || z));
        // Unclipped, Entity#move leaves deltaMovement as the move over the stuck scale; checkFallDamage's current
        // follows it.
        double alternateX = x ? moved.x / scale.x + current.x : clippedX;
        double alternateY = y ? moved.y / scale.y + current.y : clippedY;
        double alternateZ = z ? moved.z / scale.z + current.z : clippedZ;
        List<Moved> variants = new ArrayList<>(8);
        for (int other = 0; other < 8; other++) {
            boolean swapX = (other & 1) != 0, swapY = (other & 2) != 0, swapZ = (other & 4) != 0;
            if (swapX && !otherX || swapY && !otherY || swapZ && !otherZ) continue;
            Vec3 variant = new Vec3(
                    swapX ? alternateX : collided.x, swapY ? alternateY : collided.y, swapZ ? alternateZ : collided.z);
            variants.add(new Moved(variant, x != swapX || z != swapZ));
        }
        return variants;
    }

    /** The exact Entity#move collision of a candidate the engine moved forward, or null. */
    static JavaMoveCollision forwardMove(PredictionResult result) {
        return result.getCollideAxisData() instanceof JavaMoveCollision move ? move : null;
    }

    /**
     * Entity#move's deltaMovement after the forward {@code move} (1.21.11 Entity.java:735-772): a collided horizontal
     * axis is zeroed, or from 26.2 restituted with the bounciness attribute, and a vertical collision applies the
     * landing block's response at Entity#getOnPosLegacy. Exactly one velocity; the move's flags are not guessed.
     */
    private static Moved afterForwardMove(PredictionResult result, JavaMoveCollision move, Vec3 velocity) {
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        boolean modern = player != null && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        double restitution = modern && !(context.getVehicle() == null && context.isSneaking())
                ? player.compensatedEntities.getEntityInControl().bounciness
                : 0.0D;
        double x = move.collidedX() ? (restitution == 0.0D ? 0.0D : -velocity.x * restitution) : velocity.x;
        double y = move.verticalCollision()
                ? applyPostVerticalCollisionBlockVelocity(result, velocity.y, move.collided().y)
                : velocity.y;
        double z = move.collidedZ() ? (restitution == 0.0D ? 0.0D : -velocity.z * restitution) : velocity.z;
        return new Moved(new Vec3(x, y, z), move.horizontalCollision());
    }

    /**
     * Whether Entity#collide certainly clipped the closest movement's Y: it stepped, or stopped at a known floor's or
     * ceiling's limit. A nearby unknown collider can stop it anywhere.
     */
    private static boolean certainlyClippedVertically(PredictionResult result) {
        if (result.getValidMovements().getClosestTrace().position().hasReason("step")) return true;
        var collisions = result.getCollideAxisData();
        return collisions != null && !hasUnknown(collisions) && endsAtVerticalLimit(result, collisions);
    }

    /**
     * Whether Entity#collide may have clipped a Y the closest movement completed unclipped: a nearby unknown collider
     * can stop it anywhere, and a move that reaches or passes a known floor's or ceiling's limit, which a shove can
     * make up, may have been one it stopped.
     */
    private static boolean mayHaveClippedVertically(PredictionResult result) {
        var collisions = result.getCollideAxisData();
        if (collisions == null) return false;
        var limit = requestedMovementY(result) < 0 ? collisions.getYNeg() : collisions.getYPos();
        if (limit == null || !limit.isLikelyCollide()) return false;
        return hasUnknown(collisions) || reachesVerticalLimit(result, collisions);
    }

    private static boolean reachesVerticalLimit(
            PredictionResult result, ac.cult.cultac.utils.data.CollideAxisData collisions) {
        boolean down = requestedMovementY(result) < 0;
        var limit = down ? collisions.getYNeg() : collisions.getYPos();
        if (limit == null || !limit.isLikelyCollide()) return false;
        Vec3 stuck = result.getValidMovements()
                .getClosestTrace()
                .position()
                .collisionStuckSpeedMultiplier(result.getSimulationContext());
        double bound = limit.getResult() * (stuck == null ? 1 : stuck.y);
        double moved = collisionMovement(result).y;
        return down ? moved <= bound : moved >= bound;
    }

    private static boolean endsAtVerticalLimit(
            PredictionResult result, ac.cult.cultac.utils.data.CollideAxisData collisions) {
        var limit = requestedMovementY(result) < 0 ? collisions.getYNeg() : collisions.getYPos();
        if (limit == null || !limit.isLikelyCollide()) return false;
        Vec3 stuck = result.getValidMovements()
                .getClosestTrace()
                .position()
                .collisionStuckSpeedMultiplier(result.getSimulationContext());
        return collisionMovement(result).y == limit.getResult() * (stuck == null ? 1 : stuck.y);
    }

    /**
     * Whether Entity#collide certainly clipped the closest movement on X or Z: the axis stops at a known collider's
     * limit. A nearby unknown collider can stop any axis anywhere, and a stop short of a known limit is reachable
     * without one, so neither proves the clip.
     */
    private static boolean certainlyClipped(PredictionResult result, boolean xAxis) {
        var collisions = result.getCollideAxisData();
        if (collisions == null || hasUnknown(collisions)) return false;
        var axis = xAxis ? collisions.getX() : collisions.getZ();
        if (axis == null || !axis.isLikelyCollide()) return false;
        return CultMath.equal(clippedAxis(result, xAxis), axis.getResult());
    }

    /**
     * Whether Entity#collide may have clipped an axis the closest movement completed unclipped: a nearby unknown
     * collider can stop it anywhere, and a move that ends at a known collider's limit may have been a longer one it
     * stopped.
     */
    private static boolean mayHaveClipped(PredictionResult result, boolean xAxis) {
        var collisions = result.getCollideAxisData();
        if (collisions == null) return false;
        var axis = xAxis ? collisions.getX() : collisions.getZ();
        if (axis == null || !axis.isLikelyCollide()) return false;
        return hasUnknown(collisions) || CultMath.equal(clippedAxis(result, xAxis), axis.getResult());
    }

    private static boolean hasUnknown(ac.cult.cultac.utils.data.CollideAxisData collisions) {
        return collisions.getUnknown() != null && !collisions.getUnknown().isEmpty();
    }

    /**
     * The move on X or Z, with the accepted input and without external collision-base shoves, as the collision probes
     * measure it. LivingEntity#moveRelative adds the input before Entity#collide, so the clip acts on this whole move,
     * not on the closest movement before the input.
     */
    private static double clippedAxis(PredictionResult result, boolean xAxis) {
        Vec3 clipped = result.getLegacyLikePredictionVector()
                .subtract(result.getValidMovements().getClosestTrace().collisionBaseOffset());
        return xAxis ? clipped.x : clipped.z;
    }

    /** The trace's travel move, in client movement space, without external collision-base shoves. */
    private static Vec3 collisionMovement(PredictionResult result) {
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
        Vec3 move = trace.position().hasReason("Entity#move setPos guard")
                ? trace.position().vectorBeforeFirstReason("Entity#move setPos guard")
                : trace.position().subtract(trace.collisionBaseOffset());
        return stuck == null ? move : move.multiply(stuck);
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
                || ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRiddenTravel.clearsWithoutTravel(context)
                || vehicle != null && (EntityTypeUtil.isBoat(vehicle.type) || vehicle instanceof PacketEntityHappyGhast)
                || player.getClientVersion() == ClientVersion.V_1_21_4
                || context.getWorldData().isInWater()) {
            return postMoveDelta;
        }

        if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            PacketEntity actor = vehicle == null ? player.compensatedEntities.getSelf() : vehicle;
            return JavaLivingMoveFluids.apply(
                            player,
                            context.getVersion(),
                            actor,
                            postMoveFluidBox(result),
                            postMoveDelta,
                            context.getWorldData().isInWater(),
                            context.getWorldData().isInLava())
                    .velocity();
        }

        // Entity#move updates the position, then LivingEntity#checkFallDamage
        // pushes the entity by the water current at its post-move box when it
        // was not in water before the move. The current does not affect the
        // packet-visible move which just completed; it does affect deltaMovement
        // before block speed and travel drag derive the next tick.
        // The current's 0.003 minimum-push test reads deltaMovement before Entity#move zeroes collided axes.
        Vec3 moving = postMoveDelta;
        Vec3 delta = postMoveDelta;
        if (vehicle == null || vehicle.isLivingEntity()) {
            Vec3 water = WaterCurrent.calculateWaterCurrent(player, context, context.getEnd(), moving);
            delta = addFinite(delta, water);
            moving = addFinite(moving, water);
        }
        // Before 26.1 checkFallDamage runs updateInWaterStateAndDoWaterCurrentPushing, which pushes water only.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                && context.getWorldData().isInLava()) {
            delta = addFinite(delta, WaterCurrent.calculateLavaCurrent(player, context, context.getEnd(), moving));
        }
        return delta;
    }

    /**
     * The box LivingEntity#checkFallDamage refreshes the fluid interaction at, inside the travel's own Entity#move:
     * EntityDimensions#makeBoundingBox where that move left the actor, before any later block-entity pass.
     */
    static SimpleCollisionBox postMoveFluidBox(PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        var moved = result.getJavaCommittedMove();
        return context.getActualPoseAt(
                moved == null ? context.getEnd() : context.getStart().add(moved.movement()));
    }

    /** The accepted LivingEntity#moveRelative input: the circular allowance the closest movement used. */
    static Vec3 acceptedInput(PredictionResult result) {
        Vec3 replayed = result.getValidMovements().getReplayedTravelInput();
        if (replayed != null) return replayed;
        // A candidate holding its exact input already moved by it: there is no allowance left to accept.
        if (result.getInitialStartingVel() != null
                && result.getInitialStartingVel().hasTravelInput()) return Vec3.ZERO;
        Vec3 closest = result.getAcceptedClosestToTarget();
        Vec3 predicted = result.getLegacyLikePredictionVector();
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
        Vec3 before = trace.preCollisionMovement();
        if (stuck != null) before = before.multiply(1 / stuck.x, 1 / stuck.y, 1 / stuck.z);
        before = before.add(trace.collisionBaseOffset());
        var collisions = result.getCollideAxisData();
        // CollisionModifier clamps towards the target inside the probe envelope. Away from a collider's
        // actual
        // limit, a horizontal adjustment can be moveRelative input rather than Entity#collide clipping
        // velocity.
        double x = !CultMath.equal(before.x, closest.x)
                        && !CultMath.equal(
                                closest.x - trace.collisionBaseOffset().x,
                                collisions.getX().getResult())
                ? before.x
                : closest.x;
        double z = !CultMath.equal(before.z, closest.z)
                        && !CultMath.equal(
                                closest.z - trace.collisionBaseOffset().z,
                                collisions.getZ().getResult())
                ? before.z
                : closest.z;
        if (x == closest.x && z == closest.z) {
            // This allowance is the X/Z moveRelative circle. Vertical jump/swim changes are already
            // in the trace; a difference in reported Y never becomes additional movement input.
            Vec3 input = predicted.subtract(closest);
            return new Vec3(input.x, 0, input.z);
        }
        PredVector inputBase = new PredVector(new Vec3(x, closest.y, z));
        return UncertaintyHelper.handleCircular(inputBase, predicted, result.getHorizontalInputRadius())
                .subtract(inputBase);
    }

    private static Vec3 addFinite(Vec3 delta, Vec3 current) {
        return current != null && Double.isFinite(current.x) && Double.isFinite(current.y) && Double.isFinite(current.z)
                ? delta.add(current)
                : delta;
    }

    /**
     * The deltaMovement Entity#move received. LivingEntity#travelFallFlying and AbstractBoat#tick replace it before
     * the move in every version (1.21.11 LivingEntity.java:2357, AbstractBoat.java:233-239), so a gliding or boat
     * tick never carries the velocity it started from.
     */
    public static Vec3 preCollisionVelocity(PredictionResult result) {
        return result.getValidMovements().getClosestTrace().preCollisionVelocity();
    }

    /** Whether this source trace, including its accepted input, actually hit a horizontal collider. */
    private static boolean collidedHorizontally(PredictionResult result) {
        if (!hasEngineTrace(result)) return false;
        JavaMoveCollision forward = forwardMove(result);
        if (forward != null) return forward.horizontalCollision();
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        Vec3 scale = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
        if (scale == null) scale = NO_STUCK_SPEED;
        Vec3 attempted = trace.preCollisionMovement().add(acceptedInput(result).multiply(scale));
        Vec3 moved = collisionMovement(result)
                .add(result.getLegacyLikePredictionVector()
                        .subtract(result.getAcceptedClosestToTarget())
                        .multiply(scale));
        return !CultMath.equal(attempted.x, moved.x) || !CultMath.equal(attempted.z, moved.z);
    }

    public static boolean carriesClimbBoost(PredictionResult result) {
        return result != null
                && result.getPlayer() != null
                && !result.getPlayer().isBedrockMovement()
                && hasEngineTrace(result)
                && result.getSimulationContext().getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
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
        float restitution = BlockIds.is(onBlock, BlockIds.SLIME_BLOCK) ? 1.0F : isBouncyBlock(onBlock) ? 0.75F : 0.0F;
        return living ? restitution : restitution * 0.8F;
    }

    private static boolean livingMover(SimulationContext context) {
        PacketEntity vehicle = context.getVehicle();
        return vehicle == null || vehicle.isLivingEntity();
    }

    private static double restitutedBounceY(
            PacketEntity actor, double deltaY, double clippedY, double gravity, double restitution) {
        // AbstractBoat overrides Entity#getAirDrag; LivingEntity applies its own attribute modifier.
        double airDrag = actor.isBoat() ? 1.0F : actor.isLivingEntity()
                ? Friction.computeModifiedFriction(0.98F, (float) actor.airDragModifier) : 0.98F;
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

    private static double effectiveGravity(PacketEntity actor, double deltaY, ClientVersion version) {
        if (!actor.hasGravity) return 0.0D;
        // AbstractBoat#getDefaultGravity is independent of LivingEntity's gravity attribute and effects.
        if (actor.isBoat()) return 0.04D;
        if (actor.type == EntityTypeIds.FISHING_BOBBER)
            return version.isNewerThanOrEquals(ClientVersion.V_26_3) ? (double) 0.03F : 0.03D;
        double gravity = actor.gravity;
        if (actor.isLivingEntity() && deltaY <= 0 && actor.potionsMap != null
                && actor.potionsMap.containsKey(MovementEffect.SLOW_FALLING))
            gravity = Math.min(gravity, 0.01D);
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

    private static boolean consumedRequiredStuckSpeedThisMove(
            PredictionResult result, PredictionResult lastPrediction) {
        SimulationContext context = result.getSimulationContext();
        if (context == null) {
            return false;
        }

        Vec3 lastStuckSpeed = context.getLastStuckSpeed();
        if (lastStuckSpeed != null && lastStuckSpeed.distanceToSqr(NO_STUCK_SPEED) > 1.0E-14) return true;
        // A block can set an all-ones multiplier at full movement efficiency. Entity#move still
        // consumes that
        // nonzero multiplier and clears deltaMovement; null in the exact carry means no multiplier was
        // set.
        if (hasEngineTrace(result)
                && context.getProfileCarry() instanceof JavaPredictionCarry carry
                && !carry.states().isEmpty()) {
            Vec3 start = result.getInitialStartingVel().carriedVelocity();
            boolean set = false;
            for (var state : carry.states()) {
                if (!state.velocity().equals(start)) continue;
                if (state.stuckSpeed() == null || state.stuckSpeed().lengthSqr() <= 1.0E-7) return false;
                set = true;
            }
            return set;
        }
        if (lastPrediction != null
                && lastPrediction.getSimulationContext() != null
                && lastPrediction.getSimulationContext().getWorldData() != null) {
            Vec3 required = lastPrediction
                    .getSimulationContext()
                    .getWorldData()
                    .getStuckSpeed()
                    .getStuckSpeedMultiplier();
            return required != null && required.equals(lastStuckSpeed) && required.lengthSqr() > 1.0E-7;
        }
        return false;
    }

    /** Whether the selected travel actually clipped Y; probes alone do not prove a landing. */
    public static boolean collidedVertically(PredictionResult result) {
        if (!hasEngineTrace(result)) return likelyVerticalCollision(result);
        JavaMoveCollision forward = forwardMove(result);
        if (forward != null) return forward.verticalCollision();
        double requested = requestedMovementY(result), moved = collisionMovement(result).y;
        if (requested == moved) return false;
        var limit = requested < 0
                ? result.getCollideAxisData().getYNeg()
                : result.getCollideAxisData().getYPos();
        Vec3 stuck = result.getValidMovements()
                .getClosestTrace()
                .position()
                .collisionStuckSpeedMultiplier(result.getSimulationContext());
        if (limit != null && limit.isLikelyCollide() && moved == limit.getResult() * (stuck == null ? 1 : stuck.y))
            return true;
        // Entity#setPos adds the simulated move to an absolute double position. The collision-ignored
        // selector
        // can follow the packet's rounded subtraction by one position ulp without reaching the probed
        // collider.
        Vec3 end = result.getSimulationContext().getEnd();
        return end == null || Math.abs(requested - moved) > Math.ulp(end.y);
    }

    /** Entity#move's attempted Y before collision, including a step's original downward travel. */
    public static double requestedMovementY(PredictionResult result) {
        JavaMoveCollision forward = forwardMove(result);
        if (forward != null) return forward.attempted().y;
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        Vec3 attempted = trace.preCollisionMovement();
        // StepTransform changes only the move's Y, so the pre-step velocity supplies the attempted Y.
        double requestedY = attempted.y;
        if (trace.position().hasReason("step")) {
            Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
            requestedY = trace.preCollisionVelocity().y * (stuck == null ? 1.0D : stuck.y);
        }
        return requestedY;
    }

    private static boolean likelyVerticalCollision(PredictionResult result) {
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
                context.getVehicle() == null ? context.getEntities().getSelf() : context.getVehicle(),
                context.getWorldData().getOnBlock(),
                context.getVehicle() == null && context.isSneaking(),
                livingMover(context),
                requestedMovementY(result) < 0.0D,
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
        return afterVerticalCollision(player, player == null ? null : player.compensatedEntities.getEntityInControl(),
                onBlock, suppressingBounce, living, below, deltaY, clippedY);
    }

    /** The actor that moved owns gravity, drag and restitution, including a passenger's own travel. */
    public static double afterVerticalCollision(
            CultPlayer player,
            PacketEntity actor,
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

            double gravity = effectiveGravity(actor, deltaY, player.getClientVersion());
            double bounciness = actor.isLivingEntity() ? actor.bounciness : 0.0D;
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
            return restitutedBounceY(actor, deltaY, clippedY, gravity, restitution);
        }

        if (suppressingBounce) {
            return 0.0D;
        }

        if (deltaY < 0.0D) {
            // 1.21.11 and 26.1 SlimeBlock#bounceUp and BedBlock#bounceUp scale by 0.8 unless the entity
            // is living.
            double factor = living ? 1.0D : 0.8D;
            if (BlockIds.is(onBlock, BlockIds.SLIME_BLOCK)) {
                return -deltaY * factor;
            }

            if (ClientBlockProperties.isBed(onBlock)) {
                return -deltaY * 0.66F * factor;
            }
        }

        if (BlockIds.is(onBlock, BlockIds.SLIME_BLOCK) || ClientBlockProperties.isBed(onBlock)) {
            return deltaY;
        }

        return 0.0D;
    }
}
