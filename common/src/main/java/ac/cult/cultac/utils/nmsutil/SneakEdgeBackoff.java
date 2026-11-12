package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;

/**
 * Entity#deltaMovement across a sneaking edge. Entity#move moves by the delta Player#maybeBackOffFromEdge shortens,
 * while deltaMovement keeps the delta LivingEntity#travel built, so the next tick starts from velocity the position
 * never showed. On a shortened axis that delta holds the water current Entity#baseTick added and the input of the keys
 * in the client's latest PLAYER_INPUT, which LocalPlayer#sendChanges sends before the tick's position.
 */
final class SneakEdgeBackoff {
    private static final double STEP = 0.05D;
    // Bounds the 0.05 probe loop; a larger shortened delta keeps the engine's candidates.
    private static final double MAX_PROBED_AXIS = 1.0D;

    private SneakEdgeBackoff() {}

    /** What a tick's sneaking edge left in Entity#deltaMovement across the realities that carry it. */
    enum Carry {
        /** Every reality reproduces the move only with a delta the edge left whole: deltaMovement is the move's. */
        NOTHING_HIDDEN,
        /** No carry, and at least one reality's back-off is unknown. */
        UNKNOWN,
        /** Carries, but not one for every reality, or not the only delta that reproduces some reality's move. */
        PARTIAL,
        /** One carry for every reality. */
        EVERY;

        boolean found() {
            return this == PARTIAL || this == EVERY;
        }

        /** Whether the carries are the client's deltaMovement, given whether the tick's start was. */
        boolean carriesExactly(boolean exactStart) {
            return exactStart && this == EVERY;
        }

        /** Whether the next-tick velocities hide nothing a sneaking edge's back-off kept in deltaMovement. */
        boolean leavesExact(boolean exactStart, boolean onEdge) {
            if (found()) return carriesExactly(exactStart);
            return !onEdge || exactStart && this == NOTHING_HIDDEN;
        }
    }

    // Compared by identity: a delta Player#maybeBackOffFromEdge left whole that reproduces the move, and a delta whose
    // back-off and collision land elsewhere.
    private static final Vec3 NOT_SHORTENED = new Vec3(0.0D, 0.0D, 0.0D);
    private static final Vec3 ELSEWHERE = new Vec3(0.0D, 0.0D, 0.0D);
    // How one reality reproduces the move: by one carry alone, by a carry or a whole delta, by a whole delta, with no
    // speed and multiplier the client allows, or unknown.
    private static final int CARRIED = 0;
    private static final int CARRIED_OR_WHOLE = 1;
    private static final int WHOLE = 2;
    private static final int NOT_REPRODUCED = 3;
    private static final int UNKNOWN = 4;

    /**
     * Adds to {@code out} the deltaMovement Entity#move leaves after an edge shortened the accepted move, one per
     * reality whose starting velocity reproduces that move with the client's keys: the result itself and every
     * zero-flag reality. Returns whether every one of them, some or none did. A zero-flag reality that no speed and
     * multiplier the client allows moves to the accepted position is not a state the client can be in, so it counts
     * for neither. From an {@code exactStart}, neither does a zero-flag reality that starts from a tick skip: the
     * client's CLIENT_TICK_END proves LocalPlayer#tick ran every tick, so its start is the committed deltaMovement.
     */
    static Carry addDeltaMovements(PredictionResult result, Vec3 accepted, Set<Vec3> out, boolean exactStart) {
        // Every reality of a result belongs to the result's player.
        CultPlayer player = result.getPlayer();
        if (player == null || !appliesTo(player)) return Carry.UNKNOWN;
        int[] counts = new int[5];
        int own = addDeltaMovement(result, accepted, out);
        counts[own == NOT_REPRODUCED ? UNKNOWN : own]++;
        List<PredictionResult> realities = result.getRealities();
        for (int i = 0, size = realities == null ? 0 : realities.size(); i < size; i++) {
            PredictionResult reality = realities.get(i);
            if (reality == result || reality.getFlagSeverity() != 0.0) continue;
            if (exactStart && reality.getInitialStartingVel().isTickSkip()) continue;
            counts[addDeltaMovement(reality, accepted, out)]++;
        }
        boolean others = counts[WHOLE] > 0 || counts[UNKNOWN] > 0;
        if (counts[CARRIED] > 0 || counts[CARRIED_OR_WHOLE] > 0) {
            return others || counts[CARRIED_OR_WHOLE] > 0 ? Carry.PARTIAL : Carry.EVERY;
        }
        return counts[UNKNOWN] == 0 ? Carry.NOTHING_HIDDEN : Carry.UNKNOWN;
    }

