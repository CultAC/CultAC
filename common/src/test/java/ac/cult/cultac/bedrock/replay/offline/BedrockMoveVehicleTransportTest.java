package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BedrockMoveVehicleTransportTest {
    @Test
    public void movementWithoutCurrentAuthorityStopsBeforeJavaChecks() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            ServerboundMoveVehiclePacket packet = new ServerboundMoveVehiclePacket(net.minecraft.core.PositionAndRotation.of(new Vec3(2.0D, 64.0D, 3.0D), 0.0F, 0.0F), true);
            PacketReceiveEvent event = new PacketReceiveEvent(
                    player.user, packet, ConnectionProtocol.PLAY);
            BadPacketsJ badPackets = player.checkManager.getCheck(BadPacketsJ.class);

            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertTrue(event.isCancelled());
            assertEquals(0.0D, badPackets.getViolations(), 0.0D);
            assertNull(player.checkManager.getSimulationProcessor().getLastPrediction());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void currentPassengerAuthorityLeavesGeneratedVehicleMovementUntouched() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int vehicleId = 51;
            player.compensatedEntities.vehicles.setServerVehicle(
                    vehicleId, new int[]{player.entityID}, player.lastTransactionSent.get());
            ServerboundMoveVehiclePacket packet = vehiclePacket(
                    new Vec3(2.0D, 64.0D, 3.0D));
            PacketReceiveEvent event = receiveEvent(player, packet);

            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertFalse(event.isCancelled());
            assertEquals(
                    0.0D,
                    player.checkManager.getCheck(BadPacketsJ.class).getViolations(),
                    0.0D);
            assertNull(player.checkManager.getSimulationProcessor().getLastPrediction());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void rejectedRawAuthCancelsOnlyItsGeneratedVehicleMovement() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int vehicleId = 53;
            player.compensatedEntities.vehicles.setServerVehicle(
                    vehicleId, new int[]{player.entityID}, player.lastTransactionSent.get());
            player.packetStateData.rejectBedrockTranslatedMovement(42L);
            ServerboundMoveVehiclePacket packet = vehiclePacket(
                    new Vec3(2.0D, 64.0D, 3.0D));
            PacketReceiveEvent event = receiveEvent(player, packet);

            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertTrue(event.isCancelled());
            assertTrue(player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());
            assertNull(player.checkManager.getSimulationProcessor().getLastPrediction());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void currentPassengerAuthorityStillObeysPendingCultSetback() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int vehicleId = 57;
            player.compensatedEntities.vehicles.setServerVehicle(
                    vehicleId, new int[]{player.entityID}, player.lastTransactionSent.get());
            player.getSetbackTeleportUtil().lastKnownGoodPosition =
                    new SetbackPosWithVector(new Vec3(8.0D, 72.0D, -4.0D), Vec3.ZERO, 0);
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            assertTrue(player.getSetbackTeleportUtil().isPendingSetback());

            ServerboundMoveVehiclePacket packet = vehiclePacket(
                    new Vec3(9.0D, 72.0D, -4.0D));
            PacketReceiveEvent event = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertTrue(event.isCancelled());
            assertNull(player.checkManager.getSimulationProcessor().getLastPrediction());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void exactQueuedVehicleTeleportAcknowledgementRemainsAccepted() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int vehicleId = 61;
            Vec3 position = new Vec3(4.0D, 68.0D, 6.0D);
            player.getSetbackTeleportUtil().addVehicleTeleport(
                    vehicleId, player.lastTransactionReceived.get(), position);
            ServerboundMoveVehiclePacket packet = vehiclePacket(position);
            PacketReceiveEvent event = receiveEvent(player, packet);

            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertFalse(event.isCancelled());
            assertFalse(player.getSetbackTeleportUtil()
                    .checkVehicleTeleportQueue(vehicleId, position.x, position.y, position.z)
                    .isTeleport());
            assertNull(player.checkManager.getSimulationProcessor().getLastPrediction());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketReceiveEvent receiveEvent(
            CultPlayer player,
            ServerboundMoveVehiclePacket packet
    ) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static ServerboundMoveVehiclePacket vehiclePacket(Vec3 position) {
        return new ServerboundMoveVehiclePacket(net.minecraft.core.PositionAndRotation.of(position, 0.0F, 0.0F), true);
    }
}
