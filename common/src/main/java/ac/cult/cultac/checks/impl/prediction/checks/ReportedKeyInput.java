package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPositionWorlds;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.SneakEdge;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UnknownCollisionProvider;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the keys of the client's latest PLAYER_INPUT move the player the way it moved. LocalPlayer#sendChanges sends
 * them before the tick's position with the keys KeyboardInput#tick read for that tick's travel, so on the ground and in
 * the air the tick adds exactly their input to the starting velocity before Entity#move collides it.
 * Player#maybeBackOffFromEdge shortens that move only while Player#isStayingOnGroundSurface, which is
 * LocalPlayer#isShiftKeyDown.
 */
final class ReportedKeyInput {
    private static final Vec3 NO_STUCK_SPEED = new Vec3(1, 1, 1);

    private ReportedKeyInput() {}

    /**
     * The horizontal distance between the client's movement and the nearest movement the reported keys produce from
     * this candidate's starting velocity and uncertainties, or 0 when the tick's state does not decide that input.
     */
    static double offset(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        SimulationContext context = result.getSimulationContext();
        if (!appliesTo(player, result)) return 0.0D;
        SimpleCollisionBox extents = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        if (extents == null) return 0.0D;

        KnownInput keys = player.packetStateData.knownInput;
        ClientItemUseState.Snapshot use = context.getItemUse();
        MovementTrace selected = result.getValidMovements().getClosestTrace();
        float[] slows =
                ClientKeyInput.movingSlowlyFactors(player, context, selected == null ? null : selected.position());
        boolean onGround = context.getWorldData().getLastOnGround() == DesyncStatus.TRUE;
        float[] speeds = onGround ? groundSpeeds(player, result) : airSpeeds(context);
        // LocalPlayer#modifyInput's item multiplier is the legal one, the required one of a use the client owes, or
        // that of a use it may have started: Minecraft#tick runs handleKeybinds, whose startUseItem precedes the
        // tick's LocalPlayer#aiStep, so a use slows the tick that sends its USE_ITEM.
        float[] itemUses = {use.legalMultiplier(), use.requiredMultiplier(), use.slowestMultiplier()};
        double best = Double.MAX_VALUE;
        for (float slow : slows) {
            for (int s = 0; s < speeds.length; s++) {
                float speed = speeds[s];
                // A repeated speed or multiplier gives the same input and offset as its first occurrence.
                if (repeats(speeds, s)) continue;
                for (int u = 0; u < itemUses.length; u++) {
                    float itemUse = itemUses[u];
                    if (repeats(itemUses, u)) continue;
                    Vec3 input = ClientKeyInput.travelInput(player, context, keys, itemUse, slow, speed);
                    if (input == null) return 0.0D;
                    best = Math.min(best, offset(player, result, extents, input, onGround));
                    if (best == 0.0D) return 0.0D;
                }
            }
        }
        return best;
    }

    /**
     * LivingEntity#getFrictionInfluencedSpeed on the ground reads getSpeed(), which carries minecraft:sprinting while
     * applied. ClientSprintState can still hold the modifier after one aiStep started and stopped a wall sprint.
     */
    private static float[] groundSpeeds(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        float walk = context.getMoveRelativeSpeed(player, true, false, false);
        float[] sprint = context.isSprintModifier()
                ? context.getGroundMoveRelativeSpeeds(
                        player, context.getSprintOutcome(player, result.getInitialStartingVel()))
                : new float[0];
        float[] speeds = new float[sprint.length + 1];
        speeds[0] = walk;
        System.arraycopy(sprint, 0, speeds, 1, sprint.length);
        return speeds;
    }

    /**
     * LivingEntity#getFrictionInfluencedSpeed in the air is Player#getFlyingSpeed, which reads the sprint flag the
     * client's last START/STOP_SPRINTING reports for this travel.
     */
    private static float[] airSpeeds(SimulationContext context) {
        List<Boolean> sprinting = context.getIsSprinting().getStates();
        float[] speeds = new float[sprinting.size()];
        for (int i = 0; i < speeds.length; i++) speeds[i] = sprinting.get(i) ? 0.025999999F : 0.02F;
        return speeds;
    }

    private static boolean repeats(float[] values, int index) {
        int bits = Float.floatToRawIntBits(values[index]);
        for (int i = 0; i < index; i++) if (Float.floatToRawIntBits(values[i]) == bits) return true;
        return false;
    }

