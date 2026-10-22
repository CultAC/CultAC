package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable catalog metadata; instances are routing identities. A type declares every wire name its family
 * has used; the checked-in {@link ProtocolData} of each version decides which of them exist there.
 */
public final class PacketType<R> {
    private static final java.util.concurrent.atomic.AtomicInteger INDICES =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Unique per instance; lets a runtime reach its slot table without hashing. */
    final int index = INDICES.getAndIncrement();

    private final String key;
    private final Class<R> recordClass;
    private final PacketDirection direction;
    private final Set<ConnectionPhase> phases;
    private final List<String> names;
    private final ProtocolVersion since;
    private final PacketCodec<R> codec;
    private final Opaque opaque;

    /**
     * @param names namespaced wire names: a {@link VariantCodec}'s names in its order, or a vanilla rename's
     *              successive names, of which each version carries at most one
     * @param since the first version this codec is verified against, independent of which versions carry the names
     */
    @SuppressWarnings("unchecked") // Opaque families own the one Opaque value of their own type.
    public PacketType(
            String key,
            Class<R> recordClass,
            PacketDirection direction,
            Set<ConnectionPhase> phases,
            List<String> names,
            ProtocolVersion since,
            PacketCodec<R> codec) {
        this.key = Objects.requireNonNull(key);
        this.recordClass = Objects.requireNonNull(recordClass);
        this.direction = Objects.requireNonNull(direction);
        this.phases = Set.copyOf(phases);
        this.names = List.copyOf(names);
        this.since = Objects.requireNonNull(since);
        this.codec = Objects.requireNonNull(codec);
        Class<?> marker = direction == PacketDirection.SERVERBOUND ? ServerboundPacket.class : ClientboundPacket.class;
        if (!recordClass.isRecord() || !marker.isAssignableFrom(recordClass) || phases.isEmpty()) {
            throw new IllegalArgumentException("Invalid packet metadata: " + key);
        }
        if (this.names.isEmpty()) throw new IllegalArgumentException("No wire names: " + key);
        if (this.names.stream().distinct().count() != this.names.size())
            throw new IllegalArgumentException("Duplicate wire name: " + key);
        if (codec instanceof VariantCodec<R> variants && variants.variants().size() != this.names.size()) {
            throw new IllegalArgumentException("Wire names do not match codec variants: " + key);
        }
        this.opaque = recordClass == Opaque.class ? new Opaque((PacketType<Opaque>) this) : null;
    }

    public String key() {
        return key;
    }

    public Class<R> recordClass() {
        return recordClass;
    }

    public PacketDirection direction() {
        return direction;
    }

    public Set<ConnectionPhase> phases() {
        return phases;
    }
    /** Every declared wire name, present on some version or not; variant order for {@link VariantCodec} families. */
    public List<String> wireNames() {
        return names;
    }

    public ProtocolVersion since() {
        return since;
    }

    public PacketCodec<R> codec() {
        return codec;
    }

    public boolean isOpaque() {
        return opaque != null;
    }

    public Opaque opaqueValue() {
        return opaque;
    }

    public boolean writable() {
        return codec instanceof WritablePacketCodec<?>;
    }

    /** The declared names that {@code data}'s version carries in any declared phase; empty before {@link #since()}. */
    public List<String> wireNames(ProtocolData data) {
        if (!data.version().atLeast(since)) return List.of();
        return names.stream()
                .filter(name -> phases.stream()
                        .anyMatch(phase -> data.packets(phase, direction).id(name) >= 0))
                .toList();
    }

    @Override
    public String toString() {
        return key;
    }
}
