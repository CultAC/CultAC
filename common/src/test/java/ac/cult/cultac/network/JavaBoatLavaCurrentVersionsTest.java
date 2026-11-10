package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * AbstractBoat#tick runs Entity#baseTick before floatBoat, and baseTick pushes the boat by the water current and then
 * the lava current. Lava is not water to AbstractBoat#getStatus, so a boat floating in lava is IN_AIR.
 */
class JavaBoatLavaCurrentVersionsTest {
    private static final int BOAT = 100;
    // The boat box spans blocks x -1..1, all lava at least 0.444 high, and stays IN_AIR above the floor for two ticks.
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 0.25, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double INV_FRICTION = 0.9F;
    private static final double GRAVITY = 0.04;
    private static final double MIN_PUSH = 0.0045000000000000005;

    @ParameterizedTest(name = "{0}: an idle boat drifts with slow Overworld lava")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void slowLavaPushesAnIdleBoat(String release, ClientVersion version) throws Exception {
        // Tick 1 starts still, so the 0.00233 push grows to the minimum push; tick 2 is already faster than 0.003.
        double first = MIN_PUSH * INV_FRICTION;
        double second = (first + 0.0023333333333333335) * INV_FRICTION;
        drift(version, false, first, second);
    }

    @ParameterizedTest(name = "{0}: an idle boat drifts with fast Nether lava")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void fastLavaPushesAnIdleBoat(String release, ClientVersion version) throws Exception {
        double first = 0.007 * INV_FRICTION;
        double second = (first + 0.007) * INV_FRICTION;
        drift(version, true, first, second);
    }

    private static void drift(ClientVersion version, boolean fastLava, double firstX, double secondX)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            JavaTickFixture.setField(CompensatedWorld.class, f.player.compensatedWorld, "fastLava", fastLava);
            // One layer of lava whose level rises eastward, so every block the boat touches flows due east.
            var registry = DataTables.defaults().registry();
            for (int x = -3; x <= 4; x++) {
                int lava = registry.with(BlockIds.LAVA.defaultState(), "level", Integer.toString(x + 3));
                for (int z = -3; z <= 3; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), lava);
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, START, 0, 0, 0);
            var boat = f.player.compensatedEntities.getEntity(BOAT);
            boat.boatStatus = BoatEntityStatus.IN_AIR;
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);

            Vec3 afterFirst = START.add(firstX, -GRAVITY, 0);
            assertDrift(f.rideTick(afterFirst, false), firstX, -GRAVITY);
            assertDrift(f.rideTick(afterFirst.add(secondX, -2 * GRAVITY, 0), false), secondX, -2 * GRAVITY);
            assertEquals(START.x + firstX + secondX, boat.clientPhysicalPosition.x, 1e-15);
        }
    }

    private static void assertDrift(PredictionResult result, double x, double y) {
        assertTrue(exact(result), describe(result));
        Vec3 moved = result.getAcceptedClosestToTarget();
        assertEquals(x, moved.x, 1e-15, "Entity#baseTick's lava current must reach the boat move: " + describe(result));
        assertEquals(y, moved.y, 1e-15, describe(result));
        assertEquals(0, moved.z, 1e-15, describe(result));
        assertEquals(
                x, VelocityCandidates.preCollisionVelocity(result).x, 1e-15, "the carried delta keeps the current");
    }
}
