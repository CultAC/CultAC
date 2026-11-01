package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Ping and pong are registered in configuration as in play, so the timeout runs in both. It holds only before
 * the first ping, which a client attached in configuration does not get until play traffic starts one, and
 * while a protocol switch is unacknowledged.
 */
class ConfigurationPingTimeoutTest {
    private static final Vec3 SAFE = new Vec3(4, 64, 2);
    private static final long PAST_THE_TIMEOUT = TimeUnit.MINUTES.toNanos(5);

    @Test
    void aClientAttachedInConfigurationIsStillInspected() throws Exception {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
            connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
            player.lastTransSent = 0;
            unansweredFor(player, PAST_THE_TIMEOUT);

            player.pollData();
            assertFalse(exempt(player));
        });
    }

    @Test
    void theTimeoutRunsFromTheFirstPing() throws Exception {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
            connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
            player.lastTransSent = 0;
            unansweredFor(player, PAST_THE_TIMEOUT);
            player.pollData();

            connection.forwarded(ClientboundPackets.FINISH_CONFIGURATION, null);
            connection.forwarded(ServerboundPackets.FINISH_CONFIGURATION, null);
            player.lastTransSent = System.currentTimeMillis();
            player.pollData();
            assertFalse(exempt(player));

            unansweredFor(player, PAST_THE_TIMEOUT);
            player.pollData();
            assertTrue(exempt(player), "an unanswered player in play still times out");
        });
    }

    @Test
    void aClientWithholdingPongsInConfigurationTimesOut() throws Exception {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            player.lastTransSent = System.currentTimeMillis();
            connection.forwarded(ClientboundPackets.START_CONFIGURATION, null);
            connection.forwarded(ServerboundPackets.CONFIGURATION_ACKNOWLEDGED, null);
            unansweredFor(player, PAST_THE_TIMEOUT);

            player.pollData();
            assertTrue(exempt(player), "configuration does not pause the timeout once pings flow");
        });
    }

    @Test
    void anUnacknowledgedProtocolSwitchHoldsTheTimeout() throws Exception {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            player.lastTransSent = System.currentTimeMillis();
            connection.forwarded(ClientboundPackets.START_CONFIGURATION, null);
            unansweredFor(player, PAST_THE_TIMEOUT);

            player.pollData();
            assertFalse(exempt(player));
        });
    }

    private static boolean exempt(CultPlayer player) {
        return CultAPI.INSTANCE.getPlayerDataManager().isExemptUser(player.user);
    }

    private static void unansweredFor(CultPlayer player, long nanos) {
        long since = System.nanoTime() - nanos;
        set(player, "playerClockAtLeast", since);
        set(player, "pingsHeldAt", since);
    }

    private static void set(CultPlayer player, String name, long value) {
        try {
            Field field = CultPlayer.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(player, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void withPlayer(Consumer<CultPlayer> body) throws Exception {
        try (var services = new RecordConsumerServices()) {
            OfflinePlayers.withSafePlayer(SAFE, player -> {
                PlatformPlayer platform = mock(PlatformPlayer.class);
                player.user.bind(platform);
                player.platformPlayer = platform;
                try {
                    body.accept(player);
                } finally {
                    CultAPI.INSTANCE.getPlayerDataManager().clearExemptions(player.user);
                }
            });
        }
    }
}
