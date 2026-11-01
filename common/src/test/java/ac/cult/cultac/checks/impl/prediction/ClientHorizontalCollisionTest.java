package ac.cult.cultac.checks.impl.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#shouldStopRunSprinting stops sprinting on {@code horizontalCollision && !minorHorizontalCollision}, and
 * Entity#move takes the minor flag from LocalPlayer#isHorizontalCollisionMinor: the angle between the key input and
 * the collided movement is below 8 degrees.
 */
public class ClientHorizontalCollisionTest {
    private static final float SPRINT_SPEED = 0.13F;

    @Test
    void aBlockedMoveCannotBeToldFromANegligibleInput() {
        // A fully blocked move is never minor, but an input too short to pass the wall's tolerance does not collide.
        assertNotEquals(DesyncStatus.TRUE, classify(0, Vec3.ZERO, Vec3.ZERO, wallAtPositiveZ()));
        assertNotEquals(
                DesyncStatus.TRUE, classify(0, new Vec3(0, 0, 0.2), new Vec3(0, 0, 0.05), wallAtPositiveZ(0.05)));
    }

    @Test
    void aWallThatLeavesTheMoveAlongTheInputIsMinor() {
        // Facing yaw 0 with momentum (0.3, 0.2), W into a +X wall: the collided movement points straight forward.
        Vec3 momentum = new Vec3(0.3, 0, 0.2);
        Vec3 attempted = momentum.add(input(0, 0, 1, 0.13));
        assertEquals(DesyncStatus.FALSE, classify(0, momentum, new Vec3(0, 0, attempted.z), wallAtPositiveX()));
    }

    @Test
    void slidingAlongAWallWithin8DegreesOfTheInputIsMinor() {
        // Facing yaw 85, W points 5 degrees off -X into the wall; the -X slide stays within 8 degrees of it.
        Vec3 slide = new Vec3(-0.2, 0, 0);
        Vec3 attempted = slide.add(input(85, 0, 1, 0.13));
        assertNotEquals(DesyncStatus.TRUE, classify(85, slide, new Vec3(attempted.x, 0, 0), wallAtPositiveZ()));
    }

    @Test
    void slidingAlongAWallMoreThan8DegreesFromTheInputIsHard() {
        // Facing yaw 80, W points 10 degrees off the -X slide, so pushing into the wall stops the sprint.
        Vec3 slide = new Vec3(-0.2, 0, 0);
        Vec3 attempted = slide.add(input(80, 0, 1, 0.13));
        assertEquals(DesyncStatus.TRUE, classify(80, slide, new Vec3(attempted.x, 0, 0), wallAtPositiveZ()));
    }

    @Test
    void anInputThatOnlyTheWallAbsorbedCannotBeTold() {
        // At yaw 0, W into the wall moves exactly like no input at all, which does not collide.
        assertEquals(DesyncStatus.UNKNOWN, classify(0, new Vec3(0.25, 0, 0), new Vec3(0.25, 0, 0), wallAtPositiveZ()));
    }

    @Test
    void turningIntoAWallAgainstTheMomentumIsHard() {
        // Facing yaw 30 while sliding +X, every input that explains the slowed X pushes into the wall at a wide angle.
        Vec3 slide = new Vec3(0.3, 0, 0);
        Vec3 attempted = slide.add(input(30, 0, 1, 0.13));
        assertEquals(DesyncStatus.TRUE, classify(30, slide, new Vec3(attempted.x, 0, 0), wallAtPositiveZ()));
    }

    @Test
    void aCollisionProbeAwayFromTheMoveIsNoWall() {
        // Sprint-jumping under a ceiling: the envelope's X probe met a block the reported move never reached.
        CollideAxisData collide = collide(new CollideResult(true, 0.4, 1), new CollideResult(false, 0.3, 0));
        assertEquals(DesyncStatus.FALSE, classify(0, new Vec3(0, 0, 0.3), new Vec3(0.1, 0.2, 0.3), collide));
    }

    public static Vec3 input(float yaw, int strafe, int forward, double length) {
        double radians = yaw * (Math.PI / 180.0);
        double norm = Math.sqrt(strafe * strafe + forward * forward);
        return new Vec3(
                (strafe * Math.cos(radians) - forward * Math.sin(radians)) / norm * length,
                0,
                (forward * Math.cos(radians) + strafe * Math.sin(radians)) / norm * length);
    }

    static CollideAxisData wallAtPositiveX() {
        return collide(new CollideResult(true, 0, 1), new CollideResult(false, 1, 0));
    }

    public static CollideAxisData wallAtPositiveZ() {
        return wallAtPositiveZ(0);
    }

    static CollideAxisData wallAtPositiveZ(double flushZ) {
        return collide(new CollideResult(false, 1, 0), new CollideResult(true, flushZ, 1));
    }

    static CollideAxisData collide(CollideResult x, CollideResult z) {
        return new CollideAxisData(x, new CollideResult(false, 0), new CollideResult(false, 0), z, List.of());
    }

    public static PredictionResult result(float yaw, Vec3 attemptedMomentum, Vec3 target, CollideAxisData collide) {
        SimulationContext context = mock(SimulationContext.class);
        doReturn(yaw).when(context).getXRot();
        doReturn(SPRINT_SPEED * 1.3D).when(context).getHorizontalInputRadius(any(), any());
        ValidMovements valid = mock(ValidMovements.class);
        PredVector position = new PredVector(attemptedMomentum);
        doReturn(new MovementTrace(position, Vec3.ZERO, Vec3.ZERO, attemptedMomentum, attemptedMomentum))
                .when(valid)
                .getClosestTrace();
        PredictionResult result =
                new PredictionResult(null, new PredVector(Vec3.ZERO), context, target, null, List.of(), List.of());
        result.setValidMovements(valid);
        result.setCollideAxisData(collide);
        return result;
    }

    private static DesyncStatus classify(float yaw, Vec3 attemptedMomentum, Vec3 target, CollideAxisData collide) {
        return ClientHorizontalCollision.hard(result(yaw, attemptedMomentum, target, collide));
    }
}
