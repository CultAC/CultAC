package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.JavaLivingMoveFluids;
import ac.cult.cultac.utils.nmsutil.JavaPassengerFluids;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A minecart passenger in lava flowing due east. Entity#baseTick's updateInWaterStateAndDoFluidPushing pushes it by
 * water and then lava in every release. Through 1.21.11 its travel's LivingEntity#checkFallDamage refreshes only water
 * (updateInWaterStateAndDoWaterCurrentPushing); from 26.1 updateFluidInteraction applies both currents again.
 */
class JavaPassengerLavaCallbackVersionsTest {
    private static final int MINECART = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 0.0625, 0.5);
    // Entity#updateFluidHeightAndDoFluidPushing's minimum push for a still passenger.
    private static final double MIN_PUSH = 0.0045000000000000005;

    @ParameterizedTest(name = "{0}: the passenger's fall-damage callback pushes by lava only from 26.1")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theCallbackPushesByLavaOnlyFrom26Dot1(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, version);
            var self = f.player.compensatedEntities.getSelf();
            var callback = JavaLivingMoveFluids.apply(f.player, version, self, body(), Vec3.ZERO, false, true);
            // Water only through 1.21.11; the 1.21.4 local player's Entity#move never reaches the callback at all.
            boolean pushes = version.isNewerThanOrEquals(ClientVersion.V_26_1);
            assertEquals(pushes ? MIN_PUSH : 0, callback.velocity().x, 1.0E-15);
            assertEquals(0, callback.velocity().y, 0);
            assertEquals(0, callback.velocity().z, 0);

            var baseTick = JavaPassengerFluids.sample(f.player, body(), SEAT, Vec3.ZERO, null);
            assertTrue(baseTick.lava());
            assertEquals(MIN_PUSH, baseTick.velocity().x, 1.0E-15);
        }
    }

    /**
     * A still minecart one block up seats the passenger with only its legs in the lava. Its ride tick's baseTick push
     * reaches the minimum push, LivingEntity#travel's lava drag halves it, and the delta a dismount keeps holds only
     * that, until 26.1's callback pushes again by the lava current.
     */
    @ParameterizedTest(name = "{0}: a passive ride tick in lava keeps one lava push")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPassiveRideTickKeepsOneLavaPush(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, version, new Vec3(-1.5, FLOOR_Y + 1, 0.5));
            f.rideTick();
            double pushes = version.isNewerThanOrEquals(ClientVersion.V_26_1)
                    ? MIN_PUSH + 0.0023333333333333335
                    : MIN_PUSH;
            // LivingEntity#travel's lava branch: deltaMovement * 0.5, then a quarter of gravity.
            assertEquals(
                    List.of(new Vec3(pushes * 0.5, -0.02, 0)),
                    f.player.checkManager.getSimulationProcessor().riderKnownMovements());
        }
    }

    /** One layer of lava whose level rises eastward, so every block the passenger touches flows due east. */
    private static void mount(JavaTickFixture f, ClientVersion version) throws Exception {
        mount(f, version, SEAT);
    }

    private static void mount(JavaTickFixture f, ClientVersion version, Vec3 cart) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        var registry = DataTables.defaults().registry();
        for (int x = -3; x <= 4; x++) {
            int lava = registry.with(BlockIds.LAVA.defaultState(), "level", Integer.toString(x + 3));
            for (int z = -3; z <= 3; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), lava);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(
                MINECART, EntityTypes.defaults().byKey("minecraft:minecart").id(), cart, 0, 0, 0);
        f.setPassengers(MINECART, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().riding != null);
    }

    private static SimpleCollisionBox body() {
        return new SimpleCollisionBox(
                SEAT.x - 0.3, SEAT.y, SEAT.z - 0.3, SEAT.x + 0.3, SEAT.y + 1.8, SEAT.z + 0.3, false);
    }
}
