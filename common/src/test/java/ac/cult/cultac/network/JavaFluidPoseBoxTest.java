package ac.cult.cultac.network;

import static ac.cult.cultac.network.FluidPools.FORWARD;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** EntityFluidInteraction#update finds the water in the player's whole box, at the pose and scale it has. */
class JavaFluidPoseBoxTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final float LAND_SPEED = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
    private static final float WATER_SPEED = 0.02F;
    private static final float SLOW = 0.3F;
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);

    @Test
    void waterAtHeadHeightHoldsAStandingPlayer() throws Exception {
        assertFalse(exact(walk(1.0F, FLOOR_Y + 1, LAND_SPEED)));
        PredictionResult swim = walk(1.0F, FLOOR_Y + 1, WATER_SPEED);
        assertTrue(exact(swim), describe(swim));
    }

    @Test
    void waterAboveAHalfScaledPlayerLeavesItOnLand() throws Exception {
        PredictionResult land = walk(0.5F, FLOOR_Y + 1, LAND_SPEED);
        assertTrue(exact(land), describe(land));
        assertFalse(exact(walk(0.5F, FLOOR_Y + 1, WATER_SPEED)));
    }

    @Test
    void waterThreeBlocksUpHoldsADoubleScaledPlayer() throws Exception {
        assertFalse(exact(walk(2.0F, FLOOR_Y + 3, LAND_SPEED)));
        PredictionResult swim = walk(2.0F, FLOOR_Y + 3, WATER_SPEED);
        assertTrue(exact(swim), describe(swim));
    }

    @Test
    void waterAtHeadHeightHoldsACrouchingPlayer() throws Exception {
        assertFalse(exact(crouchWalk(LAND_SPEED)));
        PredictionResult swim = crouchWalk(WATER_SPEED);
        assertTrue(exact(swim), describe(swim));
    }

    @Test
    void waterAboveACrawlingPlayerLeavesItOnLand() throws Exception {
        PredictionResult land = crawl(BlockIds.WATER, FLOOR_Y + 1, LAND_SPEED);
        assertTrue(exact(land), describe(land));
        assertFalse(exact(crawl(BlockIds.WATER, FLOOR_Y + 1, WATER_SPEED)));
    }

    @Test
    void aCrawlThroughAOneHighWaterTunnelIsInWater() throws Exception {
        PredictionResult swim = crawl(BlockIds.STONE, FLOOR_Y, WATER_SPEED);
        assertTrue(exact(swim), describe(swim));
        assertFalse(exact(crawl(BlockIds.STONE, FLOOR_Y, LAND_SPEED)));
    }

    /** One forward tick from rest on the floor at {@code speed}, under a water layer at {@code waterY}. */
    private static PredictionResult walk(float scale, int waterY, float speed) throws Exception {
        try (var f = new JavaTickFixture()) {
            layer(f, waterY, BlockIds.WATER);
            FluidPools.settle(f);
            f.player.compensatedEntities.getSelf().scale = scale;
            f.player.dimensionScale = scale;
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.place(START, Vec3.ZERO);
            f.input(FORWARD);
            return f.tick(START.add(ClientMoveInput.of(f, FORWARD, 1.0F, speed, 0.0F)), true);
        }
    }

    /** A still sneaking tick that crouches the player under a water layer at head height, then a forward one. */
    private static PredictionResult crouchWalk(float speed) throws Exception {
        try (var f = new JavaTickFixture()) {
            layer(f, FLOOR_Y + 1, BlockIds.WATER);
            FluidPools.settle(f);
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.place(START, Vec3.ZERO);
            f.input(SNEAK);
            PredictionResult still = f.tick(START, true);
            assertTrue(exact(still), describe(still));
            assertEquals(Pose.CROUCHING, f.player.pose);
            f.input(FORWARD_SNEAK);
            return f.tick(START.add(ClientMoveInput.of(f, FORWARD_SNEAK, SLOW, speed, 0.0F)), true);
        }
    }

    /**
     * One forward tick of a player in the swimming pose, not swimming, with {@code above} filling the cell over its
     * feet and water filling the feet cell when {@code waterY} is the floor.
     */
    private static PredictionResult crawl(BlockDefinition above, int waterY, float speed) throws Exception {
        try (var f = new JavaTickFixture()) {
            layer(f, FLOOR_Y + 1, above);
            if (waterY == FLOOR_Y) layer(f, FLOOR_Y, BlockIds.WATER);
            FluidPools.settle(f);
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.player.pose = Pose.SWIMMING;
            f.place(START, Vec3.ZERO);
            f.input(FORWARD);
            return f.tick(START.add(ClientMoveInput.of(f, FORWARD, SLOW, speed, 0.0F)), true);
        }
    }

    private static void layer(JavaTickFixture f, int y, BlockDefinition block) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), block.defaultState());
            }
        }
    }
}
