package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BedrockMoveVehicleTransportTest {
    @Test
    public void equineVariantsRequireSaddleAndValidatedMovement() {
        OfflineCultTestBootstrap.installConfig();
        for (var type : java.util.List.of(EntityTypesCompat.DONKEY, EntityTypesCompat.MULE,
                EntityTypesCompat.SKELETON_HORSE, EntityTypesCompat.ZOMBIE_HORSE)) {
            CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
            try {
                mountHorse(player, 71, type);
                var horse = (PacketEntityHorse) player.compensatedEntities.getEntity(71);
                assertEquals(horse, ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.controlledVehicle(player));
                assertEquals(1.3965F, ac.cult.cultac.utils.nmsutil.BoundingBoxSize.getWidth(player, horse), 0);
                assertEquals(1.6F, ac.cult.cultac.utils.nmsutil.BoundingBoxSize.getHeight(player, horse), 0);
                horse.hasSaddle = false;
                assertNull(ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.controlledVehicle(player));
                horse.hasSaddle = true;

                var listener = new CheckManagerListener();
                Vec3 position = new Vec3(2.0F, 64.0F, 3.0F);
                var packet = vehiclePacket(position);
                var unchecked = receiveEvent(player, packet);
                listener.onMoveVehicle(unchecked, player, packet);
                assertTrue(unchecked.isCancelled());
                player.packetStateData.grantBedrockVehicleMovementPermit(71, position);
                var checked = receiveEvent(player, packet);
                listener.onMoveVehicle(checked, player, packet);
                assertFalse(checked.isCancelled());
                var reused = receiveEvent(player, packet);
                listener.onMoveVehicle(reused, player, packet);
                assertTrue(reused.isCancelled());
            } finally {
                OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            }
        }
    }

    @Test
    public void horseProjectionRequiresItsOwnAcceptedAuthFrameAndCannotBeReused() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountHorse(player, 71);
            Vec3 position = new Vec3(2.0F, 64.0F, 3.0F);
            var packet = new ServerboundMoveVehiclePacket(net.minecraft.core.PositionAndRotation.of(position, 0.0F, 0.0F), false);
            var fallback = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(fallback, player, packet);
            assertTrue(fallback.isCancelled());
            assertFalse(player.packetStateData.hasPendingBedrockTranslatedMovementDecision());

            player.packetStateData.grantBedrockVehicleMovementPermit(71, position);
            var accepted = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(accepted, player, packet);
            assertFalse(accepted.isCancelled());
            assertTrue(NmsPacketUtil.readMoveVehicle((ServerboundMoveVehiclePacket) accepted.getNmsPacket()).onGround());

            var repeated = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(repeated, player, packet);
            assertTrue(repeated.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void horsePermitCannotAuthorizeAnotherTickActorOrPosition() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountHorse(player, 71);
            var listener = new CheckManagerListener();
            Vec3 position = new Vec3(2.0F, 64.0F, 3.0F);
            var packet = vehiclePacket(position);
            player.packetStateData.grantBedrockVehicleMovementPermit(71, position);
            var tickEnd = ServerboundClientTickEndPacket.INSTANCE;
            listener.onClientTickEnd(new PacketReceiveEvent(player.user, tickEnd, ConnectionProtocol.PLAY), player, tickEnd);
            var nextTick = receiveEvent(player, packet);
            listener.onMoveVehicle(nextTick, player, packet);
            assertTrue(nextTick.isCancelled());

            player.packetStateData.grantBedrockVehicleMovementPermit(72, position);
            var wrongHorse = receiveEvent(player, packet);
            listener.onMoveVehicle(wrongHorse, player, packet);
            assertTrue(wrongHorse.isCancelled());
            assertFalse(player.packetStateData.hasPendingBedrockTranslatedMovementDecision());

            player.packetStateData.grantBedrockVehicleMovementPermit(71, position);
            var displacedPacket = vehiclePacket(position.add(1.0D, 0.0D, 0.0D));
            var wrongPosition = receiveEvent(player, displacedPacket);
            listener.onMoveVehicle(wrongPosition, player, displacedPacket);
            assertTrue(wrongPosition.isCancelled());
            assertFalse(player.packetStateData.hasPendingBedrockTranslatedMovementDecision());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void horseSetbackCompletionDoesNotRequireAnExactPositionEchoOrGrantMovement() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountHorse(player, 71);
            var teleports = player.getSetbackTeleportUtil();
            Vec3 target = new Vec3(0.0D, 64.0D, 0.0D);
            teleports.lastKnownGoodPosition = new SetbackPosWithVector(target, Vec3.ZERO, 0);
            teleports.hasFullyLoaded = true;
            teleports.hasFullyJoined = true;
            teleports.executeNonSimulatingSetback();
            teleports.blockOffsets = true;
            int sent = teleports.getRequiredSetBack().getTeleportData().getTransaction();
            teleports.addBedrockVehicleTeleport(71, sent, target);

            player.lastTransactionReceived.set(sent - 1);
            assertFalse(teleports.checkVehicleTeleportQueue(71, target.x, target.y, target.z).isTeleport());
            teleports.completeBedrockMovementCorrection(new BedrockMovementCorrection(
                    1, 0, 71, 71, 42, target, Vec3.ZERO, 0, 0, false, BedrockCoordinateFrame.IDENTITY, sent));
            assertTrue(teleports.isPendingSetback());
            assertTrue(teleports.hasUnacknowledgedSetbackVehicleTeleport());

            player.lastTransactionReceived.set(sent);
            teleports.completeBedrockMovementCorrection(new BedrockMovementCorrection(
                    1, 0, 72, 72, 42, target, Vec3.ZERO, 0, 0, false, BedrockCoordinateFrame.IDENTITY, sent));
            assertTrue(teleports.isPendingSetback());
            player.compensatedEntities.vehicles.applyAcceptedVehicleTeleportEntityState(
                    71, target, 0.0F, 0.0F, false, Vec3.ZERO, false);
            teleports.completeBedrockMovementCorrection(new BedrockMovementCorrection(
                    1, 0, 71, 71, 42, target, Vec3.ZERO, 0, 0, false, BedrockCoordinateFrame.IDENTITY, sent));
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.blockOffsets);
            assertEquals(0, teleports.queuedVehicleTeleportCount());

            var packet = vehiclePacket(target.add(0.1D, 0.0D, 0.0D));
            var unvalidated = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(unvalidated, player, packet);
            assertTrue(unvalidated.isCancelled());

            Vec3 position = NmsPacketUtil.readMoveVehicle(packet).position();
            player.packetStateData.grantBedrockVehicleMovementPermit(71, position);
            var validated = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(validated, player, packet);
            assertFalse(validated.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void olderHorseCorrectionCannotCompleteANewerSetback() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountHorse(player, 71);
            var teleports = player.getSetbackTeleportUtil();
            teleports.lastKnownGoodPosition = new SetbackPosWithVector(Vec3.ZERO, Vec3.ZERO, 0);
            teleports.hasFullyLoaded = true;
            teleports.hasFullyJoined = true;
            teleports.executeNonSimulatingSetback();
            int sent = teleports.getRequiredSetBack().getTeleportData().getTransaction();
            teleports.addBedrockVehicleTeleport(71, sent, Vec3.ZERO);

            player.lastTransactionSent.set(sent + 2);
            teleports.executeTooHighLatencySetback("horse-correction-order");
            teleports.blockOffsets = true;
            int newer = teleports.getRequiredSetBack().getTeleportData().getTransaction();
            assertTrue(newer > sent);
            teleports.addBedrockVehicleTeleport(71, newer, Vec3.ZERO);

            player.lastTransactionReceived.set(sent);
            teleports.completeBedrockMovementCorrection(new BedrockMovementCorrection(
                    1, 0, 71, 71, 42, Vec3.ZERO, Vec3.ZERO, 0, 0, false, BedrockCoordinateFrame.IDENTITY, sent));
            assertTrue(teleports.isPendingSetback());
            assertTrue(teleports.blockOffsets);
            assertEquals(1, teleports.queuedVehicleTeleportCount());

            player.lastTransactionReceived.set(newer);
            teleports.completeBedrockMovementCorrection(new BedrockMovementCorrection(
                    2, 0, 71, 71, 43, Vec3.ZERO, Vec3.ZERO, 0, 0, false, BedrockCoordinateFrame.IDENTITY, newer));
            assertFalse(teleports.isPendingSetback());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void unmappableVehicleFrameInMountWindowStagesRejection() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int entityId = 71;
            player.bedrockState.offerAuthInputFrame(
                    BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(944).build());
            player.compensatedEntities.addEntity(entityId, EntityTypesCompat.HORSE, Vec3.ZERO, 0.0F, 0.0F, 0);
            ((PacketEntityHorse) player.compensatedEntities.getEntity(entityId)).hasSaddle = true;
            // Mount window: the server passenger timeline is installed while getRiding() is not.
            player.compensatedEntities.vehicles.setServerVehicle(
                    entityId, new int[]{player.entityID}, player.lastTransactionSent.get());

            // An unmapped vehicle ID must not allow unchecked movement through.
            BedrockAuthInputFrame frame = BedrockAuthInputFrame.builder(player.playerUUID)
                    .protocolVersion(944)
                    .clientTick(17L)
                    .position(new Vec3(0.0D, 80.0D, 0.0D))
                    .packetPosition(new Vec3(0.0D, 80.0D, 0.0D))
                    .predictedVehicleId(-1L)
                    .build();
            assertNull(player.checkManager.getSimulationProcessor().processBedrockAuthInputFrame(
                    frame, BedrockPredictionTrigger.OFFLINE_REPLAY));
            assertTrue(player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());

            ServerboundMoveVehiclePacket packet = vehiclePacket(new Vec3(0.0D, 80.0D, 0.0D));
            PacketReceiveEvent event = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(event, player, packet);
            assertTrue(event.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void mountHorse(CultPlayer player, int entityId) {
        mountHorse(player, entityId, EntityTypesCompat.HORSE);
    }

    private static void mountHorse(CultPlayer player, int entityId, net.minecraft.world.entity.EntityType<?> type) {
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(944).build());
        player.compensatedEntities.addEntity(entityId, type, Vec3.ZERO, 0.0F, 0.0F, 0);
        ((PacketEntityHorse) player.compensatedEntities.getEntity(entityId)).hasSaddle = true;
        player.compensatedEntities.vehicles.setServerVehicle(entityId, new int[]{player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(entityId, new int[]{player.entityID});
    }

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
            player.packetStateData.rejectBedrockTranslatedMovement();
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
