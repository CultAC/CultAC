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
 * A player on the ground beside a boat. EntityGetter#getEntityCollisions gives Entity#collide the boat's bounding box,
 * which clips a move only on an axis where the boat lies ahead of the player's box (VoxelShape#collideX), and
 * Entity#collide steps onto the 0.5625 tall boat when the boat stops a grounded move horizontally.
 */
class JavaWalkBesideBoatTest {
    private static final int BOAT = 100;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final float SPEED = 0.1F;
    private static final float SNEAKING_SPEED = 0.3F;
    // LivingEntity#travelInAir keeps 0.6F * 0.91F of the velocity on stone.
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final double GROUNDED_Y = -0.0784000015258789;
    // Yaw 0 faces +z, yaw -90 faces +x.
    private static final float ALONG = 0.0F;
    private static final float TOWARDS = -90.0F;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    // AbstractBoat is 1.375 wide; its west face against the player's east face, or 0.0125 from it.
    private static final double BOAT_HALF_WIDTH = 1.375 / 2.0;
    private static final double[] GAPS = {0.0, 0.0125};
    private static final double BOAT_TOP = 0.5625;
    private static final int TICKS = 8;

    @Test
    void walkingAlongABoatsSideKeepsTheWholeMove() throws Exception {
        for (double gap : GAPS) {
            try (var f = new JavaTickFixture()) {
                boatBeside(f, gap);
                along(f, FORWARD, 1.0F, false, gap);
            }
        }
    }

    @Test
    void sneakingAlongABoatsSideIsSneakSlowed() throws Exception {
        for (double gap : GAPS) {
            try (var f = new JavaTickFixture()) {
                boatBeside(f, gap);
                along(f, SNEAK_FORWARD, SNEAKING_SPEED, true, gap);
            }
        }
    }

    @Test
    void walkingSpeedWhileSneakingAlongABoatsSideIsRejected() throws Exception {
        for (double gap : GAPS) {
            try (var f = new JavaTickFixture()) {
                boatBeside(f, gap);
                start(f, SNEAK_FORWARD, ALONG, true);
                Vec3 input = ClientMoveInput.of(f, SNEAK_FORWARD, 1.0F, SPEED, ALONG);
                assertRejected(f.tick(START.add(input.x, 0.0, input.z), true), "NoSneakSlow");
            }
        }
    }

