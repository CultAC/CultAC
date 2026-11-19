package ac.cult.cultac.bedrock.prediction.simulation.frame;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import org.junit.Test;

/**
 * A capture of a boat paddled forward on water while its rider is taken off and put back on the same tick: the boat
 * moved 0.369776 along z and 0.009076 along x that tick, and on its first tick with the rider back it moves 0.335396
 * and 0.008235, the paddles pushing with half their strength and no turn. The tick after, both paddles start a new
 * stroke.
 */
public final class BedrockBoatPaddleReleaseTest {
    private static final Vec3d MOVED = new Vec3d(0.009078F, 0.0F, 0.369776F);
    private static final BedrockBoatMovement.Surface WATER = new BedrockBoatMovement.Surface(0.9F, false, 0);

    @Test
    public void aBoatWithoutItsRidersInputReleasesBothPaddles() {
        var step = BedrockBoatMovement.control(boat(BedrockBoatState.PaddleInput.NONE), MOVED, WATER, Vec3d.ZERO);

        assertEquals(0.335396F, (float) step.velocity().z(), 2.0E-6F);
        assertEquals(0.008235F, (float) step.velocity().x(), 2.0E-6F);
        assertEquals(88.59375F, step.yaw(), 0);
        assertEquals(1.45F, step.state().left().strength(), 1.0E-6F);
        assertEquals(1.45F, step.state().right().strength(), 1.0E-6F);
        assertEquals(-1, step.state().left().strokeStart());
        assertEquals(-1, step.state().right().strokeStart());
    }

    @Test
    public void theRidersHeldForwardInputKeepsBothStrokesGoing() {
        var step = BedrockBoatMovement.control(
                boat(BedrockBoatState.PaddleInput.ANALOG), MOVED, WATER, new Vec3d(0, 0, 1));

        assertEquals(2.9F, step.state().left().strength(), 1.0E-6F);
        assertEquals(0.371272F, (float) step.velocity().z(), 2.0E-6F);
    }

    /**
     * The same ride turning: the tick before its rider is put back the boat turns by -4.5829 degrees to yaw -3.5519 and
     * moves (0.26693, -0.00445). The link that seats the rider carries an angular velocity of 0, and on that tick the
     * boat keeps its yaw and moves (0.25203, -0.00582).
     */
    @Test
    public void theLinkSeatingTheRiderStopsTheBoatTurning() {
        var stroke = new BedrockBoatState.Paddle(-1, 15, 2.9F);
        var turning = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(20.0, 118.6750, 12.3),
                        Vec3d.ZERO,
                        BedrockInputFrame.idle(2873),
                        BedrockCollisionFlags.AIR)
                .withBoat(new BedrockBoatState(
                        null, BedrockBoatProperties.initial(12), -4.5829F, 0, 20, stroke, stroke,
                        BedrockBoatState.PaddleInput.NONE))
                .withRotation(-3.5519F, 0);
        var seated = turning.withBoat(turning.boat().withAngularVelocity(0.0F));
        var moved = new Vec3d(0.26693F, 0.0F, -0.00445F);

        var step = BedrockBoatMovement.control(seated, moved, WATER, Vec3d.ZERO);

        assertEquals(-3.5519F, step.yaw(), 1.0E-6F);
        assertEquals(0.25203F, (float) step.velocity().x(), 2.0E-5F);
        assertEquals(-0.00582F, (float) step.velocity().z(), 2.0E-5F);
        // Still turning, the boat would have kept rotating.
        assertEquals(-7.2642F, BedrockBoatMovement.control(turning, moved, WATER, Vec3d.ZERO).yaw(), 1.0E-3F);
    }

    private static BedrockMovementState boat(BedrockBoatState.PaddleInput input) {
        var stroke = new BedrockBoatState.Paddle(-1, 15, 2.9F);
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(17.628810, 118.6750, 7.644210),
                        MOVED,
                        BedrockInputFrame.idle(14794),
                        BedrockCollisionFlags.AIR)
                .withBoat(new BedrockBoatState(
                        null, BedrockBoatProperties.initial(12), 0, 0, 20, stroke, stroke, input))
                .withRotation(88.59375F, 0);
    }
}
