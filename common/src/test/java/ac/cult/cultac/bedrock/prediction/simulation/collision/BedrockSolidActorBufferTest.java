package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockSolidActorBuffer;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A moving boat or happy ghast that ends a move along an axis lets the move end up to its buffer either side of that
 * end: never back past the start, past the requested move or into a block. The buffer is a pool of 0.2 that refills by
 * 0.01 a tick and lends at most 0.05 to one tick.
 */
public final class BedrockSolidActorBufferTest {
    private static final Vec3d FEET = new Vec3d(0.5, 120.0, 0.5);
    private static final Vec3d NORTH = new Vec3d(0.0, 0.0, -1.0);

    @Test
    public void aMovingActorThatEndsTheMoveLetsItEndWithinItsBuffer() {
        var end = move(NORTH, ghastNorth(0.05F));

        assertEquals(-0.5D, moved(end).z(), 1.0E-6D);
        assertTrue(end.zCollision());
        var range = end.solidActorOrderRange();
        assertNotNull(range);
        assertEquals(4, range.bufferedAxes());
        assertEquals(-0.05D, range.minZ(), 1.0E-6D);
        assertEquals(0.05D, range.maxZ(), 1.0E-6D);
        assertEquals(0.0D, range.minX(), 0.0D);
        assertEquals(0.0D, range.maxX(), 0.0D);
        // The client stopped 0.0031 short of Cult's end, inside the buffer: charged that much.
        Vec3d own = moved(end);
        Vec3d claimed = own.add(new Vec3d(0.0, 0.0, 0.0031));
        assertEquals(0.0031D, range.buffered(own, claimed), 1.0E-9D);
        // A claim past the buffer is taken only to its edge.
        assertEquals(0.05D, range.buffered(own, own.add(new Vec3d(0.0, 0.0, 0.2))), 1.0E-6D);
    }

    @Test
    public void landingOnAMovingBoatLetsTheFallEndWithinItsBuffer() {
        // Feet 0.1 above the boat's top: the fall ends on it, and the client's boat may stand a step higher or lower.
        var end = move(new Vec3d(0.0, -0.5, 0.0), boatBelow(119.9F, 0.05F));

        assertEquals(-0.1D, moved(end).y(), 1.0E-5D);
        var range = end.solidActorOrderRange();
        assertNotNull(range);
        assertEquals(2, range.bufferedAxes());
        assertEquals(-0.05D, range.minY(), 1.0E-5D);
        assertEquals(0.05D, range.maxY(), 1.0E-5D);
        // Standing 0.02 above Cult's top is charged 0.02, and no more than the buffer.
        Vec3d own = moved(end);
        assertEquals(0.02D, range.buffered(own, own.add(new Vec3d(0.0, 0.02, 0.0))), 1.0E-6D);
        assertEquals(0.05D, range.buffered(own, own.add(new Vec3d(0.0, 0.3, 0.0))), 1.0E-5D);
    }

    @Test
    public void anActorThatIsNotMovingGivesNoBuffer() {
        var end = move(NORTH, ghastNorth(0.0F));

        assertEquals(-0.5D, moved(end).z(), 1.0E-6D);
        assertNull(end.solidActorOrderRange());
    }

    @Test
    public void theBufferStopsAtABlockBeyondTheActorsFace() {
        // A block face 0.02 past the actor's face, inside the actor: the move may go 0.02 further, not 0.05.
        var end = move(NORTH, block(0, 120, -2, -0.32F), ghastNorth(0.05F));

        var range = end.solidActorOrderRange();
        assertNotNull(range);
        assertEquals(-0.02D, range.minZ(), 1.0E-6D);
        assertEquals(0.05D, range.maxZ(), 1.0E-6D);
    }

    @Test
    public void aBlockThatEndsTheMoveAfterTheActorTakesTheBufferBack() {
        var end = move(NORTH, block(0, 120, -1, -0.1F), ghastNorth(0.05F));

        assertEquals(-0.3D, moved(end).z(), 1.0E-6D);
        assertNull(end.solidActorOrderRange());
    }

    @Test
    public void thePoolRefillsATickAtATimeAndLendsAtMostItsPerTickCap() {
        var buffer = new BedrockSolidActorBuffer();
        assertEquals(0.05F, buffer.available(1), 0.0F);
        for (long tick = 1; tick <= 4; tick++) buffer.charge(tick, 0.05D);
        // 0.2 less four ticks of 0.05, with 0.01 back each later tick.
        assertEquals(0.04F, buffer.available(5), 1.0E-6F);
        // Charging a tick again replaces its charge.
        buffer.charge(5, 0.04D);
        buffer.charge(5, 0.01D);
        assertEquals(0.04F, buffer.available(6), 1.0E-6F);
        // It refills to its maximum and no further.
        assertEquals(0.05F, buffer.available(1000), 0.0F);
        buffer.charge(1000, 0.0D);
        buffer.charge(1001, 0.5D);
        assertEquals(0.05F, buffer.available(1002), 1.0E-6F);
    }

    private static Vec3d moved(BedrockEntityMove.Result end) {
        return end.position().subtract(FEET);
    }

    /** Blocks first, then actors, as the world lists them. */
    private static BedrockEntityMove.Result move(Vec3d delta, PlacedBlockCollision... shapes) {
        var blocks = new ArrayList<PlacedBlockCollision>(List.of(shapes));
        var start = BedrockMovementState.fromPhysicalFeet(
                FEET, delta, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        var dimensions = PlayerDimensionsState.DEFAULT;
        var fetchBox = BedrockCollisionFetchBox.of(
                start.collisionBox(dimensions),
                delta,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                start.coordinateFrame());
        return BedrockEntityMove.move(
                start,
                BedrockInputFrame.idle(1),
                new BlockCollisionWorld(blocks),
                dimensions,
                FEET.add(delta),
                delta,
                delta.y(),
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                false,
                fetchBox,
                0);
    }

    /** A happy ghast north of the player whose south face is at z -0.3, half a block from the player's box. */
    private static PlacedBlockCollision ghastNorth(float buffer) {
        return BedrockDepenetrationLimit.HAPPY_GHAST.solidActor(
                new WorldCollisionBox(-1.5F, 118.0F, -4.3F, 2.5F, 122.0F, -0.3F), buffer);
    }

    /** A boat 1.6 wide under the player's feet, 0.1 below them, whose top is at {@code top}. */
    private static PlacedBlockCollision boatBelow(float top, float buffer) {
        return BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                new WorldCollisionBox(-0.3F, top - 0.6F, -0.3F, 1.3F, top, 1.3F), buffer);
    }

    /** A stone block whose south face is moved to {@code maxZ}. */
    private static PlacedBlockCollision block(int x, int y, int z, float maxZ) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(x, y, z, x + 1, y + 1, maxZ)));
    }
}
