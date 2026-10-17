package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.data.ProtocolData;
import io.netty.buffer.ByteBuf;

/** Stateless packet reader. The connection owns index/error discipline. */
public interface PacketCodec<R> {
    /** Null when the value needs connection state this connection lacks; see {@link CodecState#requiresValues}. */
    R read(ByteBuf input, ProtocolContext context);

    default void write(ByteBuf output, ProtocolContext context, R packet) {
        throw new UnsupportedOnVersionException("Read-only codec " + getClass().getName());
    }

    default boolean readsEntirePayload() {
        return true;
    }

    /** Numeric/native values must be projected into the model's protocol before decoding. */
    default boolean requiresModelValues() {
        return false;
    }

    /**
     * True when the frame whose payload starts at {@code payloadIndex} carries nothing for handlers and is forwarded
     * without being decoded. Reads without moving the frame's indices.
     */
    default boolean forwardsUndecoded(ByteBuf frame, int payloadIndex) {
        return false;
    }

    /** Validate version-dependent enum mappings and other prerequisites eagerly. */
    default void validate(ProtocolData data) {}
}
