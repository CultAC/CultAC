package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPositionWorlds;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UnknownCollisionProvider;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;
import ac.cult.cultac.utils.data.StuckEdgeData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the keys of the client's latest PLAYER_INPUT move the player the way it moved. LocalPlayer#sendChanges sends
 * them before the tick's position with the keys KeyboardInput#tick read for that tick's travel, so on the ground the
 * tick adds exactly their input to the starting velocity before Entity#move collides it. Player#maybeBackOffFromEdge
 * shortens that move only while Player#isStayingOnGroundSurface, which is LocalPlayer#isShiftKeyDown.
 */
public final class ReportedKeyInput {
    private static final Vec3 NO_STUCK_SPEED = new Vec3(1, 1, 1);
    private static final int ENDED_USE = 3;

    private ReportedKeyInput() {}

    /** Working space of one owner's {@link #offset} calls, which run on its thread one at a time. */
    static final class Scratch {
        private float[] speeds = new float[4];
        private final float[] slows = new float[2];
        private final float[] itemUses = new float[4];
        private double[] inputs = new double[24];
        private boolean[] endedInputs = new boolean[12];
        private final SimpleCollisionBox pose = new SimpleCollisionBox();
        private final SimpleCollisionBox firstRest = new SimpleCollisionBox();
        private final SimpleCollisionBox secondRest = new SimpleCollisionBox();
        private final SimpleCollisionBox entityBox = new SimpleCollisionBox();
        private final SimpleCollisionBox entityScratch = new SimpleCollisionBox();
        private final List<SimpleCollisionBox> unknown = new ArrayList<>();
    }

    /**
     * The horizontal distance between the client's movement and the nearest movement the reported keys produce from
     * this candidate's starting velocity and uncertainties, or 0 when the tick's state does not decide that input.
     */
    static double offset(CultPlayer player, PredictionResult result, PredictionResult lastResult, Scratch scratch) {
        if (!appliesTo(player, result, lastResult)) return 0.0D;
        int speeds = groundSpeeds(player, result, scratch);
        int inputs = travelInputs(player, result, speeds, scratch);
        SimpleCollisionBox extents = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        if (extents == null) return 0.0D;
        double best = Double.MAX_VALUE;
        double ended = Double.MAX_VALUE;
        for (int i = 0; i < inputs; i++) {
            double offset =
                    offset(player, result, extents, scratch.inputs[2 * i], scratch.inputs[2 * i + 1], scratch);
            if (scratch.endedInputs[i]) {
                ended = Math.min(ended, offset);
            } else {
                best = Math.min(best, offset);
                if (best == 0.0D) return 0.0D;
            }
        }
        // An ended use's multiplier is not vanilla, so it may only explain a move, never rank one candidate over another.
        return ended <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY ? Math.min(best, ended) : best;
    }

    /**
     * Every moveRelative addition the client's latest PLAYER_INPUT keys can give this candidate's travel, one of which
     * its LivingEntity#travel adds: on the ground where {@link #offset} judges the keys, and in the air, where
     * LivingEntity#getFrictionInfluencedSpeed is Player#getFlyingSpeed. Null when the tick's state does not decide
     * that input.
     */
    public static List<Vec3> travelInputs(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        float[] itemUses = itemUses(result.getSimulationContext().getItemUse());
        if (appliesTo(player, result, lastResult))
            return travelInputs(player, result, groundSpeeds(player, result), itemUses);
        return inAir(player, result, lastResult)
                ? travelInputs(player, result, flyingSpeeds(player, result.getSimulationContext()), itemUses)
                : null;
    }

    /**
     * LocalPlayer#modifyInput's item multiplier is the legal one, the required one of a use the client owes, or that of
     * a use it may have started: Minecraft#tick runs handleKeybinds, whose startUseItem precedes the tick's
     * LocalPlayer#aiStep, so a use slows the tick that sends its USE_ITEM.
     */
    private static float[] itemUses(ClientItemUseState.Snapshot use) {
        return new float[] {use.legalMultiplier(), use.requiredMultiplier(), use.slowestMultiplier()};
    }

