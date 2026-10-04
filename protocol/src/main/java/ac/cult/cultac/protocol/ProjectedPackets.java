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

    private final ProtocolRuntime observed, model;
    private final Supplier<PacketValueAdapter> adapter;
    private boolean closed;

    public ProjectedPackets(ProtocolRuntime observed, ProtocolRuntime model, Supplier<PacketValueAdapter> adapter) {
        this.observed = Objects.requireNonNull(observed);
        this.model = Objects.requireNonNull(model);
        this.adapter = Objects.requireNonNull(adapter);
    }

    public ProtocolRuntime observed() {
        return observed;
    }

    public boolean translated() {
        return observed.data().version() != model.data().version();
    }

    /** Borrows the physical frame; no generated frames or mirrored observations exist. */
    public List<Value> read(
            ConnectionPhase phase,
            PacketDirection direction,
            ByteBuf physical,
            CodecState state,
            Predicate<PacketType<?>> consumed) {
        if (closed) throw new IllegalStateException("Packet values are closed");
        var input = physical.duplicate();
        int id = Wire.readVarInt(input);
        var bound = observed.binding(phase, direction, id);
        PacketType<?> type = bound == null
                ? model.typeForKey(direction.name().toLowerCase(java.util.Locale.ROOT) + "."
                        + observed.data().packets(phase, direction).name(id).replace("minecraft:", ""))
                : bound.type();
        if (type == null || !consumed.test(type)) return List.of();
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
            packet = Objects.requireNonNull(adapter.get(), "Missing observed value decoder")
                    .read(input, context);
            if (!type.recordClass().isInstance(packet))
                throw new ProtocolResolutionException("Wrong observed value record for " + type);
            if (type.codec().readsEntirePayload() && input.isReadable())
                throw new MalformedPacketException("Trailing bytes on " + type);
        } else packet = observed.decode(phase, direction, id, input, state);
        return List.of(new Value(type, packet));
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
