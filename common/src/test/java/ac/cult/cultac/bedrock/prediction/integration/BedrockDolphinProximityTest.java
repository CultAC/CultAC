package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import org.junit.Test;

/** The client's dolphin scan meets the dolphins whose boxes meet the player's box grown by five blocks on every side. */
public final class BedrockDolphinProximityTest {
    private static final Vec3d FEET = new Vec3d(0.5D, 60.0D, 0.5D);
    private static final PlayerDimensionsState SWIMMING =
            new PlayerDimensionsState(0.6000000238418579D, 0.6000000238418579D);

    @Test
    public void aDolphinWithinFiveBlocksOfThePlayersBoxIsNear() {
        assertTrue(near(dolphin(0.5D + 0.3D + 4.9D + 0.45D, 60.0D, 0.5D)));
        assertTrue(near(dolphin(0.5D, 60.0D + 0.6D + 4.9D, 0.5D)));
    }

    @Test
    public void aDolphinPastFiveBlocksFromThePlayersBoxIsNotNear() {
        assertFalse(near(dolphin(0.5D + 0.3D + 5.1D + 0.45D, 60.0D, 0.5D)));
        assertFalse(near(dolphin(0.5D, 60.0D, 0.5D - 0.3D - 5.1D - 0.45D)));
        assertFalse(near(dolphin(0.5D, 60.0D + 0.6D + 5.1D, 0.5D)));
    }

    private static boolean near(SimpleCollisionBox dolphin) {
        return BedrockEntityContactFactory.isWithinDolphinProximity(FEET, SWIMMING, dolphin);
    }

    private static SimpleCollisionBox dolphin(double x, double y, double z) {
        return new SimpleCollisionBox(x - 0.45D, y, z - 0.45D, x + 0.45D, y + 0.6D, z + 0.45D);
    }
}
