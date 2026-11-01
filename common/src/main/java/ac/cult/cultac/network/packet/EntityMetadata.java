package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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
     * range of a read-only payload, of an array, or of a lent frame, whose position and limit are never moved, so
     * views are cut on demand. Bytes of a lent frame are readable only until its lease ends; a holder that keeps the
     * entry past its frame and needs the bytes keeps {@link #detach()} instead.
     */
    public static final class Entry {
        private final int id;
        private final Object value;
        /** A read-only {@link ByteBuffer}, a {@code byte[]} or a {@link FrameLease}. */
        private final Object payload;
        private final int offset;
        private final int length;

        /** The entry's bytes are {@code bytes}' remaining bytes, kept through a read-only view of their own. */
        public Entry(int id, Object value, ByteBuffer bytes) {
            this(id, value, bytes.asReadOnlyBuffer(), bytes.position(), bytes.remaining());
        }

        private Entry(int id, Object value, Object payload, int offset, int length) {
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

        /**
         * An entry whose bytes are {@code length} bytes of {@code payload} from index {@code offset}. The entry keeps
         * the array: nothing may change its contents afterwards.
         */
        public static Entry of(int id, Object value, byte[] payload, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, payload.length);
            return new Entry(id, value, payload, offset, length);
        }

        /**
         * An entry whose bytes are {@code length} bytes of {@code lease}'s frame from absolute index {@code offset},
         * readable while the lease lasts. Nothing may change those bytes while it does.
         */
        public static Entry of(int id, Object value, FrameLease lease, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, lease.frame().writerIndex());
            return new Entry(id, value, lease, offset, length);
        }

        public int id() {
            return id;
        }

        public Object value() {
            return value;
        }

        /**
         * A fresh read-only view of the entry's bytes, at position 0.
         *
         * @throws IllegalStateException when the bytes were borrowed from a frame that has since been released
         */
        public ByteBuffer bytes() {
            if (payload instanceof ByteBuffer buffer) return buffer.slice(offset, length);
            if (payload instanceof FrameLease lease)
                return ByteBuffer.wrap(ByteBufUtil.getBytes(lease.frame(), offset, length))
                        .asReadOnlyBuffer();
            return ByteBuffer.wrap((byte[]) payload, offset, length).slice().asReadOnlyBuffer();
        }

        /**
         * Writes the entry's bytes to {@code output}.
         *
         * @throws IllegalStateException when the bytes were borrowed from a frame that has since been released
         */
        public void writeTo(ByteBuf output) {
            if (payload instanceof FrameLease lease) {
                ByteBuf frame = lease.frame();
                // Netty copies out of a direct buffer it cannot address through a fresh duplicate per call.
                if (!frame.hasArray() && frame.nioBufferCount() == 1)
                    output.writeBytes(frame.internalNioBuffer(offset, length));
                else output.writeBytes(frame, offset, length);
            } else if (payload instanceof byte[] array) output.writeBytes(array, offset, length);
            else output.writeBytes(((ByteBuffer) payload).slice(offset, length));
        }

        /**
         * This entry with bytes of its own: itself unless its bytes are borrowed from a frame, which must still be
         * live.
         */
        public Entry detach() {
            if (!(payload instanceof FrameLease lease)) return this;
            return new Entry(id, value, ByteBufUtil.getBytes(lease.frame(), offset, length), 0, length);
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
            boolean released = payload instanceof FrameLease lease && !lease.live();
            return "Entry[id=" + id + ", value=" + value + ", bytes=" + (released ? "<released frame>" : bytes()) + "]";
        }
    }
}
