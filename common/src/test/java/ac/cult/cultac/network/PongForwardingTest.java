package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.CultPlayer.TrackedTransaction;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A pong answering a ping CultAC wrote never reaches the backend, before or after its player is removed from a
 * connection that stays open; backend pings and their pongs pass through unchanged.
 */
class PongForwardingTest {
    private static final int BACKEND_PONG = 42;
    private static final int OTHER_ENTITY = 4242;
    private static final long PAST_THE_TIMEOUT = TimeUnit.MINUTES.toNanos(5);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailingPongHandlerForwardsNoOwnPong(boolean observeOnly) throws Exception {
        try (var c = new Client(observeOnly)) {
            c.f.registrar.register(routes -> routes.receive(
                    ServerboundPackets.PONG, PacketListenerPriority.MONITOR, (event, player, packet) -> {
                        throw new IllegalStateException("pong handler failure");
                    }));
            int own = c.ownPing();
            assertFalse(c.answer(own), "the pong answering CultAC's ping reached the backend");
            assertEquals(1, c.f.player.lastTransactionReceived.get());
            assertTrue(c.answer(BACKEND_PONG), "a backend pong is forwarded");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pongsForPingsWrittenBeforeRemovalStayWithCultAC(boolean observeOnly) throws Exception {
        try (var c = new Client(observeOnly)) {
            var player = c.f.player;
            List<Integer> own = new ArrayList<>();
            for (int i = 0; i < 3; i++) own.add(c.ownPing());
            TrackedTransaction queued = player.createTrackedTransactionPacketForDeferredSend(CultPlayer.PING_LEADING);
            // A flush bundle opened ahead of the removal and closed only after it.
            c.f.channel.write(c.motion(OTHER_ENTITY));
            c.f.channel.pipeline().flush();
            assertTrue(c.f.connection.flushBundleOpen(), "the update opened a flush bundle");
            int created = player.lastTransactionSent.get();

            assertTrue(CultAPI.INSTANCE.getPlayerDataManager().remove(c.f.user));
            assertNull(c.f.user.getCultPlayer());
            c.f.connection.write(queued.packet());
            int queuedId = ((ClientboundPing) queued.packet().packet()).id();
            player.sendTransaction();
            player.sendTransaction(true);
            c.f.channel.write(c.motion(OTHER_ENTITY));

            List<Integer> written = c.ownPingsToClient();
            assertEquals(created, player.lastTransactionSent.get(), "no ping of CultAC's own after the removal");
            assertTrue(written.contains(queuedId), "the queued ping is written");
            assertEquals(2, written.size(), "the bundle's leading ping and the queued ping: " + written);
            for (int id : written) assertTrue(player.awaitsTransactionResponse(id), "ping " + id + " predates it");
            assertFalse(c.f.connection.flushBundleOpen());
            assertEquals(0, c.delimiters % 2, "bundle delimiters to the client are balanced");

            own.addAll(written);
            for (int id : own) assertFalse(c.answer(id), "pong " + id + " reached the backend");
            assertTrue(c.answer(own.get(1)), "a second pong for one id is the backend's");
            assertTrue(c.answer(BACKEND_PONG), "a backend pong is forwarded");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backendPingsAndTheirPongsPassThroughUnchanged(boolean observeOnly) throws Exception {
        try (var c = new Client(observeOnly)) {
            var player = c.f.player;
            List<String> backendFrames = new ArrayList<>();
            List<String> backendFramesAtClient = new ArrayList<>();
            List<Integer> forwarded = new ArrayList<>();
            int nextId = 1;
            int ownPings = 0;
            for (int tick = 0; tick < 200; tick++) {
                List<ByteBuf> tickFrames = List.of(
                        c.ping(nextId++),
                        c.setTime(tick),
                        c.motion(player.entityID),
                        c.motion(OTHER_ENTITY),
                        c.ping(nextId++));
                for (ByteBuf frame : tickFrames) {
                    backendFrames.add(ByteBufUtil.hexDump(frame));
                    c.f.channel.write(frame);
                }
                player.sendTransaction();
                for (Frame frame : c.toClient()) {
                    if (frame.type == c.delimiterType) continue;
                    if (frame.ping != null && frame.ping < 0) {
                        ownPings++;
                        assertFalse(c.answer(frame.ping), "a pong answering CultAC's ping reached the backend");
                        continue;
                    }
                    backendFramesAtClient.add(frame.hex);
                    if (frame.ping != null && c.answer(frame.ping)) forwarded.add(frame.ping);
                }
            }
            assertEquals(backendFrames, backendFramesAtClient);
            assertEquals(IntStream.range(1, nextId).boxed().toList(), forwarded);
            assertTrue(ownPings >= 200, "CultAC wrote its own pings: " + ownPings);
            assertTrue(c.delimiters > 0, "flush bundles were written");
            assertEquals(0, c.delimiters % 2, "bundle delimiters to the client are balanced");
            assertFalse(c.f.connection.flushBundleOpen());
        }
    }

    @Test
    void pongsPastTheTimeoutInObserveOnlyStayWithCultAC() throws Exception {
        try (var c = new Client(true)) {
            var player = c.f.player;
            List<Integer> own = new ArrayList<>(List.of(c.ownPing(), c.ownPing()));
            player.lastTransSent = System.currentTimeMillis() - 1000;
            long since = System.nanoTime() - PAST_THE_TIMEOUT;
            set(player, "playerClockAtLeast", since);
            set(player, "pingsHeldAt", since);
            c.f.channel.write(c.motion(OTHER_ENTITY));

            player.pollData();
            assertNull(c.f.user.getCultPlayer(), "the timeout removed the player");
            assertTrue(CultAPI.INSTANCE.getPlayerDataManager().isExemptUser(c.f.user));
            own.addAll(c.ownPingsToClient());

            assertNoPingAfterRemoval(c);
            assertFalse(c.f.connection.flushBundleOpen());
            assertEquals(0, c.delimiters % 2, "bundle delimiters to the client are balanced");
            for (int id : own) assertFalse(c.answer(id), "a late pong reached the backend");
            assertTrue(c.answer(BACKEND_PONG), "a backend pong is forwarded");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExemptPermissionGainedWhileConnectedRemovesThePlayerOnTheNextPoll(boolean observeOnly) throws Exception {
        try (var c = new Client(observeOnly)) {
            var player = c.f.player;
            List<Integer> own = new ArrayList<>(List.of(c.ownPing(), c.ownPing()));
            player.pollData();
            assertSame(player, c.f.user.getCultPlayer());

            when(c.platform.hasPermission("cult.exempt")).thenReturn(true);
            player.lastTransSent = System.currentTimeMillis() - 1000;
            c.f.channel.write(c.motion(OTHER_ENTITY));
            player.pollData();
            assertNull(c.f.user.getCultPlayer(), "the first poll after the grant removed the player");
            own.addAll(c.ownPingsToClient());

            assertNoPingAfterRemoval(c);
            assertFalse(c.f.connection.flushBundleOpen());
            assertEquals(0, c.delimiters % 2, "bundle delimiters to the client are balanced");
            for (int id : own) assertFalse(c.answer(id), "pong " + id + " reached the backend");
            assertTrue(c.answer(own.getFirst()), "a second pong for one id is the backend's");
            assertTrue(c.answer(BACKEND_PONG), "a backend pong is forwarded");
        }
    }

    /** The event loop runs pollData's deferred ping inline; on a server it runs after the removal and writes none. */
    private static void assertNoPingAfterRemoval(Client c) {
        int created = c.f.player.lastTransactionSent.get();
        c.f.player.sendTransaction(true);
        c.f.player.sendTransaction();
        c.f.channel.write(c.motion(OTHER_ENTITY));
        assertEquals(List.of(), c.ownPingsToClient(), "no ping of CultAC's own after the removal");
        assertEquals(created, c.f.player.lastTransactionSent.get());
    }

    private static void set(CultPlayer player, String name, long value) throws ReflectiveOperationException {
        Field field = CultPlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(player, value);
    }

    private record Frame(int type, String hex, Integer ping) {}

    private static final class Client implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture f = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final AutoCloseable observeOnly;
        final PlatformPlayer platform = mock(PlatformPlayer.class);
        final int pingType = clientbound("ping");
        final int delimiterType = clientbound("bundle_delimiter");
        int delimiters;

        Client(boolean observeOnly) throws Exception {
            this.observeOnly = RecordReceiveFixture.observeOnly(observeOnly);
            f.registrar.registerOwn(PacketManager::registerListeners);
            // An update CultAC proves with the flush bundle's leading ping, as it proves entity and block updates.
            f.registrar.register(routes -> routes.send(
                    ClientboundPackets.ENTITY_MOTION, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                        if (packet.entityId() == OTHER_ENTITY) player.appendFlushProofTransaction(event);
                    }));
            f.user.bind(platform);
            f.player.platformPlayer = platform;
        }

        private int clientbound(String name) {
            return f.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:" + name);
        }

        ByteBuf ping(int id) {
            return f.id(ClientboundPackets.PING, "ping").writeInt(id);
        }

        ByteBuf setTime(int tick) {
            return f.id(ClientboundPackets.PING, "set_time")
                    .writeLong(tick)
                    .writeLong(6000L + tick)
                    .writeBoolean(true);
        }

        ByteBuf motion(int entityId) {
            return f.encode(
                    ClientboundPackets.ENTITY_MOTION, new ClientboundEntityMotion(entityId, new Vec3d(0, 0.2, 0)));
        }

        /** Writes one CultAC ping and returns its id once it reached the client. */
        int ownPing() {
            f.player.sendTransaction();
            List<Integer> pings = ownPingsToClient();
            assertEquals(1, pings.size(), "one CultAC ping: " + pings);
            return pings.getFirst();
        }

        List<Integer> ownPingsToClient() {
            return ownPings(toClient());
        }

        List<Integer> ownPings(List<Frame> frames) {
            List<Integer> pings = new ArrayList<>();
            for (Frame frame : frames) if (frame.ping != null && frame.ping < 0) pings.add(frame.ping);
            return pings;
        }

        /** What the client reads once the event loop is idle, in wire order. */
        List<Frame> toClient() {
            f.channel.flush();
            f.channel.runPendingTasks();
            return written();
        }

        /** What reached the wire so far, in order, without running queued tasks. */
        List<Frame> written() {
            List<Frame> frames = new ArrayList<>();
            ByteBuf frame;
            while ((frame = f.channel.readOutbound()) != null) {
                try {
                    String hex = ByteBufUtil.hexDump(frame);
                    int type = Wire.readVarInt(frame);
                    if (type == delimiterType) delimiters++;
                    frames.add(new Frame(type, hex, type == pingType ? frame.readInt() : null));
                } finally {
                    frame.release();
                }
            }
            return frames;
        }

        /** The client's pong for {@code id}; true when the backend received it with the bytes the client sent. */
        boolean answer(int id) {
            ByteBuf pong = f.pong(id);
            String sent = ByteBufUtil.hexDump(pong);
            f.channel.writeInbound(pong);
            ByteBuf forwarded = f.channel.readInbound();
            if (forwarded == null) return false;
            try {
                assertEquals(sent, ByteBufUtil.hexDump(forwarded));
            } finally {
                forwarded.release();
            }
            assertNull(f.channel.readInbound());
            return true;
        }

        @Override
        public void close() throws Exception {
            try {
                f.close();
            } finally {
                try {
                    observeOnly.close();
                } finally {
                    services.close();
                }
            }
        }
    }
}
