package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.DroppedPacketException;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.flow.FlowControlHandler;
import java.util.function.Predicate;

/** Processes and forwards a frame synchronously on its connection's event loop. */
public final class CultDecoder extends ChannelInboundHandlerAdapter {
    public static final String NAME = "cult-decoder";
    private static final String HOST_FLOW_NAME = NAME + "-host-flow";
    private static final long FAILURE_LOG_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    private final CultConnection connection;
    private long lastFailureLog;
    private int suppressedFailures;
    private final Predicate<PacketType<?>> routed;

    public CultDecoder(CultConnection connection) {
        this.connection = connection;
        routed = type -> connection.dispatcher().get(type) != null;
    }

    public static void install(CultConnection connection) {
        var pipeline = connection.channel().pipeline();
        String before = pipeline.get("decoder") != null ? "decoder" : "inbound_config";
        pipeline.addBefore(
                connection.owner() == connection.channel().eventLoop() ? null : connection.owner(),
                before,
                NAME,
                new CultDecoder(connection));
        if (connection.owner() != connection.channel().eventLoop()) {
            // Frames queued on the packet owner have passed the host's upstream
            // flow handler. Honor terminal-packet read pauses at the native decoder.
            pipeline.addAfter(NAME, HOST_FLOW_NAME, new HostFlow());
        }
    }

    public static void uninstall(Channel channel) {
        var pipeline = channel.pipeline();
        if (pipeline.get(HOST_FLOW_NAME) != null) pipeline.remove(HOST_FLOW_NAME);
        if (pipeline.get(NAME) != null) pipeline.remove(NAME);
    }

    private static final class HostFlow extends FlowControlHandler {
        @Override
        public void read(ChannelHandlerContext ctx) throws Exception {
            // A terminal decoder can request a read as it is removed. Paper's
            // inbound_config rejects every frame until the next codec is installed.
            if (ctx.pipeline().get("inbound_config") == null) super.read(ctx);
        }
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
            CultNetworkManager.reportFailure("inbound packet owner", failure);
            ctx.fireChannelRead(frame);
            return;
        }
        boolean[] consumed = {false};
        try {
            connection.runInModel(() -> {
                consumed[0] = true;
                var output = process(frame);
                if (output != null) {
                    // beginLogin can install its codec and resume LocalChannel reads before
                    // the handshake listener returns. Its next frame already belongs to LOGIN.
                    boolean handshake = output.type == ServerboundPackets.INTENTION;
                    boolean reconfiguration = output.type == ServerboundPackets.CONFIGURATION_ACKNOWLEDGED;
                    if (handshake) forwarded(output);
                    if (reconfiguration && !connection.handoffAtReconfiguration()) {
                        output.frame.release();
                        return;
                    }
                    ctx.fireChannelRead(output.frame);
                    if (!handshake && output.type != null) forwarded(output);
                }
            });
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("inbound model context", failure);
            if (!consumed[0]) ctx.fireChannelRead(frame);
        }
    }

    /** Consumes the input on every path; null means it was cancelled or discarded. */
    @SuppressWarnings("unchecked")
    private Forward process(ByteBuf frame) {
        ByteBuf originalFrame = frame;
        boolean releaseOriginal = true;
        PacketType<?> originalType = null;
        ServerboundPacket originalPacket = null;
        try {
            var phase = connection.phase(PacketDirection.SERVERBOUND);
            connection.observePacket(PacketDirection.SERVERBOUND, phase, frame);
            PacketType<?> forwardedType = null;
            ServerboundPacket forwardedPacket = null;
            var value = connection.packets().readOne(phase, PacketDirection.SERVERBOUND, frame, connection, routed);
            if (value != null) {
                var route = connection.dispatcher().get(value.type());
                var type = (PacketType<ServerboundPacket>) route.type();
                var original = (ServerboundPacket) value.packet();
                originalType = type;
                originalPacket = original;
                var pausing = connection.player();
                // A debug interaction pause discards before Cult or the server sees the packet.
                if (pausing != null && pausing.discardsPausedInteraction(original)) return null;
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
                    if (frame != originalFrame) frame.release();
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
                                    new ac.cult.cultac.utils.math.Vec3(move.x(), move.y(), move.z()));
                }
                forwardedType = type;
                forwardedPacket = packet;
            }
            var output = new Forward(frame, forwardedType, forwardedPacket);
            releaseOriginal = frame != originalFrame;
            frame = null;
            return output;
        } catch (Throwable failure) {
            reportDecodeFailure("inbound packet decoding", failure);
            if (frame != originalFrame) frame.release();
            frame = null;
            if (failure instanceof DroppedPacketException) return null;
            releaseOriginal = false;
            return new Forward(originalFrame, originalType, originalPacket);
        } finally {
            if (frame != null && frame != originalFrame) frame.release();
            if (releaseOriginal) originalFrame.release();
        }
    }

    /** Hostile input can fail every frame; log the first failure and then at most one per second. */
    private void reportDecodeFailure(String operation, Throwable failure) {
        long now = System.nanoTime();
        if (lastFailureLog != 0 && now - lastFailureLog < FAILURE_LOG_INTERVAL_NANOS) {
            suppressedFailures++;
            return;
        }
        String suffix = suppressedFailures == 0 ? "" : " (" + suppressedFailures + " similar failures suppressed)";
        suppressedFailures = 0;
        lastFailureLog = now;
        CultNetworkManager.reportFailure(operation + suffix, failure);
    }

    private void forwarded(Forward output) {
        try {
            connection.forwarded(output.type, output.packet);
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("inbound protocol transition", failure);
        }
    }

    private record Forward(ByteBuf frame, PacketType<?> type, Object packet) {}
}
