package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import java.util.UUID;
import org.junit.Test;

public final class BedrockPlayerTransportGateTest {
    @Test
    public void mountedRotationUsesJavaRotBehaviorWithoutAnotherPermit() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountImmediately(player, 71);
            CheckManagerListener listener = new CheckManagerListener();
            ServerboundMovePlayer rotation =
                    new ServerboundMovePlayer(0, 0, 0, 45.0F, 10.0F, false, false, false, true);

            PacketReceiveEvent first = receiveEvent(player, rotation);
            listener.onMovePlayer(first, player, rotation);
            assertFalse(first.isCancelled());

            PacketReceiveEvent nextTick = receiveEvent(player, rotation);
            listener.onMovePlayer(nextTick, player, rotation);
            assertFalse(nextTick.isCancelled());

            player.packetStateData.bedrockTranslatedMovement.reject();
            PacketReceiveEvent rejected = receiveEvent(player, rotation);
            listener.onMovePlayer(rejected, player, rotation);
            assertTrue(rejected.isCancelled());
            assertFalse(player.packetStateData.bedrockTranslatedMovement.isRejected());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void ordinaryPlayerPositionWhileMountedIsCancelledAndSetBack() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountImmediately(player, 73);
            seedSetbackAnchor(player, new Vec3(0.5D, 64.0D, 0.5D));
            ServerboundMovePlayer position =
                    new ServerboundMovePlayer(3.0D, 67.0D, 4.0D, 0, 0, false, false, true, false);
            PacketReceiveEvent event = receiveEvent(player, position);

            new CheckManagerListener().onMovePlayer(event, player, position);

            assertTrue(event.isCancelled());
            assertTrue(player.getSetbackTeleportUtil().isPendingSetback());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void serverResponseDoesNotAcknowledgeRoundedBedrockTeleport() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountImmediately(player, 79);
            seedSetbackAnchor(player, new Vec3(0.5D, 64.0D, 0.5D));
            Vec3 expected = new Vec3(527.2019975614745, 64.84375002384186, -78.90235218181445);
            int pendingBefore = player.getSetbackTeleportUtil().pendingTeleports.size();
            int teleportId = 37;
            player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(
                            new Vec3(527.2020263671875, 64.84375, -78.90235137939453), false);
            player.packetStateData.bedrockServerResponse = true;

            ServerboundAcceptTeleportation accept = new ServerboundAcceptTeleportation(
                    teleportId, new Vec3d(player.x, player.y, player.z), player.xRot, player.yRot);
            new PacketServerTeleport()
                    .onAcceptTeleportation(RecordReceiveTestEvents.teleport(player, accept), player, accept);

            ServerboundMovePlayer acknowledgement = new ServerboundMovePlayer(
                    expected.x, expected.y, expected.z, 15.0F, 5.0F, false, false, true, true);
            PacketReceiveEvent event = receiveEvent(player, acknowledgement);

            new CheckManagerListener().onMovePlayer(event, player, acknowledgement);

            assertFalse(event.isCancelled());
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            assertTrue(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());
            assertTrue(player.getSetbackTeleportUtil().pendingTeleports.size() > pendingBefore);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void teleportAcknowledgingFrameProjectsOffGround() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            seedSetbackAnchor(player, new Vec3(10.0D, 64.0D, 20.0D));
            CheckManagerListener listener = new CheckManagerListener();
            var gate = player.packetStateData.bedrockTranslatedMovement;
            ServerboundMovePlayer projection =
                    new ServerboundMovePlayer(10.0D, 64.0D, 20.0D, 0, 0, true, false, true, false);

            gate.clear();
            gate.markTeleportFrame();
            gate.allowPlayer(true);
            PacketReceiveEvent<ServerboundMovePlayer> teleport = receiveEvent(player, projection);
            listener.onMovePlayer(teleport, player, projection);
            assertFalse(teleport.isCancelled());
            assertFalse(teleport.getPacket().onGround());
            assertFalse(player.packetStateData.lastPacketWasTeleport);

            gate.clear();
            gate.allowPlayer(true);
            PacketReceiveEvent<ServerboundMovePlayer> nextFrame = receiveEvent(player, projection);
            listener.onMovePlayer(nextFrame, player, projection);
            assertFalse(nextFrame.isCancelled());
            assertTrue(nextFrame.getPacket().onGround());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void geyserTeleportResponsePassesOffGroundOncePerAcknowledgement() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            seedSetbackAnchor(player, new Vec3(0.5D, 64.0D, 0.5D));
            CheckManagerListener listener = new CheckManagerListener();
            ServerboundMovePlayer response =
                    new ServerboundMovePlayer(5.0D, 70.0D, 5.0D, 10.0F, 20.0F, true, false, true, true);

            PacketReceiveEvent<ServerboundMovePlayer> unannounced = receiveEvent(player, response);
            listener.onMovePlayer(unannounced, player, response);
            assertTrue(unannounced.isCancelled());

            ServerboundAcceptTeleportation accept =
                    new ServerboundAcceptTeleportation(9, new Vec3d(5.0D, 70.0D, 5.0D), 10.0F, 20.0F);
            listener.onAcceptTeleportation(RecordReceiveTestEvents.teleport(player, accept), player, accept);
            PacketReceiveEvent<ServerboundMovePlayer> answered = receiveEvent(player, response);
            listener.onMovePlayer(answered, player, response);
            assertFalse(answered.isCancelled());
            assertFalse(answered.getPacket().onGround());
            var visible = player.getSetbackTeleportUtil().getClass().getDeclaredField("bedrockPaperVisiblePosition");
            visible.setAccessible(true);
            assertEquals(new Vec3(5.0D, 70.0D, 5.0D), visible.get(player.getSetbackTeleportUtil()));

            PacketReceiveEvent<ServerboundMovePlayer> repeated = receiveEvent(player, response);
            listener.onMovePlayer(repeated, player, response);
            assertTrue(repeated.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bridgedTickEndOnlyClosesTheProjectedFrame() {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BedrockPlayerState state = new BedrockPlayerState(uuid);
        state.setSetbacksEnabled(true);
        User user = OfflineCultTestBootstrap.wireUser(new User.Profile(uuid, ".Bridged_Client"), new Object());
        CultPlayer player = new CultPlayer(user, MovementPlatform.BEDROCK, state);
        try {
            int acceptedTicks = player.packetStateData.acceptedClientTick;
            player.packetStateData.bedrockTranslatedMovement.allowPlayer(true);

            new CheckManagerListener().processClientTickEndReceive(RecordReceiveTestEvents.tickEnd(player), player);

            assertFalse(player.packetStateData.bedrockTranslatedMovement.hasPending());
            // The bridge already counted this client tick when it translated the frame.
            assertEquals(acceptedTicks, player.packetStateData.acceptedClientTick);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void mountImmediately(CultPlayer player, int vehicleId) {
        player.compensatedEntities.vehicles.setServerVehicle(
                vehicleId, new int[] {player.entityID}, player.lastTransactionSent.get());
    }

    private static void seedSetbackAnchor(CultPlayer player, Vec3 position) {
        player.getSetbackTeleportUtil().lastKnownGoodPosition = new SetbackPosWithVector(position, Vec3.ZERO, 0);
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
    }

    private static PacketReceiveEvent<ServerboundMovePlayer> receiveEvent(
            CultPlayer player, ServerboundMovePlayer packet) {
        return RecordReceiveTestEvents.movement(player, packet);
    }
}
