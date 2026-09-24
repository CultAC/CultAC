package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.protocol.*;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedrockSetbackRecoveryTest {
    @Test public void newerOwnedTeleportRetiresOlderBoundaryButStillRequiresItsExactResponse() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil();
            Vec3 first = new Vec3(1, 64, 2), second = new Vec3(4, 64, 2);
            var old = own(player, first, 10, 1);
            own(player, second, 12, 2);
            player.lastTransactionReceived.set(100);
            assertEquals(1, teleports.pendingTeleports.size());
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(first).isTeleport());
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(second.add(1, 0, 0)).isTeleport());
            assertTrue(teleports.isPendingSetback());
            teleports.addImmediateBedrockTransportTeleport(first, false, BedrockCoordinateFrame.IDENTITY, null, old, 10);
            assertEquals(1, teleports.pendingTeleports.size());
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(second).isTeleport());
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.mustAcknowledgeBedrockTransportTeleport());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @Test public void originChangeRequiresFreshReceiptAndReencodedWorldTarget() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil();
            var oldOrigin = new BedrockCoordinateFrame(512, 0, 1);
            var newOrigin = new BedrockCoordinateFrame(1024, 0, 2);
            Vec3 worldTarget = new Vec3(527, 64, 2);
            teleports.addSentTeleport(worldTarget, 10, new RelativeFlag(0), false, 1);
            teleports.pendingTeleports.clear();
            var operation = new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK, 10);
            Vec3 oldLocal = oldOrigin.toLocal(worldTarget), oldPacket = packet(oldLocal);
            long first = teleports.addImmediateBedrockTransportTeleport(worldTarget, false, oldOrigin, oldPacket, operation, 10);
            teleports.confirmBedrockOrigin(first, operation, oldOrigin, oldPacket);
            Vec3 unrelated = new Vec3(20, 64, 2);
            var other = new BedrockTeleportOperation(2, BedrockTeleportProvenance.GEYSER, null);
            long second = teleports.addImmediateBedrockTransportTeleport(newOrigin.toWorld(unrelated), false,
                    newOrigin, packet(unrelated), other, 11);
            teleports.confirmBedrockOrigin(second, other, newOrigin, packet(unrelated));
            player.lastTransactionReceived.set(100);
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input(player, unrelated))).isTeleport());
            assertTrue(teleports.isPendingSetback());

            var retryOrigin = new BedrockCoordinateFrame(1536, 0, 3);
            Vec3 retryLocal = retryOrigin.toLocal(worldTarget), retryPacket = packet(retryLocal);
            long retry = teleports.addImmediateBedrockTransportTeleport(worldTarget, false, retryOrigin, retryPacket, operation, 12);
            teleports.confirmBedrockOrigin(first, operation, oldOrigin, oldPacket);
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input(player, retryLocal))).isTeleport());
            teleports.confirmBedrockOrigin(retry, operation, retryOrigin, retryPacket);
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input(player, oldLocal))).isTeleport());
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input(player, retryLocal.add(1, 0, 0)))).isTeleport());
            var accepted = teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input(player, retryLocal)));
            assertTrue(accepted.isTeleport());
            assertEquals(worldTarget, accepted.getTeleportData().getLocation());
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.mustAcknowledgeBedrockTransportTeleport());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @Test public void delayedMountReceiptCannotCancelANewerPlayerSetback() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil();
            own(player, new Vec3(1, 64, 2), 10, 1);
            Runnable received = teleports.captureBedrockVehicleMount();
            Vec3 newer = new Vec3(4, 64, 2);
            own(player, newer, 12, 2);
            var required = teleports.getRequiredSetBack();
            teleports.blockOffsets = true;
            received.run();
            assertSame(required, teleports.getRequiredSetBack());
            assertTrue(teleports.isPendingSetback());
            assertTrue(teleports.blockOffsets);
            assertEquals(1, teleports.pendingTeleports.size());
            player.lastTransactionReceived.set(100);
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(newer).isTeleport());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @Test public void mountReceiptCancelsRetriesOfThePrecedingSetback() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil();
            Vec3 target = new Vec3(1, 64, 2);
            var operation = own(player, target, 10, 1);
            Runnable received = teleports.captureBedrockVehicleMount();
            teleports.addImmediateBedrockTransportTeleport(target, false, BedrockCoordinateFrame.IDENTITY, null, operation, 11);
            assertTrue(teleports.isPendingSetback());
            received.run();
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.mustAcknowledgeBedrockTransportTeleport());
            teleports.addImmediateBedrockTransportTeleport(target, false, BedrockCoordinateFrame.IDENTITY, null, operation, 12);
            assertTrue(teleports.pendingTeleports.isEmpty());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @Test public void dismountAndRemovalTransferTheSavedSeatWithoutVehicleHistoryOrClientPosition() {
        OfflineCultTestBootstrap.installConfig();
        for (int transition = 0; transition < 4; transition++) {
            boolean removal = (transition & 1) != 0;
            CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
            try {
                mount(player, 71);
                var teleports = player.getSetbackTeleportUtil();
                var target = new Vec3(4, 64, 2);
                teleports.lastKnownGoodPosition = new SetbackPosWithVector(target, Vec3.ZERO, 0);
                teleports.hasFullyLoaded = teleports.hasFullyJoined = true;
                teleports.executeNonSimulatingSetback();
                var original = teleports.getRequiredSetBack();
                assertEquals(1, teleports.queuedVehicleTeleportCount());
                player.x = 999; player.y = 999; player.z = 999;
                player.compensatedEntities.getEntity(71).clientPhysicalPosition = new Vec3(999, 999, 999);
                if ((transition & 2) != 0) player.compensatedEntities.getSelf().eject();
                long generation = player.bedrockState.movementCorrections.generation();
                if (removal) {
                    teleports.clearVehicleTeleports();
                    player.compensatedEntities.vehicles.clearServerVehicle();
                    assertSame(original, teleports.getRequiredSetBack());
                    player.compensatedEntities.removeEntity(71);
                } else {
                    player.compensatedEntities.vehicles.setServerVehicleDismount(71, 15);
                    player.compensatedEntities.vehicles.applyVehiclePassengers(71, new int[0]);
                    player.compensatedEntities.vehicles.clearServerVehicle();
                }
                var transferred = teleports.getRequiredSetBack();
                assertNotSame(original, transferred);
                assertFalse(transferred.isVehicle());
                assertNull(transferred.getProfileState());
                assertEquals(Vec3.ZERO, transferred.getVelocity());
                Vec3 seat = transferred.getTeleportData().getLocation();
                assertEquals(4, seat.x, 0);
                assertEquals(64.85, seat.y, 0.00001);
                assertEquals(2, seat.z, 0);
                assertTrue(player.bedrockState.movementCorrections.generation() > generation);
                assertEquals(0, teleports.queuedVehicleTeleportCount());
                assertTrue(teleports.isPendingSetback());
                int transaction = transferred.getTeleportData().getTransaction();
                var operation = new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK, transaction);
                teleports.addImmediateBedrockTransportTeleport(seat, false, BedrockCoordinateFrame.IDENTITY, null, operation, transaction);
                player.lastTransactionReceived.set(100);
                teleports.completeBedrockMovementCorrection(new BedrockMovementCorrection(
                        1, generation, 71, 71, 42, target, Vec3.ZERO, 0, 0, false,
                        BedrockCoordinateFrame.IDENTITY, original.getTeleportData().getTransaction()));
                assertTrue(teleports.isPendingSetback());
                assertFalse(teleports.acknowledgeBedrockTeleportFrame(new Vec3(999, 999, 999)).isTeleport());
                assertTrue(teleports.isPendingSetback());
                assertTrue(teleports.acknowledgeBedrockTeleportFrame(seat).isTeleport());
                assertFalse(teleports.isPendingSetback());
            } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
        }
    }

    @Test public void clientVehicleClaimsCannotSupersedeSetbacksAndLateLinksPreserveNewCorrections() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mount(player, 71);
            var teleports = player.getSetbackTeleportUtil();
            Vec3 target = new Vec3(4, 64, 2);
            teleports.lastKnownGoodPosition = new SetbackPosWithVector(target, Vec3.ZERO, 0);
            teleports.hasFullyLoaded = teleports.hasFullyJoined = true;
            teleports.executeNonSimulatingSetback();
            var original = teleports.getRequiredSetBack();
            Runnable mountReceipt = teleports.captureBedrockVehicleMount();
            var forged = BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(944).clientTick(42)
                    .position(new Vec3(999, 999, 999)).rotation(0, 0, 0).moveVector(0, 0)
                    .reportedEndOfTickVelocity(Vec3.ZERO).predictedVehicleId(72L).predictedVehicleJavaId(72)
                    .vehicleRotation(new BedrockAuthInputFrame.VehicleRotation(0, 0))
                    .rawInputFlags(1L << PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE.ordinal()).build();
            assertNull(player.checkManager.getSimulationProcessor().processBedrockAuthInputFrame(forged,
                    ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger.OFFLINE_REPLAY));
            assertTrue(player.packetStateData.bedrockTranslatedMovement.isRejected());
            assertSame(original, teleports.getRequiredSetBack());
            assertTrue(teleports.isPendingSetback());
            assertEquals(target, teleports.lastKnownGoodPosition.getPos());
            assertEquals(71, player.getRidingVehicleId());

            player.lastTransactionSent.set(original.getTeleportData().getTransaction() + 2);
            teleports.executeTooHighLatencySetback("mount-receipt-order");
            var newer = teleports.getRequiredSetBack();
            long generation = player.bedrockState.movementCorrections.generation();
            mountReceipt.run();
            assertSame(newer, teleports.getRequiredSetBack());
            assertTrue(teleports.isPendingSetback());
            assertEquals(1, teleports.queuedVehicleTeleportCount());
            assertEquals(generation, player.bedrockState.movementCorrections.generation());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @Test public void boatDismountUsesTheSavedBedrockSeat() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(2193).build());
            Vec3 target = new Vec3(4, 64, 2);
            player.compensatedEntities.addEntity(71, EntityTypesCompat.OAK_BOAT, target, 0, 0, 0);
            var boat = player.compensatedEntities.getEntity(71);
            boat.bedrockBoat = ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties.initial(171);
            ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState.initializeBoat(boat,
                    new ac.cult.cultac.bedrock.prediction.geometry.Vec3d(4, 64, 2),
                    ac.cult.cultac.bedrock.prediction.geometry.Vec3d.ZERO, 90, BedrockCoordinateFrame.IDENTITY);
            player.compensatedEntities.vehicles.setServerVehicle(71, new int[]{player.entityID}, 0);
            player.compensatedEntities.vehicles.applyVehiclePassengers(71, new int[]{player.entityID});
            var safe = ac.cult.cultac.bedrock.prediction.integration.BedrockMovementEngine.INSTANCE.captureSetbackState(boat.bedrockPrediction.commit());
            var teleports = player.getSetbackTeleportUtil();
            teleports.lastKnownGoodPosition = new SetbackPosWithVector(target, Vec3.ZERO, 0, safe);
            teleports.hasFullyLoaded = teleports.hasFullyJoined = true;
            teleports.executeNonSimulatingSetback();
            player.x = 999; player.y = 999; player.z = 999;
            player.compensatedEntities.vehicles.applyClientVisibleDismount();
            var required = teleports.getRequiredSetBack();
            assertFalse(required.isVehicle());
            assertNull(required.getProfileState());
            assertEquals(4, required.getTeleportData().getLocation().x, 0);
            assertEquals(63.625, required.getTeleportData().getLocation().y, 0.00001);
            assertEquals(2, required.getTeleportData().getLocation().z, 0);
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @Test public void newServerMountSupersedesVehicleSetbackAndPreservesTheNewActor() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mount(player, 71);
            var teleports = player.getSetbackTeleportUtil();
            teleports.lastKnownGoodPosition = new SetbackPosWithVector(new Vec3(4, 64, 2), Vec3.ZERO, 0);
            teleports.hasFullyLoaded = teleports.hasFullyJoined = true;
            teleports.executeNonSimulatingSetback();
            var original = teleports.getRequiredSetBack();
            Runnable mountReceipt = teleports.captureBedrockVehicleMount();
            mount(player, 72);
            assertSame(original, teleports.getRequiredSetBack());
            assertTrue(teleports.isPendingSetback());
            mountReceipt.run();
            assertFalse(teleports.isPendingSetback());
            assertEquals(72, player.compensatedEntities.getSelf().getRiding().getEntityId());
            assertEquals(0, teleports.queuedVehicleTeleportCount());
            assertTrue(teleports.pendingTeleports.isEmpty());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    private static BedrockTeleportOperation own(CultPlayer player, Vec3 target, int transaction, long sequence) {
        var teleports = player.getSetbackTeleportUtil();
        teleports.addSentTeleport(target, transaction, new RelativeFlag(0), false, 1);
        teleports.pendingTeleports.removeIf(pending -> pending.getBedrockTransportRevision() < 0);
        var operation = new BedrockTeleportOperation(sequence, BedrockTeleportProvenance.CULT_SETBACK, transaction);
        teleports.addImmediateBedrockTransportTeleport(target, false, BedrockCoordinateFrame.IDENTITY, null, operation, transaction);
        return operation;
    }

    private static void mount(CultPlayer player, int id) {
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(944).build());
        player.compensatedEntities.addEntity(id, EntityTypesCompat.HORSE, new Vec3(4, 64, 2), 0, 0, 0);
        ((PacketEntityHorse) player.compensatedEntities.getEntity(id)).hasSaddle = true;
        player.compensatedEntities.vehicles.setServerVehicle(id, new int[]{player.entityID}, 0);
        player.compensatedEntities.vehicles.applyVehiclePassengers(id, new int[]{player.entityID});
    }

    private static Vec3 packet(Vec3 feet) { return feet.add(0, 1.6200103759765625, 0); }
    private static BedrockAuthInputFrame input(CultPlayer player, Vec3 local) {
        return BedrockAuthInputFrame.builder(player.playerUUID).clientTick(50).position(local).packetPosition(packet(local))
                .rawInputFlags(1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal()).build();
    }
}
