package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.integration.BedrockNextTickStates;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockTransportAuthorityTest {
    @Test
    public void delayedGfpRebaseAdoptsTheAcknowledgedDestinationInRunnerAndCarry() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil();
            var runner = player.checkManager.getSimulationProcessor();
            var origin = new BedrockCoordinateFrame(2944, 0, 1);
            var operation = new BedrockTeleportOperation(4, BedrockTeleportProvenance.GFP_REBASE, null);
            Vec3 packet = new Vec3(56.96044921875, 65.14468383789062, -90.75442504882812);
            Vec3 localFeet = packet.subtract(0, 1.6200103759765625, 0);
            Vec3 target = origin.toWorld(localFeet);
            var previous = BedrockMovementState.fromPhysicalFeet(
                    new Vec3d(3033.037109375, 61.40412139892578, -57.30000305175781),
                    Vec3d.ZERO,
                    BedrockInputFrame.idle(868),
                    BedrockCollisionFlags.AIR);
            var carry = new BedrockNextTickStates(List.of(
                    new BedrockProfileState.Entry(previous,
                            BedrockMobJumpComponentState.DEFAULT)));
            Field field = runner.getClass().getDeclaredField("profileCarry");
            field.setAccessible(true);
            field.set(runner, carry);
            player.x = previous.physicalFeetPosition().x();
            player.y = previous.physicalFeetPosition().y();
            player.z = previous.physicalFeetPosition().z();
            player.lastTransactionReceived.set(2346);
            long revision = teleports.addImmediateBedrockTransportTeleport(target, false, origin, packet, operation, 2346);
            var input = teleportInput(player, localFeet, packet);
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input)).isTeleport());
            teleports.confirmBedrockOrigin(revision, operation, origin, packet);
            var accepted = teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input));
            assertTrue(accepted.isTeleport());
            runner.applyAcceptedBedrockTeleport(accepted);
            assertEquals(target.x, player.x, 0.001);
            assertEquals(target.y, player.y, 0.001);
            assertEquals(target.z, player.z, 0.001);
            var committed = BedrockProfileState.previousState(
                    runner.getCurrentPredictionCommit().carry());
            assertEquals(target.x, committed.physicalFeetPosition().x(), 0.001);
            assertEquals(target.y, committed.physicalFeetPosition().y(), 0.001);
            assertEquals(target.z, committed.physicalFeetPosition().z(), 0.001);
            assertEquals(origin, committed.coordinateFrame());
            assertEquals(Vec3d.ZERO, committed.velocity());
            assertFalse(teleports.hasPendingBedrockTransportTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockTimerReturnsTransportRejectionDecision() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            TimerCheck timer = player.checkManager.getCheck(TimerCheck.class);
            timer.setEnabled(true);
            timer.resetTimerWindow();
            assertEquals(
                    TimerCheck.BedrockAuthInputDecision.ACCEPT,
                    timer.onBedrockAuthInput());

            setTimerBalance(timer, System.nanoTime() + 1_000_000_000L);

            assertEquals(
                    TimerCheck.BedrockAuthInputDecision.REJECT,
                    timer.onBedrockAuthInput());
            assertEquals(1, player.cancelledPackets.get());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void unrelatedPassengerPacketsDoNotReplaceImmediatePlayerAuthority() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int playerVehicle = 17;
            ClientboundSetPassengersPacket mount = passengersPacket(
                    playerVehicle, player.entityID);
            player.packetEntityReplication.onSetPassengers(
                    sendEvent(player, mount), player, mount);

            assertTrue(player.compensatedEntities.vehicles.isServerPlayerPassengerOf(playerVehicle));
            assertTrue(player.compensatedEntities.vehicles.hasPlayerPassengerState());

            int unrelatedVehicle = 29;
            ClientboundSetPassengersPacket unrelated = passengersPacket(unrelatedVehicle, 1234);
            player.packetEntityReplication.onSetPassengers(
                    sendEvent(player, unrelated), player, unrelated);

            assertEquals(
                    Integer.valueOf(playerVehicle),
                    player.compensatedEntities.vehicles.serverPlayerVehicle);
            assertTrue(player.compensatedEntities.vehicles.isServerPlayerPassengerOf(playerVehicle));
            assertFalse(player.compensatedEntities.vehicles.isServerPlayerPassengerOf(unrelatedVehicle));

            player.compensatedEntities.addEntity(
                    unrelatedVehicle, EntityTypesCompat.OAK_BOAT, Vec3.ZERO, 0.0F, 0.0F, 0);
            assertTrue(player.compensatedEntities.vehicles.applyVehiclePassengers(
                    unrelatedVehicle, new int[]{player.entityID}));
            assertEquals(
                    Integer.valueOf(playerVehicle),
                    player.compensatedEntities.vehicles.serverPlayerVehicle);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void passengerAuthorityCoversDelayedMountAndDismountBoundaries() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int delayedVehicle = 31;
            player.compensatedEntities.addEntity(
                    delayedVehicle, EntityTypesCompat.OAK_BOAT, Vec3.ZERO, 0.0F, 0.0F, 0);
            assertTrue(player.compensatedEntities.vehicles.applyVehiclePassengers(
                    delayedVehicle, new int[]{player.entityID}));
            assertTrue(player.compensatedEntities.vehicles.hasPlayerPassengerState());

            player.compensatedEntities.getSelf().eject();
            player.compensatedEntities.vehicles.setServerVehicleDismount(
                    delayedVehicle, player.lastTransactionSent.get() + 1);
            assertTrue(player.compensatedEntities.vehicles.hasPendingServerDismount());
            assertTrue(player.compensatedEntities.vehicles.hasPlayerPassengerState());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void immediatePassengerAuthorityUsesVehicleSetbackTransport() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int vehicleId = 43;
            Vec3 safePosition = new Vec3(8.0D, 72.0D, -4.0D);
            player.getSetbackTeleportUtil().lastKnownGoodPosition =
                    new SetbackPosWithVector(safePosition, Vec3.ZERO, 0);
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            player.compensatedEntities.vehicles.setServerVehicle(
                    vehicleId, new int[]{player.entityID}, player.lastTransactionSent.get());

            player.getSetbackTeleportUtil().executeNonSimulatingSetback();

            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            channel.runPendingTasks();
            boolean sentVehicleSetback = false;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                sentVehicleSetback |= outbound instanceof ClientboundMoveVehiclePacket;
            }
            assertTrue(sentVehicleSetback);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void passengerReceiptSupersedesEarlierPlayerTeleports() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            enableTransactionPackets(player);
            var teleports = player.getSetbackTeleportUtil();
            Vec3 earlier = new Vec3(2, 64, 3);
            teleports.addImmediateBedrockTransportTeleport(earlier, false);
            Runnable mountReceipt = teleports.captureBedrockVehicleMount();
            Vec3 later = earlier.add(5, 0, 0);
            teleports.addImmediateBedrockTransportTeleport(later, false);
            mountReceipt.run();
            assertTrue(teleports.hasPendingBedrockTransportTeleport());
            assertEquals(1, teleports.pendingTeleports.size());
            assertEquals(later, teleports.pendingTeleports.peek().getLocation());
            teleports.captureBedrockVehicleMount().run();
            assertFalse(teleports.hasPendingBedrockTransportTeleport());

        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void nativePassengerReceiptSupersedesThePrecedingCultSetback() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            enableTransactionPackets(player);
            var teleports = player.getSetbackTeleportUtil();
            teleports.addSentTeleport(Vec3.ZERO, player.lastTransactionSent.get(), new RelativeFlag(0), false, 7);
            teleports.addImmediateBedrockTransportTeleport(Vec3.ZERO, false, BedrockCoordinateFrame.IDENTITY, null,
                    new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK,
                            teleports.getRequiredSetBack().getTeleportData().getTransaction()), 0);
            var required = teleports.getRequiredSetBack();
            player.compensatedEntities.addEntity(43, EntityTypesCompat.OAK_BOAT, Vec3.ZERO, 0, 0, 0);
            var packet = passengersPacket(43, player.entityID);
            player.packetEntityReplication.onSetPassengers(sendEvent(player, packet), player, packet);
            int proof = player.compensatedEntities.vehicles.serverPlayerVehicleTransaction;
            player.lastTransactionReceived.set(proof);
            player.latencyUtils.handleNettySyncTransaction(proof);
            assertTrue(teleports.hasPendingBedrockTransportTeleport());
            teleports.captureBedrockVehicleMount().run();
            assertFalse(teleports.hasPendingBedrockTransportTeleport());
            assertFalse(teleports.isPendingSetback());
            assertTrue(required.isComplete());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void gfpTeleportRetryKeepsOriginalAuthorityAndReceiptInEitherOrder() {
        // Capture 1789858615390: HANDLE_TELEPORT at tick 867 preceded the origin receipt;
        // the retry at tick 876 must still complete the original Bedrock teleport.
        OfflineCultTestBootstrap.installConfig();
        for (boolean receiptBeforeRetry : new boolean[]{false, true}) {
            CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
            try {
                var teleports = player.getSetbackTeleportUtil();
                var origin = new BedrockCoordinateFrame(16528, 0, 3);
                var operation = new BedrockTeleportOperation(4, BedrockTeleportProvenance.GEYSER, null);
                Vec3 localFeet = new Vec3(24.5, 62, 2.5);
                Vec3 target = origin.toWorld(localFeet);
                Vec3 localPacket = localFeet.add(0, 1.6200103759765625, 0);

                player.lastTransactionReceived.set(10);
                long first = teleports.addImmediateBedrockTransportTeleport(target, false, origin, localPacket, operation, 10);
                var input = teleportInput(player, localFeet, localPacket);
                assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input)).isTeleport());
                if (receiptBeforeRetry) teleports.confirmBedrockOrigin(first, operation, origin, localPacket);
                teleports.addImmediateBedrockTransportTeleport(target, false, origin, localPacket, operation, 11);
                if (!receiptBeforeRetry) teleports.confirmBedrockOrigin(first, operation, origin, localPacket);

                var accepted = teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input));
                assertTrue(accepted.isTeleport());
                assertTrue(teleports.hasFullyLoaded);
                assertEquals(10, accepted.getTeleportData().getTransaction());
                assertEquals(target, accepted.getTeleportData().getLocation());
                assertFalse(teleports.hasPendingBedrockTransportTeleport());
                player.checkManager.getSimulationProcessor().applyAcceptedBedrockTeleport(accepted);
                assertEquals(target.x, player.x, 0.001);
                assertEquals(target.y, player.y, 0.001);
                assertEquals(target.z, player.z, 0.001);
            } finally {
                OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            }
        }
    }

    @Test
    public void gfpReceiptCannotProveDifferentOperationOrDestination() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil();
            var origin = new BedrockCoordinateFrame(4096, 0, 1);
            var firstOperation = new BedrockTeleportOperation(1, BedrockTeleportProvenance.GFP_REBASE, null);
            var secondOperation = new BedrockTeleportOperation(2, BedrockTeleportProvenance.GFP_REBASE, null);
            Vec3 local = new Vec3(8.5, 64, 2.5);
            Vec3 packet = local.add(0, 1.6200103759765625, 0);
            long first = teleports.addImmediateBedrockTransportTeleport(origin.toWorld(local), false, origin, packet, firstOperation, 0);
            // The first operation's revised target must not inherit a proof for its old target.
            long changed = teleports.addImmediateBedrockTransportTeleport(origin.toWorld(local.add(1, 0, 0)), false,
                    origin, packet.add(1, 0, 0), firstOperation, 0);
            teleports.addImmediateBedrockTransportTeleport(origin.toWorld(local), false, origin, packet, secondOperation, 0);
            teleports.confirmBedrockOrigin(first, firstOperation, origin, packet);
            var input = teleportInput(player, local, packet);
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input)).isTeleport());
            var changedInput = teleportInput(player, local.add(1, 0, 0), packet.add(1, 0, 0));
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(changedInput)).isTeleport());
            teleports.confirmBedrockOrigin(changed, firstOperation, origin, packet.add(1, 0, 0));
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(changedInput)).isTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void ownedNativeSetbackSurvivesOtherTeleportsAndRequiresItsExactReceipt() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil();
            Vec3 target = new Vec3(527.2020263671875, 64.84375, -78.90235137939453);
            teleports.addSentTeleport(target, 10, new RelativeFlag(0), false, 0);
            teleports.pendingTeleports.clear();
            var operation = new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK, 10);
            var origin = new BedrockCoordinateFrame(512, 0, 1);
            Vec3 local = origin.toLocal(target);
            Vec3 packet = local.add(0, 1.6200103759765625, 0);
            var input = teleportInput(player, local, packet).resolveCoordinates(origin);
            long revision = teleports.addImmediateBedrockTransportTeleport(target, false,
                    origin, packet, operation, 10);
            player.lastTransactionReceived.set(10);
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(input).isTeleport());
            Vec3 unrelated = target.add(5, 0, 0);
            teleports.addImmediateBedrockTransportTeleport(unrelated, false);
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(unrelated).isTeleport());
            assertTrue(teleports.isPendingSetback());
            assertTrue(teleports.hasPendingBedrockTransportTeleport());
            teleports.confirmBedrockOrigin(revision, operation, origin, packet);
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(teleportInput(player, local.add(0.001, 0, 0), packet.add(0.001, 0, 0)).resolveCoordinates(origin)).isTeleport());
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(input).isTeleport());
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.hasPendingBedrockTransportTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockAuthInputFrame teleportInput(CultPlayer player, Vec3 localFeet, Vec3 localPacket) {
        return BedrockAuthInputFrame.builder(player.playerUUID).clientTick(876).position(localFeet)
                .packetPosition(localPacket).rawInputFlags(1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal()).build();
    }

    private static void enableTransactionPackets(CultPlayer player) throws ReflectiveOperationException {
        Field handle = User.class.getDeclaredField("handle");
        handle.setAccessible(true);
        handle.set(player.user, Mockito.mock(ServerPlayer.class));
    }

    private static PacketSendEvent sendEvent(
            CultPlayer player,
            ClientboundSetPassengersPacket packet
    ) {
        return new PacketSendEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static ClientboundSetPassengersPacket passengersPacket(
            int vehicleId,
            int... passengers
    ) {
        ClientboundSetPassengersPacket packet = Mockito.mock(ClientboundSetPassengersPacket.class);
        Mockito.when(packet.getVehicle()).thenReturn(vehicleId);
        Mockito.when(packet.getPassengers()).thenReturn(passengers);
        return packet;
    }

    private static void setTimerBalance(TimerCheck timer, long balance) throws ReflectiveOperationException {
        Field field = AbstractTimerCheck.class.getDeclaredField("timerBalanceRealTime");
        field.setAccessible(true);
        field.setLong(timer, balance);
    }
}
