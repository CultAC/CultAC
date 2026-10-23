package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/** Consumed values plus owned entry bytes, so untouched metadata never needs re-encoding. */
public record EntityMetadata(int id, List<Entry> packedItems) implements ClientboundPacket {
    public EntityMetadata {
        packedItems = List.copyOf(packedItems);
    }

    /**
     * One entry. A null value means the payload was traversed but is not consumed by the anticheat. Its bytes are a
     * range of a read-only payload whose position and limit are never moved, so views are cut on demand.
     */
    public static final class Entry {
        private final int id;
        private final Object value;
        private final ByteBuffer payload;
        private final int offset;
        private final int length;

        /** The entry's bytes are {@code bytes}' remaining bytes, kept through a read-only view of their own. */
        public Entry(int id, Object value, ByteBuffer bytes) {
            this(id, value, bytes.asReadOnlyBuffer(), bytes.position(), bytes.remaining());
        }

        private Entry(int id, Object value, ByteBuffer payload, int offset, int length) {
            this.id = id;
            this.value = value;
            this.payload = payload;
            this.offset = offset;
            this.length = length;
        }

        /**
         * An entry whose bytes are {@code length} bytes of {@code payload} from absolute index {@code offset}. The
         * payload must be read-only, and nothing may change its contents, position or limit afterwards.
         */
        public static Entry of(int id, Object value, ByteBuffer payload, int offset, int length) {
            if (!payload.isReadOnly()) throw new IllegalArgumentException("Entry payload must be read-only");
            Objects.checkFromIndexSize(offset, length, payload.limit());
            return new Entry(id, value, payload, offset, length);
        }

        public int id() {
            return id;
        }

        public Object value() {
            return value;
        }

        /** A fresh read-only view of the entry's bytes, at position 0. */
        public ByteBuffer bytes() {
            return payload.slice(offset, length);
        }

        /** EntityDataSerializers.BYTE is serializer 0 in every supported observed version. */
        public static Entry byteValue(int index, byte value) {
            return new Entry(
                    index,
                    value,
                    ByteBuffer.allocate(3)
                            .put((byte) index)
                            .put((byte) 0)
                            .put(value)
                            .flip());
        }

        /** Serializer IDs are source-verified for every supported observed version. */
        public static Entry health(int index, ProtocolVersion version, float value) {
            int serializer = switch (version) {
                case V1_21_3, V1_21_4, V1_21_5, V1_21_6, V1_21_7, V1_21_9, V1_21_11, V26_1, V26_2, V26_3 -> 3;
            };
            return new Entry(
                    index,
                    value,
                    ByteBuffer.allocate(6)
                            .put((byte) index)
                            .put((byte) serializer)
                            .putFloat(value)
                            .flip());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry
                    && id == entry.id
                    && Objects.equals(value, entry.value)
                    && bytes().equals(entry.bytes());
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Integer.hashCode(id) + Objects.hashCode(value)) + bytes().hashCode();
        }

        @Override
        public String toString() {
            return "Entry[id=" + id + ", value=" + value + ", bytes=" + bytes() + "]";
        }
    }
}
