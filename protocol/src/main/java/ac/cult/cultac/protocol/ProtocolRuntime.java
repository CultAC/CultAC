package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One eagerly validated runtime per exact server protocol. */
public final class ProtocolRuntime {
    private final ProtocolData data;
    private final List<PacketType<?>> catalog;
    private final int commandInputLimit;
    private final ResolvedPacket<?>[][][] bindings;
    private final Map<Class<?>, PacketType<?>> byRecord;
    private final Map<String, PacketType<?>> byKey;
    private final Map<PacketType<?>, Integer> slots;
    private final Set<PacketType<?>> supported;

    private ProtocolRuntime(ProtocolData data, List<PacketType<?>> catalog, int commandInputLimit) {
        this.data = data;
        this.catalog = catalog;
        this.commandInputLimit = commandInputLimit;
        bindings = new ResolvedPacket<?>[ConnectionPhase.values().length][PacketDirection.values().length][];
        for (ConnectionPhase phase : ConnectionPhase.values()) {
            for (PacketDirection direction : PacketDirection.values()) {
                bindings[phase.ordinal()][direction.ordinal()] =
                        new ResolvedPacket<?>[data.packets(phase, direction).size()];
            }
        }
        Map<Class<?>, PacketType<?>> records = new HashMap<>();
        Map<String, PacketType<?>> keys = new HashMap<>();
        Map<PacketType<?>, Integer> slots = new HashMap<>();
        Set<PacketType<?>> supported = new HashSet<>();
        for (int slot = 0; slot < catalog.size(); slot++) {
            PacketType<?> type = catalog.get(slot);
            if (keys.put(type.key(), type) != null
                    || (!type.isOpaque() && records.put(type.recordClass(), type) != null)) {
                throw new ProtocolResolutionException("Duplicate catalog key/record: " + type);
            }
            slots.put(type, slot);
            // The version's data decides which declared names exist; a family absent from it stays unsupported.
            List<String> present = type.wireNames(data);
            if (present.isEmpty()) continue;
            if (present.size() > 1 && !(type.codec() instanceof VariantCodec<?>)) {
                throw new ProtocolResolutionException(
                        "Renamed family " + type + " carries " + present + " on " + data.version());
            }
            type.codec().validate(data);
            for (String name : present) {
                for (ConnectionPhase phase : type.phases()) {
                    int id = data.packets(phase, type.direction()).id(name);
                    if (id < 0) {
                        throw new ProtocolResolutionException("Packet " + type + "/" + name + " is missing on "
                                + data.version() + "/" + phase + " but present in another declared phase");
                    }
                    ResolvedPacket<?>[] table =
                            bindings[phase.ordinal()][type.direction().ordinal()];
                    if (table[id] != null) {
                        throw new ProtocolResolutionException(
                                "Duplicate catalog ID " + id + " on " + phase + "/" + type.direction());
                    }
                    table[id] = resolve(
                            type,
                            new ProtocolContext(
                                    type,
                                    data,
                                    phase,
                                    name,
                                    commandInputLimit,
                                    type.wireNames().indexOf(name)),
                            slot);
                }
            }
            supported.add(type);
        }
        this.byRecord = Map.copyOf(records);
        this.byKey = Map.copyOf(keys);
        this.slots = Map.copyOf(slots);
        this.supported = Set.copyOf(supported);
    }

    private static <R> ResolvedPacket<R> resolve(PacketType<R> type, ProtocolContext context, int slot) {
        return new ResolvedPacket<>(type, context, slot);
    }

    public static ProtocolRuntime create(ProtocolData data) {
        return create(data, Packets.all());
    }

    /** Immutable host codec configuration, shared by every connection using this runtime. */
    public static ProtocolRuntime create(ProtocolData data, int commandInputLimit) {
        return create(data, Packets.all(), commandInputLimit);
    }

    public static ProtocolRuntime create(ProtocolData data, List<PacketType<?>> catalog) {
        return create(data, catalog, 32767);
    }

    public static ProtocolRuntime create(ProtocolData data, List<PacketType<?>> catalog, int commandInputLimit) {
        if (catalog.isEmpty()) throw new ProtocolResolutionException("Empty packet catalog");
        return new ProtocolRuntime(data, List.copyOf(catalog), commandInputLimit);
    }

    public ProtocolData data() {
        return data;
    }

