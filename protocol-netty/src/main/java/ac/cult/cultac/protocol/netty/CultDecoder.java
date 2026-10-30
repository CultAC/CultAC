package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.DroppedPacketException;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
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
    private final CultConnection connection;
    private final Predicate<PacketType<?>> routed;
    /** The route {@link #routed} found during a readOne, read back on the owner loop before anything is dispatched. */
    private PacketDispatcher.Route probed;
    /** The type {@link #routed} was offered during a readOne, read back like {@link #probed}. */
    private PacketType<?> probedType;
    // Per-read scratch objects, held by at most one read at a time; a nested read allocates its own.
    private ModelRead idleRead = new ModelRead();
    private PacketReceiveEvent<?> idleReceiveEvent;

    public CultDecoder(CultConnection connection) {
        this.connection = connection;
        routed = type -> {
            probedType = type;
            return (probed = connection.dispatcher().get(type)) != null;
        };
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
        // An empty frame is a packet a handler ahead of cultac cancelled (PacketEvents leaves one); vanilla skips it.
        if (!(message instanceof ByteBuf frame) || !frame.isReadable()) {
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
        ModelRead read = idleRead;
        if (read == null) read = new ModelRead();
        else idleRead = null;
        read.ctx = ctx;
        read.frame = frame;
        read.consumed = false;
        try {
            connection.runInModel(read);
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("inbound model context", failure);
            if (!read.consumed) ctx.fireChannelRead(frame);
            return;
        }
        read.ctx = null;
        read.frame = null;
        idleRead = read;
    }

    /** The model-context work of one {@link #channelRead}, held by at most one read at a time. */
    private final class ModelRead implements Runnable {
        ChannelHandlerContext ctx;
        ByteBuf frame;
        boolean consumed;
        // Set by process when it returns true.
        ByteBuf forwardFrame;
        PacketType<?> forwardType;
        Object forwardPacket;

        @Override
        public void run() {
            consumed = true;
            if (!process(frame, this)) return;
            ByteBuf output = forwardFrame;
            PacketType<?> type = forwardType;
            Object packet = forwardPacket;
            forwardFrame = null;
            forwardType = null;
            forwardPacket = null;
            // beginLogin can install its codec and resume LocalChannel reads before
            // the handshake listener returns. Its next frame already belongs to LOGIN.
            boolean handshake = type == ServerboundPackets.INTENTION;
            boolean reconfiguration = type == ServerboundPackets.CONFIGURATION_ACKNOWLEDGED;
            if (handshake) forwarded(type, packet);
            if (reconfiguration && !connection.handoffAtReconfiguration()) {
                output.release();
                return;
            }
            ctx.fireChannelRead(output);
            if (!handshake && type != null) forwarded(type, packet);
        }

        boolean forward(ByteBuf frame, PacketType<?> type, Object packet) {
            forwardFrame = frame;
            forwardType = type;
            forwardPacket = packet;
            return true;
        }
    }

    /** Consumes the input on every path; false means it was cancelled or discarded, else {@code output} holds it. */
    @SuppressWarnings("unchecked")
    private boolean process(ByteBuf frame, ModelRead output) {
        ByteBuf originalFrame = frame;
        boolean releaseOriginal = true;
        PacketType<?> originalType = null;
        ServerboundPacket originalPacket = null;
        boolean observeOnly = CultAPI.INSTANCE.getConfigManager().isObserveOnly();
        try {
            var phase = connection.phase(PacketDirection.SERVERBOUND);
            connection.observePacket(PacketDirection.SERVERBOUND, phase, frame);
            PacketType<?> forwardedType = null;
            ServerboundPacket forwardedPacket = null;
            var value = connection.packets().readOne(phase, PacketDirection.SERVERBOUND, frame, connection, routed);
            if (value != null) {
                var route = probed;
                var valueType = probedType;
                var type = (PacketType<ServerboundPacket>) route.type();
                var original = (ServerboundPacket) value;
                originalType = type;
                originalPacket = original;
                var pausing = connection.player();
                // A debug interaction pause discards before Cult or the server sees the packet.
                if (pausing != null && pausing.discardsPausedInteraction(original)) return false;
                connection.prepare();
                var idleEvent = route.own() ? idleReceiveEvent : null;
                PacketReceiveEvent<ServerboundPacket> event;
                if (idleEvent == null) event = new PacketReceiveEvent<>(connection.user(), phase, type, original);
                else {
                    idleReceiveEvent = null;
                    event = idleEvent.restart(connection.user(), phase, type, original);
                }
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
                // Observe-only forwards what the client sent; a pong answering CultAC's own ping and a client
                // payload on the companion's bridge channel keep their cancel.
                if (observeOnly
                        && !(valueType == ServerboundPackets.PONG && event.isAcceptedTransactionResponse())
                        && !(original instanceof ServerboundCustomPayload payload
                                && ClientboundPackets.BRIDGE_CHANNEL.equals(payload.channel()))) {
                    packet = original;
                    cancelled = false;
                }
                if (cancelled) {
                    recycle(route, event);
                    return false;
                }
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
                    if (valueType == ServerboundPackets.CLIENT_TICK_END) setbacks.resetForwardedPositionThisTick();
                    else if (packet instanceof ServerboundMovePlayer move
                            && move.hasPosition()
                            && !event.isTeleportPositionResponse()) setbacks.markForwardedPositionThisTick();
                }
                recycle(route, event);
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
            output.forward(frame, forwardedType, forwardedPacket);
            releaseOriginal = frame != originalFrame;
            frame = null;
            return true;
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("inbound packet decoding", failure);
            if (frame != originalFrame) frame.release();
            frame = null;
            if (failure instanceof DroppedPacketException) return false;
            releaseOriginal = false;
            return output.forward(originalFrame, originalType, originalPacket);
        } finally {
            if (frame != null && frame != originalFrame) frame.release();
            if (releaseOriginal) originalFrame.release();
        }
    }

    /** CultAC's own listeners hold no receive event past its dispatch. */
    private void recycle(PacketDispatcher.Route route, PacketReceiveEvent<?> event) {
        if (!route.own()) return;
        event.release();
        idleReceiveEvent = event;
    }

    private void forwarded(PacketType<?> type, Object packet) {
        try {
            connection.forwarded(type, packet);
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("inbound protocol transition", failure);
        }
    }
}
