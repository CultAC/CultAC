package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.CodecState;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.ClientboundDimensionData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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
        if (state < 0 || state >= DataTables.defaults().registry().stateCount())
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
        return WorldPackets.SectionBlocksUpdate.readSection(section, count, () -> Wire.readVarLong(input), states);
    }

    static WorldPackets.BlockEntityUpdate blockEntity(ByteBuf input, RegistryNames names) {
        return blockEntity(input, blockEntityTypes(names));
    }

    static WorldPackets.BlockEntityUpdate blockEntity(ByteBuf input, IntFunction<String> types) {
        var pos = BlockPos.of(input.readLong());
        String type = types.apply(Wire.readVarInt(input));
        return new WorldPackets.BlockEntityUpdate(pos, type, NbtValueCodec.readBlockEntity(input, type));
    }

    /**
     * A pinned 26.3 chunk, its sections decoded in the dimension of the attached player when {@code state} lends the
     * frame and that dimension is resolved, see {@link #chunk(ByteBuf, RegistryNames, ClientboundDimensionData)}.
     */
    static WorldPackets.Chunk chunk(ByteBuf input, RegistryNames names, CodecState state) {
        if (!(state instanceof CultConnection connection)) return chunk(input, names, (ClientboundDimensionData) null);
        CultPlayer player = connection.player();
        boolean decodes =
                player != null && !player.compensatedWorld.isClientboundUnresolved() && connection.lease(input) != null;
        return chunk(input, names, decodes ? player.compensatedWorld.getLastClientboundDimension() : null);
    }

    /**
     * A pinned 26.3 chunk; without a {@code dimension} the sections stay bytes. With one, once the rest of the packet
     * has read and nothing trails it, the sections decode as the world reader decodes them in that dimension; a column
     * that does not decode keeps its bytes, which the reader then decodes.
     */
    static WorldPackets.Chunk chunk(ByteBuf input, RegistryNames names, ClientboundDimensionData dimension) {
        IntFunction<String> types = blockEntityTypes(names);
        int x = input.readInt(), z = input.readInt();
        long[] motionBlocking = MotionHeightmapValues.read(input, ProtocolVersion.V26_3);
        ByteBuf sections = input.readSlice(Wire.readLength(input, 2097152));
        if (dimension == null)
            return finishChunk(
                    input, ProtocolVersion.V26_3, types, x, z, motionBlocking, ByteBufUtil.getBytes(sections), null);
        var tickers = new ArrayList<BlockPos>();
        var entities = blockEntities(input, types, x, z, tickers);
        LightValueCodec.skip(input, ProtocolVersion.V26_3);
        var decoded = input.isReadable()
                ? null
                : SectionProjection.modelSections(
                        sections, dimension.sectionCount(), names, dimension.dimension(), x, z);
        var model = decoded == null ? ByteBuffer.wrap(ByteBufUtil.getBytes(sections)) : null;
        return new WorldPackets.Chunk(x, z, model, tickers, entities, motionBlocking, decoded);
    }

    static WorldPackets.Chunk chunkDecoded(
            ByteBuf input, ProtocolVersion version, IntFunction<String> types, ColumnSections sections) {
        int x = input.readInt(), z = input.readInt();
        long[] motionBlocking = MotionHeightmapValues.read(input, version);
        var model = sections.read(input.readSlice(Wire.readLength(input, 2097152)), x, z);
        return finishChunk(input, version, types, x, z, motionBlocking, null, model);
    }

    private static WorldPackets.Chunk finishChunk(
            ByteBuf input,
            ProtocolVersion version,
            IntFunction<String> types,
            int x,
            int z,
            long[] motionBlocking,
            byte[] model,
            List<SectionSnapshot> decoded) {
        var tickers = new ArrayList<BlockPos>();
        var entities = blockEntities(input, types, x, z, tickers);
        LightValueCodec.skip(input, version);
        return new WorldPackets.Chunk(
                x, z, model == null ? null : ByteBuffer.wrap(model), tickers, entities, motionBlocking, decoded);
    }

    /** The column's block entities; each ticker candidate's position is added to {@code tickers}. */
    private static List<WorldPackets.BlockEntityUpdate> blockEntities(
            ByteBuf input, IntFunction<String> types, int x, int z, List<BlockPos> tickers) {
        int count = Wire.readLength(input, input.readableBytes());
        var entities = new ArrayList<WorldPackets.BlockEntityUpdate>(count);
        for (int i = 0; i < count; i++) {
            int xz = input.readUnsignedByte(), y = input.readShort();
            String type = types.apply(Wire.readVarInt(input));
            var pos = new BlockPos((x << 4) + (xz >> 4), y, (z << 4) + (xz & 15));
            if (type.equals("minecraft:potent_sulfur")) tickers.add(pos);
            entities.add(new WorldPackets.BlockEntityUpdate(pos, type, NbtValueCodec.readBlockEntity(input, type)));
        }
        return entities;
    }

    static WorldPackets.ChunkBiomes biomes(ByteBuf input) {
        int count = Wire.readLength(input, input.readableBytes());
        var chunks = new ArrayList<WorldPackets.BiomeChunk>(count);
        for (int i = 0; i < count; i++) {
            long pos = input.readLong();
            byte[] model = ByteBufUtil.getBytes(input.readSlice(Wire.readLength(input, 2097152)));
            chunks.add(new WorldPackets.BiomeChunk((int) pos, (int) (pos >>> 32), ByteBuffer.wrap(model)));
        }
        return new WorldPackets.ChunkBiomes(chunks);
    }

    static WorldPackets.ChunkBiomes biomesDecoded(ByteBuf input, Function<ByteBuf, List<SectionSnapshot>> sections) {
        int count = Wire.readLength(input, input.readableBytes());
        var chunks = new ArrayList<WorldPackets.BiomeChunk>(count);
        for (int i = 0; i < count; i++) {
            long pos = input.readLong();
            var model = sections.apply(input.readSlice(Wire.readLength(input, 2097152)));
            chunks.add(new WorldPackets.BiomeChunk((int) pos, (int) (pos >>> 32), null, model));
        }
        return new WorldPackets.ChunkBiomes(chunks);
    }

    /** A column's section data as snapshots. */
    @FunctionalInterface
    interface ColumnSections {
        List<SectionSnapshot> read(ByteBuf sections, int chunkX, int chunkZ);
    }

    private static IntFunction<String> blockEntityTypes(RegistryNames names) {
        return id -> names.name("minecraft:block_entity_type", id);
    }
}
