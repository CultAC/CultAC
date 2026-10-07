package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundIntention;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HandshakeReentryTest {
    @Test
    void handshakeReadResumeCannotDecodeLoginBeforeCultFinishesTheHandshake() {
        var runtime = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
        var routes = new TestTransportRoutes(runtime);
        var channel = new EmbeddedChannel();
        var connection = new CultConnection(channel, routes.dispatcher, ignored -> null);
        var hello = new Hello("Define_Outside", UUID.randomUUID());
        ByteBuf login = Unpooled.buffer();
        Wire.writeVarInt(login, 0);
        Wire.writeString(login, hello.name(), 16);
        Wire.writeUuid(login, hello.id());
        var delivered = new AtomicBoolean();
        // LocalChannel can deliver its next buffered frame synchronously in read().
        channel.pipeline().addLast("local_read", new ChannelDuplexHandler() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                if (delivered.compareAndSet(false, true)) ctx.fireChannelRead(login);
            }
        });
        channel.pipeline().addLast("decoder", new HostDecoder(runtime, ConnectionPhase.HANDSHAKE));
        channel.pipeline().addLast("listener", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object packet) {
                if (packet instanceof ServerboundIntention) {
                    // Model the host's synchronous beginLogin: install LOGIN, then
                    // resume reads while the handshake call is still on the stack.
                    ctx.pipeline().replace("decoder", "decoder", new HostDecoder(runtime, ConnectionPhase.LOGIN));
                    ctx.channel().config().setAutoRead(true);
                } else ctx.fireChannelRead(packet);
            }
        });
        CultDecoder.install(connection);
        ByteBuf handshake = Unpooled.buffer();
        Wire.writeVarInt(handshake, 0);
        Wire.writeVarInt(handshake, 777);
        Wire.writeString(handshake, "localhost", 32767);
        handshake.writeShort(25565);
        Wire.writeVarInt(handshake, 2);
        try {
            assertTrue(channel.writeInbound(handshake));
            assertTrue(delivered.get());
            assertEquals(hello, channel.readInbound());
            assertNull(channel.readInbound());
            assertEquals(ConnectionPhase.LOGIN, connection.phase(PacketDirection.SERVERBOUND));
            assertEquals(ConnectionPhase.LOGIN, connection.phase(PacketDirection.CLIENTBOUND));
            assertTrue(channel.isActive());
            assertEquals(0, handshake.refCnt());
            assertEquals(0, login.refCnt());
        } finally {
            channel.finishAndReleaseAll();
            if (!delivered.get()) login.release();
        }
    }

    private record Hello(String name, UUID id) {}

    private static final class HostDecoder extends ChannelInboundHandlerAdapter {
        private final ProtocolRuntime runtime;
        private final ConnectionPhase phase;

        private HostDecoder(ProtocolRuntime runtime, ConnectionPhase phase) {
            this.runtime = runtime;
            this.phase = phase;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof ByteBuf frame)) {
                ctx.fireChannelRead(message);
                return;
            }
            Object packet;
            try {
                int id = Wire.readVarInt(frame);
                if (phase == ConnectionPhase.HANDSHAKE) {
                    packet = runtime.decode(ConnectionPhase.HANDSHAKE, PacketDirection.SERVERBOUND, id, frame);
                    ctx.channel().config().setAutoRead(false);
                } else {
                    assertEquals(ConnectionPhase.LOGIN, phase);
                    assertEquals(0, id);
                    packet = new Hello(Wire.readString(frame, 16), Wire.readUuid(frame));
                }
                assertFalse(frame.isReadable());
            } finally {
                frame.release();
            }
            ctx.fireChannelRead(packet);
        }
    }
}
