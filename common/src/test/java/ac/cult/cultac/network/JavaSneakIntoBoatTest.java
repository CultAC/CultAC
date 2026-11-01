package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A player holding shift inside a boat's box. LocalPlayer#aiStep crouches only where
 * Player#canPlayerFitWithinBlocksAndEntitiesWhen fits the crouching pose, and Level#noCollision counts the boat, so
 * the player walks at full speed until it leaves the boat's box.
 */
class JavaSneakIntoBoatTest {
    private static final int BOAT = 100;
    private static final ServerboundPlayerInput SNEAK_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final float YAW = 0.0F;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    // AbstractBoat is 1.375 wide: 0.8 apart, its box overlaps the player's.
    private static final Vec3 OVERLAPPING = START.add(0.8, 0.0, 0.0);
    private static final Vec3 BESIDE = START.add(1.5, 0.0, 0.0);
    // Interpolating from 3.5 east of the player to 3.5 south, the boat passes beside the player's box at each step.
    private static final Vec3 PASSING_FROM = START.add(3.5, 0.0, 0.0);
    private static final Vec3 PASSING_TO = START.add(0.0, 0.0, 3.5);

    @Test
    void holdingShiftWhereTheCrouchingPoseDoesNotFitKeepsStandingSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult result = walk(f, OVERLAPPING);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void walkingSpeedWhileSneakingBesideABoatIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertRejected(walk(f, BESIDE), "NoSneakSlow");
        }
    }

    @Test
    void walkingSpeedWhileSneakingBesideAPassingBoatIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, PASSING_FROM, 0, 0, 0);
            f.player
                    .compensatedEntities
                    .getEntity(BOAT)
                    .onFirstTransaction(false, true, PASSING_TO.x, PASSING_TO.y, PASSING_TO.z, f.player);
            assertRejected(walk(f), "NoSneakSlow");
        }
    }

    /** One tick of walking-speed forward input from rest, with shift held, beside a boat at {@code boatAt}. */
    private static PredictionResult walk(JavaTickFixture f, Vec3 boatAt) throws Exception {
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boatAt, 0, 0, 0);
        return walk(f);
    }

    /** One tick of walking-speed forward input from rest, with shift held. */
    private static PredictionResult walk(JavaTickFixture f) throws Exception {
        f.place(START, Vec3.ZERO);
        f.player.xRot = f.player.lastTickXRot = YAW;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        f.input(SNEAK_FORWARD);
        // Player#getSpeed on a 0.6 friction block: LivingEntity#getFrictionInfluencedSpeed keeps the 0.1F speed.
        Vec3 input = ClientMoveInput.of(f, SNEAK_FORWARD, 1.0F, 0.1F, YAW);
        return f.tick(START.add(input.x, 0.0, input.z), true);
    }
}
