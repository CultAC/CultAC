package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketHandlerScanner;
import ac.cult.cultac.network.PacketReceiveHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.LogUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/** Tests the actual per-player registrar/dispatcher with isolated callbacks, not migrated check behavior. */
class RecordCheckManagerDispatchTest {
    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void replacementsKeepEachExistingSnapshotBoundaryAndExactVariant(ProtocolVersion version) throws Exception {
        try (Fixture f = new Fixture(version)) {
            List<String> trace = new ArrayList<>();
            var status = move(false, false);
            var pos = move(true, false);
            var posRot = move(true, true);
            var rot = move(false, true);
            f.register("registerEarlyReceive", new Status((event, packet) -> {
                trace.add("early-first");
                assertSame(status, packet);
                event.replace(pos);
            }));
            f.register("registerEarlyReceive", new Status((event, packet) -> {
                trace.add("early-last");
                assertSame(status, packet);
                assertSame(pos, event.getPacket());
            }));
            f.register(
                    "registerEarlyReceive",
                    new Position((event, packet) -> fail("Early route must keep its original status snapshot")));
            f.register("registerPreReceive", new Position((event, packet) -> {
                trace.add("pre-first");
                assertSame(pos, packet);
                event.replace(posRot);
            }));
            f.register("registerPreReceive", new Position((event, packet) -> {
                trace.add("pre-last");
                assertSame(pos, packet);
                assertSame(posRot, event.getPacket());
            }));
            f.register("registerDecodedReceive", (DecodedPacketReceiveListener) event -> {
                trace.add("decoded");
                assertSame(posRot, event.getPacket());
                replace(event, rot);
            });
            f.register("registerReceive", new Rotation((event, packet) -> {
                trace.add("ordinary-first");
                assertSame(rot, packet);
                event.replace(status);
            }));
            f.register("registerReceive", new Rotation((event, packet) -> {
                trace.add("ordinary-last");
                assertSame(rot, packet);
                assertSame(status, event.getPacket());
            }));
            f.register(
                    "registerReceive",
                    new Status((event, packet) -> fail("Normal route must keep the post-decoded rotation snapshot")));
            f.register("registerNonAsyncReceive", new NonAsync(event -> {
                trace.add("non-async");
                assertSame(status, event.getPacket());
            }));
            f.build();
            var exchange = f.exchange(ServerboundPackets.MOVE_PLAYER, "move_player_status_only", status);
            var event =
                    new PacketReceiveEvent<>(f.player.user, ConnectionPhase.PLAY, exchange.type(), exchange.packet());
            assertNotNull(event.getOriginalPacket());
            f.manager.dispatchEarlyReceive(event);
            f.manager.dispatchPrePredictionReceive(event);
            f.manager.dispatchReceiveHandlers(event);
            assertEquals(
                    List.of(
                            "early-first",
                            "early-last",
                            "pre-first",
                            "pre-last",
                            "decoded",
                            "ordinary-first",
                            "ordinary-last",
                            "non-async"),
                    trace);
            assertSame(status, event.getOriginalPacket());
            assertSame(status, event.getPacket());
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void tickEndKeepsWorldThenRegisteredCallbackOrder(ProtocolVersion version) throws Exception {
        try (Fixture f = new Fixture(version)) {
            List<String> trace = new ArrayList<>();
            f.player.compensatedWorld = Mockito.spy(f.player.compensatedWorld);
            Mockito.doAnswer(invocation -> {
                        trace.add("world");
                        return invocation.callRealMethod();
                    })
                    .when(f.player.compensatedWorld)
                    .onClientTickEnd();
            f.register("registerDecodedReceive", (DecodedPacketReceiveListener) e -> trace.add("decoded"));
            f.register("registerReceive", new CheckListener() {
                @CultPacketHandler("serverbound.client_tick_end")
                void tick(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
                    trace.add("typed");
                    event.setCancelled(true); // CheckManager does not insert another cancellation boundary.
                }
            });
            f.register("registerNonAsyncReceive", new NonAsync(e -> trace.add("non-async")));
            f.register("registerTickEnd", new Tick(e -> trace.add("first-tick")));
            f.register("registerTickEnd", new Tick(e -> trace.add("second-tick")));
            f.build();
            var event = new PacketReceiveEvent<>(
                    f.player.user,
                    ConnectionPhase.PLAY,
                    f.exchange(
                                    ServerboundPackets.CLIENT_TICK_END,
                                    "client_tick_end",
                                    ac.cult.cultac.protocol.packet.ServerboundPackets.CLIENT_TICK_END.opaqueValue())
                            .type(),
                    f.exchange(
                                    ServerboundPackets.CLIENT_TICK_END,
                                    "client_tick_end",
                                    ac.cult.cultac.protocol.packet.ServerboundPackets.CLIENT_TICK_END.opaqueValue())
                            .packet());
            f.manager.dispatchReceiveHandlers(event);
            assertEquals(List.of("decoded", "typed", "non-async", "world", "first-tick", "second-tick"), trace);
            assertTrue(event.isCancelled());
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void throwingTickEndHandlerIsLoggedOnceAndLaterHandlersStillRun(ProtocolVersion version) throws Exception {
        Logger logger = LogUtil.getLogger();
        List<String> severe = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE && record.getMessage().startsWith("Tick-end handler"))
                    severe.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(capture);
        try (Fixture f = new Fixture(version)) {
            List<String> trace = new ArrayList<>();
            f.register("registerTickEnd", new Tick(e -> {
                trace.add("throwing-tick");
                throw new IllegalStateException("tick-end failure");
            }));
            f.register("registerTickEnd", new Tick(e -> trace.add("next-tick")));
            f.build();
            for (int i = 0; i < 2; i++) {
                var sample = f.exchange(
                        ServerboundPackets.CLIENT_TICK_END,
                        "client_tick_end",
                        ac.cult.cultac.protocol.packet.ServerboundPackets.CLIENT_TICK_END.opaqueValue());
                f.manager.dispatchReceiveHandlers(
                        new PacketReceiveEvent<>(f.player.user, ConnectionPhase.PLAY, sample.type(), sample.packet()));
            }
            assertEquals(List.of("throwing-tick", "next-tick", "throwing-tick", "next-tick"), trace);
            assertEquals(1, severe.size(), severe::toString);
        } finally {
            logger.removeHandler(capture);
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void nonAsyncHandlersSkipAsyncPacketsAndSeeInterveningPackets(ProtocolVersion version) throws Exception {
        try (Fixture f = new Fixture(version)) {
            List<PacketType<?>> seen = new ArrayList<>();
            f.register("registerNonAsyncReceive", new NonAsync(event -> seen.add(event.getPacketType())));
            f.build();
            f.manager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    f.player.user, ConnectionPhase.PLAY, ServerboundPackets.KEEP_ALIVE, new ServerboundKeepAlive(1L)));
            // SignUpdate bypasses the normal check route but still intervenes in ordering windows.
            new CheckManagerListener()
                    .processInterveningReceive(
                            new PacketReceiveEvent<>(
                                    f.player.user,
                                    ConnectionPhase.PLAY,
                                    ServerboundPackets.SIGN_UPDATE,
                                    ServerboundPackets.SIGN_UPDATE.opaqueValue()),
                            f.player);
            assertEquals(List.of(ServerboundPackets.SIGN_UPDATE), seen);
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void sendHandlersShareOriginalArgumentWithinOneRouteAndFreshArgumentOnNextDispatch(ProtocolVersion version)
            throws Exception {
        try (Fixture f = new Fixture(version)) {
            List<Integer> ids = new ArrayList<>();
            f.register("registerSend", new Ping((event, packet) -> {
                ids.add(packet.id());
                event.replace(new ClientboundPing(packet.id() + 1));
            }));
            f.register("registerSend", new Ping((event, packet) -> {
                ids.add(packet.id());
                assertEquals(
                        packet.id() + 1,
                        assertInstanceOf(ClientboundPing.class, event.getPacket())
                                .id());
            }));
            f.build();
            var event = new PacketSendEvent<>(
                    f.player.user,
                    ConnectionPhase.PLAY,
                    f.exchange(ClientboundPackets.PING, "ping", new ClientboundPing(8))
                            .type(),
                    f.exchange(ClientboundPackets.PING, "ping", new ClientboundPing(8))
                            .packet(),
                    f.exchange(ClientboundPackets.PING, "ping", new ClientboundPing(8))
                            .insideBundle());
            f.manager.dispatchSendHandlers(event);
            f.manager.dispatchSendHandlers(event);
            assertEquals(List.of(8, 8, 9, 9), ids);
            assertEquals(
                    10,
                    assertInstanceOf(ClientboundPing.class, event.getPacket()).id());
        }
    }

    private static ClientboundEntityMotion motion(int id) {
        return new ClientboundEntityMotion(id, new Vec3d(0, 0, 0));
    }

    @SuppressWarnings("unchecked")
    private static void replace(PacketReceiveEvent<?> event, ServerboundMovePlayer packet) {
        ((PacketReceiveEvent<ServerboundMovePlayer>) event).replace(packet);
    }

    private static ServerboundMovePlayer move(boolean position, boolean rotation) {
        return new ServerboundMovePlayer(1, 2, 3, 4, 5, false, false, position, rotation);
    }

    private record Status(BiConsumer<PacketReceiveEvent<ServerboundMovePlayer>, ServerboundMovePlayer> action)
            implements CheckListener {
        @CultPacketHandler
        void receive(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer p, ServerboundMovePlayer r) {
            if (!r.hasPosition() && !r.hasRotation()) action.accept(e, r);
        }
    }

    private record Position(BiConsumer<PacketReceiveEvent<ServerboundMovePlayer>, ServerboundMovePlayer> action)
            implements CheckListener {
        @CultPacketHandler
        void receive(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer p, ServerboundMovePlayer r) {
            if (r.hasPosition() && !r.hasRotation()) action.accept(e, r);
        }
    }

    private record Rotation(BiConsumer<PacketReceiveEvent<ServerboundMovePlayer>, ServerboundMovePlayer> action)
            implements CheckListener {
        @CultPacketHandler
        void receive(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer p, ServerboundMovePlayer r) {
            if (!r.hasPosition() && r.hasRotation()) action.accept(e, r);
        }
    }

    private record NonAsync(Consumer<PacketReceiveEvent<?>> action) implements CheckListener {
        void receive(PacketReceiveEvent<?> e, CultPlayer p, ServerboundPacket r) {
            action.accept(e);
        }
    }

    private record Tick(Consumer<PacketReceiveEvent> action) implements CheckListener, ClientTickEndListener {
        @Override
        public void onPlayerTickEnd(PacketReceiveEvent event) {
            action.accept(event);
        }
    }

    private record Ping(BiConsumer<PacketSendEvent<ClientboundPing>, ClientboundPing> action) implements CheckListener {
        @CultPacketHandler
        void send(PacketSendEvent<ClientboundPing> e, CultPlayer p, ClientboundPing r) {
            action.accept(e, r);
        }
    }

    private record Motion(BiConsumer<PacketSendEvent<ClientboundEntityMotion>, ClientboundEntityMotion> action)
            implements CheckListener {
        @CultPacketHandler
        void send(PacketSendEvent<ClientboundEntityMotion> e, CultPlayer p, ClientboundEntityMotion r) {
            action.accept(e, r);
        }
    }

    private record Sample<R>(PacketType<R> type, R packet) {
        boolean insideBundle() {
            return false;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ProtocolData data;
        final CultPlayer player;
        final CheckManager manager;
        final PacketHandlerScanner scanner;
        final Class<?> registrationType;
        final Object registrations;

        Fixture(ProtocolVersion version) throws Exception {
            OfflineCultTestBootstrap.installConfig();
            data = ProtocolData.load(version);
            scanner = new PacketHandlerScanner(ac.cult.cultac.network.TestProtocolRuntime.create(data));
            player = new CultPlayer(ac.cult.cultac.network.TestUsers.create(
                    new User.Profile(UUID.randomUUID(), ".Record_Dispatch_Test"), new EmbeddedChannel()));
            manager = player.checkManager;
            field("recordScanner").set(manager, scanner);
            registrationType = Class.forName(CheckManager.class.getName() + "$PacketRegistrations");
            var constructor = registrationType.getDeclaredConstructor(PacketHandlerScanner.class);
            constructor.setAccessible(true);
            registrations = constructor.newInstance(scanner);
            for (String name : List.of("decodedReceiveListeners", "nonAsyncReceiveHandlers", "tickEndHandlers")) {
                ((Collection<?>) field(name).get(manager)).clear();
            }
        }

        void register(String name, CheckListener listener) throws Exception {
            if (name.equals("registerNonAsyncReceive")) {
                var method =
                        CheckManager.class.getDeclaredMethod(name, CheckListener.class, PacketReceiveHandler.class);
                method.setAccessible(true);
                PacketReceiveHandler<ServerboundPacket> callback = ((NonAsync) listener)::receive;
                method.invoke(manager, listener, callback);
                return;
            }
            boolean records = List.of("registerEarlyReceive", "registerPreReceive", "registerReceive", "registerSend")
                    .contains(name);
            var method = records
                    ? CheckManager.class.getDeclaredMethod(name, registrationType, CheckListener.class)
                    : CheckManager.class.getDeclaredMethod(name, CheckListener.class);
            method.setAccessible(true);
            if (records) method.invoke(manager, registrations, listener);
            else method.invoke(manager, listener);
        }

        void build() throws Exception {
            var method = CheckManager.class.getDeclaredMethod("buildPacketRoutes", registrationType);
            method.setAccessible(true);
            method.invoke(manager, registrations);
        }

        <R> Sample<R> exchange(PacketType<R> type, String wireName, R packet) {
            int id = data.packets(ConnectionPhase.PLAY, type.direction()).id("minecraft:" + wireName);
            assertTrue(id >= 0);
            return new Sample<>(type, packet);
        }

        private static Field field(String name) throws Exception {
            var field = CheckManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        @Override
        public void close() {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
