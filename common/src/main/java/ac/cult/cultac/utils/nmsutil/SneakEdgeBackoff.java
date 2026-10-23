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
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

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

    /**
     * The deltaMovement Entity#move leaves after an edge shortened the accepted move, or null when no zero-flag
     * reality's starting velocity reproduces that move with the client's keys, or several reproduce it differently.
     */
    static Vec3 deltaMovement(PredictionResult result, Vec3 accepted) {
        Vec3 found = deltaMovementFrom(result, accepted);
        List<PredictionResult> realities = result.getRealities();
        for (int i = 0, size = realities == null ? 0 : realities.size(); i < size; i++) {
            PredictionResult reality = realities.get(i);
            if (reality == result || reality.getFlagSeverity() != 0.0) continue;
            Vec3 other = deltaMovementFrom(reality, accepted);
            if (other == null) continue;
            if (found != null && !found.equals(other)) return null;
            found = other;
        }
        return found;
    }

    private static Vec3 deltaMovementFrom(PredictionResult result, Vec3 accepted) {
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        if (player == null || context == null || !appliesTo(player, result, context)) return null;

        float sneakingSpeed =
                (float) player.compensatedEntities.getSelf().sneakingSpeed.value();
        if (context.getWorldData().getInWater() != DesyncStatus.TRUE) {
            return deltaMovementFrom(
                    player, result, context, accepted, context.isLastSneaking() ? sneakingSpeed : 1.0F);
        }
        // LocalPlayer#aiStep crouches a shift-holding player unless Entity#updateSwimming, earlier in this tick's
        // baseTick, left the player swimming.
        boolean crouching = context.isLastSneaking() && !context.getWorldData().isSwimming();
        return deltaMovementFrom(player, result, context, accepted, crouching ? sneakingSpeed : 1.0F);
    }

    /**
     * The carry for {@code movingSlowlyFactor} at the speed LivingEntity#getFrictionInfluencedSpeed gives in water, on
     * the ground or in the air, kept when one speed the client's sprint state allows alone reproduces the move.
     */
    private static Vec3 deltaMovementFrom(
            CultPlayer player,
            PredictionResult result,
            SimulationContext context,
            Vec3 accepted,
            float movingSlowlyFactor) {
        boolean water = context.getWorldData().getInWater() == DesyncStatus.TRUE;
        if (water) {
            float speed = context.getMoveRelativeSpeed(player, true, true, false);
            return deltaMovementFrom(player, result, context, accepted, movingSlowlyFactor, speed);
        }
        Vec3 found = null;
        ClientSprintState.Outcome outcome = context.getSprintOutcome(player, result.getInitialStartingVel());
        float[] speeds = context.getWorldData().getLastOnGround() == DesyncStatus.TRUE
                ? context.getGroundMoveRelativeSpeeds(player, outcome)
                : flyingSpeeds(context);
        for (float speed : speeds) {
            Vec3 carry = deltaMovementFrom(player, result, context, accepted, movingSlowlyFactor, speed);
            if (carry == null) continue;
            if (found != null && !found.equals(carry)) return null;
            found = carry;
        }
        return found;
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
            float movingSlowlyFactor,
            float speed) {
        boolean water = context.getWorldData().getInWater() == DesyncStatus.TRUE;
        PredVector start = result.getInitialStartingVel();
        Vec3 input = ClientKeyInput.travelInput(
                player, context, player.packetStateData.knownInput, 1.0F, movingSlowlyFactor, speed);
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
        if (backedOff.x == delta.x && backedOff.z == delta.z) return null;

        // Entity#move skips setPos for a collided move shorter than 1e-7 that the backoff left.
        Vec3 movement = Collisions.collide(player, box, backedOff.x, backedOff.y, backedOff.z);
        double movementLength = movement.lengthSqr();
        boolean moved = movementLength > 1.0E-7 || backedOff.lengthSqr() - movementLength < 1.0E-7;
        Vec3 position = moved ? movement : Vec3.ZERO;
        if (position.distanceToSqr(accepted) > 1.0E-14) return null;

        // Entity#restituteMovementAfterCollisions zeroes each collided axis of a player suppressing bounce.
        return new Vec3(
                CultMath.equal(backedOff.x, movement.x) ? delta.x : 0.0D,
                backedOff.y == movement.y ? delta.y : 0.0D,
                CultMath.equal(backedOff.z, movement.z) ? delta.z : 0.0D);
    }

    private static boolean appliesTo(CultPlayer player, PredictionResult result, SimulationContext context) {
        PredVector start = result.getInitialStartingVel();
        WorldData world = context.getWorldData();
        // 1.21.6 is the earliest checked client with the 1e-7 canFallAtLeast slab and the square-movement correction.
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6)
                && !player.isBedrockMovement()
                && player.packetStateData.clientMovementInputKnown
                // Player#isStayingOnGroundSurface reads the keys this tick's PLAYER_INPUT sent.
                && player.packetStateData.knownInput.shift()
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
                && !IsUsingItem.isUsingItem(player)
                && result.getValidMovements() != null
                && result.getValidMovements().getPositionOnlyDelta().lengthSqr() <= 1.0E-14;
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
        probe.set(
                box.minX + 1.0E-7 + deltaX,
                box.minY - minHeight - 1.0E-7,
                box.minZ + 1.0E-7 + deltaZ,
                box.maxX - 1.0E-7 + deltaX,
                box.minY,
                box.maxZ - 1.0E-7 + deltaZ);
        return Collisions.isEmpty(player, probe, box.minY);
    }
}
