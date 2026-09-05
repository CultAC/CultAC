package ac.cult.cultac.packet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPromiseNotifier;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

import java.util.ArrayList;
import java.util.List;

/** A custom packet handler that modifies incoming and outgoing packets. */
// Originally licensed under LGPL from Noxesium
// https://github.com/Noxcrew/noxesium/blob/main/paper/packet/src/main/kotlin/com/noxcrew/packet/ChannelPacketHandler.kt
final class ChannelPacketHandler extends ChannelDuplexHandler {
    private final PacketApi packetApi;
    private final Connection connection;
    private BundleWrite bundleWrite;

    ChannelPacketHandler(PacketApi packetApi, Connection connection) {
        this.packetApi = packetApi;
        this.connection = connection;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Hidden packets can be sent as direct byte buffers instead of packet instances.
        if (!(msg instanceof Packet<?> packet)) {
            super.write(ctx, msg, promise);
            return;
        }

        if (bundleWrite != null) {
            // Reentrant writes belong immediately before the child whose handler
            // sent them, rather than ahead of the entire enclosing bundle.
            bundleWrite.promise.addListener(new ChannelPromiseNotifier(false, promise));
            appendDispatched(ctx, packet, bundleWrite.packets);
            return;
        }
        if (packet instanceof ClientboundBundlePacket) {
            writeBundle(ctx, packet, promise);
            return;
        }

        List<Packet<?>> packets = packetApi.handlePacket(new PacketContext(connection, ctx.channel()), packet);
        if (packets.isEmpty()) {
            // A cancelled write still consumed the caller's operation. Leaving
            // its promise incomplete can stall flush/close listeners forever.
            promise.trySuccess();
            return;
        }
        if (packets.size() == 1) {
            super.write(ctx, packets.getFirst(), promise);
            return;
        }

        super.write(ctx, PacketApi.bundle(packets), promise);
    }

    private void writeBundle(ChannelHandlerContext ctx, Packet<?> packet, ChannelPromise promise) throws Exception {
        BundleWrite write = new BundleWrite(promise.unvoid());
        try {
            bundleWrite = write;
            try {
                appendDispatched(ctx, packet, write.packets);
            } finally {
                bundleWrite = null;
            }
            if (write.packets.isEmpty()) {
                write.promise.trySuccess();
            } else {
                super.write(ctx, PacketApi.bundle(write.packets), write.promise);
            }
            if (write.flush) super.flush(ctx);
        } catch (Exception | Error failure) {
            write.promise.tryFailure(failure);
            throw failure;
        }
    }

    private void appendDispatched(ChannelHandlerContext ctx, Packet<?> packet, List<Packet<?>> output) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            // ClientPacketListener#handleBundlePacket visits children in order.
            // Finish each child's handlers and output before dispatching the next.
            for (Packet<?> child : PacketBundleUtil.subPackets(bundle)) {
                if (child != null) appendDispatched(ctx, child, output);
            }
        } else {
            PacketContext context = new PacketContext(connection, ctx.channel()).asBundled();
            for (Packet<?> result : packetApi.handlePacket(context, packet)) {
                appendOutput(result, output);
            }
        }
    }

    private static void appendOutput(Packet<?> packet, List<Packet<?>> output) {
        // Replacement packets have already been dispatched. Only flatten their
        // representation here: nested bundle objects cannot be encoded on the wire.
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> child : PacketBundleUtil.subPackets(bundle)) {
                appendOutput(child, output);
            }
        } else if (packet != null) {
            output.add(packet);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (bundleWrite != null) {
            bundleWrite.flush = true;
        } else {
            super.flush(ctx);
        }
    }

    private static final class BundleWrite {
        private final List<Packet<?>> packets = new ArrayList<>();
        private final ChannelPromise promise;
        private boolean flush;

        private BundleWrite(ChannelPromise promise) {
            this.promise = promise;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Packet<?> packet)) {
            super.channelRead(ctx, msg);
            return;
        }

        List<Packet<?>> packets = packetApi.handlePacket(new PacketContext(connection, ctx.channel()), packet);
        if (packets.isEmpty()) {
            return;
        }
        if (packets.size() != 1) {
            throw new IllegalArgumentException("Cannot read multiple packets at once");
        }
        super.channelRead(ctx, packets.getFirst());
    }
}
