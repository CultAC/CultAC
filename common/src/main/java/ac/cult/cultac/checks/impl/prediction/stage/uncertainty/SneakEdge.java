package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.math.OptifineFastMath;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WaterCurrent;
import java.util.ArrayList;
import java.util.List;

/**
 * Player#maybeBackOffFromEdge, which Entity#move applies to the travel delta before Entity#collide while the
 * shift key is down. The input that LivingEntity#moveRelative added cannot be read back from the backed-off
 * displacement, so every legal input is moved through the edge and the outcome matching the packet is selected
 * with its input applied: no further input allowance is added around it. The part of the delta that the edge
 * withheld stays in Entity#deltaMovement, so it is carried as negative position-only movement.
 */
public class SneakEdge implements UncertaintyHandler {
    private static final double STEP = 0.05;
    private static final double MATCH_DISTANCE_SQR = 1.0E-10;
    /** The reason of a movement the edge shortened. */
    public static final String BACKED_OFF = "sneak edge back-off";

    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        if (valid != null && valid.isTestingMaxStartingVelExtents(end)) return trace;
        PredVector start = trace.position();
        // Player#isStayingOnGroundSurface is the shift key; abilities.flying and positive Y skip the back-off.
        if (!context.isSneaking() || context.getVehicle() != null || player.isFlying || start.y > 0.0) return trace;

        Vec3 stuck = start.collisionStuckSpeedMultiplier(context);
        if (stuck == null) stuck = new Vec3(1, 1, 1);
        SimpleCollisionBox box = GetBoundingBox.getCollisionBoxForPlayer(
                player, context.getStart().x, context.getStart().y, context.getStart().z);
        Edge edge = new Edge(player, box, context.getVersion(), player.getMaxUpStep());
        DesyncStatus aboveGround = aboveGround(player, context, start, edge);
        if (aboveGround == DesyncStatus.FALSE) return trace;

        // LivingEntity#handleRelativeFrictionAndCalculateMovement clamps the delta on a climbable after
        // moveRelative, so the input joins the delta from before the Ladder handler's clamp.
        Vec3 beforeClimb = start.vectorBeforeFirstReason("ladder");
        Vec3 base = withFluidCurrents(player, context, new Vec3(beforeClimb.x, start.y, beforeClimb.z));
        List<Input> inputs = inputs(player, context, start);

        Vec3 best = null;
        Vec3 bestDelta = null;
        double bestDistance = Double.MAX_VALUE;
        int bestPreference = Integer.MAX_VALUE;
        for (boolean clamped : Ladder.clampsDeltaMovement(context).getStates()) {
            for (Input input : inputs) {
                Vec3 delta = base.add(input.vector());
                if (clamped) {
                    Vec3 climbed = Ladder.handleOnClimbable(player, context, delta);
                    delta = new Vec3(climbed.x, start.y, climbed.z);
                }
                Vec3 backedOff = edge.backOff(delta.multiply(stuck)).multiply(1 / stuck.x, 1, 1 / stuck.z);
                double distance = horizontalDistanceSqr(backedOff, end);
                // Many inputs back off to the same position but leave different deltas behind, and carrying every
                // one compounds tick over tick. Only among outcomes that already reproduce this packet, the
                // reported keys pick the delta to carry: a hypothesis, not truth. A false report carries a delta
                // that the client's later movement will not line up with.
                if (distance <= MATCH_DISTANCE_SQR) distance = 0;
                if (distance < bestDistance || distance == bestDistance && input.preference() < bestPreference) {
                    bestDistance = distance;
                    bestPreference = input.preference();
                    best = backedOff;
                    bestDelta = delta;
                }
            }
        }
        // With an unknown ground state, a packet no edge outcome reproduces is the move that skipped the edge.
        if (best == null || aboveGround == DesyncStatus.UNKNOWN && bestDistance > MATCH_DISTANCE_SQR) return trace;

