package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.FluidCurrent;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.JavaLivingMoveFluids;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;

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
        boolean water = world.isInWater();
        boolean lava = world.isInLava();
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

    public record Moved(
            double fallDistance,
            Vec3 movement,
            boolean landed,
            boolean touchingWater,
            boolean touchingLava,
            int playerEyeFluidContact,
            ClientFluidHeights fluidHeights) {
        public Moved(
                double fallDistance,
                Vec3 movement,
                boolean landed,
                boolean touchingWater,
                boolean touchingLava,
                int playerEyeFluidContact) {
            this(fallDistance, movement, landed, touchingWater, touchingLava, playerEyeFluidContact, null);
        }

        public boolean playerEyeInWater() {
            return (playerEyeFluidContact & 1) != 0;
        }

        public Moved(
                double fallDistance,
                Vec3 movement,
                boolean landed,
                boolean touchingWater,
                boolean touchingLava,
                boolean playerEyeInWater) {
            this(
                    fallDistance,
                    movement,
                    landed,
                    touchingWater,
                    touchingLava,
                    ac.cult.cultac.utils.nmsutil.ClientFluidQueries.eyeContactFromWater(playerEyeInWater));
        }

        public Moved(
                double fallDistance,
                Vec3 movement,
                boolean landed,
                boolean touchingWater,
                boolean touchingLava) {
            this(fallDistance, movement, landed, touchingWater, touchingLava, 0);
        }

        public Moved(double fallDistance, Vec3 movement, boolean landed, boolean touchingWater) {
            this(fallDistance, movement, landed, touchingWater, false);
        }
    }

    public static Moved afterCollision(CultPlayer player, PredictionResult result, Vec3 acceptedDiff) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity actor = actor(player, context);
        double distance =
                beforeMove(player, context, result.getInitialStartingVel()).fallDistance();
        if (JavaRiddenTravel.clearsWithoutTravel(context))
            return new Moved(
                    distance,
                    Vec3.ZERO,
                    context.getLastOnGround().determineOptimistically(),
                    context.getWorldData().isInWater(),
                    context.getWorldData().isInLava(),
                    context.getWorldData().getEyeFluidContact());
        Vec3 movement = movement(result, acceptedDiff);
        // Both accepted and rejected reports include the later pass. Choose the move first, then
        // remove that pass so the travel's callbacks and the pass replay start at the travel endpoint.
        JavaPassAfterTravel pass = JavaMovementEngine.passAfterTravel(result);
        if (pass != null) movement = movement.subtract(pass.movement());
        var down = result.getCollideAxisData().getYNeg();
        boolean landed = landed(result, down, movement.y);
        // Entity#move's fall-reset ray and checkFallDamage read the collided move, not the reported position
        // change; the ray runs only inside its setPos branch.
        JavaMoveCollision forward = result.getCollideAxisData() instanceof JavaMoveCollision move ? move : null;
        Vec3 callbackMovement = forward == null ? movement : forward.collided();
        boolean setPosition = forward == null
                || callbackMovement.lengthSqr() > 1.0E-7
                || forward.attempted().lengthSqr() - callbackMovement.lengthSqr() < 1.0E-7;
        if (!context.isNoPhysics()
                && distance != 0
                && setPosition
                && callbackMovement.lengthSqr() >= 1
                && crossesResetBlock(player, context, callbackMovement)) distance = 0;
        boolean water = context.getWorldData().isInWater();
        boolean lava = context.getWorldData().isInLava();
        int eyeInWater = context.getWorldData().getEyeFluidContact();
        // Entity#move's noPhysics branch runs neither checkFallDamage nor collided-axis callbacks.
        if (context.isNoPhysics()) return new Moved(distance, movement, false, water, lava, eyeInWater);
        Vec3 end = context.getStart().add(movement);
        // LivingEntity#checkFallDamage refreshes fluids BEFORE generic accumulation.
        ClientFluidHeights fluidHeights = null;
        boolean runsCallback = runsFallDamageCallback(context.getVersion(), true, actor);
        if (runsCallback) {
            SimpleCollisionBox body = context.getToActualPose().copy().offset(end.subtract(context.getEnd()));
            var fluids = JavaLivingMoveFluids.apply(
                    player,
                    context.getVersion(),
                    actor,
                    body,
                    Vec3.ZERO,
                    water,
                    lava);
            // A root's own callback never refreshes the local passenger's eye cache.
            if (actor == player.compensatedEntities.getSelf())
                eyeInWater = fluids.playerEyeFluidContact(
                        player, body, end, end.y + context.getPose().eyeHeight * context.getScale(), eyeInWater);
            if (fluids.resetFallDistance()) distance = 0;
            if (fluids.refreshed()) {
                if (actor instanceof PacketEntityStrider
                        && context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_1))
                    fluidHeights = ClientFluidHeights.capture(player, body);
                water = fluids.inWater();
                if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_1)) lava = fluids.inLava();
            }
        }
        boolean waterBelow = ClientFluidQueries.is(
                player,
                ClientFluidQueries.fluidAt(
                        player.compensatedWorld,
                        BlockPos.containing(end.x, end.y, end.z).below()),
                FluidTags.WATER);
        if (runsCallback)
            distance = afterMoveForActor(
                    context.getVersion(),
                    distance,
                    callbackMovement.y,
                    water,
                    landed,
                    actor.isBoat(),
                    actor.riding != null,
                    waterBelow,
                    actor.isStrider() && context.getWorldData().isInLava());
        return new Moved(
                storedDistance(context.getVersion(), distance), movement, landed, water, lava, eyeInWater, fluidHeights);
    }

    static double afterMoveForActor(
            ClientVersion version,
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
        // 1.21.2/1.21.3's generic callback accumulates downward movement even in water.
        return afterMove(distance, dy, water && version.isNewerThanOrEquals(ClientVersion.V_1_21_5), landed);
    }

    /** Entity#updateFluidHeightAndDoFluidPushing for water: whether {@code box} touches it. */
    public static boolean touchesWater(CultPlayer player, SimpleCollisionBox entityBox) {
        var current = new FluidCurrent();
        current.compute(player, entityBox, true, 0, 0, FluidTags.WATER, .014D);
        return current.touching;
    }

    public static JavaPredictionCarry commit(CultPlayer player, PredictionResult result, Vec3 acceptedDiff) {
        SimulationContext context = result.getSimulationContext();
        var world = context.getWorldData();
        double distance;
        if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            // The endpoint-only block scan used before 1.21.9 still follows the same virtual move callback.
            distance = afterCollision(player, result, acceptedDiff).fallDistance();
        } else {
            distance =
                    beforeMove(player, context, result.getInitialStartingVel()).fallDistance();
            // Rejected packet endpoints must not manufacture a fall-distance reset.
            Vec3 movement = movement(result, acceptedDiff);
            boolean water = world.isInWater();
            var down = result.getCollideAxisData().getYNeg();
            boolean landed = landed(result, down, movement.y);
            // Entity#move's fall-damage-reset ray is after collision, before checkFallDamage.
            if (distance != 0.0 && movement.lengthSqr() >= 1.0 && crossesResetBlock(player, context, movement))
                distance = 0.0;
            distance = afterMove(distance, movement.y, water, landed);
        }
        // entityInside -> makeStuckInBlock and the bubble/honey callbacks occur after landing.
        if (world.getStuckSpeed().getStuckSpeedMultiplier() != null
                || world.getBubbleColumn().hasBubbleColumn()
                || world.getHoneySlide().determinePessimistically()) distance = 0.0;
        return new JavaPredictionCarry(actor(player, context), storedDistance(context.getVersion(), distance));
    }

    /** Entity#move's client-side virtual checkFallDamage call, for the local player or its controlled root. */
    public static boolean runsFallDamageCallback(ClientVersion version, boolean locallyAuthoritative) {
        // 1.21.4 additionally requires !isControlledByClient. Player returns true, and a root
        // controlled by that player delegates to it. Other releases use local authority alone.
        return locallyAuthoritative && version != ClientVersion.V_1_21_4;
    }

    public static boolean runsFallDamageCallback(
            ClientVersion version, boolean locallyAuthoritative, PacketEntity actor) {
        return runsFallDamageCallback(version, locallyAuthoritative) && !(actor instanceof PacketEntityHappyGhast);
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

    private static boolean landed(PredictionResult result, CollideAxisData.CollideResult down, double movementY) {
        if (result.getCollideAxisData() instanceof JavaMoveCollision move) return move.verticalCollisionBelow();
        // Entity#move only sets verticalCollisionBelow for an attempted downward move. A floor
        // probe at zero does not ground a controlled actor whose actual move had no Y component.
        return (!VelocityCandidates.hasEngineTrace(result) || VelocityCandidates.requestedMovementY(result) < 0)
                && landsOnClip(down, movementY, result.getSimulationContext().getLastStuckSpeed().y);
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
        return result.getJavaReportedMovement() != null || result.hasEffectiveFlags()
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
        // Entity#move traced the complete collided move until 1.21.9 introduced the eight-block cap.
        Vec3 to = context.getVersion().isOlderThan(ClientVersion.V_1_21_9)
                ? from.add(movement)
                : from.add(movement.normalize().scale(Math.min(movement.length(), 8.0)));
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
