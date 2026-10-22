package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Direct observed packet decoding; only consumed values cross into the native model. */
public final class ProjectedPackets implements AutoCloseable {
    public record Value(PacketType<?> type, Object packet) {}
    /** Owns its buffer until it is transferred to the transport. */
    public record Encoded(List<ByteBuf> frames) {}

    private static final int DIRECTIONS = PacketDirection.values().length;

    private final ProtocolRuntime observed, model;
    private final Supplier<PacketValueAdapter> adapter;
    /** Per phase and direction, the model family of each observed ID the observed runtime does not bind. */
    private final UnboundTypes[] unboundTypes =
            new UnboundTypes[ConnectionPhase.values().length * DIRECTIONS];
    private final boolean translated;
    private boolean closed;

    /** Immutable once constructed; the final field publishes the filled array to any thread. */
    private record UnboundTypes(PacketType<?>[] types) {}

    public ProjectedPackets(ProtocolRuntime observed, ProtocolRuntime model, Supplier<PacketValueAdapter> adapter) {
        this.observed = Objects.requireNonNull(observed);
        this.model = Objects.requireNonNull(model);
        this.adapter = Objects.requireNonNull(adapter);
        translated = observed.data().version() != model.data().version();
    }

    public ProtocolRuntime observed() {
        return observed;
    }

    public boolean translated() {
        return translated;
    }

    /** Whether {@link #close} has run; reads and writes then fail. */
    public boolean isClosed() {
        return closed;
    }

    /** The model family of observed wire packet {@code id}; null when the model has none. */
    public PacketType<?> type(ConnectionPhase phase, PacketDirection direction, int id) {
        var bound = observed.binding(phase, direction, id);
        if (bound != null) return bound.type();
        var types = unboundTypes(phase, direction);
        // An ID outside the observed table fails exactly as the name lookup always has.
        return id >= 0 && id < types.length ? types[id] : unboundType(phase, direction, id);
    }

    /** Whether {@code id} names a packet in the observed wire table. */
    public boolean hasId(ConnectionPhase phase, PacketDirection direction, int id) {
        return id >= 0 && id < observed.data().packets(phase, direction).size();
    }

    private PacketType<?>[] unboundTypes(ConnectionPhase phase, PacketDirection direction) {
        int index = phase.ordinal() * DIRECTIONS + direction.ordinal();
        var table = unboundTypes[index];
        if (table == null) {
            var types = new PacketType<?>[observed.data().packets(phase, direction).size()];
            for (int id = 0; id < types.length; id++) {
                if (observed.binding(phase, direction, id) == null) types[id] = unboundType(phase, direction, id);
            }
            // Deterministic: a racing thread computes and publishes an equal table.
            unboundTypes[index] = table = new UnboundTypes(types);
        }
        return table.types();
    }

    private PacketType<?> unboundType(ConnectionPhase phase, PacketDirection direction, int id) {
        return model.typeForKey(direction.name().toLowerCase(java.util.Locale.ROOT) + "."
                + observed.data().packets(phase, direction).name(id).replace("minecraft:", ""));
    }

    /** Borrows the physical frame; no generated frames or mirrored observations exist. */
    public List<Value> read(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed) {
        Value value = read(phase, direction, physical, state, consumed, false);
        return value == null ? List.of() : List.of(value);
    }

    /** As {@link #read}, for the frame's one value; null where it has none. */
    public Value readOne(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed) {
        return read(phase, direction, physical, state, consumed, false);
    }

    /** Borrows the frame and reads diagnostic values without committing adapter state. */
    public List<Value> observe(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed) {
        Value value = read(phase, direction, physical, state, consumed, true);
        return value == null ? List.of() : List.of(value);
    }

    private Value read(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed,
            boolean observation) {
        if (closed) throw new IllegalStateException("Packet values are closed");
        int readerIndex = physical.readerIndex();
        try {
            var input = physical;
            int id = Wire.readVarInt(input);
            PacketType<?> type = type(phase, direction, id);
            if (type == null || !consumed.test(type)) return null;
            if (type.codec().forwardsUndecoded(input, input.readerIndex())) return null;
            Object packet;
            if (translated() && type.codec().requiresModelValues()) {
                var context = new ProtocolContext(
                        type,
                        observed.data(),
                        phase,
                        observed.data().packets(phase, direction).name(id),
                        32767,
                        0,
                        state);
                var values = Objects.requireNonNull(adapter.get(), "Missing observed value decoder");
                packet = observation ? values.readForObservation(input, context) : values.read(input, context);
                if (packet == null) return unavailable(type, state);
                if (!type.recordClass().isInstance(packet))
                    throw new ProtocolResolutionException("Wrong observed value record for " + type);
                if (type.codec().readsEntirePayload() && input.isReadable())
                    throw new MalformedPacketException("Trailing bytes on " + type);
            } else packet = observed.decode(phase, direction, id, input, state);
            if (packet == null) return unavailable(type, state);
            return new Value(type, packet);
        } finally {
            // Codec reads are synchronous and do not mutate bytes or index marks.
            // Restore even when the codec, adapter, or consumed predicate throws.
            physical.readerIndex(readerIndex);
        }
    }

    private static Value unavailable(PacketType<?> type, CodecState state) {
        if (state.requiresValues()) throw new ProtocolResolutionException("Unavailable value for " + type);
        return null;
    }

    public <R> Encoded encode(
            ConnectionPhase phase, PacketType<R> type, R packet, ByteBufAllocator allocator, CodecState state) {
        if (closed) throw new IllegalStateException("Packet values are closed");
        ByteBuf output = allocator.buffer();
        try {
            if (translated() && type.codec().requiresModelValues()) {
                if (!type.writable()) throw new UnsupportedOnVersionException("Read-only packet " + type);
                String name = type.wireNames().stream()
                        .filter(n ->
                                observed.data().packets(phase, type.direction()).id(n) >= 0)
                        .findFirst()
                        .orElseThrow(() -> new UnsupportedOnVersionException("Unavailable packet " + type));
                Wire.writeVarInt(
                        output, observed.data().packets(phase, type.direction()).id(name));
                Objects.requireNonNull(adapter.get(), "Missing observed value writer")
                        .write(
                                output,
                                new ProtocolContext(type, observed.data(), phase, name, 32767, 0, state),
                                packet);
            } else observed.encode(phase, type, packet, output, state);
            var result = new Encoded(List.of(output));
            output = null;
            return result;
        } finally {
            if (output != null) output.release();
        }
    }

    public <R> PacketType<R> writableType(R packet) {
        return model.writableType(packet);
    }

    @Override
    public void close() {
        closed = true;
    }
}
