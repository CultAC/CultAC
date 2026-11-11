package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionFetchBox;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import org.junit.Test;

public final class BedrockCollisionSampleRegionTest {
    // A capture of a riptide III release into a wall: the player stands at Z 31.5, the wall is the block column
    // at Z 34, and the client stops against it at Z 33.7 on the release tick.
    private static final WorldCollisionBox ACTOR = new WorldCollisionBox(41.2F, 118.0F, 31.2F, 41.8F, 119.8F, 31.8F);
    private static final SimpleCollisionBox ACTOR_BOX =
            new SimpleCollisionBox(41.2F, 118.0F, 31.2F, 41.8F, 119.8F, 31.8F);
    private static final double RESTING_SPEED = 0.0784D;

    @Test
    public void regionForTheCarriedVelocityHoldsAnOrdinaryMove() {
        BedrockCollisionSampleRegion region = BedrockCollisionSampleRegion.around(ACTOR_BOX, RESTING_SPEED);
        WorldCollisionBox walk = fetch(new Vec3d(0.0D, -0.0784D, 0.13D));

        assertTrue(region.holds(walk));
        assertEquals(33, (int) Math.floor(region.maxZ()));
    }

    @Test
    public void riptideReleaseFetchesTheWallBeyondTheCarriedVelocityRegion() {
        BedrockCollisionSampleRegion region = BedrockCollisionSampleRegion.around(ACTOR_BOX, RESTING_SPEED);
        WorldCollisionBox release = fetch(new Vec3d(0.0D, 0.075D, 3.0D));

        assertFalse(region.holds(release));
        BedrockCollisionSampleRegion widened = region.including(release);
        assertTrue(widened.holds(release));
        assertTrue(Math.floor(widened.maxZ()) >= 34);
        // Only the side the move leaves through grows.
        assertEquals(region.minX(), widened.minX(), 0.0D);
        assertEquals(region.maxX(), widened.maxX(), 0.0D);
        assertEquals(region.minZ(), widened.minZ(), 0.0D);
        assertEquals(region.minY(), widened.minY(), 0.0D);
    }

    @Test
    public void carriedVelocityRegionKeepsItsBounds() {
        assertEquals(31.8F + 2.0D, BedrockCollisionSampleRegion.around(ACTOR_BOX, 0.0D).maxZ(), 0.0D);
        assertEquals(31.8F + 5.0D, BedrockCollisionSampleRegion.around(ACTOR_BOX, 3.0D).maxZ(), 0.0D);
        assertEquals(31.8F + 16.0D, BedrockCollisionSampleRegion.around(ACTOR_BOX, 40.0D).maxZ(), 0.0D);
        assertEquals(31.8F + 2.0D, BedrockCollisionSampleRegion.around(ACTOR_BOX, Double.NaN).maxZ(), 0.0D);
    }

    private static WorldCollisionBox fetch(Vec3d move) {
        return BedrockCollisionFetchBox.of(
                ACTOR, move, BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockCoordinateFrame.IDENTITY);
    }
}
