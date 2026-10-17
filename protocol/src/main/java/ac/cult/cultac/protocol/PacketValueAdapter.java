package ac.cult.cultac.protocol;

import io.netty.buffer.ByteBuf;

/** Platform-side model values decoded directly from an older observation, without projected frames. */
public interface PacketValueAdapter {
    /** Null when the value needs connection state this connection lacks; see {@link CodecState#requiresValues}. */
    Object read(ByteBuf input, ProtocolContext context);

    /** Diagnostic decoding must not update connection registry or simulation state. */
    default Object readForObservation(ByteBuf input, ProtocolContext context) {
        throw new UnsupportedOnVersionException("Adapter does not support read-only observation");
    }

    void write(ByteBuf output, ProtocolContext context, Object packet);
}
