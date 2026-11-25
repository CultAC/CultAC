package ac.cult.cultac.network;

import ac.cult.cultac.protocol.PacketType;
import io.netty.buffer.ByteBuf;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Serverbound packets the decoder has processed and not yet passed to the server, in receipt order. A gated packet
 * waits until it is settled, then is forwarded or dropped; every packet received after it waits behind it, so the
 * server receives the client's order without the dropped packets. Processing stays at receipt: only the forwarding
 * waits. A hold past {@link #MAX_PACKETS} packets or {@link #MAX_BYTES} bytes settles its gates as dropped. The tick
 * input waits apart from that order: a later one replaces it, and it is forwarded right before the packet that
 * releases it. Owner loop only.
 */
@ApiStatus.Internal
public final class ForwardHold {
    // Minecraft#tick sends a handful of packets between a use_item and the tick's end; these bound memory only.
    static final int MAX_PACKETS = 256;
    static final int MAX_BYTES = 1 << 20;

    /** The model's part of a settled gate: whether the gated packet goes to the server. */
    @FunctionalInterface
    public interface Settlement {
        void settled(boolean forwarded);
    }

    /** Passes one packet on to the server. */
    @FunctionalInterface
    public interface Sink {
        void send(ByteBuf frame, @Nullable PacketType<?> type, @Nullable Object packet);
    }

    private static final class Gate {
        @Nullable
        Settlement settlement;

        boolean settled;
        boolean forwarded;

        void settle(boolean forward) {
            settled = true;
            forwarded = forward;
            if (settlement == null) return;
            try {
                settlement.settled(forward);
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("held packet settlement", failure);
            }
        }
    }

    private record Held(
            ByteBuf frame,
            @Nullable PacketType<?> type,
            @Nullable Object packet,
            @Nullable Gate gate) {
        boolean waits() {
            return gate != null && !gate.settled;
        }
    }

    private final ArrayDeque<Held> held = new ArrayDeque<>();
    private final List<Gate> open = new ArrayList<>();
    private @Nullable Gate receiving;
    private int heldBytes;
    private boolean receivingTickInput;
    private @Nullable Held tickInput;

    /** The packet being received is the client tick's input: if forwarded, it waits for {@link #releaseTickInput}. */
    public void markTickInput() {
        receivingTickInput = true;
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
        tickInput = new Held(frame, type, packet, null);
    }

    /** Forwards the kept tick input, behind every packet still held and ahead of the packet being received. */
    public void releaseTickInput(Sink sink) {
        Held input = tickInput;
        if (input == null) return;
        tickInput = null;
        if (held.isEmpty()) {
            sink.send(input.frame, input.type, input.packet);
            return;
        }
        held.addLast(input);
        heldBytes += input.frame.readableBytes();
    }

    /** Whether a packet waits: a held one, a gate, or the kept tick input. */
    public boolean keepsAny() {
        return holds() || tickInput != null;
    }

    /** Gates the packet being received: it and everything after it wait until {@link #settle}. */
    public void gate() {
        if (receiving != null) return;
        receiving = new Gate();
        open.add(receiving);
    }

    /**
     * Gives the gated packet being received its settlement, which runs once it settles, at once if it has; false when
     * the packet being received is not gated.
     */
    public boolean whenSettled(Settlement settlement) {
        Gate gate = receiving;
        if (gate == null) return false;
        if (gate.settled) settlement.settled(gate.forwarded);
        else gate.settlement = settlement;
        return true;
    }

    /** Whether a gate waits for its settlement. */
    public boolean gating() {
        return !open.isEmpty();
    }

    /** Settles every open gate, the one on the packet being received included. */
    public void settle(boolean forward) {
        if (open.isEmpty()) return;
        var gates = List.copyOf(open);
        open.clear();
        for (Gate gate : gates) gate.settle(forward);
    }

    /** Whether a packet received now has to wait: it is gated, or a packet before it still is held. */
    public boolean holds() {
        return receiving != null || !held.isEmpty();
    }

    /** Holds the packet being received, which the decoder would forward now. */
    public void add(ByteBuf frame, @Nullable PacketType<?> type, @Nullable Object packet) {
        held.addLast(new Held(frame, type, packet, receiving));
        heldBytes += frame.readableBytes();
        receiving = null;
        if (held.size() > MAX_PACKETS || heldBytes > MAX_BYTES) settle(false);
    }

    /** The packet being received does not reach the server; a gate on it settles as dropped. */
    public void notForwarded() {
        Gate gate = receiving;
        if (gate == null) return;
        receiving = null;
        open.remove(gate);
        if (!gate.settled) gate.settle(false);
    }

    /** Sends each held packet up to the first that still waits; a dropped one is released. */
    public void drain(Sink sink) {
        while (!held.isEmpty() && !held.peekFirst().waits()) {
            Held next = held.pollFirst();
            heldBytes -= next.frame.readableBytes();
            if (next.gate == null || next.gate.forwarded) sink.send(next.frame, next.type, next.packet);
            else next.frame.release();
        }
    }

    /** Ends the hold: every open gate settles as forwarded and every held packet is sent. */
    public void forwardAll(Sink sink) {
        settle(true);
        drain(sink);
        releaseTickInput(sink);
    }

    /** Releases every held packet of a connection the server no longer reads. */
    public void discard() {
        open.clear();
        receiving = null;
        receivingTickInput = false;
        if (tickInput != null) tickInput.frame.release();
        tickInput = null;
        heldBytes = 0;
        Held next;
        while ((next = held.pollFirst()) != null) next.frame.release();
    }
}
