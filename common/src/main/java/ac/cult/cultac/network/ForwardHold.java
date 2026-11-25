package ac.cult.cultac.network;

import ac.cult.cultac.protocol.PacketType;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The client tick's input the decoder has processed and not yet passed to the server: a later one replaces it, and it
 * is forwarded right before the packet that releases it. Owner loop only.
 */
@ApiStatus.Internal
public final class ForwardHold {
    /** Passes one packet on to the server. */
    @FunctionalInterface
    public interface Sink {
        void send(ByteBuf frame, @Nullable PacketType<?> type, @Nullable Object packet);
    }

    private record Held(ByteBuf frame, @Nullable PacketType<?> type, @Nullable Object packet) {}

    private boolean receivingTickInput;
    private @Nullable Held tickInput;

    /** The packet being received is the client tick's input: if forwarded, it waits for {@link #releaseTickInput}. */
    public void markTickInput() {
        receivingTickInput = true;
    }

    /** Whether {@link #markTickInput} marked the packet being received. */
    public boolean receivesTickInput() {
        return receivingTickInput;
    }

    /** Takes the mark {@link #markTickInput} put on the packet being received. */
    public boolean takeTickInputMark() {
        boolean marked = receivingTickInput;
        receivingTickInput = false;
        return marked;
    }

    /** Keeps the forwarded tick input until {@link #releaseTickInput}; it replaces a tick input still kept. */
    public void keepTickInput(ByteBuf frame, @Nullable PacketType<?> type, @Nullable Object packet) {
        if (tickInput != null) tickInput.frame.release();
        tickInput = new Held(frame, type, packet);
    }

    /** Forwards the kept tick input ahead of the packet being received. */
    public void releaseTickInput(Sink sink) {
        Held input = tickInput;
        if (input == null) return;
        tickInput = null;
        sink.send(input.frame, input.type, input.packet);
    }

    /** Whether the tick input is kept. */
    public boolean keepsAny() {
        return tickInput != null;
    }

    /** Forwards the kept tick input of a connection the decoder stops reading. */
    public void forwardAll(Sink sink) {
        releaseTickInput(sink);
    }

    /** Releases the kept tick input of a connection the server no longer reads. */
    public void discard() {
        receivingTickInput = false;
        if (tickInput != null) tickInput.frame.release();
        tickInput = null;
    }
}
