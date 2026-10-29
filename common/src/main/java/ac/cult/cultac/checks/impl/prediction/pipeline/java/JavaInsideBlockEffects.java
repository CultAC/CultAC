package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.entity.EntityTags;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.InsideBlockEffectScan;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;

/** Java Entity#checkInsideBlocks and the movement-affecting block callbacks. */
public final class JavaInsideBlockEffects {
    private JavaInsideBlockEffects() {}

    public record State(double fallDistance, Vec3 velocity, Vec3 stuckSpeed, boolean powderContact) {
        public State(double fallDistance, Vec3 velocity, Vec3 stuckSpeed) {
            this(fallDistance, velocity, stuckSpeed, false);
        }
    }

    /** handleRelativeFrictionAndCalculateMovement's powder branch, separate from onClimbable's ladder clamp. */
    public static boolean canClimbPowderSnow(CultPlayer player, SimulationContext context, Vec3 end) {
        PacketEntity actor = context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
        boolean walks = EntityTags.POWDER_SNOW_WALKABLE_MOBS.test(actor.type)
                || context.getVehicle() == null
                        && player.getInventory().getBoots().getItem() == ItemTypes.LEATHER_BOOTS;
        if (!walks) return false;
        if (context.getVersion().isOlderThan(ClientVersion.V_1_21_5))
            return BlockIds.is(
                    player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(end.x, end.y, end.z)),
                    BlockIds.POWDER_SNOW);
        // Entity#baseTick copies the preceding callbacks' flag, then clears the current contact flag.
        return context.getProfileCarry() instanceof JavaPredictionCarry carry
                && carry.actor() == actor
                && carry.powderContact();
    }

    /**
     * The velocity-independent bounds of one inside-block pass: the movements recorded earlier in the tick, then the
     * scanned move.
     */
    public record Scan(
            PacketEntity actor,
            Vec3 end,
            SimpleCollisionBox from,
            SimpleCollisionBox to,
            Vec3 originalMovement,
            List<Segment> recordedBefore) {}

    /** A recorded movement's boxes, with the scan's dimensions. */
    public record Segment(SimpleCollisionBox from, SimpleCollisionBox to, Vec3 originalMovement) {}

    /** One Entity#move's recorded movement: its positions and the delta collision started from. */
    public record RecordedMove(Vec3 from, Vec3 to, Vec3 delta) {}

    /** Returns {@code null} when no block callback can change any velocity resolved for this move. */
    public static Scan scan(CultPlayer player, PredictionResult result, Vec3 movement) {
        var context = result.getSimulationContext();
        return travelScan(
                player, context, context.getStart(), context.getStart().add(movement), attemptedMovement(result));
    }

    /**
     * The travel's LivingEntity#aiStep pass. From 1.21.5 Entity#applyEffectsFromBlocks replays every movement
     * Entity#move recorded this tick, so it starts with {@link SimulationContext#getMovesBeforeTravel}.
     */
    public static Scan travelScan(
            CultPlayer player, SimulationContext context, Vec3 start, Vec3 end, Vec3 originalMovement) {
        return scan(player, context, start, end, originalMovement, null, context.getMovesBeforeTravel());
    }

    /** The separate applyEffectsFromBlocks(from, to) pass of a piston's shove has no axis-order delta. */
    public static Scan scan(CultPlayer player, SimulationContext context, Vec3 start, Vec3 end, Vec3 originalMovement) {
        return scan(player, context, start, end, originalMovement, null);
    }

    /** A late block-entity callback uses the pose Player#updatePlayerPose left after travel. */
    public static Scan scan(
            CultPlayer player, SimulationContext context, Vec3 start, Vec3 end, Vec3 originalMovement, Pose pose) {
        return scan(player, context, start, end, originalMovement, pose, List.of());
    }

    private static Scan scan(
            CultPlayer player,
            SimulationContext context,
            Vec3 start,
            Vec3 end,
            Vec3 originalMovement,
            Pose pose,
            List<RecordedMove> recordedBefore) {
        PacketEntity actor =
                context.getVehicle() == null ? context.getEntities().getSelf() : context.getVehicle();
        if (actor.isDead || player.gamemode == GameMode.SPECTATOR) return null;
        SimpleCollisionBox to = pose == null || context.getVehicle() != null
                ? context.getToActualPose().offset(end.subtract(context.getEnd()))
                : boxAt(context, pose, end);
        SimpleCollisionBox from = to.copy().offset(start.subtract(end));
        List<Segment> segments = recordedBefore.isEmpty() ? List.of() : new ArrayList<>(recordedBefore.size());
        // Entity#checkInsideBlocks makes each movement's boxes at its own positions, with the scan's dimensions.
        Pose dimensions = context.getVehicle() == null ? pose : null;
        for (RecordedMove move : recordedBefore)
            segments.add(new Segment(
                    boxAt(context, dimensions, move.from()), boxAt(context, dimensions, move.to()), move.delta()));
        return scan(player, actor, end, from, to, originalMovement, segments);
    }

    private static SimpleCollisionBox boxAt(SimulationContext context, Pose pose, Vec3 position) {
        if (pose == null) return context.getActualPoseAt(position);
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                position.x, position.y, position.z, pose.width * context.getScale(), pose.height * context.getScale());
    }

    /** Entity#applyEffectsFromBlocks for an explicit actor, independent of the local player's abilities and pose. */
    public static Scan scan(
            CultPlayer player,
            PacketEntity actor,
            Vec3 end,
            SimpleCollisionBox from,
            SimpleCollisionBox to,
            Vec3 originalMovement) {
        return scan(player, actor, end, from, to, originalMovement, List.of());
    }

    private static Scan scan(
            CultPlayer player,
            PacketEntity actor,
            Vec3 end,
            SimpleCollisionBox from,
            SimpleCollisionBox to,
            Vec3 originalMovement,
            List<Segment> recordedBefore) {
        if (actor.isDead) return null;
        boolean effects = InsideBlockEffectScan.mayHaveEffects(player, from, to, false);
        for (int i = 0; !effects && i < recordedBefore.size(); i++) {
            Segment segment = recordedBefore.get(i);
            effects = InsideBlockEffectScan.mayHaveEffects(player, segment.from(), segment.to(), false);
        }
        if (!effects) return null;
        return new Scan(actor, end, from, to, originalMovement, recordedBefore);
    }

    public static State resolve(
            CultPlayer player, PredictionResult result, Scan scan, double distance, Vec3 velocity, boolean landed) {
        return resolve(player, result.getSimulationContext(), scan, distance, velocity, landed);
    }

    public static State resolve(
            CultPlayer player, SimulationContext context, Scan scan, double distance, Vec3 velocity, boolean landed) {
        return resolve(
                player,
                context,
                scan,
                distance,
                velocity,
                landed,
                true,
                false,
                context == null ? null : context.getMovementPatch());
    }

    /**
     * The actor tick's Entity#applyEffectsFromBlocks passes after the move of {@code result}: each pass runs
     * SlimeBlock#stepOn on the ground before the inside-block callbacks, and AbstractBoat#tick runs two passes.
     */
    public static State resolveAfterMove(
            CultPlayer player,
            PredictionResult result,
            Scan scan,
            JavaFallDistance.Moved moved,
            Vec3 velocity) {
        return resolveAfterMove(
                player,
                result.getSimulationContext(),
                scan,
                moved.fallDistance(),
                velocity,
                moved.landed(),
                stepsOnSlime(player, result, moved));
    }

    /** {@link #resolveAfterMove} for a move no prediction ran, given whether its pass steps on slime. */
    static State resolveAfterMove(
            CultPlayer player,
            SimulationContext context,
            Scan scan,
            double fallDistance,
            Vec3 velocity,
            boolean landed,
            boolean slime) {
        if (scan == null) {
            PacketEntity actor =
                    context.getVehicle() == null ? context.getEntities().getSelf() : context.getVehicle();
            for (int pass = 0; slime && pass < actorTickPasses(actor); pass++) velocity = slimeStepOn(velocity);
            return new State(fallDistance, velocity, null);
        }
        return resolve(player, context, scan, fallDistance, velocity, landed, true, slime, context.getMovementPatch());
    }

    /** AbstractBoat#tick invokes applyEffectsFromBlocks twice; every other actor tick once. */
    private static int actorTickPasses(PacketEntity actor) {
        return actor.isBoat() ? 2 : 1;
    }

    /**
     * PistonMovingBlockEntity#moveEntityByPiston's one block pass, outside AbstractBoat#tick's repeated pass;
     * {@code landed} is the Entity#onGround the piston's Entity#move left.
     */
    public static State resolveBlockEntityMove(
            CultPlayer player, SimulationContext context, Scan scan, double distance, Vec3 velocity, boolean landed) {
        return resolveBlockEntityMove(
                player, context, scan, distance, velocity, landed, context == null ? null : context.getMovementPatch());
    }

    public static State resolveBlockEntityMove(
            CultPlayer player,
            SimulationContext context,
            Scan scan,
            double distance,
            Vec3 velocity,
            boolean landed,
            JavaMovementPatch patch) {
        return resolve(player, context, scan, distance, velocity, landed, false, false, patch);
    }

    private static State resolve(
            CultPlayer player,
            SimulationContext context,
            Scan scan,
            double distance,
            Vec3 velocity,
            boolean landed,
            boolean actorTick,
            boolean slime,
            JavaMovementPatch patch) {
        if (scan == null) return new State(distance, velocity, null);
        Vec3 originalMovement = scan.originalMovement();
        PacketEntity actor = scan.actor();
        boolean shulker = actor.type == EntityTypeIds.SHULKER;
        State[] current = {new State(distance, shulker ? Vec3.ZERO : velocity, null)};
        Vec3 end = scan.end();
        SimpleCollisionBox to = scan.to();
        SimpleCollisionBox from = scan.from();
        boolean living = actor.isLivingEntity();
        ClientVersion version = context == null ? player.getClientVersion() : context.getVersion();
        boolean stuckAllowed = actor != player.compensatedEntities.getSelf() || !player.isFlying;
        // These virtual makeStuckInBlock overrides also bypass Entity's fall-distance reset.
        boolean acceptsStuck = stuckAllowed && !EntityTypeUtil.isType(actor.type, "wither");
        boolean acceptsWeb = actor.type != EntityTypeIds.SPIDER && !EntityTypeUtil.isType(actor.type, "cave_spider");
        boolean powderAllowed = !living
                || BlockIds.is(
                        player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(end.x, end.y, end.z)),
                        BlockIds.POWDER_SNOW);
        // AbstractBoat#tick invokes applyEffectsFromBlocks twice. The second pass
        // has no recorded axis-dependent movement and uses oldPosition -> position.
        int passes = actorTick ? actorTickPasses(actor) : 1;
        for (int pass = 0; pass < passes; pass++) {
            // Entity#applyEffectsFromBlocks runs Block#stepOn before this pass's inside-block callbacks.
            if (slime) {
                State state = current[0];
                current[0] = new State(
                        state.fallDistance(), slimeStepOn(state.velocity()), state.stuckSpeed(), state.powderContact());
            }
            Collisions.InsideBlockVisitor visitor = (segmentFrom, segmentTo, pos, precise) -> {
                int block = player.compensatedWorld.getBlockStateIdAt(pos);
                int effects = InsideBlockEffectScan.effects(block);
                if (effects == 0) return false;
                boolean powder = (effects & InsideBlockEffectScan.POWDER) != 0;
                boolean web = (effects & InsideBlockEffectScan.WEB) != 0;
                boolean berry = (effects & InsideBlockEffectScan.BERRY) != 0;
                boolean honey = (effects & InsideBlockEffectScan.HONEY) != 0;
                boolean bubble = (effects & InsideBlockEffectScan.BUBBLE) != 0;
                VoxelShape shape = powder
                        ? JavaCollisionState.of(player, actor, current[0].fallDistance())
                                .powderSnowShape(pos.getY(), end.y)
                        : Shapes.block();
                if (shape.isEmpty()) shape = Shapes.block(); // PowderSnowBlock#getEntityInsideCollisionShape
                boolean inside = shape == Shapes.block() || intersects(segmentFrom, segmentTo, shape, pos);
                if (!inside) return false;
                State state = current[0];
                Vec3 speed = null;
                if (acceptsStuck) {
                    if (powder && powderAllowed) speed = new Vec3(0.9F, 1.5, 0.9F);
                    if (web && acceptsWeb)
                        speed = Collisions.getCobwebStuckSpeed(
                                living,
                                actor.potionsMap != null && actor.potionsMap.containsKey(MovementEffect.WEAVING));
                    if (berry && actor.type != EntityTypeIds.FOX && actor.type != EntityTypeIds.BEE)
                        speed = Collisions.getSweetBerryBushStuckSpeed(living);
                }
                if (speed != null) state = new State(0, state.velocity(), speed, state.powderContact());
                // PowderSnowBlock sets FREEZE even when the feet block prevents its stuck multiplier.
                if (powder) state = new State(state.fallDistance(), state.velocity(), state.stuckSpeed(), true);
                if (honey && slides(end, pos, to.maxX - to.minX, landed, state.velocity().y)) {
                    state = new State(0, slideVelocity(state.velocity()), state.stuckSpeed(), state.powderContact());
                }
                if (bubble && (precise || !JavaMovementPatch.preciseBubbles(version, patch)) && stuckAllowed) {
                    int above = player.compensatedWorld.getBlockStateIdAt(pos.above());
                    boolean surface = version.isOlderThan(ClientVersion.V_1_21_5)
                            ? DataTables.defaults().registry().facts(above).has(StateFacts.AIR)
                            : player.compensatedWorld
                                            .geometry()
                                            .collision(above, pos)
                                            .isEmpty()
                                    && ClientFluidQueries.modelFluid(above).isEmpty();
                    // AbstractBoat overrides the surface callback without changing client velocity.
                    if (!surface || !actor.isBoat()) {
                        if (context != null || version.isOlderThan(ClientVersion.V_26_3)
                                || actor.type == EntityTypeIds.FISHING_BOBBER) {
                            state = bubble(state, surface, BlockProps.DRAG.booleanValue(block));
                        } else if (!surface) {
                            // 26.3 still resets fall distance when a non-authoritative remote actor enters a column.
                            state = new State(0, state.velocity(), state.stuckSpeed(), state.powderContact());
                        }
                    }
                }
                // Shulker's velocity setter is a no-op; the next block callback reads ZERO again.
                current[0] = shulker
                        ? new State(state.fallDistance(), Vec3.ZERO, state.stuckSpeed(), state.powderContact())
                        : state;
                return true;
            };
            // Entity#checkInsideBlocks shares Entity#visitedBlocks across the movements of one pass.
            LongSet visited = new LongOpenHashSet();
            if (pass == 0)
                for (Segment recorded : scan.recordedBefore())
                    visitMovement(
                            version, recorded.from(), recorded.to(), recorded.originalMovement(), visited, visitor);
            visitMovement(version, from, to, pass == 0 ? originalMovement : null, visited, visitor);
        }
        return current[0];
    }

    /** One movement of Entity#checkInsideBlocks; {@code originalMovement} is null for a movement without axis steps. */
    private static void visitMovement(
            ClientVersion version,
            SimpleCollisionBox from,
            SimpleCollisionBox to,
            Vec3 originalMovement,
            LongSet visited,
            Collisions.InsideBlockVisitor visitor) {
        if (version.isOlderThan(ClientVersion.V_1_21_9)) {
            Collisions.visitInsideBlocksBefore1Dot21Dot9(
                    from,
                    to,
                    version.isNewerThanOrEquals(ClientVersion.V_1_21_5) ? originalMovement : null,
                    version.isOlderThan(ClientVersion.V_1_21_4),
                    visited,
                    visitor);
        } else {
            Collisions.visitInsideBlocks26Dot2(from, to, originalMovement, visited, visitor);
        }
    }

    /**
     * SlimeBlock#stepOn, which Entity#applyEffectsFromBlocks runs before the inside-block callbacks when Entity#move
     * left the actor on the ground ({@link JavaFallDistance#collidesBelow}): below 0.1 vertical speed it scales the
     * carried horizontal velocity by 0.4 + |y| * 0.2, ahead of the next tick's LivingEntity#aiStep movement threshold
     * and ridden input.
     */
    public static Vec3 stepOn(CultPlayer player, PredictionResult result, JavaFallDistance.Moved moved, Vec3 velocity) {
        return stepsOnSlime(player, result, moved) ? slimeStepOn(velocity) : velocity;
    }

    /** Whether each Entity#applyEffectsFromBlocks pass after the move of {@code result} runs SlimeBlock#stepOn. */
    private static boolean stepsOnSlime(CultPlayer player, PredictionResult result, JavaFallDistance.Moved moved) {
        SimulationContext context = result.getSimulationContext();
        return carriesStepOn(player, context)
                && BlockIds.is(context.getWorldData().getOnBlock(), BlockIds.SLIME_BLOCK)
                // Entity#isSteppingCarefully: the client's shift key, or the vehicle's shared crouching flag.
                && !(context.getVehicle() == null
                        ? context.isSneaking()
                        : (context.getVehicle().actionSharedFlags & 2) != 0)
                && JavaFallDistance.collidesBelow(attemptedMovement(result).y, moved);
    }

    /** SlimeBlock#stepOn on the actor's deltaMovement. */
    private static Vec3 slimeStepOn(Vec3 velocity) {
        double absDeltaY = Math.abs(velocity.y);
        if (absDeltaY >= 0.1) return velocity;
        double scale = 0.4 + absDeltaY * 0.2;
        return velocity.multiply(scale, 1.0, scale);
    }

    /**
     * Whether the velocity carried out of {@code context}'s move holds {@link #stepOn}: Entity#applyEffectsFromBlocks
     * runs it for a Java client and for any vehicle it controls, boats included.
     */
    public static boolean carriesStepOn(CultPlayer player, SimulationContext context) {
        if (JavaRiddenTravel.clearsWithoutTravel(context)) return false;
        return !player.isBedrockMovement() && context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }

    public static boolean carriesStepOn(CultPlayer player, PredictionResult result) {
        return carriesStepOn(player, result.getSimulationContext())
                && (JavaMovementEngine.contextUsesExactEffects(result.getSimulationContext())
                        || VelocityCandidates.hasEngineTrace(result));
    }

    /**
     * Entity#move's recorded delta for Direction#axisStepOrder: the selected velocity plus the accepted
     * LivingEntity#moveRelative input, stuck-scaled, before collision.
     */
    static Vec3 attemptedMovement(PredictionResult result) {
        var trace = result.getValidMovements().getClosestTrace();
        Vec3 input = result.getLegacyLikePredictionVector().subtract(result.getAcceptedClosestToTarget());
        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
        return trace.preCollisionMovement().add(input.multiply(stuck));
    }

    static boolean intersects(SimpleCollisionBox from, SimpleCollisionBox to, VoxelShape shape, BlockPos pos) {
        SimpleCollisionBox box =
                SimpleCollisionBox.between(from.minX, from.minY, from.minZ, from.maxX, from.maxY, from.maxZ);
        return box.collidedAlongVector(
                new Vec3(to.minX - from.minX, to.minY - from.minY, to.minZ - from.minZ),
                NativeBlockCollisionHelper.toBoxes(shape.move(pos.getX(), pos.getY(), pos.getZ())));
    }

    static boolean slides(Vec3 end, BlockPos pos, double width, boolean landed, double velocityY) {
        return !landed
                && end.y <= pos.getY() + 0.9375 - 1.0E-7
                && oldDeltaY(velocityY) < -0.08
                && (Math.abs(pos.getX() + 0.5 - end.x) + 1.0E-7 > 0.4375 + width / 2
                        || Math.abs(pos.getZ() + 0.5 - end.z) + 1.0E-7 > 0.4375 + width / 2);
    }

    private static double oldDeltaY(double y) {
        return y / 0.98F + 0.08;
    }

    static Vec3 slideVelocity(Vec3 velocity) {
        double scale = oldDeltaY(velocity.y) < -0.13 ? -0.05 / oldDeltaY(velocity.y) : 1;
        return new Vec3(velocity.x * scale, (-0.05 - 0.08) * 0.98F, velocity.z * scale);
    }

    public static State bubble(State state, boolean surface, boolean down) {
        double y = down
                ? Math.max(surface ? -0.9 : -0.3, state.velocity().y - 0.03)
                : Math.min(surface ? 1.8 : 0.7, state.velocity().y + (surface ? 0.1 : 0.06));
        return new State(
                surface ? state.fallDistance() : 0,
                new Vec3(state.velocity().x, y, state.velocity().z),
                state.stuckSpeed(),
                state.powderContact());
    }
}
