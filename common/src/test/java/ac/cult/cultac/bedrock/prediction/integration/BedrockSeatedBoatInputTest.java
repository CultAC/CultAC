package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockTickInput;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import java.util.UUID;
import org.junit.Test;

/**
 * A capture of a boat whose rider is put back while it still turns by -2.4942 degrees a tick: on that tick it keeps
 * its yaw, the link that seats the rider carrying an angular velocity of 0. Every candidate the tick starts from must
 * start with it.
 */
public final class BedrockSeatedBoatInputTest {
    @Test
    public void everyCandidateOfTheSeatingTickStartsWithTheLinksAngularVelocity() {
        var carried = boat(-2.4942F, BedrockBoatState.PaddleInput.ANALOG);
        var seated = boat(0.0F, BedrockBoatState.PaddleInput.NONE);
        var input = new BedrockMovementInputFactory.Input(
                BedrockAuthInputFrame.builder(UUID.randomUUID()).protocolVersion(944).build(),
                seated,
                new BedrockTickInput(BedrockInputFrame.idle(8280), seated.physicalFeetPosition(), 8280),
                BedrockActorHistoryTest.ground());

        var candidate = input.withPreviousState(carried, BedrockMobJumpComponentState.DEFAULT).previousState();

        assertEquals(0.0F, candidate.boat().angularVelocity(), 0);
        assertEquals(BedrockBoatState.PaddleInput.NONE, candidate.boat().paddleInput());
    }

    @Test
    public void aTickWithTheRidersInputKeepsTheCandidatesTurn() {
        var carried = boat(-2.4942F, BedrockBoatState.PaddleInput.ANALOG);
        var frame = boat(0.0F, BedrockBoatState.PaddleInput.ANALOG);
        var input = new BedrockMovementInputFactory.Input(
                BedrockAuthInputFrame.builder(UUID.randomUUID()).protocolVersion(944).build(),
                frame,
                new BedrockTickInput(BedrockInputFrame.idle(8279), frame.physicalFeetPosition(), 8279),
                BedrockActorHistoryTest.ground());

        var candidate = input.withPreviousState(carried, BedrockMobJumpComponentState.DEFAULT).previousState();

        assertEquals(-2.4942F, candidate.boat().angularVelocity(), 0);
    }

    private static BedrockMovementState boat(float angular, BedrockBoatState.PaddleInput input) {
        var stroke = new BedrockBoatState.Paddle(-1, 15, 2.9F);
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(289, 82, -86), Vec3d.ZERO, BedrockInputFrame.idle(8279), BedrockCollisionFlags.AIR)
                .withBoat(new BedrockBoatState(
                        null, BedrockBoatProperties.initial(12), angular, 0, 20, stroke, stroke, input))
                .withRotation(-7.3796F, 0);
    }
}
