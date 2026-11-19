package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Strider#tick runs Strider#floatStrider after LivingEntity#tick. A strider in lava that ends above the 0.5-high
 * liquid column of its block, with no lava over it, is set on the ground by Entity#setOnGround(true), even when its
 * own move went up and collided with nothing. ServerboundMoveVehiclePacket#fromEntity reports that ground, and the
 * next ridden travel accelerates with it.
 */
class JavaStriderSurfaceGroundTest {
    private static final int STRIDER = 100;
    private static final float RIDDEN_SPEED = (float) ((double) 0.175F * 0.55F);

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aStriderRisingOutOfTheColumnIsGroundedAndWalksOnTheSurface(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.checkManager.getNoFall().setEnabled(true);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.LAVA.defaultState());
            }
            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.49D, 0.5);
            var strider = mount(f, position);
            strider.commitFluidHeights(ClientFluidHeights.capture(
                    f.player,
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.9F, 1.7F)));
            strider.onGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            Vec3 velocity = new Vec3(0, 0.02D, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);

            // Airborne at the start, LivingEntity#travelInAir rises through the column's top with air input.
            if (version.isOlderThan(ClientVersion.V_1_21_5)) velocity = velocity.scale(0.98D);
            Vec3 rise = new Vec3(0, velocity.y, RIDDEN_SPEED * 0.1F);
            position = position.add(rise);
            assertTrue(position.y > FLOOR_Y + 0.5D);
            var risen = f.rideTick(position, true);
            assertTrue(exact(risen), release + ": " + describe(risen));
            assertTrue(strider.onGround, release + ": floatStrider's ground must carry to the next travel");
            assertEquals(
                    DesyncStatus.TRUE, f.player.checkManager.getSimulationProcessor().getLastOnGround(), release);
            // On the surface floatStrider grounds the strider and leaves the delta movement unscaled.
            velocity = new Vec3(0, (rise.y - 0.08D) * 0.98F, rise.z * 0.91F);
            var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertEquals(1, carried.size(), release);
            Vec3 actual = carried.iterator().next();
            assertEquals(velocity.y, actual.y, 1.0E-12, release);
            assertEquals(velocity.z, actual.z, 1.0E-12, release);

            // Grounded, the next travel takes the full ridden speed and lands on the column's top.
            if (version.isOlderThan(ClientVersion.V_1_21_5)) velocity = velocity.scale(0.98D);
            Vec3 walk = new Vec3(0, FLOOR_Y + 0.5D - position.y, velocity.z + RIDDEN_SPEED);
            var walked = f.rideTick(position.add(walk), true);
            assertTrue(exact(walked), release + ": " + describe(walked));
        }
    }

    /**
     * From 26.1 LivingEntity#checkFallDamage refreshes the lava cache inside Entity#move. A strider falling from above
     * the lava's surface into the column's upper half collides with nothing, yet floatStrider then finds it in lava
     * above the column and grounds it, and ServerboundMoveVehiclePacket#fromEntity reports that ground.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fromLavaRefreshInMove")
    void aStriderFallingIntoTheColumnTopIsGroundedWithoutALanding(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.checkManager.getCheck(VehicleGround.class).setEnabled(true);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.LAVA.defaultState());
            }
            // Above the source's 8/9 fluid height: the tick's Entity#baseTick leaves the strider out of lava.
            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.9216D, 0.5);
            var strider = mount(f, position);
            strider.commitFluidHeights(ClientFluidHeights.capture(
                    f.player,
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.9F, 1.7F)));
            assertFalse(strider.isInLavaAfterBaseTick(f.player), release);
            strider.onGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            Vec3 velocity = new Vec3(0, -0.155232D, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);

            Vec3 fall = new Vec3(0, velocity.y, RIDDEN_SPEED * 0.1F);
            position = position.add(fall);
            assertTrue(position.y > FLOOR_Y + 0.5D && position.y < FLOOR_Y + 8.0D / 9.0D);
            var fallen = f.rideTick(position, true);
            assertTrue(exact(fallen), release + ": " + describe(fallen));
            assertEquals(0.0D, f.player.checkManager.getCheck(VehicleGround.class).violations, release);
            assertTrue(strider.onGround, release + ": floatStrider's ground must carry to the next travel");
            assertEquals(
                    DesyncStatus.TRUE, f.player.checkManager.getSimulationProcessor().getLastOnGround(), release);
        }
    }

    static Stream<Arguments> fromLavaRefreshInMove() {
        return JavaPistonReleaseCases.releases()
                .filter(c -> ((ClientVersion) c.get()[1]).isNewerThanOrEquals(ClientVersion.V_26_1));
    }

    private static PacketEntityStrider mount(JavaTickFixture f, Vec3 position) throws Exception {
        f.place(position, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, position, 0, 0, 0);
        var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
        strider.hasSaddle = true;
        f.player
                .getInventory()
                .inventory
                .setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
        f.setPassengers(STRIDER, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        return strider;
    }
}
