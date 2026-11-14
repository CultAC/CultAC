package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Direct observed packet decoding; only consumed values cross into the native model. */
public final class ProjectedPackets implements AutoCloseable {
    public record Value(PacketType<?> type, Object packet) {}
    /** Owns its buffer until it is transferred to the transport. */
    public record Encoded(List<ByteBuf> frames) {}

    private static final Object NO_MODEL_TYPE = new Object();

    private final ProtocolRuntime observed, model;
    private final Supplier<PacketValueAdapter> adapter;
    private final byte[][] bundleDelimiters = new byte[ConnectionPhase.values().length][];
    private final Object[][][] modelTypes;
    // Contexts of translated reads and writes by wire ID; each holds the state it was built for.
    private final ProtocolContext[][][] readContexts, writeContexts;
    private boolean closed;

    public ProjectedPackets(ProtocolRuntime observed, ProtocolRuntime model, Supplier<PacketValueAdapter> adapter) {
        this.observed = Objects.requireNonNull(observed);
        this.model = Objects.requireNonNull(model);
        this.adapter = Objects.requireNonNull(adapter);
        modelTypes = new Object[ConnectionPhase.values().length][PacketDirection.values().length][];
        readContexts = new ProtocolContext[ConnectionPhase.values().length][PacketDirection.values().length][];
        writeContexts = new ProtocolContext[ConnectionPhase.values().length][PacketDirection.values().length][];
        for (ConnectionPhase phase : ConnectionPhase.values()) {
            for (PacketDirection direction : PacketDirection.values()) {
                modelTypes[phase.ordinal()][direction.ordinal()] =
                        new Object[observed.data().packets(phase, direction).size()];
            }
        }
    }

    public ProtocolRuntime observed() {
        return observed;
    }

    public boolean closed() {
        return closed;
    }

    public boolean translated() {
        return observed.data().version() != model.data().version();
    }

    /** The model family of observed wire packet {@code id}; null when the model has none. */
    public PacketType<?> type(ConnectionPhase phase, PacketDirection direction, int id) {
        var bound = observed.binding(phase, direction, id);
        if (bound != null) return bound.type();
        Object[] types = modelTypes[phase.ordinal()][direction.ordinal()];
        if (id < 0 || id >= types.length) return modelType(phase, direction, id);
        Object type = types[id];
        if (type == null) {
            PacketType<?> resolved = modelType(phase, direction, id);
            type = resolved == null ? NO_MODEL_TYPE : resolved;
            types[id] = type;
        }
        return type == NO_MODEL_TYPE ? null : (PacketType<?>) type;
    }

    private PacketType<?> modelType(ConnectionPhase phase, PacketDirection direction, int id) {
        return model.typeForKey(direction.name().toLowerCase(Locale.ROOT) + "."
                + observed.data().packets(phase, direction).name(id).replace("minecraft:", ""));
    }

    /** Borrows the physical frame; no generated frames or mirrored observations exist. */
    public List<Value> read(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed) {
        return values(phase, direction, physical, state, consumed, false);
    }

    /**
     * As {@link #read}, for the packet of the frame's one value, whose type is the one {@code consumed} accepted; null
     * where it has none.
     */
    public Object readOne(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed) {
        return packet(phase, direction, physical, state, consumed, false);
    }

    /** Borrows the frame and reads diagnostic values without committing adapter state. */
    public List<Value> observe(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed) {
        return values(phase, direction, physical, state, consumed, true);
    }

    private List<Value> values(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed,
            boolean observation) {
        PacketType<?>[] accepted = {null};
        Object packet = packet(
                phase,
                direction,
                physical,
                state,
                type -> {
                    accepted[0] = type;
                    return consumed.test(type);
                },
                observation);
        return packet == null ? List.of() : List.of(new Value(accepted[0], packet));
    }