    private static int addDeltaMovement(PredictionResult result, Vec3 accepted, Set<Vec3> out) {
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        if (player == null || context == null || !appliesTo(player, result, context)) return UNKNOWN;

        return addDeltaMovement(player, result, context, accepted, movingSlowlyFactor(player, context), out);
    }

    private static float movingSlowlyFactor(CultPlayer player, SimulationContext context) {
        float sneakingSpeed =
                (float) player.compensatedEntities.getSelf().sneakingSpeed.value();
        if (context.getWorldData().getInWater() != DesyncStatus.TRUE) {
            return context.isLastSneaking() ? sneakingSpeed : 1.0F;
        }
        // LocalPlayer#aiStep crouches a shift-holding player unless Entity#updateSwimming, earlier in this tick's
        // baseTick, left the player swimming.
        boolean crouching = context.isLastSneaking() && !context.getWorldData().isSwimming();
        return crouching ? sneakingSpeed : 1.0F;
    }

    /**
     * A bound on the horizontal length of the deltaMovement this tick leaves for the next, from {@code startBound}, one
     * on this tick's start: LivingEntity#travel adds the input of the keys, Player#maybeBackOffFromEdge, collisions and
     * the block speed factor only shorten the delta, and travel's friction scales it. Infinite where anything else may
     * add to it (a water current, an entity push, a velocity packet) or the input is unknown.
     */
    static double nextSpeedBound(PredictionResult result, double startBound) {
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        if (!(startBound < Double.POSITIVE_INFINITY)
                || player == null
                || context == null
                || !appliesTo(player, result, context)) {
            return Double.POSITIVE_INFINITY;
        }
        WorldData world = context.getWorldData();
        if (world.getInWater() != DesyncStatus.FALSE
                || world.getNumPushing() != 0
                || world.getLoadingLevelPushes() != null) {
            return Double.POSITIVE_INFINITY;
        }
        List<PredictionResult> realities = result.getRealities();
        for (int i = 0, size = realities == null ? 0 : realities.size(); i < size; i++) {
            PredVector start = realities.get(i).getInitialStartingVel();
            if (start.packetModifiersLength() != 0 || start.isEntityMotion()) return Double.POSITIVE_INFINITY;
        }
        double input = 0.0D;
        if (player.packetStateData.knownInput.strafeImpulse() != 0.0F
                || player.packetStateData.knownInput.forwardImpulse() != 0.0F) {
            float[] speeds = speeds(player, result, context);
            float movingSlowlyFactor = movingSlowlyFactor(player, context);
            ClientItemUseState.Snapshot use = context.getItemUse();
            for (int u = 0; u < 3; u++) {
                for (float speed : speeds) {
                    Vec3 travel = ClientKeyInput.travelInput(
                            player,
                            context,
                            player.packetStateData.knownInput,
                            itemUseMultiplier(use, u),
                            movingSlowlyFactor,
                            speed);
                    if (travel == null) return Double.POSITIVE_INFINITY;
                    input = Math.max(input, Math.sqrt(travel.x * travel.x + travel.z * travel.z));
                }
            }
        }
        // LivingEntity#travel's friction: the supporting block's on the ground, 0.91 in the air.
        float friction = world.getLastOnGround() == DesyncStatus.TRUE
                ? BlockProperties.getFriction(
                                player, context.getLastTickMainSupportingBlockData(), result.getInitialStartingVel())
                        * 0.91F
                : 0.91F;
        return (startBound + input) * friction;
    }

