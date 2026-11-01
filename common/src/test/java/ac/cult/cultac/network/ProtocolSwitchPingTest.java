package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** A ping read together with a terminal packet disconnects the client, so none is written until the client acks it. */
class ProtocolSwitchPingTest {
    private static final Vec3 SAFE = new Vec3(4, 64, 2);

    @Test
    void noPingBetweenStartConfigurationAndItsAcknowledgement() throws Exception {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            assertEquals(ConnectionPhase.PLAY, connection.phase(PacketDirection.SERVERBOUND));
            assertEquals(ConnectionPhase.PLAY, connection.phase(PacketDirection.CLIENTBOUND));

            connection.forwarded(ClientboundPackets.START_CONFIGURATION, null);
            assertHeld(player, channel);

            connection.forwarded(ServerboundPackets.CONFIGURATION_ACKNOWLEDGED, null);
            assertPolledPing(player, channel);
        });
    }

    @Test
    void noPingBetweenFinishConfigurationAndItsAcknowledgement() throws Exception {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            connection.forwarded(ClientboundPackets.START_CONFIGURATION, null);
            connection.forwarded(ServerboundPackets.CONFIGURATION_ACKNOWLEDGED, null);
            assertPolledPing(player, channel);

            connection.forwarded(ClientboundPackets.FINISH_CONFIGURATION, null);
            assertEquals(ConnectionPhase.PLAY, connection.phase(PacketDirection.CLIENTBOUND));
            assertEquals(ConnectionPhase.CONFIGURATION, connection.phase(PacketDirection.SERVERBOUND));
            assertHeld(player, channel);

            connection.forwarded(ServerboundPackets.FINISH_CONFIGURATION, null);
            assertPolledPing(player, channel);
        });
    }

    private static void assertHeld(CultPlayer player, EmbeddedChannel channel) {
        drain(channel);
        int sent = player.lastTransactionSent.get();
        poll(player, channel);
        assertEquals(-1, player.sendTrailingTransaction());
        assertEquals(-1, player.sendTransactionAndGetId());
        assertNull(player.createTrackedTransactionPacketForDeferredSend());
        channel.runPendingTasks();
        assertEquals(sent, player.lastTransactionSent.get(), "no ping id was allocated");
        assertEquals(0, drain(channel), "no ping was written");
    }

    private static void assertPolledPing(CultPlayer player, EmbeddedChannel channel) {
        drain(channel);
        int sent = player.lastTransactionSent.get();
        poll(player, channel);
        assertEquals(sent + 1, player.lastTransactionSent.get());
        assertEquals(1, drain(channel), "the next poll pings");
    }

    private static void poll(CultPlayer player, EmbeddedChannel channel) {
        player.lastTransSent = 1;
        player.pollData();
        channel.runPendingTasks();
    }

    private static void withPlayer(Consumer<CultPlayer> body) throws Exception {
        try (var services = new RecordConsumerServices()) {
            OfflinePlayers.withSafePlayer(SAFE, player -> {
                PlatformPlayer platform = mock(PlatformPlayer.class);
                player.user.bind(platform);
                player.platformPlayer = platform;
                body.accept(player);
            });
        }
    }

    private static int drain(EmbeddedChannel channel) {
        int frames = 0;
        Object written;
        while ((written = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(written);
            frames++;
        }
        return frames;
    }
}
