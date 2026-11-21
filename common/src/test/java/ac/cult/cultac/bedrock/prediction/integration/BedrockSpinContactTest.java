package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import org.junit.Test;

/**
 * A riptide spin meets the mobs whose boxes meet the player's box grown by a block on every side, at the box the tick
 * starts with. A spin through a school of four fish met the two within that reach and left at a twenty-fifth of its
 * speed.
 */
public final class BedrockSpinContactTest {
    private static final PlayerDimensionsState SPINNING =
            new PlayerDimensionsState(0.6000000238418579D, 0.6000000238418579D);

    @Test
    public void aSpinMeetsTheMobsWithinABlockOfTheBoxItsTickStartsWith() {
        SimpleCollisionBox reach = reach(new Vec3d(2791.541748046875D, 59.89390182495117D, 1424.643310546875D));

        assertTrue(BedrockEntityContactFactory.strictlyMeets(fish(2792.483D, 60.261497D, 1424.6157D), reach));
        assertTrue(BedrockEntityContactFactory.strictlyMeets(fish(2792.3677D, 61.01985D, 1425.0438D), reach));
        assertFalse(BedrockEntityContactFactory.strictlyMeets(fish(2793.5073D, 60.853626D, 1425.9001D), reach));
        assertFalse(BedrockEntityContactFactory.strictlyMeets(fish(2792.4958D, 60.682312D, 1426.5219D), reach));
    }

    @Test
    public void theTickBeforeMetNoneOfThem() {
        SimpleCollisionBox reach = reach(new Vec3d(2789.873D, 59.4688D, 1422.9D));

        assertFalse(BedrockEntityContactFactory.strictlyMeets(fish(2792.4827D, 60.231304D, 1424.6259D), reach));
        assertFalse(BedrockEntityContactFactory.strictlyMeets(fish(2792.3674D, 61.03141D, 1425.093D), reach));
    }

    @Test
    public void aBoxThatOnlyTouchesTheReachIsNotMet() {
        SimpleCollisionBox reach = reach(new Vec3d(0.5D, 64.0D, 0.5D));

        assertFalse(BedrockEntityContactFactory.strictlyMeets(
                new SimpleCollisionBox(reach.maxX, 64.0D, 0.0D, reach.maxX + 0.5D, 64.5D, 1.0D), reach));
        assertTrue(BedrockEntityContactFactory.strictlyMeets(
                new SimpleCollisionBox(reach.maxX - 1.0E-4D, 64.0D, 0.0D, reach.maxX + 0.5D, 64.5D, 1.0D), reach));
    }

    private static SimpleCollisionBox reach(Vec3d feet) {
        return BedrockEntityContactFactory.grownPlayerBox(feet, SPINNING, 1.0F, BedrockCoordinateFrame.IDENTITY);
    }

    private static SimpleCollisionBox fish(double x, double y, double z) {
        return new SimpleCollisionBox(x - 0.25D, y, z - 0.25D, x + 0.25D, y + 0.3D, z + 0.25D);
    }
}