    /** LivingEntity#getFrictionInfluencedSpeed in water, else the speeds on the ground or in the air sprinting allows. */
    private static float[] speeds(CultPlayer player, PredictionResult result, SimulationContext context) {
        if (context.getWorldData().getInWater() == DesyncStatus.TRUE) {
            return new float[] {context.getMoveRelativeSpeed(player, true, true, false)};
        }
        ClientSprintState.Outcome outcome = context.getSprintOutcome(player, result.getInitialStartingVel());
        return context.getWorldData().getLastOnGround() == DesyncStatus.TRUE
                ? context.getGroundMoveRelativeSpeeds(player, outcome)
                : flyingSpeeds(context);
    }

    /**
     * Adds the carry for {@code movingSlowlyFactor} at the speed LivingEntity#getFrictionInfluencedSpeed gives in water,
     * on the ground or in the air, and at LocalPlayer#modifyInput's item-use multiplier, when one speed the client's
     * sprint state allows and one multiplier its item use allows alone reproduce the move with a shortened delta.
     * Returns how the client's speeds and multipliers reproduce the move.
     */
    private static int addDeltaMovement(
            CultPlayer player,
            PredictionResult result,
            SimulationContext context,
            Vec3 accepted,
            float movingSlowlyFactor,
            Set<Vec3> out) {
        float[] speeds = speeds(player, result, context);
        ClientItemUseState.Snapshot use = context.getItemUse();
        Vec3 found = null;
        boolean whole = false;
        boolean tried = false;
        boolean elsewhere = true;
        for (int u = 0; u < 3; u++) {
            float itemUse = itemUseMultiplier(use, u);
            if (u > 0 && itemUse == itemUseMultiplier(use, 0) || u > 1 && itemUse == itemUseMultiplier(use, 1)) {
                continue;
            }
            for (float speed : speeds) {
                Vec3 carry = deltaMovementFrom(player, result, context, accepted, itemUse, movingSlowlyFactor, speed);
                tried = true;
                if (carry == ELSEWHERE) continue;
                elsewhere = false;
                if (carry == NOT_SHORTENED) {
                    whole = true;
                    continue;
                }
                if (carry == null) continue;
                if (found != null && !found.equals(carry)) return UNKNOWN;
                found = carry;
            }
        }
        if (found == null) return whole ? WHOLE : tried && elsewhere ? NOT_REPRODUCED : UNKNOWN;
        out.add(found);
        return whole ? CARRIED_OR_WHOLE : CARRIED;
    }

    /**
     * LocalPlayer#modifyInput's item multiplier: the legal one, the required one of a use the client owes, or that of a
     * use it may have started.
     */
    private static float itemUseMultiplier(ClientItemUseState.Snapshot use, int index) {
        return index == 0
                ? use.legalMultiplier()
                : index == 1 ? use.requiredMultiplier() : use.slowestMultiplier();
    }

    /** Player#getFlyingSpeed off the ground for each sprint flag the client may have held during travel. */
    private static float[] flyingSpeeds(SimulationContext context) {
        List<Boolean> sprinting = context.getIsSprinting().getStates();
        float[] speeds = new float[sprinting.size()];
        for (int i = 0; i < speeds.length; i++) speeds[i] = sprinting.get(i) ? 0.025999999F : 0.02F;
        return speeds;
    }

