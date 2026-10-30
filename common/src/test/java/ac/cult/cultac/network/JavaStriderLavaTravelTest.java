package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaStriderLavaTravelTest {
    private static final int STRIDER = 100;

    /** flag-1 after f764852: lava at the feet selects travelInAir, even while the body is submerged. */
    @ParameterizedTest(name = "excessive lava input={0}")
    @ValueSource(booleans = {false, true})
    void aSubmergedStriderUsesAirInputAndCarriesAirDragBeforeBuoyancy(boolean excessiveInput) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(251.00495467941738 - 250, FLOOR_Y + 0.50101216359849, -34.00677894295113 + 34);
            f.place(start, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, start, 0, 0, 0);
            var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
            strider.noAI = true;
            strider.hasSaddle = true;
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(
                    new Vec3(-0.008255065541601202, 0.03890625, -0.003404482542809488));
            int falling = DataTables.defaults().registry().with(BlockIds.LAVA.defaultState(), "level", "8");
            int flowing = DataTables.defaults().registry().with(BlockIds.LAVA.defaultState(), "level", "2");
            for (int x = -2; x <= 4; x++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 4; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, -1), falling);
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, -3), falling);
                }
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, -2), flowing);
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 5, -2), BlockIds.STONE.defaultState());
            }
            f.player.xRot = 115.01934F;
            if (excessiveInput) {
                var result = f.rideTick(start.add(-0.026378879004071362, 0.03890625, -0.014195200988695495), false);
                JavaTickFixture.assertRejected(result, "VehicleOffset");
                assertTrue(result.getOffset() > 0.01D, describe(result));
                return;
            }
            var result = f.rideTick(new Vec3(
                    250.98797752849927 - 250,
                    FLOOR_Y + 0.53991841359849,
                    -34.01658687548161 + 34), false);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0D, result.getOffset(), 1.0E-12, describe(result));
            // Air travel applies gravity and air drag; Strider#floatStrider then halves all axes and adds 0.05Y.
            Vec3 moved = result.getAcceptedClosestToTarget();
            Vec3 expected = new Vec3(moved.x * 0.91F, (moved.y - 0.08D) * 0.98F, moved.z * 0.91F)
                    .scale(0.5D).add(0, 0.05D, 0);
            var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertEquals(1, carried.size());
            Vec3 actual = carried.iterator().next();
            assertEquals(expected.x, actual.x, 1.0E-12);
            assertEquals(expected.y, actual.y, 1.0E-12);
            assertEquals(expected.z, actual.z, 1.0E-12);
        }
    }

    /** Air travel remains selected throughout a deep lava pool; contact still supplies warmth and buoyancy. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aStriderUsesGroundThenAirTravelThroughoutDeepLava(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.LAVA.defaultState());
                }
            }
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, position, 0, 0, 0);
            var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
            strider.hasSaddle = true;
            strider.commitFluidHeights(ClientFluidHeights.capture(f.player,
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.9F, 1.7F)));
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            Vec3 velocity = new Vec3(0, -0.0784000015258789D, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
            boolean onGround = true;
            float riddenSpeed = (float) ((double) 0.175F * 0.55F);
            for (int tick = 0; tick < 12; tick++) {
                if (version.isOlderThan(ClientVersion.V_1_21_5)) velocity = velocity.scale(0.98D);
                double y = Math.abs(velocity.y) < 0.003D ? 0 : velocity.y;
                double z = Math.abs(velocity.z) < 0.003D ? 0 : velocity.z;
                float speed = onGround ? riddenSpeed : riddenSpeed * 0.1F;
                Vec3 movement = new Vec3(0, position.y == FLOOR_Y && y < 0 ? 0 : y, z + speed);
                boolean landed = y < 0 && movement.y == 0;
                position = position.add(movement);
                var result = f.rideTick(position, landed);
                assertTrue(exact(result), release + " tick " + tick + ": " + describe(result));
                assertTrue(result.getSimulationContext().getWorldData().isInLava());
                assertTrue(result.getSimulationContext().getWorldData().isCanStandOnStartFluid());
                float friction = onGround ? 0.6F * 0.91F : 0.91F;
                velocity = new Vec3(0, (movement.y - 0.08D) * 0.98F, movement.z * friction)
                        .scale(0.5D).add(0, 0.05D, 0);
                var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
                assertEquals(1, carried.size());
                Vec3 actual = carried.iterator().next();
                assertEquals(velocity.y, actual.y, 1.0E-12, release + " tick " + tick);
                assertEquals(velocity.z, actual.z, 1.0E-12, release + " tick " + tick);
                onGround = landed;
            }
        }
    }

    /** The move leaving lava keeps its start's air path; dry feet with a body still in lava use the fluid path next. */
    @Test
    void leavingLavaSwitchesTravelOnTheFollowingTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -3; x <= 3; x++) {
                for (int z = 0; z <= 3; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.LAVA.defaultState());
                }
            }
            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.25D, 0.015625D);
            f.place(position, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, position, 0, 0, 0);
            var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
            strider.noAI = true;
            strider.hasSaddle = true;
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            Vec3 velocity = new Vec3(0, 0.02D, -0.03D);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
            // At zero yaw the strider moves +Z; reverse the starting motion through the edge while still steering +Z.
            float airInput = (float) ((double) 0.175F * 0.55F) * 0.1F;
            Vec3 firstMove = velocity.add(0, 0, airInput);
            position = position.add(firstMove);
            assertFalse(strider.newPacketLocation.hasActiveInterpolationTarget(), "the spawn starts without a server interpolation target");
            var first = f.rideTick(position, false);
            assertTrue(exact(first), describe(first));
            assertTrue(first.getSimulationContext().getWorldData().isCanStandOnStartFluid());
            assertTrue(position.z < 0);
            velocity = new Vec3(0, (firstMove.y - 0.08D) * 0.98F, firstMove.z * 0.91F)
                    .scale(0.5D).add(0, 0.05D, 0);
            position = position.add(velocity.add(0, 0, (double) 0.02F));
            var second = f.rideTick(position, false);
            assertTrue(exact(second), describe(second));
            assertTrue(second.getSimulationContext().getWorldData().isInLava());
            assertFalse(second.getSimulationContext().getWorldData().isCanStandOnStartFluid());
        }
    }
}