    private static float[] groundSpeeds(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        float walk = context.getMoveRelativeSpeed(player, true, false, false);
        // LivingEntity#getFrictionInfluencedSpeed reads getSpeed(), which carries minecraft:sprinting while applied.
        // ClientSprintState can still hold the modifier after one aiStep started and stopped a wall sprint.
        float[] sprint = context.isSprintModifier() && !stopsRunSprinting(player, context)
                ? context.getGroundMoveRelativeSpeeds(
                        player, context.getSprintOutcome(player, result.getInitialStartingVel()))
                : new float[0];
        float[] speeds = new float[sprint.length + 1];
        speeds[0] = walk;
        System.arraycopy(sprint, 0, speeds, 1, sprint.length);
        return speeds;
    }

    /** {@link #groundSpeeds(CultPlayer, PredictionResult)} written into {@code scratch.speeds}; returns their count. */
    private static int groundSpeeds(CultPlayer player, PredictionResult result, Scratch scratch) {
        SimulationContext context = result.getSimulationContext();
        float walk = context.getMoveRelativeSpeed(player, true, false, false);
        int sprints = 0;
        float[] sprint = null;
        if (context.isSprintModifier() && !stopsRunSprinting(player, context)) {
            sprint = context.getGroundMoveRelativeSpeeds(
                    player, context.getSprintOutcome(player, result.getInitialStartingVel()));
            sprints = sprint.length;
        }
        if (scratch.speeds.length < sprints + 1) scratch.speeds = new float[sprints + 1];
        scratch.speeds[0] = walk;
        if (sprint != null) System.arraycopy(sprint, 0, scratch.speeds, 1, sprints);
        return sprints + 1;
    }

    /** Player#getFlyingSpeed out of creative flight for each sprint flag the client may hold during the travel. */
    private static float[] flyingSpeeds(CultPlayer player, SimulationContext context) {
        List<Boolean> sprinting = context.getIsSprinting().getStates();
        boolean stopped = stopsRunSprinting(player, context);
        float[] speeds = new float[sprinting.size()];
        for (int i = 0; i < speeds.length; i++) speeds[i] = sprinting.get(i) && !stopped ? 0.025999999F : 0.02F;
        return speeds;
    }

    private static boolean stopsRunSprinting(CultPlayer player, SimulationContext context) {
        return context.getIsSprinting() == DesyncStatus.TRUE && player.packetStateData.knownInput.forwardImpulse() <= 0;
    }

    private static List<Vec3> travelInputs(
            CultPlayer player, PredictionResult result, float[] speeds, float[] itemUses) {
        SimulationContext context = result.getSimulationContext();
        KnownInput keys = player.packetStateData.knownInput;
        float[] slows = new float[2];
        int slowCount = slows(player, context, slows);
        List<Vec3> inputs = new ArrayList<>(slowCount * speeds.length * itemUses.length);
        for (int l = 0; l < slowCount; l++) {
            float slow = slows[l];
            for (int s = 0; s < speeds.length; s++) {
                float speed = speeds[s];
                // A repeated speed or multiplier gives the same input as its first occurrence.
                if (repeats(speeds, s)) continue;
                for (int u = 0; u < itemUses.length; u++) {
                    float itemUse = itemUses[u];
                    if (repeats(itemUses, u)) continue;
                    inputs.add(ClientKeyInput.travelInput(player, context, keys, itemUse, slow, speed));
                }
            }
        }
        return inputs;
    }