    private static double offset(
            CultPlayer player, PredictionResult result, SimpleCollisionBox extents, Vec3 input, boolean onGround) {
        // Piston and shulker box shoves move the player in their own Entity#move calls, before the travel the keys
        // drive or, from 26.3, a shulker box's after it; the trace carries what they moved apart from that travel.
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        Vec3 target = trace == null ? result.getTarget() : result.getTarget().subtract(trace.collisionBaseOffset());
        // The starting velocity and uncertainties nearest the movement the keys leave unexplained.
        double startX = CultMath.clamp(target.x - input.x, extents.minX, extents.maxX);
        double startZ = CultMath.clamp(target.z - input.z, extents.minZ, extents.maxZ);
        double deltaX = startX + input.x;
        double deltaZ = startZ + input.z;
        double offset = Math.hypot(deltaX - target.x, deltaZ - target.z);
        if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;

        // Entity#collide clips the delta, so an axis that reaches past an obstacle ends on it.
        SimulationContext context = result.getSimulationContext();
        PredVector start = result.getInitialStartingVel();
        SimpleCollisionBox box = context.getFromActualPose();
        // Entity#collide steps from Entity#onGround at the tick's start, or from a downward move that lands.
        Vec3 movement = Collisions.collide(player, box, deltaX, start.y, deltaZ, onGround);
        offset = Math.min(offset, Math.hypot(movement.x - target.x, movement.z - target.z));
        if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;

        // Direction#axisStepOrder moves the longer horizontal axis first, so the nearest delta can slip past an
        // obstacle's end that a longer delta along a clipped axis meets first: such an axis also reaches its farthest.
        double farX = deltaX > target.x ? extents.maxX + input.x : deltaX < target.x ? extents.minX + input.x : deltaX;
        double farZ = deltaZ > target.z ? extents.maxZ + input.z : deltaZ < target.z ? extents.minZ + input.z : deltaZ;
        for (int far = 1; far <= 3; far++) {
            double x = (far & 1) != 0 ? farX : deltaX;
            double z = (far & 2) != 0 ? farZ : deltaZ;
            if (x == deltaX && z == deltaZ) continue;
            Vec3 clipped = Collisions.collide(player, box, x, start.y, z, onGround);
            double clippedOffset = Math.hypot(clipped.x - target.x, clipped.z - target.z);
            if (clippedOffset >= offset) continue;
            offset = clippedOffset;
            movement = clipped;
            if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;
        }

        // Entities and changing blocks the engine cannot place exactly may also clip it, but only where the client
        // stopped: an unknown box must lie in the rest of the keys' move, from the reported end onwards.
        SimpleCollisionBox rest = box.copy()
                .offset(target.x, target.y, target.z)
                .expandToCoordinate(movement.x - target.x, 0, movement.z - target.z);
        List<SimpleCollisionBox> unknown = new ArrayList<>();
        UnknownCollisionProvider.addMovementUnknownCollisions(
                player, context, context.getStart(), rest, unknown, new SimpleCollisionBox(), new SimpleCollisionBox());
        if (unknown.isEmpty()) return offset;

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
                        Collisions.collideWithAdditionalCollisionBoxes(player, box, x, start.y, z, colliders, onGround);
                offset = Math.min(offset, Math.hypot(clipped.x - target.x, clipped.z - target.z));
                if (offset <= HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY) return offset;
            }
        }
        return offset;
    }

    private static boolean appliesTo(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        WorldData world = context.getWorldData();
        // PLAYER_INPUT carries the full key state from 1.21.2.
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.isBedrockMovement()
                // No PLAYER_INPUT yet leaves the keys at Input.EMPTY, which MultiPlayerGameMode#createPlayer starts
                // every LocalPlayer from; LocalPlayer#sendChanges only sends keys that differ from the last sent.
                // LocalPlayer#applyInput reads the keys only while LocalPlayer#isControlledCamera.
                && player.cameraEntity.isSelf()
                && context.getVehicle() == null
                && !player.isFlying
                && !context.usesFallFlyingMovement()
                && result.getInitialStartingVel() != null
                && result.getValidMovements() != null
                // LivingEntity#travelInAir moves by the keys on the ground and in the air alike; the start's
                // Entity#onGround picks the speed and whether Entity#collide may step without landing.
                && !world.getLastOnGround().isDesync()
                && world.getInWater() == DesyncStatus.FALSE
                && world.getInLava() == DesyncStatus.FALSE
                && world.getClimbingAtStart() == DesyncStatus.FALSE
                && !world.isMightBeInBlock()
                // Entity#move scales the delta by the stuck speed before Entity#collide.
                && NO_STUCK_SPEED.equals(context.getLastStuckSpeed())
                && world.getStuckSpeed().getUnknownStuckSpeedMultiplier() == null
                // A shift edge shortens the delta through Player#maybeBackOffFromEdge; SneakEdge already moved every
                // legal input through it, and carries the delta the edge withheld exactly.
                && !backedOffFromEdge(result);
    }

    private static boolean backedOffFromEdge(PredictionResult result) {
        var trace = result.getValidMovements().getClosestTrace();
        return trace != null && trace.position().hasReason(SneakEdge.BACKED_OFF);
    }
}