    private static Vec3 deltaMovementFrom(
            CultPlayer player,
            PredictionResult result,
            SimulationContext context,
            Vec3 accepted,
            float itemUseMultiplier,
            float movingSlowlyFactor,
            float speed) {
        boolean water = context.getWorldData().getInWater() == DesyncStatus.TRUE;
        PredVector start = result.getInitialStartingVel();
        Vec3 input = ClientKeyInput.travelInput(
                player, context, player.packetStateData.knownInput, itemUseMultiplier, movingSlowlyFactor, speed);
        if (input == null) return null;
        // Entity#baseTick's updateInWaterStateAndDoFluidPushing adds the water current before LocalPlayer#aiStep.
        Vec3 current = water ? WaterCurrent.calculateWaterCurrent(player, context, context.getStart(), start) : null;
        double startX = current == null ? start.x : start.x + current.x;
        double startY = current == null ? start.y : start.y + current.y;
        double startZ = current == null ? start.z : start.z + current.z;
        // LocalPlayer#aiStep's goDownInWater adds -0.04F while shift is held in water, before travel.
        double deltaY = water ? startY + (double) -0.04F : startY;
        if (water && context.getWorldData().isSwimming()) deltaY = swimPull(player, context, deltaY);
        Vec3 delta = new Vec3(startX + input.x, deltaY, startZ + input.z);
        if (!(Math.abs(delta.x) <= MAX_PROBED_AXIS) || !(Math.abs(delta.z) <= MAX_PROBED_AXIS)) return null;

        SimpleCollisionBox box = context.getFromActualPose();
        Vec3 backedOff = backOff(player, box, delta, player.getMaxUpStep());

        // Entity#move skips setPos for a collided move shorter than 1e-7 that the backoff left.
        Vec3 movement = Collisions.collide(player, box, backedOff.x, backedOff.y, backedOff.z);
        double movementLength = movement.lengthSqr();
        boolean moved = movementLength > 1.0E-7 || backedOff.lengthSqr() - movementLength < 1.0E-7;
        Vec3 position = moved ? movement : Vec3.ZERO;
        if (position.distanceToSqr(accepted) > 1.0E-14) return ELSEWHERE;
        if (backedOff.x == delta.x && backedOff.z == delta.z) return NOT_SHORTENED;

        // Entity#restituteMovementAfterCollisions zeroes each collided axis of a player suppressing bounce.
        return new Vec3(
                CultMath.equal(backedOff.x, movement.x) ? delta.x : 0.0D,
                backedOff.y == movement.y ? delta.y : 0.0D,
                CultMath.equal(backedOff.z, movement.z) ? delta.z : 0.0D);
    }

    private static boolean appliesTo(CultPlayer player, PredictionResult result, SimulationContext context) {
        PredVector start = result.getInitialStartingVel();
        WorldData world = context.getWorldData();
        return appliesTo(player)
                && context.isSneaking()
                && context.getVehicle() == null
                && !player.isFlying
                && !context.usesFallFlyingMovement()
                && start != null
                && !start.isTickSkip()
                && !start.isJump()
                && !start.isKnockback()
                && !(start.y > 0.0D)
                && isAboveGround(player, result, context)
                && world.getInLava() == DesyncStatus.FALSE
                && world.getClimbingAtStart() == DesyncStatus.FALSE
                && !world.isMightBeInBlock()
                && context.getMaxAttackSlow() == 0
                // LivingEntity#checkAutoSpinAttack can reset sprinting after travel when it hits a RemotePlayer.
                && !world.isSpinAttackPlayer()
                // LivingEntity#travelInWater moves by moveRelative(0.02F) when no water_movement_efficiency raises it.
                && (world.getInWater() == DesyncStatus.FALSE
                        || world.getInWater() == DesyncStatus.TRUE && context.getWaterMovementEfficiency() == 0.0F)
                && result.getValidMovements() != null
                && result.getValidMovements().getPositionOnlyDelta().lengthSqr() <= 1.0E-14;
    }