    /**
     * {@link #travelInputs(CultPlayer, PredictionResult, float[], float[])} for the first {@code speedCount} of
     * {@code scratch.speeds}, each input's x and z written in order into {@code scratch.inputs}; returns their count.
     */
    private static int travelInputs(CultPlayer player, PredictionResult result, int speedCount, Scratch scratch) {
        SimulationContext context = result.getSimulationContext();
        KnownInput keys = player.packetStateData.knownInput;
        ClientItemUseState.Snapshot use = context.getItemUse();
        float[] slows = scratch.slows;
        int slowCount = slows(player, context, slows);
        float[] speeds = scratch.speeds;
        float[] itemUses = scratch.itemUses;
        itemUses[0] = use.legalMultiplier();
        itemUses[1] = use.requiredMultiplier();
        itemUses[2] = use.slowestMultiplier();
        // The multiplier of a use that has ended moves the keys at the walking speed only.
        itemUses[ENDED_USE] = use.endedMultiplier();
        if (scratch.inputs.length < 2 * slowCount * speedCount * itemUses.length) {
            scratch.inputs = new double[2 * slowCount * speedCount * itemUses.length];
            scratch.endedInputs = new boolean[slowCount * speedCount * itemUses.length];
        }
        int count = 0;
        for (int l = 0; l < slowCount; l++) {
            float slow = slows[l];
            for (int s = 0; s < speedCount; s++) {
                float speed = speeds[s];
                if (repeats(speeds, s)) continue;
                for (int u = 0; u < itemUses.length; u++) {
                    float itemUse = itemUses[u];
                    // An ended multiplier of 1 is no ended use, not an unslowed reality beside a held one.
                    if (u == ENDED_USE && (s != 0 || itemUse >= 1) || repeats(itemUses, u)) continue;
                    ClientKeyInput.travelInput(scratch.inputs, 2 * count, player, context, keys, itemUse, slow, speed);
                    scratch.endedInputs[count] = u == ENDED_USE;
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * LocalPlayer#modifyInput's slow multipliers: LocalPlayer#isMovingSlowly is LocalPlayer#isCrouching or
     * Entity#isVisuallyCrawling, which out of water is a SWIMMING pose or, through LivingEntity#isVisuallySwimming, a
     * FALL_FLYING pose without fall flying. LocalPlayer#aiStep sets crouching where the crouching pose fits blocks and
     * entities (Player#canPlayerFitWithinBlocksAndEntitiesWhen) and either the previous keys held shift or, for a
     * player not sleeping, the standing pose does not fit. It writes one multiplier into {@code out} for each outcome
     * the possible entity positions allow and returns their count.
     */
    private static int slows(CultPlayer player, SimulationContext context, float[] out) {
        float sneaking = (float) player.compensatedEntities.getSelf().sneakingSpeed.value();
        if (context.getPose() == Pose.SWIMMING || context.getPose() == Pose.FALL_FLYING) return slows(out, sneaking);
        Vec3 from = context.getStart();
        if (!player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z)) {
            return slows(out, 1.0F);
        }
        boolean crouchFits = player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z);
        if (context.isLastSneaking()) return crouchFits ? slows(out, sneaking) : slows(out, sneaking, 1.0F);
        if (player.isInBed || !player.mayFitOnlyCrouchingWhen(from.x, from.y, from.z)) return slows(out, 1.0F);
        return crouchFits && !player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, from.x, from.y, from.z)
                ? slows(out, sneaking)
                : slows(out, sneaking, 1.0F);
    }

    private static int slows(float[] out, float only) {
        out[0] = only;
        return 1;
    }

    private static int slows(float[] out, float first, float second) {
        out[0] = first;
        out[1] = second;
        return 2;
    }

    private static boolean repeats(float[] values, int index) {
        int bits = Float.floatToRawIntBits(values[index]);
        for (int i = 0; i < index; i++) if (Float.floatToRawIntBits(values[i]) == bits) return true;
        return false;
    }

    private static double offset(
            CultPlayer player,
            PredictionResult result,
            SimpleCollisionBox extents,
            double inputX,
            double inputZ,
            Scratch scratch) {
        // From 26.3 the shulker box block shoves move the player after the travel the keys drive.
        ShulkerBoxShoveResult shove = JavaMovementEngine.shulkerBoxShoveAfterTravel(result);
        Vec3 target = shove == null ? result.getTarget() : result.getTarget().subtract(shove.movement());
        // The starting velocity and uncertainties nearest the movement the keys leave unexplained.
        double startX = CultMath.clamp(target.x - inputX, extents.minX, extents.maxX);
        double startZ = CultMath.clamp(target.z - inputZ, extents.minZ, extents.maxZ);
        double deltaX = startX + inputX;
        double deltaZ = startZ + inputZ;
        double offset = Math.hypot(deltaX - target.x, deltaZ - target.z);
        if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;

        // Entity#collide clips the delta, so an axis that reaches past an obstacle ends on it.
        SimulationContext context = result.getSimulationContext();
        PredVector start = result.getInitialStartingVel();
        SimpleCollisionBox box = context.getFromActualPose(scratch.pose);
        // Entity#collide steps from Entity#onGround, which appliesTo requires at the tick's start.
        Vec3 movement = Collisions.collide(player, box, deltaX, start.y, deltaZ, true);
        offset = Math.min(offset, Math.hypot(movement.x - target.x, movement.z - target.z));
        if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;

        // Direction#axisStepOrder moves the longer horizontal axis first, so the nearest delta can slip past an
        // obstacle's end that a longer delta along a clipped axis meets first: such an axis also reaches its farthest.
        double farX = deltaX > target.x ? extents.maxX + inputX : deltaX < target.x ? extents.minX + inputX : deltaX;
        double farZ = deltaZ > target.z ? extents.maxZ + inputZ : deltaZ < target.z ? extents.minZ + inputZ : deltaZ;
        for (int far = 1; far <= 3; far++) {
            double x = (far & 1) != 0 ? farX : deltaX;
            double z = (far & 2) != 0 ? farZ : deltaZ;
            if (x == deltaX && z == deltaZ) continue;
            Vec3 clipped = Collisions.collide(player, box, x, start.y, z, true);
            double clippedOffset = Math.hypot(clipped.x - target.x, clipped.z - target.z);
            if (clippedOffset >= offset) continue;
            offset = clippedOffset;
            movement = clipped;
            if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;
        }

        // Entities and changing blocks the engine cannot place exactly may also clip it, but only where the client
        // stopped: an unknown box must lie in the rest of the keys' move along an axis. Direction#axisStepOrder moves
        // the first horizontal axis from the start of the second, and the second from the reported end of the first.
        double nearX = minAbs(extents.minX + inputX, extents.maxX + inputX);
        double nearZ = minAbs(extents.minZ + inputZ, extents.maxZ + inputZ);
        double farthestX = Math.max(Math.abs(extents.minX + inputX), Math.abs(extents.maxX + inputX));
        double farthestZ = Math.max(Math.abs(extents.minZ + inputZ), Math.abs(extents.maxZ + inputZ));
        List<SimpleCollisionBox> unknown = scratch.unknown;
        unknown.clear();
        if (nearX < farthestZ) {
            addUnknownAlongTheRest(player, context, box, target, movement, farX, farZ, true, unknown, scratch);
        }
        if (farthestX >= nearZ) {
            addUnknownAlongTheRest(player, context, box, target, movement, farX, farZ, false, unknown, scratch);
        }
        boolean noUnknown = unknown.isEmpty();
        unknown.clear();
        if (noUnknown) return offset;

        // Entity#collide collides with each entity at the one position the client holds it.
        SimpleCollisionBox region = box.copy();
        for (int far = 0; far <= 3; far++) {
            region.expandToCoordinate((far & 1) != 0 ? farX : deltaX, start.y, (far & 2) != 0 ? farZ : deltaZ);
        }
        EntityPositionWorlds.withStepEntities(player, region).expand(SimpleCollisionBox.COLLISION_EPSILON);
        List<List<SimpleCollisionBox>> worlds = EntityPositionWorlds.of(player, context, context.getStart(), region);
        if (worlds == null) return 0.0D;
        for (List<SimpleCollisionBox> colliders : worlds) {
            if (colliders.isEmpty()) continue;
            for (int far = 0; far <= 3; far++) {
                double x = (far & 1) != 0 ? farX : deltaX;
                double z = (far & 2) != 0 ? farZ : deltaZ;
                if (far != 0 && x == deltaX && z == deltaZ) continue;
                Vec3 clipped =
                        Collisions.collideWithAdditionalCollisionBoxes(player, box, x, start.y, z, colliders, true);
                offset = Math.min(offset, Math.hypot(clipped.x - target.x, clipped.z - target.z));
                if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;
            }
        }
        return offset;
    }

    private static void addUnknownAlongTheRest(
            CultPlayer player,
            SimulationContext context,
            SimpleCollisionBox box,
            Vec3 target,
            Vec3 movement,
            double farX,
            double farZ,
            boolean zFirst,
            List<SimpleCollisionBox> unknown,
            Scratch scratch) {
        SimpleCollisionBox first = scratch.firstRest
                .set(box)
                .offset(zFirst ? 0 : target.x, target.y, zFirst ? target.z : 0)
                .expandToCoordinate(zFirst ? 0 : movement.x - target.x, 0, zFirst ? movement.z - target.z : 0)
                .expandToCoordinate(zFirst ? 0 : farX - target.x, 0, zFirst ? farZ - target.z : 0);
        SimpleCollisionBox second = scratch.secondRest
                .set(box)
                .offset(target.x, target.y, target.z)
                .expandToCoordinate(zFirst ? movement.x - target.x : 0, 0, zFirst ? 0 : movement.z - target.z)
                .expandToCoordinate(zFirst ? farX - target.x : 0, 0, zFirst ? 0 : farZ - target.z);
        UnknownCollisionProvider.addMovementUnknownCollisions(
                player, context, context.getStart(), first, unknown, scratch.entityBox, scratch.entityScratch);
        UnknownCollisionProvider.addMovementUnknownCollisions(
                player, context, context.getStart(), second, unknown, scratch.entityBox, scratch.entityScratch);
    }

    private static double minAbs(double low, double high) {
        return low <= 0 && high >= 0 ? 0 : Math.min(Math.abs(low), Math.abs(high));
    }

    private static boolean appliesTo(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        WorldData world = result.getSimulationContext().getWorldData();
        return keysDecideTravel(player, result, lastResult)
                && result.getValidMovements() != null
                && world.getLastOnGround() == DesyncStatus.TRUE
                && !world.isMightBeInBlock();
    }

    /** LivingEntity#travelInAir off the ground, where Player#getFlyingSpeed moves the keys' input. */
    private static boolean inAir(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        return keysDecideTravel(player, result, lastResult)
                && result.getSimulationContext().getWorldData().getLastOnGround() == DesyncStatus.FALSE;
    }

    /**
     * Whether the keys alone decide the input LivingEntity#travelInAir adds this tick and Entity#move takes unchanged,
     * whatever Entity#onGround is.
     */
    private static boolean keysDecideTravel(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        SimulationContext context = result.getSimulationContext();
        WorldData world = context.getWorldData();
        StuckEdgeData edge = world.getSneak();
        StuckEdgeData lastEdge = lastResult == null
                ? null
                : lastResult.getSimulationContext().getWorldData().getSneak();
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6)
                && !player.isBedrockMovement()
                // No PLAYER_INPUT yet leaves the keys at Input.EMPTY, which MultiPlayerGameMode#createPlayer starts
                // every LocalPlayer from; LocalPlayer#sendChanges only sends keys that differ from the last sent.
                // LocalPlayer#applyInput reads the keys only while LocalPlayer#isControlledCamera.
                && player.cameraEntity.isSelf()
                && context.getVehicle() == null
                && !player.isFlying
                && !context.usesFallFlyingMovement()
                && result.getInitialStartingVel() != null
                && world.getInWater() == DesyncStatus.FALSE
                && world.getInLava() == DesyncStatus.FALSE
                && world.getClimbingAtStart() == DesyncStatus.FALSE
                // Entity#move scales the delta by the stuck speed before Entity#collide.
                && NO_STUCK_SPEED.equals(context.getLastStuckSpeed())
                && world.getStuckSpeed().getUnknownStuckSpeedMultiplier() == null
                // A shift edge shortens the delta through Player#maybeBackOffFromEdge; Sneaking models that movement.
                && !edge.isOnEdge()
                && (lastEdge == null || !lastEdge.isOnEdge());
    }
}