    @Test
    void walkingIntoABoatsSideStepsOntoIt() throws Exception {
        for (double gap : GAPS) {
            try (var f = new JavaTickFixture()) {
                boatBeside(f, gap);
                start(f, FORWARD, TOWARDS, false);
                Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, SPEED, TOWARDS);
                PredictionResult step = f.tick(START.add(input.x, BOAT_TOP, input.z), true);
                assertTrue(exact(step), gap + ": " + describe(step));
            }
        }
    }

    @Test
    void walkingIntoABoatsSideWithoutSteppingOntoItIsRejected() throws Exception {
        for (double gap : GAPS) {
            try (var f = new JavaTickFixture()) {
                boatBeside(f, gap);
                start(f, FORWARD, TOWARDS, false);
                Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, SPEED, TOWARDS);
                PredictionResult clipped = f.tick(START.add(gap, 0.0, input.z), true);
                assertRejected(clipped, "InputKeys");
            }
        }
    }

    @Test
    void driftingAlongABoatsSideWithoutInputIsRejected() throws Exception {
        for (double gap : GAPS) {
            try (var f = new JavaTickFixture()) {
                boatBeside(f, gap);
                start(f, NONE, ALONG, false);
                PredictionResult drift = f.tick(START.add(0.0, 0.0, 0.05), true);
                assertRejected(drift, "InputKeys");
            }
        }
    }

    @Test
    void aBoatTickingBeforeThePlayerPushesItAway() throws Exception {
        for (double gap : new double[] {0.0, 0.1}) {
            try (var f = new JavaTickFixture()) {
                double boatX = boatBeside(f, gap);
                f.player.compensatedEntities.appendSelfToClientTickOrder();
                start(f, NONE, ALONG, false);
                Vec3 position = START;
                double velocity = 0.0;
                for (int tick = 0; tick < 6; tick++) {
                    double delta = velocity + boatPush(position.x, boatX);
                    position = position.add(delta, 0.0, 0.0);
                    PredictionResult result = f.tick(position, true);
                    assertTrue(exact(result), gap + " tick " + tick + ": " + describe(result));
                    velocity = delta * GROUND_FRICTION;
                }
            }
        }
    }

    @Test
    void walkingIntoABoatWhilePushedStepsOntoIt() throws Exception {
        for (boolean boatFirst : new boolean[] {false, true}) {
            for (double gap : new double[] {0.3, 0.45}) {
                try (var f = new JavaTickFixture()) {
                    double boatX = boatBeside(f, gap);
                    if (boatFirst) f.player.compensatedEntities.appendSelfToClientTickOrder();
                    start(f, FORWARD, TOWARDS, false);
                    Vec3 position = START;
                    double velocity = 0.0;
                    boolean onBoat = false;
                    for (int tick = 0; tick < TICKS; tick++) {
                        if (boatFirst && !onBoat) velocity += boatPush(position.x, boatX);
                        double delta = velocity + ClientMoveInput.of(f, FORWARD, 1.0F, SPEED, TOWARDS).x;
                        double rise = 0.0;
                        if (!onBoat && position.x + 0.3 + delta > boatX - BOAT_HALF_WIDTH) {
                            rise = BOAT_TOP;
                            onBoat = true;
                        }
                        position = position.add(delta, rise, 0.0);
                        PredictionResult result = f.tick(position, true);
                        assertTrue(exact(result), boatFirst + " " + gap + " tick " + tick + ": " + describe(result));
                        velocity = delta * GROUND_FRICTION;
                        if (!boatFirst && !onBoat) velocity += boatPush(position.x, boatX);
                    }
                }
            }
        }
    }

    /**
     * The x impulse Entity#push gives a player at {@code x} from a boat centred at {@code boatX} on the same floor,
     * when AbstractBoat#tick's box, the boat inflated by 0.2 horizontally, reaches the player's.
     */
    private static double boatPush(double x, double boatX) {
        if (boatX - BOAT_HALF_WIDTH - 0.2 >= x + 0.3 || boatX + BOAT_HALF_WIDTH + 0.2 <= x - 0.3) return 0.0;
        double dx = x - boatX;
        double distance = Math.abs(dx);
        if (distance < 0.01F) return 0.0;
        distance = Math.sqrt(distance);
        return dx / distance * Math.min(1.0, 1.0 / distance) * 0.05F;
    }

    /** Adds the boat {@code gap} east of the player's box and returns its centre's x. */
    private static double boatBeside(JavaTickFixture f, double gap) {
        Vec3 boat = new Vec3(START.x + 0.3F + gap + BOAT_HALF_WIDTH, FLOOR_Y, START.z);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boat, 0, 0, 0);
        return boat.x;
    }

    private static void start(JavaTickFixture f, ServerboundPlayerInput keys, float yaw, boolean sneaking)
            throws Exception {
        f.place(START, new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.xRot = f.player.lastTickXRot = yaw;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", sneaking);
        f.input(keys);
    }

    /** {@link #TICKS} ticks of {@code keys} along the boat's side and past its end, each exact. */
    private static void along(
            JavaTickFixture f, ServerboundPlayerInput keys, float factor, boolean sneaking, double gap)
            throws Exception {
        start(f, keys, ALONG, sneaking);
        Vec3 position = START;
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < TICKS; tick++) {
            Vec3 delta = velocity.add(ClientMoveInput.of(f, keys, factor, SPEED, ALONG));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult result = f.tick(position, true);
            assertTrue(exact(result), gap + " tick " + tick + ": " + describe(result));
            velocity = new Vec3(delta.x * GROUND_FRICTION, 0.0, delta.z * GROUND_FRICTION);
        }
    }
}
