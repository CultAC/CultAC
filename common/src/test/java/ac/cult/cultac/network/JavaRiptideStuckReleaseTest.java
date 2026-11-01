package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A riptide released on the ground inside a block that leaves an Entity#stuckSpeedMultiplier. The release's
 * Entity#move scales the lift by the multiplier, clears it and zeroes deltaMovement, so the tick's travel starts from
 * rest and is not scaled; the inside-block pass after travel sets the multiplier the next tick's move consumes.
 */
class JavaRiptideStuckReleaseTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double FALLING = (0.0 - 0.08) * 0.98F;
    private static final float LIFT = 1.1999999F;
    // WebBlock, SweetBerryBushBlock and PowderSnowBlock#entityInside: Entity#makeStuckInBlock.
    private static final Vec3 WEB = new Vec3(0.25, 0.05F, 0.25);
    private static final Vec3 BUSH = new Vec3(0.8F, 0.75, 0.8F);
    private static final Vec3 SNOW = new Vec3(0.9F, 1.5, 0.9F);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aCobwebScalesTheLiftAndStopsTheRelease(boolean certain) throws Exception {
        try (var f = settledIn(BlockIds.COBWEB)) {
            Vec3 lifted = START.add(new Vec3(0, LIFT * WEB.y, 0));
            release(f, certain);
            assertExact(f.tick(lifted, false));
            // The lifted box is still in the web.
            assertExact(f.tick(lifted.add(new Vec3(0, FALLING * WEB.y, 0)), false));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSweetBerryBushScalesTheLiftAndStopsTheRelease(boolean certain) throws Exception {
        try (var f = settledIn(BlockIds.SWEET_BERRY_BUSH)) {
            Vec3 lifted = START.add(new Vec3(0, LIFT * BUSH.y, 0));
            release(f, certain);
            assertExact(f.tick(lifted, false));
            assertExact(f.tick(lifted.add(new Vec3(0, FALLING * BUSH.y, 0)), false));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void powderSnowScalesTheLiftAndStopsTheRelease(boolean certain) throws Exception {
        try (var f = settledIn(BlockIds.POWDER_SNOW)) {
            Vec3 lifted = START.add(new Vec3(0, LIFT * SNOW.y, 0));
            release(f, certain);
            assertExact(f.tick(lifted, false));
            // PowderSnowBlock#entityInside holds only a living entity whose feet are in the snow.
            assertExact(f.tick(lifted.add(new Vec3(0, FALLING, 0)), false));
        }
    }

    /** One grounded tick inside {@code block} at the feet, which sets its multiplier. */
    private static JavaTickFixture settledIn(BlockDefinition block) throws Exception {
        var f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), block.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(START, new Vec3(0, FALLING, 0));
        f.input(NONE);
        assertExact(f.tick(START, true));
        return f;
    }

    /** Releases a riptide looking straight up. */
    private static void release(JavaTickFixture f, boolean certain) {
        f.player.xRot = 0.0F;
        f.player.yRot = -90.0F;
        f.player.packetStateData.riptideLevel = 3;
        f.player.packetStateData.riptideReleaseCertain = certain;
        f.player.riptideSpinAttackTicks = 20;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
