package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.routing.NetSettings;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkService;
import ac.cult.cultac.network.routing.TestNetworks;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.HostContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A player whose {@code toggle} turns off keeps an open connection with no CultAC player, decoder or encoder, only a
 * {@link DormantWatch} that swallows the pongs of the pings CultAC wrote before; the setting for the backend server
 * they switch to decides at each reconfiguration acknowledgement whether they stay dormant or are checked again.
 */
class DormantDetachTest {
    private static final int BACKEND_PONG = 42;

    private final AtomicLong at = new AtomicLong(10);
    private final List<Client> clients = new ArrayList<>();
    private RecordConsumerServices services;
    private NetworkService network;
    private TestPlatform platform;
    private @Nullable CultNetworkManager manager;

    @BeforeEach
    void start() throws Exception {
        services = new RecordConsumerServices();
        network = new TestNetworks()
                .service("p3")
                .platform(new TestNetworks.Players().proxy(null).backends("BRG_1", "lobby"))
                .start();
        TestNetworks.install(network);
        platform = new TestPlatform();
        DormancyController.platform(platform);
    }

    @AfterEach
    void stop() throws Exception {
        try {
            for (Client client : clients) client.close();
        } finally {
            DormancyController.platform(null);
            NetworkModule.stop();
            services.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detachingSwallowsExactlyThePongsOfPingsWrittenBeforeIt(boolean pongCancellingDisabled) throws Exception {
        try (var ignored = disablePongCancelling(pongCancellingDisabled)) {
            Client c = new Client("lobby");
            List<Integer> inFlight = List.of(c.ownPing(), c.ownPing(), c.ownPing());

            toggle("all", NetSettings.OFF);
            settle();

            assertNull(c.f.user.getCultPlayer(), "the player is detached");
            assertNotNull(c.f.connection.dormant());
            assertEquals(List.of(c.id), platform.detached);
            assertNull(CultAPI.INSTANCE.getNetworkManager().connection(c.f.channel), "the connection is released");
            DormantWatch watch = c.watch();
            assertTrue(watch.play());
            assertArrayEquals(
                    inFlight.stream().mapToInt(Integer::intValue).sorted().toArray(),
                    Arrays.stream(watch.retired()).sorted().toArray());

            for (int id : inFlight)
                assertEquals(pongCancellingDisabled, c.answer(id), "pong " + id + " for a ping CultAC wrote");
            assertEquals(0, watch.retired().length, "every retired id is taken");
            assertTrue(c.answer(inFlight.getFirst()), "a second pong for one id is the backend's");
            assertTrue(c.answer(BACKEND_PONG), "a backend pong is forwarded");
        }
    }

    @Test
    void aDormantConnectionHoldsOnlyTheWatchInFrontOfThePlatformDecoder() throws Exception {
        Client c = new Client("lobby");
        toggle("all", NetSettings.OFF);
        settle();

        List<String> names = c.f.channel.pipeline().names();
        assertEquals(
                List.of(DormantWatch.NAME),
                names.stream().filter(name -> name.startsWith("cult-")).toList(),
                "CultAC handlers left: " + names);
        assertEquals(names.indexOf(DormantWatch.NAME) + 1, names.indexOf("decoder"));
        assertEquals(List.of(c.watch()), List.copyOf(DormantWatch.all()));

        ByteBuf clientbound = Unpooled.buffer();
        Wire.writeVarInt(clientbound, c.pingType);
        clientbound.writeInt(7);
        String sent = ByteBufUtil.hexDump(clientbound);
        assertTrue(c.f.channel.writeOutbound(clientbound));
        ByteBuf written = c.f.channel.readOutbound();
        try {
            assertEquals(sent, ByteBufUtil.hexDump(written), "clientbound frames pass unchanged");
        } finally {
            written.release();
        }
    }

    @Test
    void anAcknowledgementWhereThePlayerIsCheckedAttachesThemAgainWithTheirContinuation() throws Exception {
        Client c = new Client("lobby");
        c.f.connection.loginNotified = true;
        c.f.player.checkManager.getCheck(ClientBrand.class).restore("fabric");
        toggle("all", NetSettings.OFF);
        settle();
        c.watch();
        toggle("all", NetSettings.ON);
        settle();
        assertTrue(platform.resumed.isEmpty(), "a dormant player is checked again only at a reconfiguration");

        assertTrue(c.acknowledge());

        assertEquals(1, platform.resumed.size());
        CultConnection next = platform.resumed.getFirst();
        assertSame(next, CultAPI.INSTANCE.getNetworkManager().connection(c.f.channel));
        assertNull(c.f.channel.pipeline().get(DormantWatch.NAME));
        assertNotNull(c.f.channel.pipeline().get(CultDecoder.NAME));
        assertNotNull(next.player(), "a fresh player");
        assertNotSame(c.f.player, next.player());
        assertEquals("fabric", next.player().getBrand());
        assertEquals(c.f.user.getConnectionId(), next.user().getConnectionId());
        assertEquals(List.of(), platform.probe.seen, "the new decoder never saw the acknowledgement");
        assertEquals(ConnectionPhase.CONFIGURATION, next.phase(PacketDirection.SERVERBOUND));

        assertTrue(c.finish());
        assertEquals(1, platform.probe.seen.size());
        assertEquals(ConnectionPhase.PLAY, next.phase(PacketDirection.SERVERBOUND));
    }

    @Test
    void anAcknowledgementWhileDormantKeepsTheWatchUntilConfigurationFinishes() throws Exception {
        Client c = new Client("lobby");
        toggle("all", NetSettings.OFF);
        settle();
        DormantWatch watch = c.watch();
        List<String> order = new ArrayList<>();
        platform.order = order;
        c.f.channel.pipeline().addAfter("decoder", "native", new Probe(order, "native"));

        assertTrue(c.acknowledge());

        assertEquals(List.of("target", "native"), order, "the watch decides before the acknowledgement passes");
        assertSame(watch, c.f.channel.pipeline().get(DormantWatch.NAME));
        assertFalse(watch.play());
        assertTrue(platform.resumed.isEmpty());
        assertTrue(c.answer(ConnectionPhase.CONFIGURATION, BACKEND_PONG));

        assertTrue(c.finish());
        assertTrue(watch.play());
        assertTrue(c.answer(BACKEND_PONG));
        assertSame(watch, c.f.channel.pipeline().get(DormantWatch.NAME));
    }

    @Test
    void aDormantConnectionIsHandedToANewerGenerationAtItsAcknowledgement() throws Exception {
        Client c = new Client("lobby");
        c.f.connection.loginNotified = true;
        toggle("all", NetSettings.OFF);
        settle();
        DormantWatch older = c.watch();
        Probe probe = new Probe(new ArrayList<>(), null);
        DormantWatch[] newer = new DormantWatch[1];
        DormantWatch.Hooks adopting = new DormantWatch.Hooks() {
            @Override
            public boolean acknowledged(DormantWatch watch) {
                return fail("the newer generation's watch saw the acknowledgement");
            }

            @Override
            public void closed(DormantWatch watch) {}
        };
        HostContext host = mock(HostContext.class);
        when(host.platform()).thenReturn("velocity");
        when(host.handoff(eq(c.f.channel), any())).thenAnswer(invocation -> {
            // The host copies the released state between engine class loaders.
            Map<String, Object> state = new HashMap<>(Map.copyOf(older.release()));
            assertEquals(true, state.remove(DormantWatch.DORMANT));
            int[] retired = assertInstanceOf(int[].class, state.remove(DormantWatch.RETIRED));
            newer[0] = new DormantWatch(
                    c.f.channel, ProtocolVersion.V26_3, c.id, null, null, state, retired, false, adopting);
            c.f.channel.pipeline().addBefore("decoder", DormantWatch.NAME, newer[0]);
            c.f.channel.pipeline().addBefore("decoder", "probe", probe);
            return true;
        });
        Field context = RuntimeEnvironment.class.getDeclaredField("context");
        context.setAccessible(true);
        Object previous = context.get(null);
        context.set(null, host);
        try {
            assertTrue(c.acknowledge());
        } finally {
            context.set(null, previous);
        }

        assertNotNull(newer[0]);
        assertSame(newer[0], c.f.channel.pipeline().get(DormantWatch.NAME));
        assertNull(c.f.channel.pipeline().context(older));
        assertTrue(platform.resumed.isEmpty());
        assertEquals(List.of(), probe.seen, "the newer generation never saw the acknowledgement");
        assertFalse(newer[0].play());
        assertTrue(newer[0].loginNotified());
        assertEquals(c.f.user.getConnectionId().toString(), newer[0].state().get("connection-id"));

        assertTrue(c.finish());
        assertEquals(1, probe.seen.size());
        assertTrue(newer[0].play());
    }

    @Test
    void aBackendScopedToggleDetachesThePlayersOnThatBackendAndTheSwitchDecides() throws Exception {
        Client onBrg = new Client("BRG_1");
        Client inLobby = new Client("lobby");
        toggle("BRG_1", NetSettings.OFF);
        settle();

        assertNull(onBrg.f.user.getCultPlayer());
        onBrg.watch();
        assertSame(inLobby.f.player, inLobby.f.user.getCultPlayer(), "a player on another backend stays checked");
        assertNotNull(inLobby.f.channel.pipeline().get(CultDecoder.NAME));
        assertNull(inLobby.f.channel.pipeline().get(DormantWatch.NAME));
        assertEquals(List.of(onBrg.id), platform.detached);
        assertEquals(1, DormancyController.dormantPlayers(List.of("brg_1")));
        assertEquals(0, DormancyController.dormantPlayers(List.of("lobby")));

        platform.targets.put(inLobby.id, "BRG_1");
        assertTrue(inLobby.acknowledge());
        assertNull(inLobby.f.user.getCultPlayer(), "switching into the dormant backend detaches at the switch");
        assertNull(inLobby.f.channel.pipeline().get(CultDecoder.NAME));
        assertNull(inLobby.f.channel.pipeline().get(CultEncoder.NAME));
        DormantWatch switched = inLobby.watch();
        assertFalse(switched.play());
        assertEquals(List.of(onBrg.id, inLobby.id), platform.detached);
        assertTrue(inLobby.finish());
        assertTrue(switched.play());

        platform.targets.put(onBrg.id, "lobby");
        assertTrue(onBrg.acknowledge());
        assertEquals(1, platform.resumed.size(), "switching out of the dormant backend checks the player again");
        assertSame(
                platform.resumed.getFirst(),
                CultAPI.INSTANCE.getNetworkManager().connection(onBrg.f.channel));
        assertNotNull(platform.resumed.getFirst().player());
        assertNull(onBrg.f.channel.pipeline().get(DormantWatch.NAME));
    }

    private void toggle(String target, String value) {
        TestNetworks.merge(
                network,
                List.of(new NetSettings.Record(
                        NetSettings.TOGGLE,
                        value,
                        target,
                        at.getAndAdd(10),
                        "Mod",
                        UUID.randomUUID().toString())));
    }

    /** Detaches every connection the setting now makes dormant and runs their packet owners' tasks. */
    private void settle() {
        CompletableFuture<Void> settled = DormancyController.settle();
        for (Client client : clients) client.f.channel.runPendingTasks();
        assertTrue(settled.isDone());
    }

    private static AutoCloseable disablePongCancelling(boolean disabled) throws ReflectiveOperationException {
        Field field = BaseConfigManager.class.getDeclaredField("disablePongCancelling");
        field.setAccessible(true);
        Object config = CultAPI.INSTANCE.getConfigManager();
        boolean original = field.getBoolean(config);
        field.setBoolean(config, disabled);
        return () -> field.setBoolean(config, original);
    }

    private static PacketConnections connections(CultNetworkManager manager) throws ReflectiveOperationException {
        Field field = CultNetworkManager.class.getDeclaredField("connections");
        field.setAccessible(true);
        return (PacketConnections) field.get(manager);
    }

    /** Adds {@code label}, or without one the frame's hex, to {@code seen} for each frame it passes on. */
    private static final class Probe extends ChannelInboundHandlerAdapter {
        final List<String> seen;
        private final @Nullable String label;

        Probe(List<String> seen, @Nullable String label) {
            this.seen = seen;
            this.label = label;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof ByteBuf frame) seen.add(label != null ? label : ByteBufUtil.hexDump(frame));
            ctx.fireChannelRead(message);
        }
    }

    /** One client on the proxy, inspected and registered with the network manager. */
    private final class Client implements AutoCloseable {
        final RecordReceiveFixture f = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final PlatformPlayer platformPlayer = mock(PlatformPlayer.class);
        final UUID id = f.user.getUUID();
        final int pingType = f.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                .id("minecraft:ping");

        Client(String backend) throws ReflectiveOperationException {
            clients.add(this);
            f.registrar.registerOwn(PacketManager::registerListeners);
            f.user.bind(platformPlayer);
            f.player.platformPlayer = platformPlayer;
            if (manager == null) {
                manager = f.manager;
                services.useNetwork(manager);
            }
            connections(manager).attach(f.connection);
            platform.backends.put(id, backend);
        }

        DormantWatch watch() {
            return assertInstanceOf(DormantWatch.class, f.channel.pipeline().get(DormantWatch.NAME));
        }

        /** Writes one CultAC ping and returns its id once it reached the client. */
        int ownPing() {
            f.player.sendTransaction();
            f.channel.flush();
            f.channel.runPendingTasks();
            List<Integer> pings = new ArrayList<>();
            ByteBuf frame;
            while ((frame = f.channel.readOutbound()) != null) {
                try {
                    if (Wire.readVarInt(frame) == pingType) {
                        int ping = frame.readInt();
                        if (ping < 0) pings.add(ping);
                    }
                } finally {
                    frame.release();
                }
            }
            assertEquals(1, pings.size(), "one CultAC ping: " + pings);
            return pings.getFirst();
        }

        boolean answer(int ping) {
            return answer(ConnectionPhase.PLAY, ping);
        }

        boolean answer(ConnectionPhase phase, int ping) {
            return forwarded(serverbound(phase, "pong").writeInt(ping));
        }

        /** The client acknowledges a reconfiguration the backend started. */
        boolean acknowledge() {
            CultConnection inspected = CultAPI.INSTANCE.getNetworkManager().connection(f.channel);
            if (inspected != null) inspected.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
            return forwarded(serverbound(ConnectionPhase.PLAY, "configuration_acknowledged"));
        }

        boolean finish() {
            return forwarded(serverbound(ConnectionPhase.CONFIGURATION, "finish_configuration"));
        }

        private ByteBuf serverbound(ConnectionPhase phase, String name) {
            int type = f.data.packets(phase, PacketDirection.SERVERBOUND).id("minecraft:" + name);
            assertTrue(type >= 0, name);
            ByteBuf frame = Unpooled.buffer();
            Wire.writeVarInt(frame, type);
            return frame;
        }

        /** Whether the backend received {@code frame} with the bytes the client sent. */
        private boolean forwarded(ByteBuf frame) {
            String sent = ByteBufUtil.hexDump(frame);
            f.channel.writeInbound(frame);
            ByteBuf received = f.channel.readInbound();
            if (received == null) return false;
            try {
                assertEquals(sent, ByteBufUtil.hexDump(received));
            } finally {
                received.release();
            }
            assertNull(f.channel.readInbound());
            return true;
        }

        @Override
        public void close() {
            CultConnection current = CultAPI.INSTANCE.getNetworkManager().connection(f.channel);
            if (current != null && current != f.connection && current.user() != null)
                CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(current.user());
            f.close();
        }
    }

    /**
     * The platform side for these clients: each player's backend server and the one they switch to, a watch and a
     * fresh connection built as the proxy builds them.
     */
    private final class TestPlatform implements DormancyController.Platform {
        final Map<UUID, String> backends = new ConcurrentHashMap<>();
        final Map<UUID, String> targets = new ConcurrentHashMap<>();
        final List<UUID> detached = new ArrayList<>();
        final List<CultConnection> resumed = new ArrayList<>();
        final Probe probe = new Probe(new ArrayList<>(), null);
        List<String> order = new ArrayList<>();

        @Override
        public @Nullable UUID player(CultConnection connection) {
            User user = connection.user();
            return user == null ? null : user.getUUID();
        }

        @Override
        public @Nullable String backend(UUID player) {
            return backends.get(player);
        }

        @Override
        public @Nullable String target(UUID player) {
            order.add("target");
            return targets.getOrDefault(player, backends.get(player));
        }

        @Override
        public String decoder() {
            return CultDecoder.NAME;
        }

        @Override
        public DormantWatch watch(CultConnection connection, boolean play) {
            User user = connection.user();
            return new DormantWatch(
                    connection.channel(),
                    ProtocolVersion.V26_3,
                    user.getUUID(),
                    user.getPlayer(),
                    user.getPlayer(),
                    connection.dormant(),
                    connection.drainRetiredPings(),
                    play,
                    DormancyController.hooks());
        }

        @Override
        public void removeHandlers(CultConnection connection) {
            CultDecoder.uninstall(connection.channel());
            CultEncoder.uninstall(connection.channel());
        }

        @Override
        public void detached(UUID player) {
            detached.add(player);
        }

        @Override
        public boolean accepting() {
            return true;
        }

        @Override
        public void resume(DormantWatch watch) {
            var connection = OfflineCultTestBootstrap.platformConnection();
            when(connection.authenticatedProfile()).thenReturn(new User.Profile(watch.player(), ".Dormant_Test"));
            var next = CultAPI.INSTANCE.getNetworkManager().createConnection(connection, watch.channel());
            if (watch.loginNotified()) next.continuation(ConnectionContinuation.from(watch.state()));
            next.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
            next.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
            CultDecoder.install(next);
            CultEncoder.install(next);
            watch.channel().pipeline().addBefore("decoder", "probe", probe);
            next.prepare();
            resumed.add(next);
        }

        @Override
        public void closed(DormantWatch watch) {}
    }
}
