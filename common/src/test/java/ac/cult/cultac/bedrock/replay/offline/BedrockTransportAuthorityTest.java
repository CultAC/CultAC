package ac.cult.cultac.bedrock.replay.offline;

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
            teleports.addSentTeleport(earlier, player.lastTransactionSent.get(), new RelativeFlag(0), true, 7);
            teleports.addImmediateBedrockTransportTeleport(earlier, false);
            player.compensatedEntities.addEntity(43, EntityTypesCompat.OAK_BOAT, earlier, 0, 0, 0);
            var packet = passengersPacket(43, player.entityID);
            player.packetEntityReplication.onSetPassengers(sendEvent(player, packet), player, packet);
            int proof = player.compensatedEntities.vehicles.serverPlayerVehicleTransaction;
            assertTrue(teleports.hasPendingBedrockTransportTeleport());

            player.lastTransactionReceived.set(proof - 1);
            player.latencyUtils.handleNettySyncTransaction(proof - 1);
            assertTrue(teleports.hasPendingBedrockTransportTeleport());

            // This later server teleport cannot be retired by the earlier mount's receipt.
            Vec3 later = earlier.add(5, 0, 0);
            teleports.addSentTeleport(later, proof + 1, new RelativeFlag(0), true, 8);
            var required = teleports.getRequiredSetBack();
            player.lastTransactionReceived.set(proof);
            player.latencyUtils.handleNettySyncTransaction(proof);
            assertFalse(teleports.hasPendingBedrockTransportTeleport());
            assertTrue(teleports.hasPendingPlayerPositionTeleport());
            assertFalse(required.isComplete());
            assertEquals(later, teleports.lastKnownGoodPosition.getPos());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void passengerReceiptCannotRetireACultSetback() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            enableTransactionPackets(player);
            var teleports = player.getSetbackTeleportUtil();
            teleports.addSentTeleport(Vec3.ZERO, player.lastTransactionSent.get(), new RelativeFlag(0), false, 7);
            teleports.addImmediateBedrockTransportTeleport(Vec3.ZERO, false);
            var required = teleports.getRequiredSetBack();
            player.compensatedEntities.addEntity(43, EntityTypesCompat.OAK_BOAT, Vec3.ZERO, 0, 0, 0);
            var packet = passengersPacket(43, player.entityID);
            player.packetEntityReplication.onSetPassengers(sendEvent(player, packet), player, packet);
            int proof = player.compensatedEntities.vehicles.serverPlayerVehicleTransaction;
            player.lastTransactionReceived.set(proof);
            player.latencyUtils.handleNettySyncTransaction(proof);
            assertTrue(teleports.hasPendingBedrockTransportTeleport());
            assertTrue(teleports.isPendingSetback());
            assertFalse(required.isComplete());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
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
