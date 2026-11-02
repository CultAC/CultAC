package ac.cult.cultac.protocol.codec.world;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetChunkCacheCenter;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetChunkCacheRadius;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class ChunkCacheCodecs {
    private ChunkCacheCodecs() {}

    public static final class Center implements PacketCodec<ClientboundSetChunkCacheCenter> {
        @Override
        public ClientboundSetChunkCacheCenter read(ByteBuf input, ProtocolContext context) {
            return new ClientboundSetChunkCacheCenter(Wire.readVarInt(input), Wire.readVarInt(input));
        }
    }

    public static final class Radius implements PacketCodec<ClientboundSetChunkCacheRadius> {
        @Override
        public ClientboundSetChunkCacheRadius read(ByteBuf input, ProtocolContext context) {
            return new ClientboundSetChunkCacheRadius(Wire.readVarInt(input));
        }
    }
}
