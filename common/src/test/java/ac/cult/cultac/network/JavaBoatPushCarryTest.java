package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** AbstractBoat#tick pushes after the local body tick; from 26.3 the movement report follows that boat tick. */
class JavaBoatPushCarryTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double GRAVITY = -0.08 * (double) 0.98F;
    private static final double FRICTION = (double) (0.6F * 0.91F);
    private static final double PUSH = (double) 0.05F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_9", "V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aLateBoatPushIsCarriedAtTheVersionsReportingBoundaryOnce(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            boat(f, 100, 0.5);
            f.place(START, new Vec3(0.04, GRAVITY, 0));
            f.input(NONE);
            Vec3 position = START.add(0.04, 0, 0);
            f.move(position, true);
            var result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(exact(result), describe(result));
            double velocity = 0.04 * FRICTION;
            boolean lateReport = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            assertTrue(covers(f, velocity - (lateReport ? PUSH : 0), 0));
            assertFalse(covers(f, velocity - 2 * PUSH, 0), "one boat cannot create two pushes");
            if (!lateReport) assertFalse(covers(f, velocity - PUSH, 0), "older reports precede the boat");
            else assertEquals(0, result.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer());

            f.tickEnd();
            assertTrue(
                    covers(f, velocity - PUSH, 0), "the completed client tick includes the late boat on every version");
            assertFalse(covers(f, velocity - 2 * PUSH, 0));
            // In both protocols the next body travel consumes exactly one late push.
            velocity -= PUSH;
            position = position.add(velocity, 0, 0);
            var next = f.tick(position, true);
            assertTrue(exact(next), describe(next));
            assertTrue(covers(f, velocity * FRICTION - PUSH, 0));
        }
    }

    @Test
    void twoLateBoatsEachContributeOnce() throws Exception {
        try (var f = new JavaTickFixture()) {
            boat(f, 100, 0.5);
            boat(f, 101, 0.8);
            f.place(START, new Vec3(0.04, GRAVITY, 0));
            f.input(NONE);
            Vec3 position = START.add(0.04, 0, 0);
            var result = f.tick(position, true);
            assertTrue(exact(result), describe(result));
            double dx = position.x - (START.x + 1.1);
            double diagonalZPush = -0.3 / Math.abs(dx) * PUSH;
            assertTrue(covers(f, 0.04 * FRICTION - 2 * PUSH, diagonalZPush));
            assertFalse(covers(f, 0.04 * FRICTION - 3 * PUSH, diagonalZPush));
        }
    }

    @Test
    void aLateBoatInterpolatesIntoContactBeforePushing() throws Exception {
        try (var f = new JavaTickFixture()) {
            interpolatingBoat(f, 1.25, 0.95);
            f.place(START, new Vec3(0.04, GRAVITY, 0));
            f.input(NONE);
            var result = f.tick(START.add(0.04, 0, 0), true);
            assertTrue(exact(result), describe(result));
            assertTrue(
                    covers(f, 0.04 * FRICTION - PUSH, 0),
                    "Entity#commonTick advances the boat before AbstractBoat#tick pushes");
        }
    }

    @Test
    void aLateBoatInterpolatingOutOfContactCannotPushFromItsEarlierBox() throws Exception {
        try (var f = new JavaTickFixture()) {
            interpolatingBoat(f, 1.1, 1.7);
            f.place(START, new Vec3(0.04, GRAVITY, 0));
            f.input(NONE);
            var result = f.tick(START.add(0.04, 0, 0), true);
            assertTrue(exact(result), describe(result));
            assertTrue(covers(f, 0.04 * FRICTION, 0));
            assertFalse(
                    covers(f, 0.04 * FRICTION - PUSH, 0),
                    "contact before the boat's tick cannot create a late push after it leaves");
        }
    }

    private static void interpolatingBoat(JavaTickFixture f, double fromX, double toX) {
        f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, START.add(fromX, 0, 0), 0, 0, 0);
        var boat = f.player.compensatedEntities.getEntity(100);
        boat.setClientTickOrder(f.player.compensatedEntities.getSelf().getClientTickOrder() + 1);
        boat.onBundleFirstTransaction(false, true, START.x + toX, FLOOR_Y, START.z, f.player);
        boat.onSecondTransaction();
    }

    private static void boat(JavaTickFixture f, int id, double z) {
        f.player.compensatedEntities.addEntity(
                id, EntityTypeIds.OAK_BOAT, new Vec3(START.x + 1.1, FLOOR_Y, z), 0, 0, 0);
        f.player
                .compensatedEntities
                .getEntity(id)
                .setClientTickOrder(f.player.compensatedEntities.getSelf().getClientTickOrder() + id);
    }

    /** Whether the carry holds a starting velocity of ({@code x}, {@code z}). */
    private static boolean covers(JavaTickFixture f, double x, double z) {
        for (Vec3 point : f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels()) {
            if (Math.abs(point.x - x) <= 1e-10 && Math.abs(point.z - z) <= 1e-10) return true;
        }
        return false;
    }
}
