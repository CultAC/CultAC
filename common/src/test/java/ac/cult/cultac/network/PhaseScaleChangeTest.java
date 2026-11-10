package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.Phase;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LivingEntity#tick runs aiStep before refreshDirtyAttributes, so the tick after a scale attribute changes still
 * travels with the box of the previous scale, and Entity#refreshDimensions resizes it in place afterwards. A box that
 * already overlapped a block at the larger scale and slid further into it has entered nothing once it shrinks.
 */
class PhaseScaleChangeTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double GRAVITY = -0.0784000015258789;

    @Test
    void aShrunkBoxStillInsideTheBlockItsLargerBoxOverlappedHasNotPhased() throws Exception {
        try (var f = new JavaTickFixture()) {
            // A two-high wall whose west face is x = 2.
            for (int y = FLOOR_Y; y <= FLOOR_Y + 3; y++) {
                for (int z = -1; z <= 1; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(2, y, z), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            Phase phase = f.player.checkManager.getCheck(Phase.class);

            // At scale 2 the 1.2 wide box reaches x = 2.1, 0.1 into the wall.
            var self = f.player.compensatedEntities.getSelf();
            self.scale = 2.0F;
            f.player.refreshPlayerPose();
            Vec3 jammed = new Vec3(1.5, FLOOR_Y, 0.5);
            f.place(jammed, new Vec3(0.3, GRAVITY, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);

            // The scale attribute changes before this tick; its travel still moves the scale 2 box.
            self.scale = 1.0F;
            Vec3 slid = jammed.add(0.3, 0, 0);
            PredictionResult shrinking = f.tick(slid, true);
            assertTrue(exact(shrinking), describe(shrinking));
            assertEquals(1.0F, f.player.dimensionScale);
            assertEquals(
                    0, phase.getViolations(), "the scale 1 box at x = 1.8 reaches x = 2.1, as the scale 2 box did");
        }
    }
}
