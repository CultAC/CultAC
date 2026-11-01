package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.PacketSendRoute;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.api.config.ConfigManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Thread CPU of packet dispatch plumbing: scanned handlers behind {@link PacketDispatcher}, frames through
 * {@link CultDecoder} and {@link CultEncoder}, and walking Java players through the production check listeners. Runs
 * only with {@code CULT_DISPATCH_BENCH} set; results go to stdout and {@code build/dispatch-cpu-bench.txt}. It lives
 * in this package for the package-private offline helpers it uses: {@link OfflineCultTestBootstrap}'s config
 * overrides, {@link RecordReceiveTestEvents} and {@link OfflineBedrockReplayRunnerTest}'s player cleanup.
 */
class DispatchCpuBenchTest {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final int FLOOR_Y = 64;
    private static final int FLOOR_RADIUS = 48;
    private static PrintStream report;

    @Test
    void dispatchCpu() throws IOException, ReflectiveOperationException {
        Assumptions.assumeTrue(System.getenv("CULT_DISPATCH_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_DISPATCH_BENCH_REPS", "7"));
        Path out = Path.of("build", "dispatch-cpu-bench.txt");
        Files.createDirectories(out.getParent());
        report = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8);
        OfflineCultTestBootstrap.installConfig();
        Class.forName(VerboseCodecs.class.getName());
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        Mockito.when(config.getLongElse(Mockito.anyString(), Mockito.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Field dataStore = CultAPI.class.getDeclaredField("dataStoreLifecycle");
        dataStore.setAccessible(true);
        Object previousDataStore = dataStore.get(CultAPI.INSTANCE);
        dataStore.set(
                CultAPI.INSTANCE,
                new DataStoreLifecycle(CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry()));
        OfflineCultTestBootstrap.setDoubleConfigOverride("Simulation.threshold", 1e9);
        OfflineCultTestBootstrap.setDoubleConfigOverride("Simulation.immediate-setback-threshold", -1);
        try {
            plumbing(repetitions);
            transport(repetitions);
            players(repetitions);
        } finally {
            dataStore.set(CultAPI.INSTANCE, previousDataStore);
            report.close();
        }
    }

    private static PacketDispatcher trivialDispatcher() {
        var dispatcher = new PacketDispatcher(
                CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
        dispatcher.register(registrar -> {
            for (int i = 0; i < 2; i++) {
                registrar.registerListener(PacketListenerPriority.NORMAL, new TrivialA());
                registrar.registerListener(PacketListenerPriority.NORMAL, new TrivialB());
                registrar.registerListener(PacketListenerPriority.NORMAL, new TrivialC());
                registrar.registerListener(PacketListenerPriority.NORMAL, new TrivialD());
            }
        });
        return dispatcher;
    }

    private static void plumbing(int repetitions) {
        var dispatcher = trivialDispatcher();
        CultPlayer player = player(new EmbeddedChannel());
        attach(player);
        var receive = dispatcher.get(ServerboundPackets.PONG).receive();
        var send = dispatcher.get(ClientboundPackets.PING).send();
        var pong = new PacketReceiveEvent<>(
                player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, new ServerboundPong(1));
        var ping = new PacketSendEvent<>(
                player.user, ConnectionPhase.PLAY, ClientboundPackets.PING, new ClientboundPing(1), false);
        int batch = 20_000_000;
        try {
            for (int i = 0; i < 3; i++) dispatchBatch(dispatcher, receive, send, pong, ping, batch);
            double[] perPair = new double[repetitions];
            for (int r = 0; r < repetitions; r++) {
                long start = THREADS.getCurrentThreadCpuTime();
                dispatchBatch(dispatcher, receive, send, pong, ping, batch);
                perPair[r] = (THREADS.getCurrentThreadCpuTime() - start) / (double) batch;
            }
            log("plumbing: receive+send through 8 scanned handlers each", perPair, "ns/pair");
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
        if (TrivialA.sink == 42) log("unreachable");
    }

    private static void dispatchBatch(
            PacketDispatcher dispatcher,
            PacketDispatcher.ReceiveRoute<ServerboundPacket> receive,
            PacketSendRoute<Object> send,
            PacketReceiveEvent<ServerboundPong> pong,
            PacketSendEvent<ClientboundPing> ping,
            int count) {
        for (int i = 0; i < count; i++) {
            dispatcher.receive(pong, receive);
            dispatcher.send(ping, send);
        }
    }

    private static void transport(int repetitions) {
        var dispatcher = trivialDispatcher();
        var channel = new EmbeddedChannel();
        var connection = new CultConnection(channel, dispatcher, ignored -> new PacketOwner(channel.eventLoop(), null));
        for (var direction : PacketDirection.values()) connection.phase(direction, ConnectionPhase.PLAY);
        var user = new User(new User.Profile(UUID.randomUUID(), ".Dispatch_Bench"), connection);
        CultPlayer player = new CultPlayer(user);
        attach(player);
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        CultDecoder.install(connection);
        CultEncoder.install(connection);
        var data = dispatcher.runtime().data();
        ByteBuf pong = Unpooled.buffer();
        Wire.writeVarInt(
                pong,
                data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:pong"));
        pong.writeInt(1);
        ByteBuf ping = Unpooled.buffer();
        Wire.writeVarInt(
                ping,
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:ping"));
        ping.writeInt(1);
        int batch = 2_000_000;
        try {
            for (int i = 0; i < 3; i++) frameBatch(channel, pong, ping, batch);
            double[] perPair = new double[repetitions];
            for (int r = 0; r < repetitions; r++) {
                long start = THREADS.getCurrentThreadCpuTime();
                frameBatch(channel, pong, ping, batch);
                perPair[r] = (THREADS.getCurrentThreadCpuTime() - start) / (double) batch;
            }
            log("transport: pong frame in + ping frame out, 8 scanned handlers each", perPair, "ns/pair");
        } finally {
            pong.release();
            ping.release();
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static void frameBatch(EmbeddedChannel channel, ByteBuf pong, ByteBuf ping, int count) {
        for (int i = 0; i < count; i++) {
            channel.writeInbound(pong.retainedDuplicate());
            ReferenceCountUtil.release(channel.readInbound());
            channel.writeAndFlush(ping.retainedDuplicate());
            ReferenceCountUtil.release(channel.readOutbound());
        }
    }

    private static void players(int repetitions) {
        int bots = 128;
        var dispatcher = new PacketDispatcher(
                CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
        // The PacketManager registrations a Java player's movement tick reaches, in its order.
        dispatcher.register(registrar -> {
            CheckManagerListener checkManagerListener = new CheckManagerListener();
            checkManagerListener.registerForwardingEarlyReceivePackets(registrar, PacketListenerPriority.LOW);
            registrar.registerReceiveListener(PacketListenerPriority.LOW, new PacketEntityAction());
            registrar.registerReceiveListener(PacketListenerPriority.LOW, checkManagerListener);
            checkManagerListener.registerForwardingSendPackets(registrar, PacketListenerPriority.LOW);
            registrar.registerReceiveListener(PacketListenerPriority.LOW, new PacketPlayerSteer());
        });
        CultPlayer[] players = new CultPlayer[bots];
        Mover[] movers = new Mover[bots];
        for (int b = 0; b < bots; b++) {
            players[b] = player(new EmbeddedChannel());
            movers[b] = new Mover(b);
            seed(players[b], movers[b]);
            attach(players[b]);
        }
        int windowTicks = 400;
        double[] perTick = new double[repetitions];
        long keepAlive = 0;
        try {
            for (int window = -1; window < repetitions; window++) {
                long start = THREADS.getCurrentThreadCpuTime();
                for (int t = 0; t < windowTicks; t++) {
                    for (int b = 0; b < bots; b++) tick(dispatcher, players[b], movers[b], keepAlive++);
                }
                long cpu = THREADS.getCurrentThreadCpuTime() - start;
                if (window >= 0) perTick[window] = cpu / 1e3 / ((double) windowTicks * bots);
            }
            log("players: one Java tick per player through dispatch (" + bots + " players)", perTick, "us/player-tick");
            int predicted = 0;
            for (CultPlayer player : players) {
                if (player.checkManager.getSimulationProcessor().getLastPrediction() != null) predicted++;
            }
            log("players predicted " + predicted + "/" + bots);
        } finally {
            for (CultPlayer player : players) OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void tick(PacketDispatcher dispatcher, CultPlayer player, Mover mover, long keepAlive) {
        var out = new PacketSendEvent<>(
                player.user,
                ConnectionPhase.PLAY,
                ClientboundPackets.KEEP_ALIVE,
                new ClientboundKeepAlive(keepAlive),
                false);
        dispatcher.send(out, dispatcher.get(ClientboundPackets.KEEP_ALIVE).send());
        receive(dispatcher, RecordReceiveTestEvents.keepAlive(player, new ServerboundKeepAlive(keepAlive)));
        ServerboundPlayerInput input = mover.tick();
        if (input != null) receive(dispatcher, RecordReceiveTestEvents.input(player, input));
        ServerboundMovePlayer move = new ServerboundMovePlayer(
                mover.x, mover.y, mover.z, mover.yaw, mover.pitch, mover.onGround, false, true, true);
        receive(dispatcher, RecordReceiveTestEvents.movement(player, move));
        receive(dispatcher, RecordReceiveTestEvents.tickEnd(player));
    }

    private static <R extends ServerboundPacket> void receive(
            PacketDispatcher dispatcher, PacketReceiveEvent<R> event) {
        dispatcher.receive(event, dispatcher.get(event.getPacketType()).receive());
    }

    private static CultPlayer player(EmbeddedChannel channel) {
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Dispatch_Bench"), channel);
        CultPlayer player = new CultPlayer(user);
        player.gamemode = GameMode.SURVIVAL;
        player.canFly = false;
        player.isFlying = false;
        CompensatedWorld world = player.compensatedWorld;
        BlockRegistry registry = DataTables.defaults().registry();
        int stone = registry.block("minecraft:stone").defaultState();
        for (int cx = -FLOOR_RADIUS >> 4; cx <= FLOOR_RADIUS >> 4; cx++) {
            for (int cz = -FLOOR_RADIUS >> 4; cz <= FLOOR_RADIUS >> 4; cz++) {
                world.putChunk(cx, cz, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            }
        }
        for (int x = -FLOOR_RADIUS; x < FLOOR_RADIUS; x++) {
            for (int z = -FLOOR_RADIUS; z < FLOOR_RADIUS; z++) {
                world.applyBlockChangeRawDANGER(x, FLOOR_Y - 1, z, stone);
            }
        }
        return player;
    }

    private static void seed(CultPlayer player, Mover mover) {
        player.x = player.lastX = mover.x;
        player.y = player.lastY = mover.y;
        player.z = player.lastZ = mover.z;
        player.xRot = player.lastTickXRot = mover.yaw;
        player.yRot = player.lastTickYRot = mover.pitch;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, mover.x, mover.y, mover.z);
        boolean onGround = Collisions.collide(player, 0, -SimpleCollisionBox.COLLISION_EPSILON, 0).y == 0;
        player.onGround = onGround;
        player.lastOnGround = onGround;
        player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
    }

    private static void attach(CultPlayer player) {
        var connection = player.user.getCultConnection();
        synchronized (connection) {
            connection.player(player);
        }
    }

    private static void log(String label, double[] samples, String unit) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        log(String.format(
                Locale.ROOT,
                "%s: median %.3f %s, min %.3f, max %.3f, all %s",
                label,
                sorted[sorted.length / 2],
                unit,
                sorted[0],
                sorted[sorted.length - 1],
                Arrays.toString(Arrays.stream(samples)
                        .map(v -> Math.round(v * 1000) / 1000.0)
                        .toArray())));
    }

    private static void log(String line) {
        System.out.println(line);
        report.println(line);
    }

    /** A Java client walking on flat stone along z, turning every 150 ticks: friction 0.6, gravity 0.08, drag 0.98. */
    private static final class Mover {
        double x = 0.5;
        double y = FLOOR_Y;
        double z;
        double vx;
        double vy = (0 - 0.08) * 0.98F;
        double vz;
        float yaw;
        float pitch = 10;
        boolean onGround = true;
        int tick;
        ServerboundPlayerInput lastInput;

        Mover(int seed) {
            z = -20.5 + seed % 5;
            tick = seed * 11;
        }

        /** Advances one tick and returns the input packet the client sends, or null when it is unchanged. */
        ServerboundPlayerInput tick() {
            tick++;
            yaw = tick / 150 % 2 == 0 ? 0 : 180;
            ServerboundPlayerInput input = new ServerboundPlayerInput(true, false, false, false, false, false, false);
            ServerboundPlayerInput changed = input.equals(lastInput) ? null : input;
            lastInput = input;
            if (vx * vx + vz * vz < 9.0E-6) {
                vx = 0;
                vz = 0;
            }
            if (Math.abs(vy) < 0.003) vy = 0;
            float speed = onGround ? 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F)) : 0.02F;
            float forward = 0.98F * speed;
            float angle = yaw * ((float) Math.PI / 180F);
            vx += -Math.sin(angle) * forward;
            vz += Math.cos(angle) * forward;
            double dy = vy;
            if (y + dy < FLOOR_Y) {
                dy = FLOOR_Y - y;
                onGround = vy < 0;
                vy = 0;
            } else {
                onGround = false;
            }
            x += vx;
            y += dy;
            z += vz;
            float friction = (onGround ? 0.6F : 1.0F) * 0.91F;
            vx *= friction;
            vy = (vy - 0.08) * 0.98F;
            vz *= friction;
            return changed;
        }
    }

    public static final class TrivialA {
        static long sink;

        @CultPacketHandler
        public void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            sink += packet.id();
        }

        @CultPacketHandler
        public void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
            sink += packet.id();
        }
    }

    public static final class TrivialB {
        @CultPacketHandler
        public void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            TrivialA.sink ^= packet.id();
        }

        @CultPacketHandler
        public void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
            TrivialA.sink ^= packet.id();
        }
    }

    public static final class TrivialC {
        @CultPacketHandler
        public void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            TrivialA.sink -= packet.id();
        }

        @CultPacketHandler
        public void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
            TrivialA.sink -= packet.id();
        }
    }

    public static final class TrivialD {
        @CultPacketHandler
        public void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            TrivialA.sink *= 31;
        }

        @CultPacketHandler
        public void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
            TrivialA.sink *= 31;
        }
    }
}
