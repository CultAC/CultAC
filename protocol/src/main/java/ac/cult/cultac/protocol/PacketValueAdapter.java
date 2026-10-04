package ac.cult.cultac.protocol;

import io.netty.buffer.ByteBuf;

/** Platform-side model values decoded directly from an older observation, without projected frames. */
public interface PacketValueAdapter {
    Object read(ByteBuf input, ProtocolContext context);

    void write(ByteBuf output, ProtocolContext context, Object packet);
}
