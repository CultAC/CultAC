package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockPlayerTransportGateTest {
    @Test
    public void mountedRotationUsesJavaRotBehaviorWithoutAnotherPermit() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountImmediately(player, 71);
            CheckManagerListener listener = new CheckManagerListener();
            ServerboundMovePlayerPacket.Rot rotation = new ServerboundMovePlayerPacket.Rot(
                    45.0F, 10.0F, false, false);

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
            ServerboundMovePlayerPacket.Pos position = new ServerboundMovePlayerPacket.Pos(
                    3.0D, 67.0D, 4.0D, false, false);
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
            player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(
                    new Vec3(527.2020263671875, 64.84375, -78.90235137939453), false);
            player.packetStateData.bedrockServerResponse = true;

            ServerboundAcceptTeleportationPacket accept =
                    new ServerboundAcceptTeleportationPacket(teleportId, player.x, player.y, player.z, player.xRot, player.yRot);
            new PacketServerTeleport().onAcceptTeleportation(
                    receiveEvent(player, accept), player, accept);

            ServerboundMovePlayerPacket.PosRot acknowledgement = new ServerboundMovePlayerPacket.PosRot(
                    expected.x, expected.y, expected.z, 15.0F, 5.0F, false, false);
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

    private static void mountImmediately(CultPlayer player, int vehicleId) {
        player.compensatedEntities.vehicles.setServerVehicle(
                vehicleId,
                new int[]{player.entityID},
                player.lastTransactionSent.get());
    }

    private static void seedSetbackAnchor(CultPlayer player, Vec3 position) {
        player.getSetbackTeleportUtil().lastKnownGoodPosition =
                new SetbackPosWithVector(position, Vec3.ZERO, 0);
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
    }

    private static PacketReceiveEvent receiveEvent(CultPlayer player, Packet<?> packet) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }
}
