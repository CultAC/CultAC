package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * The movement input a tick's keys add. KeyboardInput#tick builds the move vector from the keys the
 * client sends in PLAYER_INPUT, LocalPlayer#modifyInput scales it, and LivingEntity#travel adds it through
 * Entity#moveRelative and Entity#getInputVector.
 */
public final class ClientKeyInput {
    private ClientKeyInput() {}

    /**
     * LivingEntity#moveRelative's addition for {@code keys}: KeyboardInput#tick, LocalPlayer#modifyInput with
     * {@code itemUseMultiplier} and {@code movingSlowlyFactor}, and Entity#getInputVector at {@code speed}.
     * LocalPlayer#aiStep computes crouching from the previous keys before Input#tick. The caller supplies each
     * source-proven slow factor from movingSlowlyFactors, including forced crouching in obstructed space.
     */
    public static Vec3 travelInput(
            CultPlayer player,
            SimulationContext context,
            KnownInput keys,
            float itemUseMultiplier,
            float movingSlowlyFactor,
            float speed) {
        // LivingEntity#aiStep clears xxa/zza after applyInput when health <= 0. PLAYER_INPUT still reports the
        // held keys. Use receipt-proven health, not the legacy isDead flag which may precede the health packet.
        if (!player.compensatedEntities.getSelf().actionAlive || !player.isBedrockMovement() && player.isInBed)
            return Vec3.ZERO;
        return modifiedInput(context, keys, itemUseMultiplier, movingSlowlyFactor, speed);
    }

    private static Vec3 modifiedInput(
            SimulationContext context,
            KnownInput keys,
            float itemUseMultiplier,
            float movingSlowlyFactor,
            float speed) {
        return inputVector(context, movementInput(context, keys, itemUseMultiplier, movingSlowlyFactor), speed);
    }

