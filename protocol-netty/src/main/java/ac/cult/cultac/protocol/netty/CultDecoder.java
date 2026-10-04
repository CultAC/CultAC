package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/** Processes and forwards a frame synchronously on its connection's event loop. */
public final class CultDecoder extends ChannelInboundHandlerAdapter {
    public static final String NAME = "cult-decoder";
    private final CultConnection connection;

    public CultDecoder(CultConnection connection) {
        this.connection = connection;
    }

    public static void install(CultConnection connection) {
        var pipeline = connection.channel().pipeline();
        String before = pipeline.get("decoder") != null ? "decoder" : "inbound_config";
        pipeline.addBefore(
                connection.owner() == connection.channel().eventLoop() ? null : connection.owner(),
                before,
                NAME,
                new CultDecoder(connection));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf frame)) {
            ctx.fireChannelRead(message);
            return;
        }
        try {
            connection.resolveOwner();
            if (ctx.executor() != connection.owner()) {
                PacketExecutors.rebindAndRead(ctx, connection, frame);
                return;
            }
        } catch (Throwable failure) {
            frame.release();
            fail(ctx, failure);
            return;
        }
        connection.runInModel(() -> {
            try {
                var output = process(frame);
                if (output != null) {
                    // beginLogin can install its codec and resume LocalChannel reads before
                    // the handshake listener returns. Its next frame already belongs to LOGIN.
                    boolean handshake = output.type == ServerboundPackets.INTENTION;
                    if (handshake) connection.forwarded(output.type, output.packet);
                    ctx.fireChannelRead(output.frame);
                    if (!handshake && output.type != null) connection.forwarded(output.type, output.packet);
                }
            } catch (Throwable failure) {
                fail(ctx, failure);
            }
        });
    }

    /** Consumes the input on every path; null means it was cancelled or discarded. */
    @SuppressWarnings("unchecked")
    private Forward process(ByteBuf frame) {
        try {
            var phase = connection.phase(PacketDirection.SERVERBOUND);
            PacketType<?> forwardedType = null;
            ServerboundPacket forwardedPacket = null;
            for (var value : connection
                    .packets()
                    .read(
                            phase,
                            PacketDirection.SERVERBOUND,
                            frame,
                            connection,
                            type -> connection.dispatcher().get(type) != null)) {
                var route = connection.dispatcher().get(value.type());
                var type = (PacketType<ServerboundPacket>) route.type();
                var original = (ServerboundPacket) value.packet();
                connection.prepare();
                var event = new PacketReceiveEvent<>(connection.user(), phase, type, original);
                if (route.receive() != null) connection.dispatcher().receive(event, route.receive());
                ServerboundPacket packet = event.getPacket();
                boolean cancelled = event.isCancelled();
                var player = connection.player();
                // A correction may override cancellation only while the ordinary
                // position slot is unused. Otherwise retain it for the next tick
                // and honor the listener's cancellation of this movement.
                if (original instanceof ServerboundMovePlayer move
                        && move.hasPosition()
                        && !event.isTeleportPositionResponse()
                        && player != null
                        && !player.getSetbackTeleportUtil().hasForwardedPositionThisTick()) {
                    var pending = player.getSetbackTeleportUtil().takePendingServerMove();
                    if (pending != null) {
                        packet = move.withPosition(pending.x(), pending.y(), pending.z(), pending.onGround());
                        cancelled = false;
                    }
                }
                if (cancelled) return null;
                if (packet != original) {
                    var replacement = connection
                            .packets()
                            .encode(phase, type, packet, connection.channel().alloc(), connection);
                    if (replacement.frames().size() != 1) {
                        replacement.frames().forEach(ByteBuf::release);
                        throw new IllegalStateException("Inbound replacement must encode one physical frame");
                    }
                    frame.release();
                    frame = replacement.frames().getFirst();
                }
                if (player != null) {
                    var setbacks = player.getSetbackTeleportUtil();
                    if (value.type() == ServerboundPackets.CLIENT_TICK_END) setbacks.resetForwardedPositionThisTick();
                    else if (packet instanceof ServerboundMovePlayer move
                            && move.hasPosition()
                            && !event.isTeleportPositionResponse()) setbacks.markForwardedPositionThisTick();
                }
                if (packet instanceof ServerboundMovePlayer move
                        && move.hasPosition()
                        && player != null
                        && connection.user() != null
                        && connection.user().getBedrockBridgeConnection() != null) {
                    player.getSetbackTeleportUtil()
                            .setBedrockPaperVisiblePosition(
                                    new net.minecraft.world.phys.Vec3(move.x(), move.y(), move.z()));
                }
                forwardedType = type;
                forwardedPacket = packet;
            }
            var output = new Forward(frame, forwardedType, forwardedPacket);
            frame = null;
            return output;
        } finally {
            if (frame != null) frame.release();
        }
    }

    private void fail(ChannelHandlerContext ctx, Throwable failure) {
        try {
            ctx.fireExceptionCaught(failure);
        } finally {
            ctx.close();
        }
    }

    private record Forward(ByteBuf frame, PacketType<?> type, Object packet) {}
}
