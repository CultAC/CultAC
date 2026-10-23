package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.InsideBlockEffectScan;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;

/** Entity#checkInsideBlocks and the movement-affecting block callbacks, per client version. */
public final class JavaInsideBlockEffects {
    private JavaInsideBlockEffects() {}

    public record State(double fallDistance, Vec3 velocity, Vec3 stuckSpeed) {}

    /** The velocity-independent bounds of one move's inside-block pass. */
    public record Scan(
            PacketEntity actor, Vec3 end, SimpleCollisionBox from, SimpleCollisionBox to, Vec3 originalMovement) {}

    /** Returns {@code null} when no block callback can change any velocity resolved for this move. */
    public static Scan scan(CultPlayer player, PredictionResult result, Vec3 movement) {
        var context = result.getSimulationContext();
        SimpleCollisionBox poseAtEnd = context.getToActualPose();
        PacketEntity actor =
                context.getVehicle() == null ? context.getEntities().getSelf() : context.getVehicle();
        if (actor.isDead || player.gamemode == GameMode.SPECTATOR) return null;
        Vec3 end = context.getStart().add(movement);
        SimpleCollisionBox to = poseAtEnd.offset(end.subtract(context.getEnd()));
        SimpleCollisionBox from = to.copy().offset(movement.scale(-1));
        if (!InsideBlockEffectScan.mayHaveEffects(player, from, to, false)) return null;
        return new Scan(actor, end, from, to, attemptedMovement(result));
    }

