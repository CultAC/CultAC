package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.BridgeProtocolException;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.UUID;
import java.util.logging.Level;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/** Consumes inbound {@code cultac:bridge} payloads; every other packet passes after one instanceof check. */
final class BridgeChannelHandler extends ChannelInboundHandlerAdapter {
    static final String NAME = "cultac-bridge";
    static final Identifier CHANNEL = Identifier.parse(BridgeProtocol.CHANNEL);

    private final BridgeService service;
    private final ConnectionEndpoint endpoint;

    BridgeChannelHandler(BridgeService service, Connection connection) {
        this.service = service;
        this.endpoint = new ConnectionEndpoint(connection);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ServerboundCustomPayloadPacket packet)
                || !(packet.payload() instanceof DiscardedPayload payload)
                || !CHANNEL.equals(payload.id())) {
            ctx.fireChannelRead(msg);
            return;
        }
        BridgeMessage message;
        try {
            message = BridgeCodec.decode(Unpooled.wrappedBuffer(payload.data()));
        } catch (BridgeProtocolException failure) {
            service.protocolError(failure);
            return;
        } catch (RuntimeException failure) {
            service.debug("Dropping undecodable bridge frame: " + failure);
            return;
        }
        try {
            service.receive(endpoint, message);
        } catch (Throwable failure) {
            service.logger()
                    .log(
                            Level.WARNING,
                            "Bridge failed handling " + message.getClass().getSimpleName(),
                            failure);
        }
    }

    /** Call on the channel's event loop once the player has joined. */
    void attached() {
        try {
            service.attached(endpoint);
        } catch (Throwable failure) {
            service.logger().log(Level.WARNING, "Bridge failed handling a held HELLO", failure);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        service.endpointClosed(endpoint);
        super.channelInactive(ctx);
    }

    /** Writes bridge frames as clientbound custom payloads on the player's native connection. */
    private static final class ConnectionEndpoint extends BridgeEndpoint {
        private final Connection connection;

        ConnectionEndpoint(Connection connection) {
            this.connection = connection;
        }

        @Override
        @Nullable
        UUID playerId() {
            ServerPlayer player = connection.getPlayer();
            return player == null ? null : player.getUUID();
        }

        @Override
        void send(BridgeMessage message) {
            Packet<?> packet =
                    new ClientboundCustomPayloadPacket(new DiscardedPayload(CHANNEL, BridgeCodec.toBytes(message)));
            if (connection.getPacketListener() instanceof ServerCommonPacketListenerImpl listener)
                listener.send(packet);
            else connection.send(packet);
        }

        @Override
        void execute(Runnable task) {
            connection.channel.eventLoop().execute(task);
        }

        @Override
        boolean runInPacketOrder(Runnable task) {
            return OrderedTask.schedule(
                    MinecraftServer.getServer().packetProcessor(), connection.getPacketListener(), task);
        }
    }
}
