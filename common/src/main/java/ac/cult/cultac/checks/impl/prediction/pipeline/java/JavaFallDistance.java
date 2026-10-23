package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;

/** Mirrors Java Entity#baseTick/#move and LivingEntity#aiStep, in client tick order. */
public final class JavaFallDistance {
    private JavaFallDistance() {}

    private static PacketEntity actor(CultPlayer player, SimulationContext context) {
        return context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
    }

    public static JavaCollisionState beforeMove(CultPlayer player, SimulationContext context, PredVector velocity) {
        PacketEntity actor = actor(player, context);
        double distance = context.getProfileCarry() instanceof JavaPredictionCarry carry && carry.actor() == actor
                ? carry.fallDistance()
                : 0.0;
        var world = context.getWorldData();
        boolean water = world.getInWater().determinePessimistically();
        boolean lava = world.getInLava().determinePessimistically();
        // handleOnClimbable runs in air travel, not water/lava travel. Use the START block.
        Vec3 start = context.getStart();
        boolean climb = actor.isLivingEntity()
                && !water
                && !lava
                && !context.usesFallFlyingMovement()
                && Collisions.onClimbable(player, start.x, start.y, start.z);
        boolean reset = water
                || climb
                || context.getVehicle() == null && player.isFlying
                || actor.isLivingEntity()
                        && (player.compensatedEntities.getSlowFallingAmplifier() != null
                                || player.compensatedEntities.getLevitationAmplifier() != null);
        distance = beforeMove(distance, lava, reset, context.isGliding(), velocity.y);
        return JavaCollisionState.of(player, actor, storedDistance(context.getVersion(), distance));
    }

    static double beforeMove(double distance, boolean lava, boolean reset, boolean gliding, double velocityY) {
        if (lava) distance *= 0.5;
        // LivingEntity#updateFallFlying -> Entity#checkFallDistanceAccumulation is BEFORE travel.
        if (gliding && velocityY > -0.5 && distance > 1.0) distance = 1.0;
        return reset ? 0.0 : distance;
    }

    public record Moved(double fallDistance, Vec3 movement, boolean landed, DesyncStatus touchingWater) {}