    /** The same catalog and host limits resolved against another wire version's data. */
    public ProtocolRuntime forData(ProtocolData other) {
        return other == data ? this : new ProtocolRuntime(other, catalog, commandInputLimit);
    }

    public boolean supports(PacketType<?> type) {
        return supported.contains(type);
    }

    public boolean contains(PacketType<?> type) {
        return byKey.get(type.key()) == type;
    }

    public PacketType<?> typeForKey(String key) {
        return byKey.get(key);
    }
    /** Catalog metadata, including a declared type absent on this version; null means not in the catalog. */
    public PacketType<?> typeForRecord(Class<?> type) {
        return byRecord.get(type);
    }

    /** Dense index of a catalog type in this runtime, for per-type tables that frame lookups reach through {@link ResolvedPacket#slot()}. */
    public int slot(PacketType<?> type) {
        Integer slot = slots.get(type);
        if (slot == null) throw new IllegalArgumentException("Foreign packet family: " + type);
        return slot;
    }

    /** One past the largest {@link #slot}; every catalog type has a slot, supported on this version or not. */
    public int slotCount() {
        return slots.size();
    }

    public ResolvedPacket<?> binding(ConnectionPhase phase, PacketDirection direction, int id) {
        var table = bindings[phase.ordinal()][direction.ordinal()];
        return id >= 0 && id < table.length ? table[id] : null;
    }

    public record ResolvedPacket<R>(PacketType<R> type, ProtocolContext context, int slot) {}

    /** Decode an isolated view positioned after its packet ID. The view is consumed. */
    public Object decode(ConnectionPhase phase, PacketDirection direction, int id, ByteBuf input) {
        return decode(phase, direction, id, input, CodecState.EMPTY);
    }

    public Object decode(ConnectionPhase phase, PacketDirection direction, int id, ByteBuf input, CodecState state) {
        var bound = binding(phase, direction, id);
        if (bound == null) throw new ProtocolResolutionException("No consumed codec for " + direction + "/" + id);
        try {
            Object record = bound.type().codec().read(input, bound.context().withState(state));
            if (!bound.type().recordClass().isInstance(record)) {
                throw new ProtocolResolutionException("Wrong decoded record for " + bound.type());
            }
            if (bound.type().codec().readsEntirePayload() && input.isReadable()) {
                throw new MalformedPacketException("Trailing bytes on " + bound.type());
            }
            return record;
        } catch (IndexOutOfBoundsException truncated) {
            throw new MalformedPacketException("Truncated " + bound.type(), truncated);
        }
    }

    /** Writes both ID and payload; failures restore the destination writer index. */
    public <R> void encode(ConnectionPhase phase, PacketType<R> type, R packet, ByteBuf output) {
        encode(phase, type, packet, output, CodecState.EMPTY);
    }

    public <R> void encode(ConnectionPhase phase, PacketType<R> type, R packet, ByteBuf output, CodecState state) {
        if (!supports(type)) throw new UnsupportedOnVersionException("Unsupported type " + type);
        if (!type.writable()) throw new UnsupportedOnVersionException("Read-only packet " + type);
        // A variant family names its own variant; a renamed family has one name on this version.
        String name = type.codec() instanceof VariantCodec<R> variants
                ? type.wireNames().get(variants.variantOf(packet))
                : type.wireNames(data).get(0);
        int id = data.packets(phase, type.direction()).id(name);
        var bound = binding(phase, type.direction(), id);
        if (bound == null || bound.type() != type) {
            throw new UnsupportedOnVersionException(type + " is unavailable in " + phase);
        }
        int start = output.writerIndex();
        try {
            Wire.writeVarInt(output, id);
            type.codec().write(output, bound.context().withState(state), packet);
        } catch (RuntimeException | Error failure) {
            output.writerIndex(start);
            throw failure;
        }
    }

    /** Resolve authored records against this runtime's existing catalog; creates no codec or routing state. */
    @SuppressWarnings("unchecked") // The catalog validates that each record class owns exactly one type.
    public <R> PacketType<R> writableType(R packet) {
        PacketType<?> type = packet instanceof ac.cult.cultac.protocol.packet.Opaque opaque
                ? opaque.type()
                : typeForRecord(packet.getClass());
        if (type == null || !contains(type) || !type.writable()) {
            throw new UnsupportedOnVersionException(
                    "No writable catalog entry for " + packet.getClass().getName());
        }
        return (PacketType<R>) type;
    }
}
