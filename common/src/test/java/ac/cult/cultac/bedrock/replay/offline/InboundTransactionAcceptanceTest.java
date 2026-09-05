package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class InboundTransactionAcceptanceTest {
    @Test
    public void acceptedResponseFactIsScopedToTheCurrentReceiveEvent() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            CultPlayer.TrackedTransaction transaction = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(transaction);

            PacketPingListener listener = new PacketPingListener();
            PacketReceiveEvent accepted = receiveEvent(player, new ServerboundPongPacket(transaction.id()));
            listener.onPong(accepted, player, (ServerboundPongPacket) accepted.getNmsPacket());
            assertTrue(accepted.isAcceptedTransactionResponse());

            PacketReceiveEvent unknown = receiveEvent(player, new ServerboundPongPacket(transaction.id()));
            listener.onPong(unknown, player, (ServerboundPongPacket) unknown.getNmsPacket());
            assertFalse(unknown.isAcceptedTransactionResponse());

            // The accepted fact remains attached to its original event; processing the
            // next pong cannot mutate what downstream consumers saw for the first one.
            assertTrue(accepted.isAcceptedTransactionResponse());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static CultPlayer.TrackedTransaction createTrackedTransaction(CultPlayer player) throws Exception {
        Method method = CultPlayer.class.getDeclaredMethod("createTrackedTransaction");
        method.setAccessible(true);
        return (CultPlayer.TrackedTransaction) method.invoke(player);
    }

    private static PacketReceiveEvent receiveEvent(CultPlayer player, ServerboundPongPacket packet) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static CultPlayer offlineJavaPlayer() {
        UUID playerId = UUID.fromString("9c5e440b-265d-435f-98f1-1f539659c003");
        User user = new User(
                new User.Profile(playerId, ".Transaction_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
