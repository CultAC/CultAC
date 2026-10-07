package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;

final class RecordReceiveFixture implements AutoCloseable {
    final ProtocolData data;
    final CultNetworkManager manager = new CultNetworkManager();
    final PacketDispatcher registrar;
    final EmbeddedChannel channel = new EmbeddedChannel();
    final CultConnection connection;
    final CodecFixture codec;
    final User user;
    final CultPlayer player;
    User resolvedUser;

    RecordReceiveFixture(ProtocolVersion version) {
        this(version, true);
    }

    RecordReceiveFixture(ProtocolVersion version, boolean createPlayer) {
        this(version, ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.platformConnection(), createPlayer);
    }

    /**
     * A host whose wire is older than the 26.3 model, configured as Paper and Velocity configure
     * it: the dispatcher speaks the model and the connection owns model values.
     */
    static RecordReceiveFixture olderHost(ProtocolVersion observed) {
        var platform = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.platformConnection();
        var values = new ac.cult.cultac.network.codec.ConnectionModelValues(ProtocolCodecs.decoder());
        org.mockito.Mockito.when(platform.getObservedProtocol()).thenReturn(observed);
        org.mockito.Mockito.when(platform.modelValues()).thenReturn(values);
        // The PlatformConnection defaults, which both platform adapters inherit.
        org.mockito.Mockito.when(platform.registryNames()).thenAnswer(ignored -> values.registryNames());
        org.mockito.Mockito.when(platform.packetValues(org.mockito.Mockito.any()))
                .thenAnswer(invocation -> values.packetValues(observed, invocation.getArgument(0)));
        return new RecordReceiveFixture(observed, platform, true);
    }

    private RecordReceiveFixture(ProtocolVersion version, PlatformConnection platform, boolean createPlayer) {
        OfflineCultTestBootstrap.installConfig();
        data = ProtocolData.load(version);
        var runtime = TestProtocolRuntime.create(
                platform.getObservedProtocol() == null ? data : ProtocolData.load(ProtocolVersion.V26_3));
        manager.configureTransport(
                runtime, () -> {}, () -> java.util.concurrent.CompletableFuture.completedFuture(null));
        registrar = manager.dispatcher();
        var routes = registrar;
        codec = new CodecFixture(runtime);
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        connection = new CultConnection(
                platform,
                channel,
                routes,
                ignored -> null);
        CultDecoder.install(connection);
        CultEncoder.install(connection);
        phase(ConnectionPhase.PLAY);
        user = new User(new User.Profile(UUID.randomUUID(), ".Record_User_Test"), connection);
        if (createPlayer) CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
        player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        if (createPlayer) assertNotNull(player);
        resolvedUser = user;
    }

    void phase(ConnectionPhase phase) {
        codec.phase(phase);
        for (var direction : PacketDirection.values()) connection.phase(direction, phase);
    }

    ByteBuf id(PacketType<?> type, String name) {
        ByteBuf frame = Unpooled.buffer();
        int id = data.packets(connection.phase(type.direction()), type.direction())
                .id("minecraft:" + name);
        assertTrue(id >= 0);
        Wire.writeVarInt(frame, id);
        return frame;
    }

    ByteBuf pong(int id) {
        return id(ServerboundPackets.PONG, "pong").writeInt(id);
    }

    <R> ByteBuf encode(PacketType<R> type, R packet) {
        ByteBuf frame = Unpooled.buffer();
        codec.write(type, packet, frame);
        return frame;
    }

    void forward(ByteBuf frame) {
        assertTrue(channel.writeInbound(frame));
        assertSame(frame, channel.readInbound());
        frame.release();
    }

    @Override
    public void close() {
        CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(user);
        channel.finishAndReleaseAll();
    }
}
