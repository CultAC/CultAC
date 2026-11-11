package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import org.junit.Test;

public final class BedrockVerticalCollisionClassifierTest {
    @Test
    public void openAirCollisionClaimIsManufactured() {
        assertEquals(
                BedrockVerticalCollisionVerdict.MANUFACTURED_COLLISION,
                BedrockVerticalCollisionClassifier.classify(
                        true, collision(false, 0.0D, false, 0.0D), movement(-0.08D, -0.08D), -0.08D));
    }

    @Test
    public void requiredDownwardCollisionRejectsManufacturedNonCollision() {
        assertEquals(
                BedrockVerticalCollisionVerdict.MANUFACTURED_NON_COLLISION,
                BedrockVerticalCollisionClassifier.classify(
                        false, collision(false, 0.0D, true, 0.0D), movement(-0.08D, -0.08D), 0.0D));
    }

    @Test
    public void ceilingCollisionIsLegalWithoutGround() {
        assertEquals(
                BedrockVerticalCollisionVerdict.LEGAL,
                BedrockVerticalCollisionClassifier.classify(
                        true, collision(true, 0.0D, false, 0.0D), movement(0.42D, 0.42D), 0.0D));
    }

    private static CollideAxisData collision(boolean up, double upResult, boolean down, double downResult) {
        return new CollideAxisData(
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(up, upResult),
                new CollideAxisData.CollideResult(down, downResult),
                new CollideAxisData.CollideResult(false, 0.0D));
    }

    private static SimpleCollisionBox movement(double minY, double maxY) {
        return new SimpleCollisionBox(0.0D, minY, 0.0D, 0.0D, maxY, 0.0D);
    }
}
