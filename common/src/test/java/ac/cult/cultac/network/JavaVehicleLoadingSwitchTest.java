package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick still ticks ClientLevel's entities while LocalPlayer#tick/sendChanges wait for hasClientLoaded. A
 * controlled saddled strider travels by Strider#getRiddenInput, which is always forward, on each loading tick end
 * without a MoveVehicle; a passive one does not.
 */
class JavaVehicleLoadingSwitchTest {
    private static final int STRIDER = 9300;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void loadingTickEndsTravelTheControlledMount() throws Exception {
        try (var f = mounted(true)) {
            var vehicles = f.player.compensatedEntities.vehicles;
            var strider = f.player.compensatedEntities.getEntity(STRIDER);
            f.player.packetStateData.playerLoadedIntoLevel = false;
            for (int tick = 0; tick < 6; tick++) f.tickEnd();
            assertFalse(vehicles.isVehicleSwitchBufferActiveFor(strider));

            double z = f.player.z;
            for (int tick = 0; tick < 13; tick++) {
                f.tickEnd();
                assertTrue(vehicles.canClientAuthoritativelyMoveVisibleRoot(strider), "loading tick " + tick);
                assertFalse(vehicles.isVehicleSwitchBufferActiveFor(strider), "loading tick " + tick);
                // Strider#tickRidden faces the rider's look, yaw 0: forward is +Z.
                assertTrue(f.player.z > z, "loading tick " + tick);
                assertEquals(START.x, f.player.x, "loading tick " + tick);
                z = f.player.z;
            }

            // The first report that leaves out the loading travel is the client's own move.
            assertRejected(f.rideTick(START, true), "VehicleOffset");
        }
    }

    @Test
    void loadedTicksDoNotTravelAMountTheRiderStoppedControlling() throws Exception {
        try (var f = mounted(true)) {
            var vehicles = f.player.compensatedEntities.vehicles;
            var strider = f.player.compensatedEntities.getEntity(STRIDER);
            f.player.packetStateData.playerLoadedIntoLevel = false;
            for (int tick = 0; tick < 13; tick++) f.tickEnd();
            Vec3 loaded = new Vec3(f.player.x, f.player.y, f.player.z);

            // LocalPlayer sends Rot without MoveVehicle after the player loads but stops holding the fungus.
            f.player.getInventory().inventory.setHeldItem(SimItemStack.EMPTY);
            for (int tick = 0; tick < 5; tick++) f.rideTick();
            assertTrue(f.player.packetStateData.playerLoadedIntoLevel);
            assertFalse(vehicles.isVehicleSwitchBufferActiveFor(strider));
            assertFalse(vehicles.canClientAuthoritativelyMoveVisibleRoot(strider));

            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            assertRejected(f.rideTick(loaded.add(0.6325345622379714, 0, 0), true), "VehicleOffset");
        }
    }

    @Test
    void loadingDoesNotTravelAPassiveMount() throws Exception {
        try (var f = mounted(false)) {
            f.player.packetStateData.playerLoadedIntoLevel = false;
            for (int tick = 0; tick < 13; tick++) f.tickEnd();
            var vehicles = f.player.compensatedEntities.vehicles;
            var strider = f.player.compensatedEntities.getEntity(STRIDER);
            assertFalse(vehicles.canClientAuthoritativelyMoveVisibleRoot(strider));
            assertFalse(vehicles.isVehicleSwitchBufferActiveFor(strider));
            assertEquals(START, new Vec3(f.player.x, f.player.y, f.player.z));
            assertEquals(0, f.player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());
        }
    }

    @Test
    void clientsWithoutTheLoadedGateStillInferControlFromReports() throws Exception {
        try (var f = mounted(true)) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_2);
            f.player.packetStateData.playerLoadedIntoLevel = false;
            for (int tick = 0; tick < 13; tick++) f.tickEnd();
            var vehicles = f.player.compensatedEntities.vehicles;
            var strider = f.player.compensatedEntities.getEntity(STRIDER);
            assertFalse(vehicles.canClientAuthoritativelyMoveVisibleRoot(strider));
            assertFalse(vehicles.isVehicleSwitchBufferActiveFor(strider));
            assertEquals(START, new Vec3(f.player.x, f.player.y, f.player.z));
        }
    }

    private static JavaTickFixture mounted(boolean controlled) throws Exception {
        var f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.place(START, Vec3.ZERO);
        f.teleport(START, new RelativeFlag(0));
        f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, START, 0, 0, 0);
        var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
        strider.hasSaddle = true;
        if (controlled) {
            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
        }
        f.setPassengers(STRIDER, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        return f;
    }
}
