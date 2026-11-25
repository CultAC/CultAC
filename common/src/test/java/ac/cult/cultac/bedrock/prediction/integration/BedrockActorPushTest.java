package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import org.junit.jupiter.api.Test;

/**
 * A solid actor near the player can push it only away from itself, by no more than the actor's buffer or its
 * depenetration limit, and down by no more than a solid actor's limit: never up, and never toward the actor.
 */
class BedrockActorPushTest {
    @Test
    void anActorPushesOnlyAwayFromItselfWithinItsLimit() {
        var buffer = new BedrockSolidActorBuffer();
        // An actor west and north of the player with a large buffer: the push is capped at the 0.1 limit.
        buffer.touchedBy(5, 8.0F, BedrockDepenetrationLimit.SOLID_ACTOR, 9.0, 9.0, 10.0, 10.0);
        assertEquals(0.1F, buffer.actorPush(5, 0, true), 1.0E-6);
        assertEquals(0.0F, buffer.actorPush(5, 0, false), 0.0);
        assertEquals(0.1F, buffer.actorPush(5, 2, true), 1.0E-6);
        assertEquals(0.0F, buffer.actorPush(5, 2, false), 0.0);
        // Down only.
        assertEquals(0.1F, buffer.actorPush(5, 1, false), 1.0E-6);
        assertEquals(0.0F, buffer.actorPush(5, 1, true), 0.0);
    }

    @Test
    void aHappyGhastLiftsNothingAndASmallBufferPushesLess() {
        var buffer = new BedrockSolidActorBuffer();
        buffer.touchedBy(5, 0.03F, BedrockDepenetrationLimit.HAPPY_GHAST, 11.0, 11.0, 10.0, 10.0);
        assertEquals(0.03F, buffer.actorPush(5, 0, false), 1.0E-6);
        assertEquals(0.0F, buffer.actorPush(5, 0, true), 0.0);
        assertEquals(0.03F, buffer.actorPush(5, 1, false), 1.0E-6);
        assertEquals(0.0F, buffer.actorPush(5, 1, true), 0.0);
    }

    @Test
    void onlyTheTouchedTickIsPushed() {
        var buffer = new BedrockSolidActorBuffer();
        buffer.touchedBy(5, 0.5F, BedrockDepenetrationLimit.SOLID_ACTOR, 9.0, 9.0, 10.0, 10.0);
        assertEquals(0.0F, buffer.actorPush(6, 0, true), 0.0);
    }
}