    /**
     * {@code preciseBubble}: BubbleColumnBlock#entityInside acts only on a precise contact, as from 1.21.10. 1.21.9
     * acts on any contact, and shares its protocol with 1.21.10, so it resolves both ways.
     */
    public static State resolve(
            CultPlayer player,
            PredictionResult result,
            Scan scan,
            double distance,
            Vec3 velocity,
            boolean landed,
            boolean slimeStep,
            boolean preciseBubble) {
        if (scan == null) {
            // AbstractBoat#tick invokes applyEffectsFromBlocks twice, each pass running SlimeBlock#stepOn.
            int passes = result.getSimulationContext().getVehicle() != null
                            && result.getSimulationContext().getVehicle().isBoat()
                    ? 2
                    : 1;
            for (int pass = 0; slimeStep && pass < passes; pass++) velocity = slimeStepOn(velocity);
            return new State(distance, velocity, null);
        }
        var context = result.getSimulationContext();
        ClientVersion version = context.getVersion();
        // Before 1.21.5, PowderSnowBlock keeps the full inside shape and BubbleColumnBlock tests the block above
        // for air.
        boolean legacyBlocks = version.isOlderThan(ClientVersion.V_1_21_5);
        Vec3 originalMovement = scan.originalMovement();
        State[] current = {new State(distance, velocity, null)};
        PacketEntity actor = scan.actor();
        Vec3 end = scan.end();
        SimpleCollisionBox to = scan.to();
        SimpleCollisionBox from = scan.from();
        boolean living = actor.isLivingEntity();
        boolean stuckAllowed = context.getVehicle() != null || !player.isFlying;
        boolean powderAllowed = !living
                || BlockIds.is(
                        player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(end.x, end.y, end.z)),
                        BlockIds.POWDER_SNOW);
        // AbstractBoat#tick invokes applyEffectsFromBlocks twice. The second pass
        // has no recorded axis-dependent movement and uses oldPosition -> position.
        int passes = actor.isBoat() ? 2 : 1;
        Collisions.InsideBlockVisitor visitor = (segmentFrom, segmentTo, pos, precise) -> {
            int block = player.compensatedWorld.getBlockStateIdAt(pos);
            int effects = InsideBlockEffectScan.effects(block);
            if (effects == 0) return false;
            boolean powder = (effects & InsideBlockEffectScan.POWDER) != 0;
            boolean web = (effects & InsideBlockEffectScan.WEB) != 0;
            boolean berry = (effects & InsideBlockEffectScan.BERRY) != 0;
            boolean honey = (effects & InsideBlockEffectScan.HONEY) != 0;
            boolean bubble = (effects & InsideBlockEffectScan.BUBBLE) != 0;
            VoxelShape shape = powder && !legacyBlocks
                    ? JavaCollisionState.of(player, actor, current[0].fallDistance())
                            .powderSnowShape(pos.getY(), end.y)
                    : Shapes.block();
            if (shape.isEmpty()) shape = Shapes.block(); // PowderSnowBlock#getEntityInsideCollisionShape
            boolean inside = shape == Shapes.block() || intersects(segmentFrom, segmentTo, shape, pos);
            if (!inside) return false;
            State state = current[0];
            Vec3 speed = null;
            if (stuckAllowed) {
                if (powder && powderAllowed) speed = new Vec3(0.9F, 1.5, 0.9F);
                if (web) speed = Collisions.getCobwebStuckSpeed(player);
                if (berry
                        && actor.type != ac.cult.blocksim.entity.EntityTypeIds.FOX
                        && actor.type != ac.cult.blocksim.entity.EntityTypeIds.BEE)
                    speed = Collisions.getSweetBerryBushStuckSpeed(living);
            }
            if (speed != null) state = new State(0, state.velocity(), speed);
            if (honey && slides(end, pos, to.maxX - to.minX, landed, state.velocity().y)) {
                state = new State(0, slideVelocity(state.velocity()), state.stuckSpeed());
            }
            if (bubble && (precise || !preciseBubble) && (context.getVehicle() != null || !player.isFlying)) {
                int above = player.compensatedWorld.getBlockStateIdAt(pos.above());
                boolean surface = legacyBlocks
                        ? isAir(above)
                        : player.compensatedWorld
                                        .geometry()
                                        .collision(above, pos)
                                        .isEmpty()
                                && ac.cult.cultac.utils.nmsutil.ClientFluidQueries.modelFluid(above)
                                        .isEmpty();
                // AbstractBoat overrides the surface callback without changing client velocity.
                if (!surface || !actor.isBoat()) {
                    state = bubble(state, surface, BlockProps.DRAG.booleanValue(block));
                }
            }
            current[0] = state;
            return true;
        };
        for (int pass = 0; pass < passes; pass++) {
            // Entity#applyEffectsFromBlocks runs SlimeBlock#stepOn before the pass's inside-block callbacks.
            if (slimeStep) {
                current[0] = new State(
                        current[0].fallDistance(), slimeStepOn(current[0].velocity()), current[0].stuckSpeed());
            }
            if (version.isNewerThanOrEquals(ClientVersion.V_1_21_9)) {
                Collisions.visitInsideBlocks26Dot2(from, to, pass == 0 ? originalMovement : null, visitor);
            } else {
                // 1.21.5-1.21.8 Entity#move records its clipped delta for an axis replay; earlier clients and
                // AbstractBoat's second pass replay oldPosition -> position whole.
                Collisions.visitInsideBlocksLegacy(
                        from,
                        to,
                        pass == 0 && !legacyBlocks,
                        // BlockGetter#boxTraverseBlocks nudges its corner endpoints only on 1.21.2-1.21.3.
                        version.isOlderThan(ClientVersion.V_1_21_4),
                        pos -> isAir(player.compensatedWorld.getBlockStateIdAt(pos)),
                        visitor);
            }
        }
        return current[0];
    }

    private static boolean isAir(int state) {
        return DataTables.defaults().registry().facts(state).has(StateFacts.AIR);
    }

    /**
     * Whether SlimeBlock#stepOn runs at the start of each Entity#applyEffectsFromBlocks pass of this move: the actor
     * ends on slime with Entity#move's verticalCollisionBelow ({@link JavaFallDistance#collidesBelow}) and is not
     * stepping carefully.
     */
    public static boolean stepsOnSlime(CultPlayer player, PredictionResult result, JavaFallDistance.Moved moved) {
        SimulationContext context = result.getSimulationContext();
        return !player.isBedrockMovement()
                && BlockIds.is(context.getWorldData().getOnBlock(), BlockIds.SLIME_BLOCK)
                // Entity#isSteppingCarefully: the client's shift key, or the vehicle's shared crouching flag.
                && !(context.getVehicle() == null
                        ? context.isSneaking()
                        : (context.getVehicle().actionSharedFlags & 2) != 0)
                && JavaFallDistance.collidesBelow(attemptedMovement(result).y, moved);
    }

    /** SlimeBlock#stepOn: below 0.1 vertical speed it scales the horizontal velocity by 0.4 + |y| * 0.2. */
    static Vec3 slimeStepOn(Vec3 velocity) {
        double absDeltaY = Math.abs(velocity.y);
        if (absDeltaY >= 0.1) return velocity;
        double scale = 0.4 + absDeltaY * 0.2;
        return velocity.multiply(scale, 1.0, scale);
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

    static State bubble(State state, boolean surface, boolean down) {
        double y = down
                ? Math.max(surface ? -0.9 : -0.3, state.velocity().y - 0.03)
                : Math.min(surface ? 1.8 : 0.7, state.velocity().y + (surface ? 0.1 : 0.06));
        return new State(
                surface ? state.fallDistance() : 0,
                new Vec3(state.velocity().x, y, state.velocity().z),
                state.stuckSpeed());
    }
}
