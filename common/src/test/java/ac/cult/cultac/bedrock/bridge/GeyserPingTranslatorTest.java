package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.reload.integrations.GeyserQueue;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.Map;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * The answer to a ping no attached player took: retired CultAC pings stay upstream, backend pings go downstream.
 */
public final class GeyserPingTranslatorTest {
    private static final int RETIRED = Integer.MIN_VALUE + 17;
    private static final int BACKEND = 42;

    @Test
    public void aRetiredPingsAnswerIsTakenOnceAndStaysUpstream() {
        CultConnection connection = connection(null);
        connection.retirePing(RETIRED);
        assertFalse(GeyserPingTranslator.forwardsUnclaimedAnswer(connection, RETIRED));
        assertFalse(connection.holdsRetiredPing(RETIRED));
        assertTrue(
                "a second answer for one id is the backend's",
                GeyserPingTranslator.forwardsUnclaimedAnswer(connection, RETIRED));
    }

    @Test
    public void aRetiredPingsAnswerIsNotForwarded() {
        CultConnection connection = connection(null);
        connection.retirePing(RETIRED);
        assertFalse(GeyserPingTranslator.forwardsUnclaimedAnswer(connection, RETIRED));
    }

    @Test
    public void aBackendPingsAnswerGoesDownstream() {
        CultConnection connection = connection(null);
        connection.retirePing(RETIRED);
        assertTrue(GeyserPingTranslator.forwardsUnclaimedAnswer(connection, BACKEND));
        assertTrue(GeyserPingTranslator.forwardsUnclaimedAnswer(null, BACKEND));
        assertTrue(connection.holdsRetiredPing(RETIRED));
    }

    @Test
    public void theAnswerToABackendPingSendsOnePongDownstream() throws Exception {
        GeyserSession session = Mockito.mock(GeyserSession.class);
        CultConnection connection = connection(session);
        connection.retirePing(RETIRED);
        try (var ignored = tap(session, connection)) {
            GeyserPingTranslator.latencyAnswer(session, BACKEND, false).run();
        }
        Mockito.verify(session, Mockito.times(1)).sendDownstreamPacket(Mockito.any());
        Mockito.verify(session)
                .sendDownstreamPacket(ArgumentMatchers.argThat(
                        packet -> packet instanceof ServerboundPongPacket pong && pong.getId() == BACKEND));
        assertTrue(connection.holdsRetiredPing(RETIRED));
    }

    @Test
    public void theAnswerToARetiredPingWithoutAPlayerStaysUpstream() throws Exception {
        GeyserSession session = Mockito.mock(GeyserSession.class);
        CultConnection connection = connection(session);
        connection.retirePing(RETIRED);
        try (var ignored = tap(session, connection)) {
            GeyserPingTranslator.latencyAnswer(session, RETIRED, false).run();
        }
        Mockito.verify(session, Mockito.never()).sendDownstreamPacket(Mockito.any());
        assertFalse(connection.holdsRetiredPing(RETIRED));
    }

    @Test
    public void theAnswerToAnOwnedPingStaysUpstreamAfterThePlayerIsRemoved() throws Exception {
        GeyserSession session = Mockito.mock(GeyserSession.class);
        CultConnection connection = connection(session);
        try (var ignored = tap(session, connection)) {
            Runnable answer = GeyserPingTranslator.latencyAnswer(session, RETIRED, true);
            connection.retirePing(RETIRED);
            answer.run();
        }
        Mockito.verify(session, Mockito.never()).sendDownstreamPacket(Mockito.any());
        assertFalse(connection.holdsRetiredPing(RETIRED));
    }

    private static CultConnection connection(GeyserSession session) {
        OfflineCultTestBootstrap.installConfig();
        EmbeddedChannel channel = new EmbeddedChannel();
        CultConnection connection = new CultConnection(
                channel,
                CultAPI.INSTANCE.getNetworkManager().dispatcher(),
                ignored -> session == null ? null : new PacketOwner(channel.eventLoop(), session));
        connection.resolveOwner();
        return connection;
    }

    /** Installs a packet tap for {@code session} that knows {@code connection}, with no attached player. */
    @SuppressWarnings("unchecked")
    private static AutoCloseable tap(GeyserSession session, CultConnection connection) throws Exception {
        Class<?> type = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
        var constructor = type.getDeclaredConstructor(
                GeyserSession.class, BedrockSession.class, BedrockPacketHandler.class, GeyserQueue.class);
        constructor.setAccessible(true);
        Object tap = constructor.newInstance(session, null, null, new GeyserQueue());
        Field known = type.getDeclaredField("javaConnection");
        known.setAccessible(true);
        known.set(tap, connection);
        Field taps = GeyserBedrockBridgeRuntime.class.getDeclaredField("PACKET_TAPS");
        taps.setAccessible(true);
        Map<Object, Object> installed = (Map<Object, Object>) taps.get(null);
        installed.put(session, tap);
        return () -> installed.remove(session, tap);
    }
}
