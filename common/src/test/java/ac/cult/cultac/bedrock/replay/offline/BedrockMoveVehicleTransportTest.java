package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

public final class BedrockMoveVehicleTransportTest {
    @Test
    public void equineVariantsRequireSaddleAndValidatedMovement() {
        OfflineCultTestBootstrap.installConfig();
        for (var type : java.util.List.of(
                EntityTypeIds.DONKEY, EntityTypeIds.MULE, EntityTypeIds.SKELETON_HORSE, EntityTypeIds.ZOMBIE_HORSE)) {
            CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
            try {
                mountHorse(player, 71, type);
                var horse = (PacketEntityHorse) player.compensatedEntities.getEntity(71);
                assertEquals(
                        horse,
                        ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.controlledVehicle(player));
                assertEquals(1.3965F, ac.cult.cultac.utils.nmsutil.BoundingBoxSize.getWidth(player, horse), 0);
                assertEquals(1.6F, ac.cult.cultac.utils.nmsutil.BoundingBoxSize.getHeight(player, horse), 0);
                horse.hasSaddle = false;
                assertNull(
                        ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.controlledVehicle(player));
                horse.hasSaddle = true;

                var listener = new CheckManagerListener();
                Vec3 position = new Vec3(2.0F, 64.0F, 3.0F);
                var packet = vehiclePacket(position);
                var unchecked = receiveEvent(player, packet);
                listener.onMoveVehicle(unchecked, player, packet);
                assertTrue(unchecked.isCancelled());
                player.packetStateData.bedrockTranslatedMovement.allowVehicle(71);
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
            var packet =
                    new ServerboundMoveVehicle(new Vec3d(position.x, position.y, position.z), 0.0F, 0.0F, false, true);
            var fallback = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(fallback, player, packet);
            assertTrue(fallback.isCancelled());
            assertFalse(player.packetStateData.bedrockTranslatedMovement.hasPending());

            // The preserved native listener forwards both ground values unchanged.
            // Authority is single-use; accepting a projection does not rewrite it.
            for (boolean onGround : new boolean[] {false, true}) {
                var projection = new ServerboundMoveVehicle(packet.position(), 0.0F, 0.0F, onGround, true);
                player.packetStateData.bedrockTranslatedMovement.allowVehicle(71);
                var accepted = receiveEvent(player, projection);
                new CheckManagerListener().onMoveVehicle(accepted, player, projection);
                assertFalse(accepted.isCancelled());
                assertSame(projection, accepted.getPacket());
                assertEquals(onGround, accepted.getPacket().onGround());

                var repeated = receiveEvent(player, projection);
                new CheckManagerListener().onMoveVehicle(repeated, player, projection);
                assertTrue(repeated.isCancelled());
            }
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void horsePermitCannotAuthorizeAnotherTickOrActor() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountHorse(player, 71);
            var listener = new CheckManagerListener();
            Vec3 position = new Vec3(2.0F, 64.0F, 3.0F);
            var packet = vehiclePacket(position);
            player.packetStateData.bedrockTranslatedMovement.allowVehicle(71);
            var tickEnd = ac.cult.cultac.protocol.packet.ServerboundPackets.CLIENT_TICK_END.opaqueValue();
            listener.processClientTickEndReceive(RecordReceiveTestEvents.tickEnd(player), player);
            var nextTick = receiveEvent(player, packet);
            listener.onMoveVehicle(nextTick, player, packet);
            assertTrue(nextTick.isCancelled());

            player.packetStateData.bedrockTranslatedMovement.allowVehicle(72);
            var wrongHorse = receiveEvent(player, packet);
            listener.onMoveVehicle(wrongHorse, player, packet);
            assertTrue(wrongHorse.isCancelled());
            assertFalse(player.packetStateData.bedrockTranslatedMovement.hasPending());

            player.packetStateData.bedrockTranslatedMovement.allowVehicle(71);
            var displacedPacket = vehiclePacket(position.add(1.0D, 0.0D, 0.0D));
            var sameVehicle = receiveEvent(player, displacedPacket);
            listener.onMoveVehicle(sameVehicle, player, displacedPacket);
            assertFalse(sameVehicle.isCancelled());
            assertFalse(player.packetStateData.bedrockTranslatedMovement.hasPending());
            var repeated = receiveEvent(player, displacedPacket);
            listener.onMoveVehicle(repeated, player, displacedPacket);
            assertTrue(repeated.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void pendingSetbackBlocksAnAlreadyGrantedHorseProjection() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountHorse(player, 71);
            var teleports = player.getSetbackTeleportUtil();
            teleports.hasFullyLoaded = true;
            teleports.hasFullyJoined = true;
            teleports.lastKnownGoodPosition = new SetbackPosWithVector(Vec3.ZERO, Vec3.ZERO, 0);
            Vec3 position = new Vec3(2, 64, 3);
            var packet = vehiclePacket(position);
            player.packetStateData.bedrockTranslatedMovement.allowVehicle(71);
            teleports.executeNonSimulatingSetback();
            assertTrue(teleports.blocksBedrockTranslatedMovement());

            var blocked = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(blocked, player, packet);
            assertTrue(blocked.isCancelled());
            assertFalse(player.packetStateData.bedrockTranslatedMovement.hasPending());

            teleports.getRequiredSetBack().setComplete(true);
            player.packetStateData.bedrockTranslatedMovement.allowVehicle(71);
            var moving = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(moving, player, packet);
            assertFalse(moving.isCancelled());
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
            assertEquals(1, teleports.queuedVehicleTeleportCount());

            player.lastTransactionReceived.set(sent - 1);
            assertFalse(teleports
                    .checkVehicleTeleportQueue(71, target.x, target.y, target.z)
                    .isTeleport());
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

            Vec3 position = new Vec3(
                    packet.position().x(),
                    packet.position().y(),
                    packet.position().z());
            player.packetStateData.bedrockTranslatedMovement.allowVehicle(71);
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
            assertEquals(1, teleports.queuedVehicleTeleportCount());

            player.lastTransactionSent.set(sent + 2);
            teleports.executeTooHighLatencySetback("horse-correction-order");
            teleports.blockOffsets = true;
            int newer = teleports.getRequiredSetBack().getTeleportData().getTransaction();
            assertTrue(newer > sent);
            assertEquals(2, teleports.queuedVehicleTeleportCount());

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
            player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                    .protocolVersion(944)
                    .build());
            player.compensatedEntities.addEntity(entityId, EntityTypeIds.HORSE, Vec3.ZERO, 0.0F, 0.0F, 0);
            ((PacketEntityHorse) player.compensatedEntities.getEntity(entityId)).hasSaddle = true;
            // Mount window: the server passenger timeline is installed while getRiding() is not.
            player.compensatedEntities.vehicles.setServerVehicle(
                    entityId, new int[] {player.entityID}, player.lastTransactionSent.get());

            // An unmapped vehicle ID must not allow unchecked movement through.
            BedrockAuthInputFrame frame = BedrockAuthInputFrame.builder(player.playerUUID)
                    .protocolVersion(944)
                    .clientTick(17L)
                    .position(new Vec3(0.0D, 80.0D, 0.0D))
                    .packetPosition(new Vec3(0.0D, 80.0D, 0.0D))
                    .predictedVehicleId(-1L)
                    .rawInputFlags(1L << PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE.ordinal())
                    .build();
            assertNull(player.checkManager
                    .getSimulationProcessor()
                    .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY));
            assertTrue(player.packetStateData.bedrockTranslatedMovement.isRejected());

            ServerboundMoveVehicle packet = vehiclePacket(new Vec3(0.0D, 80.0D, 0.0D));
            PacketReceiveEvent event = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(event, player, packet);
            assertTrue(event.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void mountHorse(CultPlayer player, int entityId) {
        mountHorse(player, entityId, EntityTypeIds.HORSE);
    }

    private static void mountHorse(CultPlayer player, int entityId, int type) {
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .build());
        player.compensatedEntities.addEntity(entityId, type, Vec3.ZERO, 0.0F, 0.0F, 0);
        ((PacketEntityHorse) player.compensatedEntities.getEntity(entityId)).hasSaddle = true;
        player.compensatedEntities.vehicles.setServerVehicle(
                entityId, new int[] {player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(entityId, new int[] {player.entityID});
    }

    @Test
    public void movementWithoutCurrentAuthorityStopsBeforeJavaChecks() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            ServerboundMoveVehicle packet =
                    new ServerboundMoveVehicle(new Vec3d(2.0D, 64.0D, 3.0D), 0.0F, 0.0F, true, true);
            PacketReceiveEvent<ServerboundMoveVehicle> event = receiveEvent(player, packet);
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
    public void mountWindowPassengerAuthorityCannotAuthorizeUnvalidatedVehicleHeight() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int vehicleId = 51;
            player.compensatedEntities.addEntity(vehicleId, EntityTypeIds.HORSE, Vec3.ZERO, 0.0F, 0.0F, 0);
            ((PacketEntityHorse) player.compensatedEntities.getEntity(vehicleId)).hasSaddle = true;
            player.compensatedEntities.vehicles.setServerVehicle(
                    vehicleId, new int[] {player.entityID}, player.lastTransactionSent.get());
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            assertNull(player.compensatedEntities.getSelf().getRiding());
            assertNull(ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.controlledVehicle(player));
            ServerboundMoveVehicle packet = vehiclePacket(new Vec3(2.0D, 80.0D, 3.0D));
            PacketReceiveEvent event = receiveEvent(player, packet);

            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertTrue(event.isCancelled());
            assertEquals(0.0D, player.checkManager.getCheck(BadPacketsJ.class).getViolations(), 0.0D);
            assertNull(player.checkManager.getSimulationProcessor().getLastPrediction());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void pendingTransportTeleportBlocksUnpermittedVehicleMovementButAllowsQueuedEcho() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int vehicleId = 51;
            player.compensatedEntities.vehicles.setServerVehicle(
                    vehicleId, new int[] {player.entityID}, player.lastTransactionSent.get());
            var teleports = player.getSetbackTeleportUtil();
            teleports.hasFullyLoaded = true;
            teleports.hasFullyJoined = true;
            teleports.addImmediateBedrockTransportTeleport(Vec3.ZERO, true);
            assertTrue(teleports.blocksBedrockTranslatedMovement());
            Vec3 position = new Vec3(2, 64, 3);
            var packet = vehiclePacket(position);

            var ordinary = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(ordinary, player, packet);
            assertTrue(ordinary.isCancelled());

            teleports.addVehicleTeleport(vehicleId, player.lastTransactionReceived.get(), position);
            var echo = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(echo, player, packet);
            assertFalse(echo.isCancelled());
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
                    vehicleId, new int[] {player.entityID}, player.lastTransactionSent.get());
            player.packetStateData.bedrockTranslatedMovement.reject();
            ServerboundMoveVehicle packet = vehiclePacket(new Vec3(2.0D, 64.0D, 3.0D));
            PacketReceiveEvent event = receiveEvent(player, packet);

            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertTrue(event.isCancelled());
            assertTrue(player.packetStateData.bedrockTranslatedMovement.isRejected());
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
                    vehicleId, new int[] {player.entityID}, player.lastTransactionSent.get());
            player.getSetbackTeleportUtil().lastKnownGoodPosition =
                    new SetbackPosWithVector(new Vec3(8.0D, 72.0D, -4.0D), Vec3.ZERO, 0);
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            assertTrue(player.getSetbackTeleportUtil().isPendingSetback());

            ServerboundMoveVehicle packet = vehiclePacket(new Vec3(9.0D, 72.0D, -4.0D));
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
            player.getSetbackTeleportUtil()
                    .addVehicleTeleport(vehicleId, player.lastTransactionReceived.get(), position);
            ServerboundMoveVehicle packet = vehiclePacket(position);
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

    private static PacketReceiveEvent<ServerboundMoveVehicle> receiveEvent(
            CultPlayer player, ServerboundMoveVehicle packet) {
        return RecordReceiveTestEvents.vehicle(player, packet);
    }

    private static ServerboundMoveVehicle vehiclePacket(Vec3 position) {
        return new ServerboundMoveVehicle(new Vec3d(position.x, position.y, position.z), 0.0F, 0.0F, true, true);
    }
}