    private static boolean appliesTo(CultPlayer player) {
        // 1.21.6 is the earliest checked client with the 1e-7 canFallAtLeast slab and the square-movement correction.
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6)
                && !player.isBedrockMovement()
                && player.packetStateData.clientMovementInputKnown
                // Player#isStayingOnGroundSurface reads the keys this tick's PLAYER_INPUT sent.
                && player.packetStateData.knownInput.shift();
    }

    /**
     * Player#travel's pull of a swimming player's vertical delta toward its look, after LocalPlayer#aiStep's
     * goDownInWater and before LivingEntity#travelInWater's move.
     */
    private static double swimPull(CultPlayer player, SimulationContext context, double deltaY) {
        double lookY =
                ReachUtils.getLook(player, context.getXRot(), context.getYRot()).getY();
        Vec3 from = context.getStart();
        if (!(lookY <= 0.0D)
                && !player.packetStateData.knownInput.jump()
                && player.compensatedWorld.getFluidLevelAt(
                                CultMath.floor(from.x), CultMath.floor(from.y + 1.0D - 0.1D), CultMath.floor(from.z))
                        == 0.0D) {
            return deltaY;
        }
        double multiplier = lookY < -0.2D ? 0.085D : 0.06D;
        return deltaY + (lookY - deltaY) * multiplier;
    }

    /**
     * Player#isAboveGround at the tick's start box, with the fallDistance Entity#move reads before its checkFallDamage
     * adds this tick's fall.
     */
    private static boolean isAboveGround(CultPlayer player, PredictionResult result, SimulationContext context) {
        DesyncStatus onGround = context.getWorldData().getLastOnGround();
        if (onGround != DesyncStatus.FALSE) return onGround == DesyncStatus.TRUE;
        float maxDownStep = player.getMaxUpStep();
        double fallDistance = JavaFallDistance.beforeMove(player, context, result.getInitialStartingVel())
                .fallDistance();
        return fallDistance < maxDownStep
                && !canFallAtLeast(
                        player,
                        context.getFromActualPose(),
                        new SimpleCollisionBox(),
                        0.0,
                        0.0,
                        maxDownStep - fallDistance);
    }

    /** Player#maybeBackOffFromEdge once its flying, mover, shift and Player#isAboveGround conditions hold. */
    static Vec3 backOff(CultPlayer player, SimpleCollisionBox box, Vec3 delta, float maxDownStep) {
        SimpleCollisionBox probe = new SimpleCollisionBox();
        double deltaX = delta.x;
        double deltaZ = delta.z;
        double stepX = Math.signum(deltaX) * STEP;
        double stepZ = Math.signum(deltaZ) * STEP;
        while (deltaX != 0.0 && canFallAtLeast(player, box, probe, deltaX, 0.0, maxDownStep)) {
            if (Math.abs(deltaX) <= STEP) {
                deltaX = 0.0;
                break;
            }
            deltaX -= stepX;
        }
        while (deltaZ != 0.0 && canFallAtLeast(player, box, probe, 0.0, deltaZ, maxDownStep)) {
            if (Math.abs(deltaZ) <= STEP) {
                deltaZ = 0.0;
                break;
            }
            deltaZ -= stepZ;
        }
        while (deltaX != 0.0 && deltaZ != 0.0 && canFallAtLeast(player, box, probe, deltaX, deltaZ, maxDownStep)) {
            deltaX = Math.abs(deltaX) <= STEP ? 0.0 : deltaX - stepX;
            deltaZ = Math.abs(deltaZ) <= STEP ? 0.0 : deltaZ - stepZ;
        }
        return new Vec3(deltaX, delta.y, deltaZ);
    }

    /** Player#canFallAtLeast: nothing collides in the slab {@code minHeight} below the moved box's feet. */
    private static boolean canFallAtLeast(
            CultPlayer player,
            SimpleCollisionBox box,
            SimpleCollisionBox probe,
            double deltaX,
            double deltaZ,
            double minHeight) {
        boolean legacy = player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5);
        double inset = legacy ? 0.0 : 1.0E-7;
        double depth = legacy ? (double) 1.0E-5F : 1.0E-7;
        probe.set(
                box.minX + inset + deltaX,
                box.minY - minHeight - depth,
                box.minZ + inset + deltaZ,
                box.maxX - inset + deltaX,
                box.minY,
                box.maxZ - inset + deltaZ);
        return Collisions.isEmpty(player, probe, box.minY);
    }
}
