package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ClippedAxisReselectTest {
    private static final double RADIUS = 0.25D;
    private static final double CLIP_X = 0.1D;
    /** Entity#WATER_FLOW_SCALE, the circular allowance FluidPush grants a water current. */
    private static final double WATER_FLOW_SCALE = 0.014D;
    /** LivingEntity#BASE_SWIM_SPEED, the input LivingEntity#travelInWater moves by. */
    private static final double BASE_SWIM_SPEED = 0.02F;

    private static final UncertaintyHandler CIRCULAR =
            (player, valid, result, context, last, start, end) -> UncertaintyHelper.handleCircular(start, end, RADIUS);

    @Test
    public void circularAllowanceIsNotSpentOnAClippedAxis() {
        Vec3 target = new Vec3(CLIP_X, 0.0D, 0.2D);
        ValidMovements valid = selectAndClip(new Vec3(0.3D, 0.0D, 0.0D), target, clippedX(CLIP_X), CIRCULAR);
        assertEquals(0.17677669529663687D, valid.getClosestToTarget().z, 1.0E-12D);

        Vec3 retarget = valid.targetPastClippedAxes();
        assertEquals(new Vec3(0.3D, 0.0D, 0.2D), retarget);
        reselect(valid, retarget, clippedX(CLIP_X));

        assertEquals(CLIP_X, valid.getClosestToTarget().x, 1.0E-12D);
        assertEquals(0.2D, valid.getClosestToTarget().z, 1.0E-12D);
    }

    @Test
    public void negativeClipIsReselectedTowardsTheStartingVelocity() {
        Vec3 target = new Vec3(0.2D, 0.0D, -CLIP_X);
        ValidMovements valid = selectAndClip(new Vec3(0.0D, 0.0D, -0.3D), target, clippedZ(-CLIP_X), CIRCULAR);

        Vec3 retarget = valid.targetPastClippedAxes();
        assertEquals(new Vec3(0.2D, 0.0D, -0.3D), retarget);
        reselect(valid, retarget, clippedZ(-CLIP_X));

        assertEquals(0.2D, valid.getClosestToTarget().x, 1.0E-12D);
        assertEquals(-CLIP_X, valid.getClosestToTarget().z, 1.0E-12D);
    }

    @Test
    public void flushClipIsReselectedTowardsTheStartingVelocityOnItsSide() {
        UncertaintyHandler current = (player, valid, result, context, last, start, end) ->
                UncertaintyHelper.handleCircular(start, end, 0.014D);
        Vec3 start = new Vec3(0.8904352072270036D, 0.39998779222364655D, 0.7324055423304645D);
        Vec3 target = new Vec3(0.0D, 0.3999877922236479D, 0.7062298077853484D);
        ValidMovements valid = selectAndClip(start, target, flushX(1), current);
        assertEquals(0.7319941681581141D, valid.getClosestToTarget().z, 1.0E-12D);

        Vec3 retarget = valid.targetPastClippedAxes();
        assertEquals(new Vec3(start.x, target.y, target.z), retarget);
        reselect(valid, retarget, flushX(1));

        assertEquals(0.0D, valid.getClosestToTarget().x, 0.0D);
        assertEquals(0.7184055423304645D, valid.getClosestToTarget().z, 1.0E-12D);
    }

    @Test
    public void flushClipReselectionGrantsNoMoreThanTheAllowanceOnTheFreeAxis() {
        UncertaintyHandler current = (player, valid, result, context, last, start, end) ->
                UncertaintyHelper.handleCircular(start, end, WATER_FLOW_SCALE);
        Vec3 start = new Vec3(0.8904352072270036D, 0.39998779222364655D, 0.7324055423304645D);
        Vec3 target = new Vec3(0.0D, 0.3999877922236479D, 0.65D);
        ValidMovements valid = selectAndClip(start, target, flushX(1), current);

        reselect(valid, valid.targetPastClippedAxes(), flushX(1));

        double residual = valid.getClosestToTarget().z - target.z;
        assertTrue("residual " + residual, residual > BASE_SWIM_SPEED + WATER_FLOW_SCALE);
        assertEquals(0.7184055423304645D, valid.getClosestToTarget().z, 1.0E-12D);
    }

    @Test
    public void flushClipOnTheOtherSideKeepsTheTarget() {
        UncertaintyHandler current = (player, valid, result, context, last, start, end) ->
                UncertaintyHelper.handleCircular(start, end, 0.014D);
        Vec3 target = new Vec3(0.0D, 0.3999877922236479D, 0.7062298077853484D);
        ValidMovements valid = selectAndClip(
                new Vec3(0.8904352072270036D, 0.39998779222364655D, 0.7324055423304645D),
                target,
                flushX(-1),
                current);

        assertSame(target, valid.targetPastClippedAxes());
    }

    @Test
    public void targetOffTheClipKeepsTheTarget() {
        Vec3 target = new Vec3(0.15D, 0.0D, 0.2D);
        ValidMovements valid = selectAndClip(new Vec3(0.3D, 0.0D, 0.0D), target, clippedX(CLIP_X), CIRCULAR);

        assertSame(target, valid.targetPastClippedAxes());
    }

    @Test
    public void startingVelocityShortOfTheClipKeepsTheTarget() {
        Vec3 target = new Vec3(CLIP_X, 0.0D, 0.2D);
        ValidMovements valid = selectAndClip(new Vec3(0.05D, 0.0D, 0.0D), target, clippedX(CLIP_X), CIRCULAR);

        assertSame(target, valid.targetPastClippedAxes());
    }

    @Test
    public void unlikelyCollisionKeepsTheTarget() {
        Vec3 target = new Vec3(CLIP_X, 0.0D, 0.2D);
        CollideAxisData collisions = new CollideAxisData(
                new CollideAxisData.CollideResult(false, CLIP_X),
                null,
                null,
                new CollideAxisData.CollideResult(false, 0.0D),
                new ArrayList<>());
        ValidMovements valid = selectAndClip(new Vec3(0.3D, 0.0D, 0.0D), target, collisions, CIRCULAR);

        assertSame(target, valid.targetPastClippedAxes());
    }

    @Test
    public void deterministicTransformKeepsTheTarget() {
        Vec3 target = new Vec3(CLIP_X, 0.0D, 0.2D);
        UncertaintyHandler halve =
                (player, valid, result, context, last, start, end) -> start.withX(start.x * 0.5D, "halve");
        ValidMovements valid = selectAndClip(new Vec3(0.3D, 0.0D, 0.0D), target, clippedX(CLIP_X), halve);

        assertSame(target, valid.targetPastClippedAxes());
    }

    @Test
    public void circularStepOnlyOnTheFreeAxisKeepsTheTarget() {
        Vec3 target = new Vec3(CLIP_X, 0.0D, 0.2D);
        ValidMovements valid = selectAndClip(new Vec3(CLIP_X, 0.0D, 0.0D), target, clippedX(CLIP_X), CIRCULAR);

        assertSame(target, valid.targetPastClippedAxes());
    }

    @Test
    public void circularStepInTheStartingVelocityLineageDoesNotCount() {
        PredVector root = new PredVector(new Vec3(0.0D, 0.0D, 0.0D));
        PredVector starting = UncertaintyHelper.handleCircular(root, new Vec3(0.3D, 0.0D, 0.0D), 1.0D);
        PredVector moved = starting.withZ(0.2D, "deterministic");

        assertFalse(moved.reasonMovedAxisSince("circular", starting, true));
        assertTrue(moved.reasonMovedAxisSince("circular", null, true));
    }

    @Test
    public void reselectionThatEndsFartherRestoresTheFirstMovement() {
        Vec3 target = new Vec3(CLIP_X, 0.0D, 0.2D);
        ValidMovements valid = selectAndClip(new Vec3(0.3D, 0.0D, 0.0D), target, clippedX(CLIP_X), CIRCULAR);
        MovementTrace first = valid.getClosestTrace();
        CollideAxisData firstCollisions = valid.getResult().getCollideAxisData();

        CollideAxisData widerClip = clippedX(0.25D);
        reselect(valid, valid.targetPastClippedAxes(), widerClip);

        assertSame(first, valid.getClosestTrace());
        assertSame(firstCollisions, valid.getResult().getCollideAxisData());
    }

    private static ValidMovements selectAndClip(
            Vec3 start, Vec3 target, CollideAxisData collisions, UncertaintyHandler... handlers) {
        ValidMovements valid = validMovements(start, target, handlers);
        valid.computeCollisionIgnoredMovement(null);
        valid.getResult().setCollideAxisData(collisions);
        valid.applyCollisionsToSelectedMovement(null);
        return valid;
    }

    /** SimulationProcessor#reselectPastClippedAxes with {@code reprobed} as the probe's result. */
    private static void reselect(ValidMovements valid, Vec3 retarget, CollideAxisData reprobed) {
        MovementTrace firstTrace = valid.getClosestTrace();
        CollideAxisData firstCollisions = valid.getResult().getCollideAxisData();
        valid.computeCollisionIgnoredMovement(retarget, null);
        valid.getResult().setCollideAxisData(reprobed);
        valid.applyCollisionsToSelectedMovement(null);
        valid.keepNearerToTarget(firstTrace, firstCollisions);
    }

    private static CollideAxisData clippedZ(double clip) {
        return new CollideAxisData(
                new CollideAxisData.CollideResult(false, 0.0D),
                null,
                null,
                new CollideAxisData.CollideResult(true, clip),
                new ArrayList<>());
    }

    private static CollideAxisData flushX(int side) {
        return new CollideAxisData(
                new CollideAxisData.CollideResult(true, 0.0D, side),
                null,
                null,
                new CollideAxisData.CollideResult(false, 0.0D),
                new ArrayList<>());
    }

    private static ValidMovements validMovements(Vec3 start, Vec3 target, UncertaintyHandler... handlers) {
        PredVector startingVelocity = new PredVector(start);
        PredictionResult result =
                new PredictionResult(null, startingVelocity, context(), target, null, List.of(), List.of());
        return new ValidMovements(startingVelocity, null, result, false, List.of(handlers));
    }

    private static CollideAxisData clippedX(double clip) {
        return new CollideAxisData(
                new CollideAxisData.CollideResult(true, clip),
                null,
                null,
                new CollideAxisData.CollideResult(false, 0.0D),
                new ArrayList<>());
    }

    private static SimulationContext context() {
        return new SimulationContext(
                Vec3.ZERO,
                Vec3.ZERO,
                Vec3.ZERO,
                ClientVersion.V_1_21_11,
                null,
                null,
                null,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                true,
                false,
                false,
                false,
                null,
                0.0F,
                0,
                DesyncStatus.FALSE,
                0,
                0,
                0,
                null,
                false,
                null,
                false,
                null,
                null,
                Pose.STANDING,
                1.0F);
    }
}
