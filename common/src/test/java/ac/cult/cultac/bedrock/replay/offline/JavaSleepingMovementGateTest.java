package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class JavaSleepingMovementGateTest {
    @Test
    public void sleepingPositionInsideHorizontalAndVerticalBoundsCanCommit() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            player.x = 3.0D;
            player.y = 64.0D;
            player.z = -2.0D;
            player.isInBed = true;
            player.bedPosition = new Vec3(3.0D, 64.0D, -2.0D);

            ServerboundMovePlayerPacket.Pos packet = new ServerboundMovePlayerPacket.Pos(
                    3.5D, 64.09D, -1.5D, false, false);
            PacketReceiveEvent event = new PacketReceiveEvent(
                    player.user, packet, ConnectionProtocol.PLAY);

            new CheckManagerListener().onMovePlayer(event, player, packet);

            assertFalse(event.isCancelled());
            assertEquals(3.5D, player.x, 0.0D);
            assertEquals(64.09D, player.y, 0.0D);
            assertEquals(-1.5D, player.z, 0.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void sleepingPositionCannotChainBeyondStableBedAnchor() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            player.x = 3.5D;
            player.y = 64.0D;
            player.z = -2.0D;
            player.isInBed = true;
            player.bedPosition = new Vec3(3.0D, 64.0D, -2.0D);

            ServerboundMovePlayerPacket.Pos packet = new ServerboundMovePlayerPacket.Pos(
                    4.0D, 64.0D, -2.0D, false, false);
            PacketReceiveEvent event = new PacketReceiveEvent(
                    player.user, packet, ConnectionProtocol.PLAY);

            new CheckManagerListener().onMovePlayer(event, player, packet);

            assertTrue(event.isCancelled());
            assertEquals(3.5D, player.x, 0.0D);
            assertEquals(64.0D, player.y, 0.0D);
            assertEquals(-2.0D, player.z, 0.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void sleepingPositionAtVerticalLimitCannotCommit() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            player.x = 3.0D;
            player.y = 0.0D;
            player.z = -2.0D;
            player.isInBed = true;
            player.bedPosition = new Vec3(3.0D, 0.0D, -2.0D);

            ServerboundMovePlayerPacket.Pos packet = new ServerboundMovePlayerPacket.Pos(
                    3.0D, 0.1D, -2.0D, false, false);
            PacketReceiveEvent event = new PacketReceiveEvent(
                    player.user, packet, ConnectionProtocol.PLAY);

            new CheckManagerListener().onMovePlayer(event, player, packet);

            assertTrue(event.isCancelled());
            assertEquals(0.0D, player.y, 0.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static CultPlayer offlineJavaPlayer() {
        UUID playerId = UUID.fromString("df1e6a87-e857-4d54-8178-60b139cacbf8");
        User user = new User(
                new User.Profile(playerId, ".Java_Sleep_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
