package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;
import org.mockito.Mockito;

public final class BedrockTransportAuthorityTest {
    @Test
    public void bedrockTimerReturnsTransportRejectionDecision() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            TimerCheck timer = player.checkManager.getCheck(TimerCheck.class);
            timer.setEnabled(true);
            timer.resetTimerWindow();
            assertEquals(TimerCheck.BedrockAuthInputDecision.ACCEPT, timer.onBedrockAuthInput());

            setTimerBalance(timer, System.nanoTime() + 1_000_000_000L);

            assertEquals(TimerCheck.BedrockAuthInputDecision.REJECT, timer.onBedrockAuthInput());
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
            ClientboundSetPassengers mount = passengersPacket(playerVehicle, player.entityID);
            player.packetEntityReplication.onSetPassengers(sendEvent(player, mount), player, mount);

            assertTrue(player.compensatedEntities.vehicles.isServerPlayerPassengerOf(playerVehicle));
            assertTrue(player.compensatedEntities.vehicles.hasPlayerPassengerState());

            int unrelatedVehicle = 29;
            ClientboundSetPassengers unrelated = passengersPacket(unrelatedVehicle, 1234);
            player.packetEntityReplication.onSetPassengers(sendEvent(player, unrelated), player, unrelated);

            assertEquals(Integer.valueOf(playerVehicle), player.compensatedEntities.vehicles.serverPlayerVehicle);
            assertTrue(player.compensatedEntities.vehicles.isServerPlayerPassengerOf(playerVehicle));
            assertFalse(player.compensatedEntities.vehicles.isServerPlayerPassengerOf(unrelatedVehicle));

            player.compensatedEntities.addEntity(unrelatedVehicle, EntityTypeIds.OAK_BOAT, Vec3.ZERO, 0.0F, 0.0F, 0);
            assertTrue(player.compensatedEntities.vehicles.applyVehiclePassengers(
                    unrelatedVehicle, new int[] {player.entityID}));
            assertEquals(Integer.valueOf(playerVehicle), player.compensatedEntities.vehicles.serverPlayerVehicle);
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
            player.compensatedEntities.addEntity(delayedVehicle, EntityTypeIds.OAK_BOAT, Vec3.ZERO, 0.0F, 0.0F, 0);
            assertTrue(player.compensatedEntities.vehicles.applyVehiclePassengers(
                    delayedVehicle, new int[] {player.entityID}));
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
                    vehicleId, new int[] {player.entityID}, player.lastTransactionSent.get());

            player.getSetbackTeleportUtil().executeNonSimulatingSetback();

            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            channel.runPendingTasks();
            var runtime = player.user.getCultConnection().dispatcher().runtime();
            int sentVehicleSetbacks = 0;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                try {
                    if (outbound instanceof io.netty.buffer.ByteBuf frame) {
                        var decoded = runtime.decode(
                                ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, Wire.readVarInt(frame), frame);
                        assertFalse(frame.isReadable());
                        if (decoded instanceof ClientboundMoveVehicle correction) {
                            sentVehicleSetbacks++;
                            assertEquals(
                                    new Vec3d(safePosition.x, safePosition.y, safePosition.z), correction.position());
                            assertEquals(player.xRot, correction.yaw(), 0.0F);
                            assertEquals(player.yRot, correction.pitch(), 0.0F);
                        }
                    }
                } finally {
                    io.netty.util.ReferenceCountUtil.release(outbound);
                }
            }
            assertEquals(1, sentVehicleSetbacks);
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
            teleports.addImmediateBedrockTransportTeleport(
                    Vec3.ZERO,
                    false,
                    BedrockCoordinateFrame.IDENTITY,
                    null,
                    new BedrockTeleportOperation(
                            1,
                            BedrockTeleportProvenance.CULT_SETBACK,
                            teleports.getRequiredSetBack().getTeleportData().getTransaction()),
                    0);
            var required = teleports.getRequiredSetBack();
            player.compensatedEntities.addEntity(43, EntityTypeIds.OAK_BOAT, Vec3.ZERO, 0, 0, 0);
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
            long revision =
                    teleports.addImmediateBedrockTransportTeleport(target, false, origin, packet, operation, 10);
            player.lastTransactionReceived.set(10);
            assertFalse(teleports.acknowledgeBedrockTeleportFrame(input).isTeleport());
            Vec3 unrelated = target.add(5, 0, 0);
            teleports.addImmediateBedrockTransportTeleport(unrelated, false);
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(unrelated).isTeleport());
            assertTrue(teleports.isPendingSetback());
            assertTrue(teleports.hasPendingBedrockTransportTeleport());
            teleports.confirmBedrockOrigin(revision, operation, origin, packet);
            assertFalse(teleports
                    .acknowledgeBedrockTeleportFrame(
                            teleportInput(player, local.add(0.001, 0, 0), packet.add(0.001, 0, 0))
                                    .resolveCoordinates(origin))
                    .isTeleport());
            double nextWireY = Math.nextUp((float) packet.y);
            assertFalse(teleports
                    .acknowledgeBedrockTeleportFrame(teleportInput(
                                    player,
                                    local.add(0, nextWireY - packet.y, 0),
                                    new Vec3(packet.x, nextWireY, packet.z))
                            .resolveCoordinates(origin))
                    .isTeleport());
            assertTrue(teleports.hasPendingBedrockTransportTeleport());
            assertTrue(teleports.acknowledgeBedrockTeleportFrame(input).isTeleport());
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.hasPendingBedrockTransportTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockAuthInputFrame teleportInput(CultPlayer player, Vec3 localFeet, Vec3 localPacket) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .clientTick(876)
                .position(localFeet)
                .packetPosition(localPacket)
                .rawInputFlags(1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal())
                .build();
    }

    private static void enableTransactionPackets(CultPlayer player) throws ReflectiveOperationException {
        player.user.bind(Mockito.mock(ac.cult.cultac.platform.api.player.PlatformPlayer.class));
    }

    private static PacketSendEvent<ClientboundSetPassengers> sendEvent(
            CultPlayer player, ClientboundSetPassengers packet) {
        return new PacketSendEvent<>(
                player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
    }

    private static ClientboundSetPassengers passengersPacket(int vehicleId, int... passengers) {
        return new ClientboundSetPassengers(
                vehicleId, java.util.Arrays.stream(passengers).boxed().toList());
    }

    private static void setTimerBalance(TimerCheck timer, long balance) throws ReflectiveOperationException {
        Field field = AbstractTimerCheck.class.getDeclaredField("timerBalanceRealTime");
        field.setAccessible(true);
        field.setLong(timer, balance);
    }
}