    public static Moved afterCollision(CultPlayer player, PredictionResult result, Vec3 acceptedDiff) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity actor = actor(player, context);
        double distance =
                beforeMove(player, context, result.getInitialStartingVel()).fallDistance();
        Vec3 movement = movement(result, acceptedDiff);
        var down = result.getCollideAxisData().getYNeg();
        boolean landed = landsOnClip(down, movement.y, context.getLastStuckSpeed().y);
        if (distance != 0 && movement.lengthSqr() >= 1 && crossesResetBlock(player, context, movement)) distance = 0;
        boolean water = context.getWorldData().getInWater().determinePessimistically();
        Vec3 end = context.getStart().add(movement);
        // LivingEntity#checkFallDamage refreshes fluids BEFORE generic accumulation.
        if (actor.isLivingEntity() && !water && touchesWater(player, context, end)) {
            water = true;
            distance = 0;
        }
        boolean waterBelow = FluidTags.WATER.test(ClientFluidQueries.fluidAt(
                player.compensatedWorld,
                BlockPos.containing(end.x, end.y, end.z).below()));
        distance = afterMoveForActor(
                distance,
                movement.y,
                water,
                landed,
                actor.isBoat(),
                actor.riding != null,
                waterBelow,
                actor.isStrider() && context.getWorldData().getInLava().determinePessimistically());
        return new Moved(
                storedDistance(context.getVersion(), distance),
                movement,
                landed,
                water ? DesyncStatus.TRUE : context.getWorldData().getInWater());
    }

    static double afterMoveForActor(
            double distance,
            double dy,
            boolean water,
            boolean landed,
            boolean boat,
            boolean passenger,
            boolean waterBelow,
            boolean striderInLava) {
        if (striderInLava) return 0;
        if (boat) return passenger ? distance : afterMove(distance, dy, waterBelow, landed);
        return afterMove(distance, dy, water, landed);
    }

    /** LivingEntity#checkFallDamage's fluid refresh after the move: the actor's box at {@code end} touches water. */
    public static boolean touchesWater(CultPlayer player, SimulationContext context, Vec3 end) {
        return touchesWater(player, context.getToActualPose().copy().offset(end.subtract(context.getEnd())));
    }

    /** Entity#updateFluidHeightAndDoFluidPushing for water: whether {@code box} touches it. */
    public static boolean touchesWater(CultPlayer player, SimpleCollisionBox entityBox) {
        var box = entityBox.copy().expand(-0.001);
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++)
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++)
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var fluid = ClientFluidQueries.fluidAt(player.compensatedWorld, pos);
                    if (FluidTags.WATER.test(fluid)
                            && pos.getY() + ClientFluidQueries.height(player.compensatedWorld, pos, fluid) >= box.minY)
                        return true;
                }
        return false;
    }

    static double storedDistance(ClientVersion version, double distance) {
        // Entity.fallDistance is a float through 1.21.4 and a double from 1.21.5.
        // Round each stored update: even a sub-1e-7 change can cross PowderSnowBlock's 2.5 threshold.
        return version.isOlderThan(ClientVersion.V_1_21_5) ? (float) distance : distance;
    }

    static double afterMove(double distance, double resolvedY, boolean water, boolean landed) {
        // 26.2 Entity#checkFallDamage promotes the FLOAT delta into its double accumulator.
        if (!water && resolvedY < 0.0) distance -= (float) resolvedY;
        return landed ? 0.0 : distance;
    }

    /**
     * True when this tick's packet-visible Y movement is exactly the probed downward clip, i.e. the
     * client landed on it. {@code movementY} is client space, so the clip (stored divided by
     * Entity#stuckSpeedMultiplier) must be scaled back first - see CollideAxisData.CollideResult.
     */
    static boolean landsOnClip(CollideAxisData.CollideResult down, double movementY, double stuckSpeedY) {
        return down != null && down.isLikelyCollide() && sameMoveY(movementY, down.inMovementSpace(stuckSpeedY));
    }

    /** Whether the packet-visible Y movement {@code movementY} is the Y movement {@code y} the model computes. */
    static boolean sameMoveY(double movementY, double y) {
        return Math.abs(movementY - y) <= 1.0E-7D;
    }

    /** The move the tick commits: the packet's move, or the scaled prediction when the result flags. */
    static Vec3 movement(PredictionResult result, Vec3 acceptedDiff) {
        return result.hasEffectiveFlags()
                ? result.getLegacyLikePredictionVector()
                        .multiply(result.getSimulationContext().getLastStuckSpeed())
                : acceptedDiff;
    }

    /**
     * Entity#move's verticalCollisionBelow, which sets a locally controlled actor's onGround: the requested Y is
     * downward and collision changed it, by landing on the probed clip or by a step-up. A requested Y of zero is never
     * below, even at rest on a block.
     */
    static boolean collidesBelow(double requestedY, Moved moved) {
        return requestedY < 0.0D && (moved.landed() || Math.abs(moved.movement().y - requestedY) > 1.0E-7D);
    }

    private static boolean crossesResetBlock(CultPlayer player, SimulationContext context, Vec3 movement) {
        Vec3 from = context.getStart();
        Vec3 to = from.add(movement.normalize().scale(Math.min(movement.length(), 8.0)));
        return player.compensatedWorld
                        .geometry()
                        .clipResetting(
                                from,
                                to,
                                id -> {
                                    return BlockTags.FALL_DAMAGE_RESETTING.test(id)
                                            || context.getVehicle() == null
                                                    && (BlockIds.is(id, BlockIds.END_PORTAL)
                                                            || BlockIds.is(id, BlockIds.END_GATEWAY));
                                },
                                id -> FluidTags.WATER.test(ClientFluidQueries.modelFluid(id)))
                != null;
    }
}
