package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import java.nio.ByteBuffer;
import java.util.List;

/** Consumed values plus owned entry bytes, so untouched metadata never needs re-encoding. */
public record EntityMetadata(int id, List<Entry> packedItems) implements ClientboundPacket {
    public EntityMetadata {
        packedItems = List.copyOf(packedItems);
    }

    /** A null value means the payload was traversed but is not consumed by the anticheat. */
    public record Entry(int id, Object value, ByteBuffer bytes) {
        public Entry {
            bytes = bytes.asReadOnlyBuffer();
        }

        @Override
        public ByteBuffer bytes() {
            return bytes.asReadOnlyBuffer();
        }

        /** Serializer IDs are source-verified for every supported observed version. */
        public static Entry health(int index, ac.cult.cultac.protocol.ProtocolVersion version, float value) {
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
    }
}