    /** At most one type is offered to {@code consumed}; a non-null packet is a value of that type. */
    private Object packet(
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
                var context = readContext(type, phase, direction, id, state);
                var values = Objects.requireNonNull(adapter.get(), "Missing observed value decoder");
                packet = observation ? values.readForObservation(input, context) : values.read(input, context);
                if (packet == null) return unavailable(type, state);
                if (!type.recordClass().isInstance(packet))
                    throw new ProtocolResolutionException("Wrong observed value record for " + type);
                if (type.codec().readsEntirePayload() && input.isReadable())
                    throw new MalformedPacketException("Trailing bytes on " + type);
            } else packet = observed.decode(phase, direction, id, input, state);
            if (packet == null) return unavailable(type, state);
            return packet;
        } finally {
            // Codec reads are synchronous and do not mutate bytes or index marks.
            // Restore even when the codec, adapter, or consumed predicate throws.
            physical.readerIndex(readerIndex);
        }
    }

    /** The context of a translated read of wire packet {@code id}, which {@link #type} resolved to {@code type}. */
    private ProtocolContext readContext(
            PacketType<?> type, ConnectionPhase phase, PacketDirection direction, int id, CodecState state) {
        ProtocolContext[] contexts = contexts(readContexts, phase, direction);
        ProtocolContext context = id >= 0 && id < contexts.length ? contexts[id] : null;
        if (context != null && context.type() == type && context.state() == state) return context;
        context = new ProtocolContext(
                type,
                observed.data(),
                phase,
                observed.data().packets(phase, direction).name(id),
                32767,
                0,
                state);
        if (id >= 0 && id < contexts.length) contexts[id] = context;
        return context;
    }

    private ProtocolContext[] contexts(
            ProtocolContext[][][] byPhase, ConnectionPhase phase, PacketDirection direction) {
        ProtocolContext[] contexts = byPhase[phase.ordinal()][direction.ordinal()];
        if (contexts == null) {
            int size = observed.data().packets(phase, direction).size();
            contexts = new ProtocolContext[size];
            byPhase[phase.ordinal()][direction.ordinal()] = contexts;
        }
        return contexts;
    }

    /** The context of a translated write of {@code type} under its wire name {@code name}, wire packet {@code id}. */
    private ProtocolContext writeContext(
            PacketType<?> type, ConnectionPhase phase, String name, int id, CodecState state) {
        ProtocolContext[] contexts = contexts(writeContexts, phase, type.direction());
        ProtocolContext context = id >= 0 && id < contexts.length ? contexts[id] : null;
        if (context != null && context.type() == type && context.state() == state) return context;
        context = new ProtocolContext(type, observed.data(), phase, name, 32767, 0, state);
        if (id >= 0 && id < contexts.length) contexts[id] = context;
        return context;
    }

    private static Object unavailable(PacketType<?> type, CodecState state) {
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
                List<String> names = type.wireNames();
                String name = null;
                int id = -1;
                for (int i = 0; i < names.size() && name == null; i++) {
                    id = observed.data().packets(phase, type.direction()).id(names.get(i));
                    if (id >= 0) name = names.get(i);
                }
                if (name == null) throw new UnsupportedOnVersionException("Unavailable packet " + type);
                Wire.writeVarInt(output, id);
                Objects.requireNonNull(adapter.get(), "Missing observed value writer")
                        .write(output, writeContext(type, phase, name, id, state), packet);
            } else observed.encode(phase, type, packet, output, state);
            var result = new Encoded(List.of(output));
            output = null;
            return result;
        } finally {
            if (output != null) output.release();
        }
    }

    /**
     * The one frame {@link #encode} gives a bundle delimiter, whose bytes depend only on the phase; each call owns a
     * fresh buffer.
     */
    public ByteBuf encodeBundleDelimiter(ConnectionPhase phase, ByteBufAllocator allocator, CodecState state) {
        if (closed) throw new IllegalStateException("Packet values are closed");
        byte[] bytes = bundleDelimiters[phase.ordinal()];
        if (bytes == null) {
            var type = ClientboundPackets.BUNDLE_DELIMITER;
            ByteBuf frame = encode(phase, type, type.opaqueValue(), allocator, state)
                    .frames()
                    .getFirst();
            bundleDelimiters[phase.ordinal()] = ByteBufUtil.getBytes(frame);
            return frame;
        }
        ByteBuf output = allocator.buffer();
        output.writeBytes(bytes);
        return output;
    }

    public <R> PacketType<R> writableType(R packet) {
        return model.writableType(packet);
    }

    @Override
    public void close() {
        closed = true;
    }
}
