package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.cloud.diag.DiagUpload;
import ac.cult.cultac.cloud.diag.Diagnostics;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.PacketRouteBuilder;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.mockito.Mockito;

/**
 * One player on an embedded channel shaped as a proxy's or a server's pipeline, with their {@link CloudCapture}
 * uploading to {@link FakeUplinks}.
 */
final class CaptureFixture implements AutoCloseable {
    enum Host {
        /** Compression, ViaVersion, cultac, then the proxy's codec. */
        PROXY,
        /** The server's framing and codec, cultac ahead of it, no ViaVersion. */
        SERVER
    }

    static final ProtocolData DATA = ProtocolData.load(ProtocolVersion.V26_3);

    final EmbeddedChannel channel = new EmbeddedChannel();
    final CultNetworkManager manager = new CultNetworkManager();
    final PlatformConnection platform = OfflineCultTestBootstrap.platformConnection();
    final CultConnection connection;
    final User user;
    final CultPlayer player;
    final CloudCapture capture;
    final FakeUplinks uplinks = new FakeUplinks();

    CaptureFixture(Host host, boolean codec) {
        this(host, codec, routes -> {});
    }

    /** {@code routes} are the dispatcher's routes, as the platform's own listeners register them. */
    CaptureFixture(Host host, boolean codec, Consumer<PacketRouteBuilder> routes) {
        OfflineCultTestBootstrap.installConfig();
        manager.configureTransport(
                TestProtocolRuntime.create(DATA), () -> {}, () -> CompletableFuture.completedFuture(null));
        manager.dispatcher().register(routes);
        var pipeline = channel.pipeline();
        if (host == Host.PROXY) {
            pipeline.addLast("compression-decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("via-decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("compression-encoder", new ChannelOutboundHandlerAdapter());
            pipeline.addLast("via-encoder", new ChannelOutboundHandlerAdapter());
            pipeline.addLast("encoder", new ChannelOutboundHandlerAdapter());
        } else {
            pipeline.addLast("splitter", new ChannelInboundHandlerAdapter());
            pipeline.addLast("decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("prepender", new ChannelOutboundHandlerAdapter());
            pipeline.addLast("encoder", new ChannelOutboundHandlerAdapter());
        }
        connection = new CultConnection(platform, channel, manager.dispatcher(), ignored -> null);
        if (codec) {
            CultDecoder.install(connection);
            CultEncoder.install(connection);
        }
        phase(ConnectionPhase.PLAY);
        user = new User(new User.Profile(UUID.randomUUID(), "Steve"), connection);
        user.bind(Mockito.mock(PlatformPlayer.class));
        CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
        player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        assertNotNull(player);
        capture = new CloudCapture(player);
        player.observer = capture;
        CloudCapture.uplinks(uplinks);
    }

    /** The connection received {@code registries} on {@code observed}'s wire, in this order. */
    void receivedRegistries(ProtocolVersion observed, Map<String, List<String>> registries) {
        ClientComponentRegistries state = new ClientComponentRegistries();
        registries.forEach((registry, names) -> state.appendConfigurationRegistryData(new RegistryData(
                registry,
                names.stream()
                        .map(name -> new WireValueDecoder.RegistryEntry(name, null))
                        .toList())));
        state.publish(state.stageConfiguration(observed, observed, observed, null), observed);
        player.registryState = state;
    }

    void phase(ConnectionPhase phase) {
        for (PacketDirection direction : PacketDirection.values()) connection.phase(direction, phase);
    }

    /** Installs the capture and runs what it queued. */
    void install() {
        capture.reconfigure();
        channel.runPendingTasks();
    }

    int id(PacketDirection direction, String name) {
        int id = DATA.packets(ConnectionPhase.PLAY, direction).id("minecraft:" + name);
        assertTrue(id >= 0, name);
        return id;
    }

    /** A play packet's bytes: its id, then {@code body}. */
    ByteBuf packet(PacketDirection direction, String name, byte... body) {
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(frame, id(direction, name));
        frame.writeBytes(body);
        return frame;
    }

    /** The client sends a packet; it passes the whole pipeline. */
    void serverbound(String name, byte... body) {
        channel.writeInbound(packet(PacketDirection.SERVERBOUND, name, body));
        Object read;
        while ((read = channel.readInbound()) != null) ReferenceCountUtil.release(read);
    }

    /** The client ends a tick, and cultac's tick-end listeners run for it. */
    void tickEnd() {
        long before = player.clientTickEnds.get();
        serverbound("client_tick_end");
        if (player.clientTickEnds.get() == before) player.clientTickEnds.incrementAndGet();
        capture.tickEnded();
        channel.runPendingTasks();
    }

    /** Raw bytes the client is sent, past cultac's own codec. */
    void clientbound(String name, byte... body) {
        channel.writeOutbound(packet(PacketDirection.CLIENTBOUND, name, body));
        Object written;
        while ((written = channel.readOutbound()) != null) ReferenceCountUtil.release(written);
    }

    @Override
    public void close() {
        CloudCapture.uplinks(CloudCapture.Uplinks.NONE);
        CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(user);
        channel.finishAndReleaseAll();
    }

    /** Sockets by backend name; each connects at once on its first registration unless told not to. */
    static final class FakeUplinks implements CloudCapture.Uplinks {
        final Map<String, FakeUplink> sockets = new LinkedHashMap<>();
        boolean enabled = true;
        boolean connect = true;
        CloudConnection.Terms terms = CloudConnection.Terms.sections(0, 0);
        int opened;
        int released;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public FakeUplink open(String serverName) {
            if (!enabled) return null;
            opened++;
            return sockets.computeIfAbsent(serverName, name -> new FakeUplink(name, this));
        }

        @Override
        public void release(CloudCapture.Uplink uplink) {
            released++;
        }

        FakeUplink socket(String name) {
            FakeUplink socket = sockets.get(name);
            assertNotNull(socket, name);
            return socket;
        }
    }

    /** One socket: everything sent on it, decoded. */
    static final class FakeUplink implements CloudCapture.Uplink {
        final String id;
        final FakeUplinks owner;
        final DiagUpload upload = new DiagUpload();
        final Diagnostics diagnostics;
        final List<IntakeMessage> sent = new ArrayList<>();
        final Map<UUID, CloudConnection.Stream> streams = new LinkedHashMap<>();
        long generation = 1;

        FakeUplink(String id, FakeUplinks owner) {
            this.id = id;
            this.owner = owner;
            this.diagnostics = new Diagnostics(upload, id);
        }

        @Override
        public String serverId() {
            return id;
        }

        @Override
        public boolean same(CloudCapture.Uplink other) {
            return other == this;
        }

        @Override
        public void register(UUID player, CloudConnection.Stream stream) {
            streams.put(player, stream);
            if (owner.connect) stream.connected(generation);
        }

        @Override
        public void unregister(UUID player, CloudConnection.Stream stream) {
            streams.remove(player, stream);
        }

        @Override
        public boolean send(long generation, byte[] record, long resolvedExtra) {
            if (generation != this.generation) return false;
            try {
                sent.addAll(Intake.parseFrom(record).getMessagesList());
            } catch (InvalidProtocolBufferException malformed) {
                throw new AssertionError(malformed);
            }
            return true;
        }

        @Override
        public CloudConnection.Terms terms(long generation) {
            return owner.terms;
        }

        @Override
        public int maxFrameBytes() {
            return 1 << 20;
        }

        @Override
        public Diagnostics diagnostics() {
            return diagnostics;
        }

        /** The service answers the JOIN of {@code player}: every packet is wanted. */
        void captureAll(UUID player) {
            int[] all = new int[256];
            for (int i = 0; i < all.length; i++) all[i] = i;
            reply(player, new IntakeWire.Capture(player, all, all, new int[0]));
        }

        void reply(UUID player, IntakeWire.Reply reply) {
            streams.get(player).reply(generation, reply);
        }

        List<IntakeMessage.KindCase> kinds() {
            List<IntakeMessage.KindCase> kinds = new ArrayList<>();
            for (IntakeMessage message : sent) kinds.add(message.getKindCase());
            return kinds;
        }

        List<IntakeMessage> of(IntakeMessage.KindCase kind) {
            List<IntakeMessage> found = new ArrayList<>();
            for (IntakeMessage message : sent) if (message.getKindCase() == kind) found.add(message);
            return found;
        }
    }
}
