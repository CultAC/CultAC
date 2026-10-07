package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * World packet framing for every supported wire version; registries, sections and NBT are owned
 * snapshots. Callers supply the wire-to-model ID steps, which are identities on the model wire.
 */
final class ModelWorldValues {
    private ModelWorldValues() {}

    static WorldPackets.BlockUpdate blockUpdate(ByteBuf input) {
        return blockUpdate(input, ModelWorldValues::modelState);
    }

    static WorldPackets.BlockUpdate blockUpdate(ByteBuf input, IntUnaryOperator states) {
        var pos = BlockPos.of(input.readLong());
        return new WorldPackets.BlockUpdate(pos, states.applyAsInt(Wire.readVarInt(input)));
    }

    /** The single update uses byIdOrThrow; section updates use nullable byId. */
    private static int modelState(int state) {
        if (state < 0 || state >= ac.cult.blocksim.data.DataTables.defaults().registry().stateCount())
            throw new IllegalArgumentException("No value with id " + state);
        return state;
    }

    static WorldPackets.SectionBlocksUpdate sectionUpdates(ByteBuf input) {
        return sectionUpdates(input, IntUnaryOperator.identity());
    }

    /** PacketEvents WrapperPlayServerMultiBlockChange sequential entries and coordinate packing. */
    static WorldPackets.SectionBlocksUpdate sectionUpdates(ByteBuf input, IntUnaryOperator states) {
        long section = input.readLong();
        int count = Wire.readLength(input, input.readableBytes());
        var updates = new ArrayList<WorldPackets.BlockUpdate>(count);
        for (int i = 0; i < count; i++) {
            long entry = Wire.readVarLong(input);
            updates.add(new WorldPackets.BlockUpdate(
                    BlockPos.fromSection(section, (int) (entry & 4095)), states.applyAsInt((int) (entry >>> 12))));
        }
        return new WorldPackets.SectionBlocksUpdate(updates);
    }

    static WorldPackets.BlockEntityUpdate blockEntity(ByteBuf input, RegistryNames names) {
        return blockEntity(input, blockEntityTypes(names));
    }

    static WorldPackets.BlockEntityUpdate blockEntity(ByteBuf input, IntFunction<String> types) {
        var pos = BlockPos.of(input.readLong());
        String type = types.apply(Wire.readVarInt(input));
        return new WorldPackets.BlockEntityUpdate(pos, type, NbtValueCodec.readBlockEntity(input, type));
    }

    static WorldPackets.Chunk chunk(ByteBuf input, RegistryNames names) {
        return chunk(input, ProtocolVersion.V26_3, blockEntityTypes(names), ByteBufUtil::getBytes);
    }

    /** {@code sections} receives exactly the section payload and returns 26.3 section bytes. */
    static WorldPackets.Chunk chunk(
            ByteBuf input, ProtocolVersion version, IntFunction<String> types, Function<ByteBuf, byte[]> sections) {
        int x = input.readInt(), z = input.readInt();
        long[] motionBlocking = MotionHeightmapValues.read(input, version);
        byte[] model = sections.apply(input.readSlice(Wire.readLength(input, 2097152)));
        int count = Wire.readLength(input, input.readableBytes());
        var tickers = new ArrayList<BlockPos>();
        var entities = new ArrayList<WorldPackets.BlockEntityUpdate>(count);
        for (int i = 0; i < count; i++) {
            int xz = input.readUnsignedByte(), y = input.readShort();
            String type = types.apply(Wire.readVarInt(input));
            var pos = new BlockPos((x << 4) + (xz >> 4), y, (z << 4) + (xz & 15));
            if (type.equals("minecraft:potent_sulfur")) tickers.add(pos);
            entities.add(new WorldPackets.BlockEntityUpdate(pos, type, NbtValueCodec.readBlockEntity(input, type)));
        }
        return new WorldPackets.Chunk(
                x,
                z,
                ByteBuffer.wrap(model),
                tickers,
                LightValueCodec.read(input, version),
                entities,
                motionBlocking);
    }

    static WorldPackets.ChunkBiomes biomes(ByteBuf input) {
        return biomes(input, ByteBufUtil::getBytes);
    }

    static WorldPackets.ChunkBiomes biomes(ByteBuf input, Function<ByteBuf, byte[]> sections) {
        int count = Wire.readLength(input, input.readableBytes());
        var chunks = new ArrayList<WorldPackets.BiomeChunk>(count);
        for (int i = 0; i < count; i++) {
            long pos = input.readLong();
            byte[] model = sections.apply(input.readSlice(Wire.readLength(input, 2097152)));
            chunks.add(new WorldPackets.BiomeChunk((int) pos, (int) (pos >>> 32), ByteBuffer.wrap(model)));
        }
        return new WorldPackets.ChunkBiomes(chunks);
    }

    static WorldPackets.LightUpdate light(ByteBuf input) {
        return light(input, ProtocolVersion.V26_3);
    }

    static WorldPackets.LightUpdate light(ByteBuf input, ProtocolVersion version) {
        int x = Wire.readVarInt(input), z = Wire.readVarInt(input);
        return new WorldPackets.LightUpdate(x, z, LightValueCodec.read(input, version));
    }

    private static IntFunction<String> blockEntityTypes(RegistryNames names) {
        return id -> names.name("minecraft:block_entity_type", id);
    }
}
