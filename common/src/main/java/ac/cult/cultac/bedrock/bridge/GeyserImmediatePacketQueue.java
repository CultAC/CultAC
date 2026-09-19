package ac.cult.cultac.bedrock.bridge;

import java.util.AbstractList;
import java.util.List;
import java.util.function.BiConsumer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/** Geyser's tick-end batch bypasses UpstreamSession; capture its origin before enqueue. */
final class GeyserImmediatePacketQueue extends AbstractList<BedrockPacket> {
    private final List<BedrockPacket> delegate;
    private final BiConsumer<BedrockPacket, Runnable> enqueue;

    GeyserImmediatePacketQueue(List<BedrockPacket> delegate, BiConsumer<BedrockPacket, Runnable> enqueue) {
        this.delegate = delegate;
        this.enqueue = enqueue;
    }

    @Override public boolean add(BedrockPacket packet) {
        enqueue.accept(packet, () -> delegate.add(packet));
        return true;
    }

    @Override public BedrockPacket get(int index) { return delegate.get(index); }
    @Override public int size() { return delegate.size(); }
    @Override public void clear() { delegate.clear(); }
}
