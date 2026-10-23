package ac.cult.cultac.companion.bridge;

import static java.lang.invoke.MethodType.methodType;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.BridgeProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/** Consumes inbound {@code cultac:bridge} payloads; every other packet passes after one instanceof check. */
final class BridgeChannelHandler extends ChannelInboundHandlerAdapter {
    static final String NAME = "cultac-bridge";

    // DiscardedPayload's id is a ResourceLocation before 1.21.11 and an Identifier after; its data is a ByteBuf on
    // 1.21.2-1.21.3 and a byte[] after.
    private static final Object CHANNEL;
    private static final MethodHandle PAYLOAD_ID;
    private static final MethodHandle PAYLOAD_DATA;
    private static final MethodHandle NEW_PAYLOAD;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Method id = DiscardedPayload.class.getMethod("id");
            Method data = DiscardedPayload.class.getMethod("data");
            Class<?> idType = id.getReturnType();
            Class<?> dataType = data.getReturnType();
            CHANNEL = idType.getMethod("parse", String.class).invoke(null, BridgeProtocol.CHANNEL);
            MethodHandle wrap =
                    lookup.findStatic(Unpooled.class, "wrappedBuffer", methodType(ByteBuf.class, byte[].class));
            MethodType untyped = methodType(Object.class, Object.class);
            PAYLOAD_ID = lookup.unreflect(id).asType(untyped);
            MethodHandle read = lookup.unreflect(data);
            PAYLOAD_DATA =
                    (dataType == ByteBuf.class ? read : MethodHandles.filterReturnValue(read, wrap)).asType(untyped);
            MethodHandle create = MethodHandles.insertArguments(
                    lookup.findConstructor(DiscardedPayload.class, methodType(void.class, idType, dataType)),
                    0,
                    CHANNEL);
            NEW_PAYLOAD = (dataType == ByteBuf.class ? MethodHandles.filterArguments(create, 0, wrap) : create)
                    .asType(untyped);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

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
                || !CHANNEL.equals(invoke(PAYLOAD_ID, payload))) {
            ctx.fireChannelRead(msg);
            return;
        }
        BridgeMessage message;
        ByteBuf data = (ByteBuf) invoke(PAYLOAD_DATA, payload);
        try {
            message = BridgeCodec.decode(data);
        } catch (BridgeProtocolException failure) {
            service.protocolError(failure);
            return;
        } catch (RuntimeException failure) {
            service.debug("Dropping undecodable bridge frame: " + failure);
            return;
        } finally {
            data.release();
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
            Packet<?> packet = new ClientboundCustomPayloadPacket(
                    (DiscardedPayload) invoke(NEW_PAYLOAD, BridgeCodec.toBytes(message)));
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
            return OrderedTask.schedule(MinecraftServer.getServer().overworld(), connection.getPacketListener(), task);
        }
    }

    private static Object invoke(MethodHandle handle, Object argument) {
        try {
            return (Object) handle.invokeExact(argument);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
