package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick still ticks ClientLevel's entities while LocalPlayer#tick/sendChanges wait for hasClientLoaded.
 * A saddled Strider#getRiddenInput is always forward, so loading tick ends without MoveVehicle do not prove that
 * the root is passive. Resume through the existing vehicle-switch budget and resync when it cannot cover the move.
 */
class JavaVehicleLoadingSwitchTest {
    private static final int STRIDER = 9300;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void loadingTickEndsKeepTheControlledMountInTheSwitchWindow() throws Exception {
        try (var f = mounted(true)) {
            var vehicles = f.player.compensatedEntities.vehicles;
            var strider = f.player.compensatedEntities.getEntity(STRIDER);
            f.player.packetStateData.playerLoadedIntoLevel = false;

            for (int tick = 0; tick < 13; tick++) {
                f.tickEnd();
                assertTrue(vehicles.canClientAuthoritativelyMoveVisibleRoot(strider), "loading tick " + tick);
                assertTrue(vehicles.isVehicleSwitchBufferActiveFor(strider), "loading tick " + tick);
            }

            // flag-1-2026-10-30T23-41-19: first reported movement includes the unreported loading travel.
            var first = f.rideTick(START.add(0.6325345622379714, 0, -0.015665421013672187), true);
            assertTrue(first.hasFlag(VehicleOffset.class), describe(first));
            assertTrue(first.isExempt(), describe(first));
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), describe(first));
        }
    }

    @Test
    void theLoadingWindowExpiresOncePlayerTicksResume() throws Exception {
        try (var f = mounted(true)) {
            var vehicles = f.player.compensatedEntities.vehicles;
            var strider = f.player.compensatedEntities.getEntity(STRIDER);
            f.player.packetStateData.playerLoadedIntoLevel = false;
            for (int tick = 0; tick < 13; tick++) f.tickEnd();
            assertTrue(vehicles.isVehicleSwitchBufferActiveFor(strider));

            // LocalPlayer sends Rot without MoveVehicle after the player loads but stops holding the fungus.
            f.player.getInventory().inventory.setHeldItem(SimItemStack.EMPTY);
            for (int tick = 0; tick < 5; tick++) f.rideTick();
            assertTrue(f.player.packetStateData.playerLoadedIntoLevel);
            assertFalse(vehicles.isVehicleSwitchBufferActiveFor(strider));
            assertFalse(vehicles.canClientAuthoritativelyMoveVisibleRoot(strider));

            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            assertRejected(f.rideTick(START.add(0.6325345622379714, 0, 0), true), "VehicleOffset");
        }
    }

    @Test
    void loadingDoesNotExtendAPassiveMountsSwitchWindow() throws Exception {
        try (var f = mounted(false)) {
            f.player.packetStateData.playerLoadedIntoLevel = false;
            for (int tick = 0; tick < 13; tick++) f.tickEnd();
            var vehicles = f.player.compensatedEntities.vehicles;
            var strider = f.player.compensatedEntities.getEntity(STRIDER);
            assertFalse(vehicles.canClientAuthoritativelyMoveVisibleRoot(strider));
            assertFalse(vehicles.isVehicleSwitchBufferActiveFor(strider));
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