    /** The xxa/zza LocalPlayer#applyInput stores, before Entity#getInputVector rotates and scales it. */
    public static Vec3 movementInput(
            SimulationContext context, KnownInput keys, float itemUseMultiplier, float movingSlowlyFactor) {
        Vec3 modified;
        if (context.getVersion().isOlderThan(ClientVersion.V_1_21_5)) {
            // KeyboardInput#tick scales raw key impulses first; LocalPlayer applies item use, then
            // LivingEntity#aiStep applies 0.98F. Square movement correction starts in 1.21.5.
            float x = keys.strafeImpulse() * movingSlowlyFactor;
            float z = keys.forwardImpulse() * movingSlowlyFactor;
            x *= itemUseMultiplier;
            z *= itemUseMultiplier;
            modified = new Vec3(x * 0.98F, 0, z * 0.98F);
        } else {
            modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                    new Vec3(keys.strafeImpulse(), 0.0D, keys.forwardImpulse()), itemUseMultiplier, movingSlowlyFactor);
        }
        return modified;
    }

    /**
     * LocalPlayer#modifyInput's slow multipliers: LocalPlayer#isMovingSlowly is crouching or Entity#isVisuallyCrawling,
     * which out of water is a SWIMMING pose or, through LivingEntity#isVisuallySwimming, a FALL_FLYING pose without
     * fall flying. Player#updatePlayerPose sets the pose only after LocalPlayer#aiStep, which crouches a player whose
     * previous keys held shift only where the crouching pose fits blocks and entities
     * (Player#canPlayerFitWithinBlocksAndEntitiesWhen).
     */
    public static float[] movingSlowlyFactors(CultPlayer player, SimulationContext context) {
        float sneaking =
                (float) player.compensatedEntities.getSelf().sneakingSpeed.value();
        boolean visuallySwimming =
                context.getPose() == Pose.SWIMMING || context.getPose() == Pose.FALL_FLYING && !context.isGliding();
        // isVisuallyCrawling reads the isInWater this tick's Entity#baseTick left.
        if (visuallySwimming && !context.getWorldData().isInWater()) return new float[] {sneaking};
        return crouchingFactors(player, context, sneaking);
    }

    private static float[] crouchingFactors(CultPlayer player, SimulationContext context, float sneaking) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_14)) {
            return new float[] {context.isLastSneaking() ? sneaking : 1.0F};
        }
        // LocalPlayer#aiStep computes this before reading the new keys. These gates
        // apply to a held shift as well as crouching forced by the available space.
        if (FlightToggle.flyingBeforeAiStep(player)
                || context.getWorldData().isSwimming()
                || context.getVehicle() != null) return new float[] {1.0F};
        Vec3 from = context.getStart();
        // LocalPlayer#aiStep also crouches without shift when standing does not fit. This precedes
        // Input#tick and Player#updatePlayerPose, so the starting pose can still be STANDING.
        boolean forced = !context.isLastSneaking();
        if (forced
                && (player.isInBed
                        || player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, from.x, from.y, from.z))) {
            return new float[] {1.0F};
        }
        if (player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z)) {
            return forced && player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, from.x, from.y, from.z)
                    ? new float[] {sneaking, 1.0F}
                    : new float[] {sneaking};
        }
        return player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z)
                ? new float[] {sneaking, 1.0F}
                : new float[] {1.0F};
    }

    /**
     * Every LivingEntity#moveRelative addition the reported keys give {@code candidate}'s travel: one per
     * LivingEntity#getFrictionInfluencedSpeed or Player#getFlyingSpeed the tick's ground, fluid and sprint states
     * allow, LocalPlayer#modifyInput slow factor and item-use multiplier. Empty when a pose the context does not
     * determine could slow the input.
     */
    public static List<Vec3> travelInputs(CultPlayer player, SimulationContext context, PredVector candidate) {
        // Player#isImmobile includes sleeping; LivingEntity#aiStep clears xxa/zza after applyInput.
        if (!player.isBedrockMovement() && player.isInBed) return List.of(Vec3.ZERO);
        // LivingEntity#travelFallFlying updates deltaMovement directly and never calls moveRelative.
        if (context.usesFallFlyingMovement() && !context.getWorldData().isInLiquid()) return List.of(Vec3.ZERO);
        KnownInput keys = player.packetStateData.knownInput;
        ClientItemUseState.Snapshot use = context.getItemUse();
        float[] itemUses = {use.legalMultiplier(), use.requiredMultiplier(), use.slowestMultiplier()};
        float[] slows = movingSlowlyFactors(player, context);
        List<Vec3> inputs = new ArrayList<>(2);
        for (float speed : moveRelativeSpeeds(player, context, candidate)) {
            for (float slow : slows) {
                for (float itemUse : itemUses) {
                    Vec3 input = travelInput(player, context, keys, itemUse, slow, speed);
                    if (input == null) return List.of();
                    if (!inputs.contains(input)) inputs.add(input);
                }
            }
        }
        return inputs;
    }

    /**
     * The exact LivingEntity#moveRelative inputs {@code candidate}'s travel may add: those of the reported keys when
     * {@code reportedKeys}, and every KeyboardInput direction otherwise. While a use certainly slows the client
     * (a legal multiplier below 1), the unslowed input of the same keys stays a candidate too: NoSlow, not the
     * simulation, owns the offset of a move that ignored the slowdown.
     */
    public static List<Vec3> exactTravelInputs(
            CultPlayer player, SimulationContext context, PredVector candidate, boolean reportedKeys) {
        // Before 1.21.5 travelFallFlying still glides on a climbable and never calls moveRelative.
        // From 1.21.5 usesFallFlyingMovement excludes the certain travelInAir branch; an unknown climbable
        // can still supply both source branches below.
        boolean gliding =
                context.usesFallFlyingMovement() && !context.getWorldData().isInLiquid();
        DesyncStatus climbing = context.getWorldData().getClimbingAtStart();
        if (gliding
                && (context.getVersion().isOlderThan(ClientVersion.V_1_21_5) || !climbing.determineOptimistically()))
            return List.of(Vec3.ZERO);
        // LivingEntity#aiStep clears xxa/zza after applyInput when health <= 0.
        if (!player.compensatedEntities.getSelf().actionAlive || !player.isBedrockMovement() && player.isInBed)
            return List.of(Vec3.ZERO);
        ClientItemUseState.Snapshot use = context.getItemUse();
        float[] itemUses = use.legalMultiplier() < 1.0F
                ? new float[] {use.legalMultiplier(), use.requiredMultiplier(), use.slowestMultiplier(), 1.0F}
                : new float[] {use.legalMultiplier(), use.requiredMultiplier(), use.slowestMultiplier()};
        float[] slows = movingSlowlyFactors(player, context);
        List<Float> speeds = moveRelativeSpeeds(player, context, candidate);
        var inputs = new ArrayList<Vec3>(4);
        if (gliding && climbing.isDesync()) inputs.add(Vec3.ZERO);
        if (reportedKeys) {
            addInputs(inputs, context, player.packetStateData.knownInput, speeds, slows, itemUses);
            return inputs;
        }
        for (int forward = -1; forward <= 1; forward++) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                var keys = new KnownInput(forward > 0, forward < 0, strafe > 0, strafe < 0, false, false, false);
                addInputs(inputs, context, keys, speeds, slows, itemUses);
            }
        }
        return inputs;
    }

    private static void addInputs(
            List<Vec3> inputs,
            SimulationContext context,
            KnownInput keys,
            List<Float> speeds,
            float[] slows,
            float[] itemUses) {
        for (float speed : speeds) {
            for (float slow : slows) {
                for (float itemUse : itemUses) {
                    Vec3 input = modifiedInput(context, keys, itemUse, slow, speed);
                    if (!inputs.contains(input)) inputs.add(input);
                }
            }
        }
    }

    /** All KeyboardInput movement directions, independently of the keys the player reported. */
    public static List<Vec3> possibleTravelInputs(CultPlayer player, SimulationContext context, PredVector candidate) {
        if (!player.isBedrockMovement() && player.isInBed) return List.of(Vec3.ZERO);
        if (context.usesFallFlyingMovement() && !context.getWorldData().isInLiquid()) return List.of(Vec3.ZERO);
        if (!player.compensatedEntities.getSelf().actionAlive) return List.of(Vec3.ZERO);
        ClientItemUseState.Snapshot use = context.getItemUse();
        float[] itemUses = {use.legalMultiplier(), use.requiredMultiplier(), use.slowestMultiplier()};
        float[] slows = movingSlowlyFactors(player, context);
        var inputs = new ArrayList<Vec3>();
        for (int forward = -1; forward <= 1; forward++) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                var keys = new KnownInput(forward > 0, forward < 0, strafe > 0, strafe < 0, false, false, false);
                for (float speed : moveRelativeSpeeds(player, context, candidate)) {
                    for (float slow : slows) {
                        for (float itemUse : itemUses) {
                            Vec3 input = modifiedInput(context, keys, itemUse, slow, speed);
                            if (!inputs.contains(input)) inputs.add(input);
                        }
                    }
                }
            }
        }
        return inputs;
    }

    /** The moveRelative speeds of the travel: on dry ground every speed the sprint modifier allows. */
    private static List<Float> moveRelativeSpeeds(CultPlayer player, SimulationContext context, PredVector candidate) {
        WorldData world = context.getWorldData();
        List<Float> speeds = new ArrayList<>(2);
        boolean water = world.isInWater();
        boolean lava = world.isInLava();
        boolean dry = !water && !lava && context.getVehicle() == null && !player.isFlying;
        for (boolean onGround : world.getLastOnGround().getStates()) {
            if (dry && onGround && context.isSprintModifier()) {
                for (float speed :
                        context.getGroundMoveRelativeSpeeds(player, context.getSprintOutcome(player, candidate)))
                    if (!speeds.contains(speed)) speeds.add(speed);
            } else if (dry && !onGround) {
                // Player#getFlyingSpeed reads the sprint flag.
                for (boolean sprinting : context.getIsSprinting().getStates()) {
                    float speed = sprinting ? 0.025999999F : 0.02F;
                    if (!speeds.contains(speed)) speeds.add(speed);
                }
            } else {
                float speed = context.getMoveRelativeSpeed(player, onGround, water, lava);
                if (!speeds.contains(speed)) speeds.add(speed);
            }
        }
        return speeds;
    }

    /** Entity#getInputVector. */
    public static Vec3 inputVector(SimulationContext context, Vec3 input, float speed) {
        double lengthSqr = input.lengthSqr();
        if (lengthSqr < 1.0E-7D) return Vec3.ZERO;
        Vec3 movement = (lengthSqr > 1.0D ? input.normalize() : input).scale(speed);
        float yawRadians = context.getXRot() * ((float) Math.PI / 180F);
        float sin = context.getTrig().sin(yawRadians);
        float cos = context.getTrig().cos(yawRadians);
        return new Vec3(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
    }
}