        Vec3 withheld = new Vec3(best.x - bestDelta.x, 0, best.z - bestDelta.z);
        boolean backedOff = withheld.x != 0 || withheld.z != 0;
        if (bestDistance <= MATCH_DISTANCE_SQR) {
            // A legal input the edge shortens nothing of is an ordinary move: its input is judged like any other.
            if (!backedOff) return trace;
        } else {
            // No legal input reproduces the move. One the edge would not shorten either is judged like any other
            // move, so the input checks name what it got wrong; one past the edge cannot be made at all.
            Vec3 moved = new Vec3(end.x, start.y, end.z).multiply(stuck);
            Vec3 edged = edge.backOff(moved);
            if (edged.x == moved.x && edged.z == moved.z) return trace;
        }
        PredVector moved = start.withXYZ(best.x, start.y, best.z, BACKED_OFF).markInputApplied();
        // Entity#move skips setPos when Entity#collide keeps under 1.0E-7 of a longer backed-off move, but still
        // restitutes the delta from that collision.
        Vec3 scaled = best.multiply(stuck);
        double collided =
                Collisions.collide(player, box, scaled.x, scaled.y, scaled.z).lengthSqr();
        if (collided <= 1.0E-7 && scaled.lengthSqr() - collided >= 1.0E-7) {
            moved = moved.with(Vec3.ZERO, "Entity#move setPos guard");
            // The position stays put, while deltaMovement keeps the whole delta.
            withheld = new Vec3(-bestDelta.x, 0, -bestDelta.z);
        }
        return trace.withPosition(moved).withPreCollisionVelocity(bestDelta).addPositionOnlyDelta(withheld);
    }

    /**
     * Entity#baseTick's water and lava currents, which updateInWaterStateAndDoFluidPushing adds before
     * LocalPlayer#aiStep. On foot the runner leaves them to FluidPush, so the edge adds them to its delta here.
     */
    private static Vec3 withFluidCurrents(CultPlayer player, SimulationContext context, Vec3 delta) {
        Vec3 water = WaterCurrent.calculateWaterCurrent(player, context, context.getStart(), delta);
        if (water != null && finite(water)) delta = delta.add(water);
        Vec3 lava = WaterCurrent.calculateLavaCurrent(player, context, context.getStart(), delta);
        if (lava != null && finite(lava)) delta = delta.add(lava);
        return delta;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        return handleMovementTrace(player, valid, result, context, lastResult, MovementTrace.start(start), end)
                .position();
    }

    /** Player#isAboveGround: onGround, or a fall short of maxUpStep that cannot continue. */
    private static DesyncStatus aboveGround(CultPlayer player, SimulationContext context, PredVector start, Edge edge) {
        DesyncStatus onGround = context.getLastOnGround();
        if (onGround == DesyncStatus.TRUE) return DesyncStatus.TRUE;
        double fallDistance =
                JavaFallDistance.beforeMove(player, context, start).fallDistance();
        boolean landed = edge.version.isOlderThan(ClientVersion.V_1_21_5)
                ? (float) fallDistance < edge.maxUpStep
                        && !edge.canFallAtLeast(0, 0, edge.maxUpStep - (float) fallDistance)
                : fallDistance < edge.maxUpStep && !edge.canFallAtLeast(0, 0, edge.maxUpStep - fallDistance);
        if (landed) return DesyncStatus.TRUE;
        return onGround == DesyncStatus.UNKNOWN ? DesyncStatus.UNKNOWN : DesyncStatus.FALSE;
    }

    /** A legal LivingEntity#moveRelative input; lower {@code preference} agrees more with the client's report. */
    private record Input(Vec3 vector, int preference) {}

    /** LivingEntity#moveRelative for every key combination, slowdown and travel speed the client may have. */
    private static List<Input> inputs(CultPlayer player, SimulationContext context, PredVector start) {
        ClientItemUseState.Snapshot itemUse = context.getItemUse();
        float[] items = itemUse == null
                ? new float[] {1.0F}
                : itemUse.definitelyUsing()
                        ? new float[] {itemUse.requiredMultiplier(), itemUse.legalMultiplier()}
                        : new float[] {itemUse.legalMultiplier(), itemUse.requiredMultiplier()};
        var keys = player.packetStateData.knownInput;
        int keyLeft = (int) keys.strafeImpulse();
        int keyForward = (int) keys.forwardImpulse();
        float[] slows = ClientKeyInput.movingSlowlyFactors(player, context, start);
        float yaw = context.getXRot() * (float) (Math.PI / 180.0);
        // Like the sprint-jump boost, both vanilla Mth and OptiFine fast math.
        ClientVersion version = context.getVersion();
        float[][] rotations = {
            {VanillaMath.sin(version, yaw), VanillaMath.cos(version, yaw)},
            {OptifineFastMath.sin(yaw), OptifineFastMath.cos(yaw)}
        };
        List<Double> speeds = context.getMoveRelativeInputSpeeds(player, start);
        List<Input> inputs = new ArrayList<>(72);
        for (float slow : slows)
            for (int item = 0; item < items.length; item++)
                for (int forward = -1; forward <= 1; forward++)
                    for (int left = -1; left <= 1; left++) {
                        Vec3 input = keyboardInput(context.getVersion(), left, forward, slow, items[item]);
                        int keyed = left == keyLeft && forward == keyForward ? 0 : 4;
                        for (double speed : speeds)
                            for (int trig = 0; trig < rotations.length; trig++) {
                                Vec3 vector = inputVector(input, (float) speed, rotations[trig][0], rotations[trig][1]);
                                inputs.add(new Input(vector, keyed + item * 2 + trig));
                            }
                    }
        return inputs;
    }

    /** LocalPlayer#applyInput's xxa/zza, including LivingEntity#applyInput's 0.98 before 1.21.5. */
    static Vec3 keyboardInput(ClientVersion version, int left, int forward, float slow, float item) {
        float x = left;
        float z = forward;
        if (version.isOlderThan(ClientVersion.V_1_21_5)) {
            return ClientKeyInput.legacyKeyboardInput(version, x, z, slow, item);
        }

        // KeyboardInput#tick normalizes; LocalPlayer#modifyInput scales and reshapes to the unit square.
        float length = (float) Math.sqrt(x * x + z * z);
        if (length < 1.0E-4F) return Vec3.ZERO;
        x /= length;
        z /= length;
        x *= 0.98F;
        z *= 0.98F;
        if (item != 1.0F) {
            x *= item;
            z *= item;
        }
        if (slow != 1.0F) {
            x *= slow;
            z *= slow;
        }
        length = (float) Math.sqrt(x * x + z * z);
        if (length <= 0.0F) return new Vec3(x, 0, z);
        float inverse = 1.0F / length;
        float directionX = x * inverse;
        float directionZ = z * inverse;
        float absX = Math.abs(directionX);
        float absZ = Math.abs(directionZ);
        float tan = absZ > absX ? absX / absZ : absZ / absX;
        float modified = Math.min(length * (float) Math.sqrt(1.0F + tan * tan), 1.0F);
        return new Vec3(directionX * modified, 0, directionZ * modified);
    }

    /** Entity#getInputVector. */
    static Vec3 inputVector(Vec3 input, float speed, float sin, float cos) {
        double lengthSqr = input.lengthSqr();
        if (lengthSqr < 1.0E-7) return Vec3.ZERO;
        Vec3 movement = (lengthSqr > 1.0 ? input.normalize() : input).scale(speed);
        return new Vec3(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /** The start-of-move collision box Player#canFallAtLeast offsets. */
    private record Edge(CultPlayer player, SimpleCollisionBox box, ClientVersion version, float maxUpStep) {
        boolean canFallAtLeast(double dx, double dz, double minHeight) {
            SimpleCollisionBox below = version.isOlderThan(ClientVersion.V_1_21_5)
                    ? new SimpleCollisionBox(
                            box.minX + dx,
                            box.minY - (float) minHeight - 1.0E-5F,
                            box.minZ + dz,
                            box.maxX + dx,
                            box.minY,
                            box.maxZ + dz)
                    : new SimpleCollisionBox(
                            box.minX + 1.0E-7 + dx,
                            box.minY - minHeight - 1.0E-7,
                            box.minZ + 1.0E-7 + dz,
                            box.maxX - 1.0E-7 + dx,
                            box.minY,
                            box.maxZ - 1.0E-7 + dz);
            return Collisions.isEmpty(player, below);
        }

        Vec3 backOff(Vec3 delta) {
            double x = delta.x;
            double z = delta.z;
            double stepX = Math.signum(x) * STEP;
            double stepZ = Math.signum(z) * STEP;
            while (x != 0.0 && canFallAtLeast(x, 0.0, maxUpStep)) {
                if (Math.abs(x) <= STEP) {
                    x = 0.0;
                    break;
                }
                x -= stepX;
            }
            while (z != 0.0 && canFallAtLeast(0.0, z, maxUpStep)) {
                if (Math.abs(z) <= STEP) {
                    z = 0.0;
                    break;
                }
                z -= stepZ;
            }
            while (x != 0.0 && z != 0.0 && canFallAtLeast(x, z, maxUpStep)) {
                x = Math.abs(x) <= STEP ? 0.0 : x - stepX;
                z = Math.abs(z) <= STEP ? 0.0 : z - stepZ;
            }
            return new Vec3(x, delta.y, z);
        }
    }
}
