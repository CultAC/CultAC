package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A capture of a ridden horse pressed against a wall at scale 0.5: on the tick its scale returns to 1.0 the client
 * reports it 0.195463 further along x and 0.349125 back along z, flush with both blocks its grown box overlapped,
 * with no x or z velocity. No packet carries that move; the client's own swept move leaves each block through its
 * nearest face.
 */
public final class BedrockVehicleDepenetrationTest {
    private static final float HORSE_WIDTH = 1.3965F;
    private static final float HORSE_HEIGHT = 1.6F;
    private static final Vec3d PRESSED = new Vec3d(25.502789, 120.0, 33.650875);

    @Test
    public void aHorseThatGrowsIntoAWallLeavesItOnTheSameTick() {
        var horse = horse(new PlayerDimensionsState(HORSE_WIDTH * 0.5F, HORSE_HEIGHT * 0.5F));
        var grown = new PlayerDimensionsState(HORSE_WIDTH, HORSE_HEIGHT);
        Vec3d requested = new Vec3d(2.3806033E-5, -0.0784, 0.03);

        var result = BedrockEntityMove.move(
                horse,
                BedrockInputFrame.idle(20628),
                wall(),
                grown,
                PRESSED.add(requested),
                requested,
                requested.y(),
                true,
                1.0625D,
                false);

        // The engine leaves the block in its vertical phase and then still applies the 0.0000238 it was asked to
        // move along x; the client reported exactly the flush position.
        assertEquals(25.698252F, (float) result.position().x(), 3.0E-5F);
        assertEquals(120.0F, (float) result.position().y(), 0);
        assertEquals(33.30175F, (float) result.position().z(), 2.0E-6F);
        assertEquals(0.0, result.velocity().x(), 0);
        assertEquals(0.0, result.velocity().z(), 0);
        assertFalse(result.steppedUp());
        assertTrue(result.onGround());
        // Flush with the wall face at z=34 and the block face at x=25.
        assertEquals(34.0F, (float) result.collisionBox().maxZ(), 2.0E-6F);
        assertEquals(25.0F, (float) result.collisionBox().minX(), 3.0E-5F);
    }

    @Test
    public void theSmallHorseRestsAgainstTheWallWithoutBeingMoved() {
        var small = new PlayerDimensionsState(HORSE_WIDTH * 0.5F, HORSE_HEIGHT * 0.5F);
        Vec3d requested = new Vec3d(2.3806033E-5, -0.0784, 0.03);

        var result = BedrockEntityMove.move(
                horse(small),
                BedrockInputFrame.idle(20627),
                wall(),
                small,
                PRESSED.add(requested),
                requested,
                requested.y(),
                true,
                1.0625D,
                false);

        assertEquals(33.650875F, (float) result.position().z(), 2.0E-6F);
        assertEquals(25.502789F + 2.3806033E-5F, (float) result.position().x(), 2.0E-6F);
    }

    /**
     * A capture of a boat teleported 4 blocks up into the top of a wall: the client takes the placement at z 2.396726,
     * its box 0.084226 into the block face at z=3, and on that tick reports it at z 2.3125, flush with the face.
     */
    @Test
    public void aBoatPlacedIntoABlockLeavesItThroughTheNearestFace() {
        var boatSize = new PlayerDimensionsState(1.375F, 0.5625F);
        var placed = boat(BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(17.5, 122.69533, 2.396726),
                        Vec3d.ZERO,
                        BedrockInputFrame.idle(0),
                        BedrockCollisionFlags.AIR))
                .withPlayerDimensions(boatSize, true);
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (int x = 16; x <= 18; x++) blocks.add(stone(x, 122, 3));
        Vec3d requested = new Vec3d(0.0, -0.015, 0.0);

        var result = BedrockEntityMove.move(
                placed,
                BedrockInputFrame.idle(10914),
                new BlockCollisionWorld(blocks),
                boatSize,
                placed.physicalFeetPosition().add(requested),
                requested,
                requested.y());

        assertEquals(2.3125F, (float) result.position().z(), 2.0E-6F);
        assertEquals(3.0F, (float) result.collisionBox().maxZ(), 2.0E-6F);
        assertEquals(0.0, result.velocity().z(), 0);
    }

    @Test
    public void everyRiddenVehicleResolvesADeepOverlapInItsMove() {
        var standing = BedrockMovementState.fromPhysicalFeet(
                PRESSED, Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND);
        var boat = boat(standing);

        assertEquals(1.0F, BedrockCollisionClipper.maximumDepenetration(horse(PlayerDimensionsState.DEFAULT)), 0);
        assertEquals(1.0F, BedrockCollisionClipper.maximumDepenetration(boat), 0);
        assertEquals(0.01F, BedrockCollisionClipper.maximumDepenetration(standing), 0);
    }

    private static BedrockMovementState boat(BedrockMovementState state) {
        return state.withBoat(new BedrockBoatState(
                null,
                BedrockBoatProperties.initial(12),
                0,
                0,
                0,
                BedrockBoatState.Paddle.INITIAL,
                BedrockBoatState.Paddle.INITIAL,
                true));
    }

    private static BedrockMovementState horse(PlayerDimensionsState dimensions) {
        return BedrockMovementState.fromPhysicalFeet(
                        PRESSED,
                        new Vec3d(2.3806033E-5, -0.0784, 0.0),
                        BedrockInputFrame.idle(0),
                        BedrockCollisionFlags.ON_GROUND)
                .withHorse(new BedrockHorseState(null, false, 0, false, false, 0, -1, false, 0))
                .withPlayerDimensions(dimensions, true);
    }

    /** Ground under the horse and a wall two blocks high along z=34, ending at x=26. */
    private static BlockCollisionWorld wall() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (int x = 22; x <= 28; x++) {
            for (int z = 30; z <= 36; z++) {
                blocks.add(stone(x, 119, z));
            }
        }
        for (int y = 120; y <= 121; y++) {
            blocks.add(stone(24, y, 34));
            blocks.add(stone(25, y, 34));
        }
        return new BlockCollisionWorld(blocks);
    }

    private static PlacedBlockCollision stone(int x, int y, int z) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(x, y, z, x + 1, y + 1, z + 1)));
    }
}
