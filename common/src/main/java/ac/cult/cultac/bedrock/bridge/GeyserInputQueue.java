package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import io.netty.util.ReferenceCountUtil;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;

/** Owned by Geyser's tick loop. Translation still crosses the downstream I/O loop and Java connection;
 * the marker waits for those packets and their post-tasks before starting the next original input. */
final class GeyserInputQueue {
    // Keep this local control channel unguessable; it must never be supplied by the Bedrock client.
    static final String CHANNEL = "cultac:geyser_translation/" + java.util.UUID.randomUUID();
    private static final int CAPACITY = 1024;
    private final GeyserSession session;
    private final Supplier<User> user;
    private final Consumer<BedrockPacket> translate;
    private final ArrayDeque<BedrockPacket> pending = new ArrayDeque<>();
    private final ArrayDeque<DeferredWrite> writes = new ArrayDeque<>();
    private enum State { NEW, BINDING, READY, CLOSED }
    private State state = State.NEW;
    private long sequence;
    private long awaiting;

    GeyserInputQueue(GeyserSession session, Supplier<User> user, Consumer<BedrockPacket> translate) {
        this.session = session;
        this.user = user;
        this.translate = translate;
    }

    boolean ready() { return state == State.READY; }

    void deferWrite(Runnable write, Runnable cancel) {
        requireOwner();
        if (state == State.CLOSED || writes.size() >= CAPACITY) {
            cancel.run();
            if (state != State.CLOSED) fail("Bedrock initialization exceeded its queue limit");
            return;
        }
        writes.addLast(new DeferredWrite(write, cancel));
        bind();
    }

    void offer(BedrockPacket packet) {
        requireOwner();
        if (state == State.CLOSED || pending.size() >= CAPACITY) {
            if (state != State.CLOSED) fail("Bedrock input processing exceeded its queue limit");
            return;
        }
        ReferenceCountUtil.retain(packet);
        pending.addLast(packet);
        bind();
        drain();
    }

    void bind() {
        if (state != State.NEW) return;
        User current = user.get();
        if (current == null) return;
        state = State.BINDING;
        CultAPI.INSTANCE.getNetworkManager().bindPacketExecutor(current, session.getTickEventLoop(), () -> {
            if (state == State.CLOSED || user.get() != current) return;
            state = State.READY;
            while (state != State.CLOSED && !writes.isEmpty()) writes.removeFirst().write().run();
            drain();
        });
    }

    private void drain() {
        if (state != State.READY || awaiting != 0 || pending.isEmpty()) return;
        BedrockPacket packet = pending.removeFirst();
        try {
            awaiting = ++sequence;
            translate.accept(packet);
            byte[] payload = ByteBuffer.allocate(8).putLong(awaiting).array();
            session.sendDownstreamPacket(markerPacket(payload));
        } catch (RuntimeException | LinkageError failure) {
            ac.cult.cultac.utils.anticheat.LogUtil.warn("Bedrock input processing failed: " + failure);
            fail("Bedrock input processing failed");
        } finally {
            ReferenceCountUtil.release(packet);
        }
    }

    static ServerboundCustomPayloadPacket markerPacket(byte[] payload) {
        return markerPacket(CHANNEL, payload);
    }

    static ServerboundCustomPayloadPacket markerPacket(String channel, byte[] payload) {
        // Geyser-Spigot relocates Adventure's Key type. Decode the wire form so
        // this bridge works with both its shaded runtime and unshaded Geyser.
        var buffer = io.netty.buffer.Unpooled.buffer();
        try {
            org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes.writeString(buffer, channel);
            buffer.writeBytes(payload);
            return new ServerboundCustomPayloadPacket(buffer);
        } finally {
            buffer.release();
        }
    }

    void complete(User source, byte[] payload) {
        requireOwner();
        if (state != State.READY || source != user.get() || payload.length != 8) return;
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long completed = buffer.getLong();
        if (awaiting == 0 || completed != awaiting) return;
        // Packet post-tasks already queued by the preceding handlers complete first.
        source.executeLater(() -> {
            if (state != State.READY || awaiting != completed) return;
            var player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(source);
            if (player != null) player.packetStateData.clearBedrockTranslatedMovementPermit();
            awaiting = 0;
            drain();
        });
    }

    void close() {
        requireOwner();
        state = State.CLOSED;
        while (!pending.isEmpty()) ReferenceCountUtil.release(pending.removeFirst());
        while (!writes.isEmpty()) writes.removeFirst().cancel().run();
    }

    private void requireOwner() {
        if (!session.getTickEventLoop().inEventLoop()) throw new IllegalStateException("Incorrect Bedrock executor");
    }

    private record DeferredWrite(Runnable write, Runnable cancel) { }

    private void fail(String reason) {
        close();
        session.disconnect("CultAC: " + reason);
    }
}
