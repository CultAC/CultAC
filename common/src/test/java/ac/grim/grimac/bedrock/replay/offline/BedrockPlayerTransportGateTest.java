package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.events.packets.listeners.CheckManagerListener;
import ac.grim.grimac.events.packets.listeners.PacketPlayerSteer;
import ac.grim.grimac.events.packets.listeners.PacketServerTeleport;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.teleport.RelativeFlag;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.SetbackPosWithVector;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockPlayerTransportGateTest {
    @Test
    public void rejectedAuthInputSuppressesTranslatedInputUntilMovementConsumesIt() {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.packetStateData.rejectBedrockTranslatedMovement(19L);
            ServerboundPlayerInputPacket inputPacket = new ServerboundPlayerInputPacket(
                    new Input(true, false, false, false, false, true, false));
            PacketReceiveEvent inputEvent = receiveEvent(player, inputPacket);

            new CheckManagerListener().onPlayerInput(inputEvent, player, inputPacket);
            new PacketPlayerSteer().onPlayerInput(inputEvent, player, inputPacket);

            assertTrue(inputEvent.isCancelled());
            assertFalse(player.isSneaking);
            assertTrue(player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());

            ServerboundMovePlayerPacket.Pos translatedMove = new ServerboundMovePlayerPacket.Pos(
                    0.5D, 64.0D, 0.5D, false, false);
            PacketReceiveEvent movementEvent = receiveEvent(player, translatedMove);
            new CheckManagerListener().onMovePlayer(movementEvent, player, translatedMove);

            assertTrue(movementEvent.isCancelled());
            assertFalse(player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void mountedRotationUsesJavaRotBehaviorWithoutAnotherPermit() {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
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

            player.packetStateData.rejectBedrockTranslatedMovement(21L);
            PacketReceiveEvent rejected = receiveEvent(player, rotation);
            listener.onMovePlayer(rejected, player, rotation);
            assertTrue(rejected.isCancelled());
            assertFalse(player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void ordinaryPlayerPositionWhileMountedIsCancelledAndSetBack() {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
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
    public void bedrockMountedTeleportEchoUsesTheExistingTeleportQueue() {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            mountImmediately(player, 79);
            seedSetbackAnchor(player, new Vec3(0.5D, 64.0D, 0.5D));
            Vec3 expected = new Vec3(4.0D, 70.0D, -3.0D);
            int pendingBefore = player.getSetbackTeleportUtil().pendingTeleports.size();
            int teleportId = 37;
            player.getSetbackTeleportUtil().addSentTeleport(
                    expected,
                    player.lastTransactionSent.get(),
                    new RelativeFlag(0),
                    true,
                    teleportId);

            ServerboundAcceptTeleportationPacket accept =
                    new ServerboundAcceptTeleportationPacket(teleportId);
            new PacketServerTeleport().onAcceptTeleportation(
                    receiveEvent(player, accept), player, accept);

            ServerboundMovePlayerPacket.PosRot acknowledgement = new ServerboundMovePlayerPacket.PosRot(
                    expected.x, expected.y, expected.z, 15.0F, 5.0F, false, false);
            PacketReceiveEvent event = receiveEvent(player, acknowledgement);

            new CheckManagerListener().onMovePlayer(event, player, acknowledgement);

            assertFalse(event.isCancelled());
            assertTrue(player.getSetbackTeleportUtil().matchesPendingBedrockTeleportPosition(expected));
            assertTrue(player.getSetbackTeleportUtil().pendingTeleports.size() > pendingBefore);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void mountImmediately(GrimPlayer player, int vehicleId) {
        player.compensatedEntities.vehicles.setServerVehicle(
                vehicleId,
                new int[]{player.entityID},
                player.lastTransactionSent.get());
    }

    private static void seedSetbackAnchor(GrimPlayer player, Vec3 position) {
        player.getSetbackTeleportUtil().lastKnownGoodPosition =
                new SetbackPosWithVector(position, Vec3.ZERO, 0);
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
    }

    private static PacketReceiveEvent receiveEvent(GrimPlayer player, Packet<?> packet) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }
}
