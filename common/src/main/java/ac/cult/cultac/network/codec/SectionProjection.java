package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.utils.latency.PalettedSection;
import ac.cult.cultac.utils.latency.SectionPool;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.FastThreadLocal;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Older LevelChunkSection framing rewritten as pinned 26.3 sections. 1.21.2-1.21.4 prefix each
 * packed array with its length, and 26.1 added the fluid count after the non-empty block count.
 */
final class SectionProjection {
    private static final int COLUMN_SCRATCH = 65536;
    /** A heap copy of the column {@link #modelSections} decodes, which reads faster than a frame's direct memory. */
    private static final FastThreadLocal<WeakReference<ByteBuf>> COLUMN_BYTES = new FastThreadLocal<>();

    private SectionProjection() {}

    /** Registry sizes and model IDs for one connection's received registries. */
    record Registries(
            int sourceStates,
            int modelStates,
            int sourceBiomes,
            int modelBiomes,
            IntUnaryOperator states,
            IntUnaryOperator biomes) {}

    static List<SectionSnapshot> decodedSections(
            ByteBuf input, ProtocolVersion version, int count, Registries registries) {
        return decodedSections(input, version, count, registries, null, 0, 0);
    }

    /** Block storage is shared with the sections pooled at the column in {@code dimension}, if any; null pools none. */
    static List<SectionSnapshot> decodedSections(
            ByteBuf input,
            ProtocolVersion version,
            int count,
            Registries registries,
            String dimension,
            int chunkX,
            int chunkZ) {
        var sections = new ArrayList<SectionSnapshot>(count);
        for (int i = 0; i < count; i++)
            sections.add(SectionSnapshot.read(
                    input,
                    version,
                    registries.sourceStates(),
                    registries.modelStates(),
                    registries.sourceBiomes(),
                    registries.modelBiomes(),
                    registries.states(),
                    registries.biomes(),
                    SectionPool.existing(dimension, chunkX, i, chunkZ)));
        return List.copyOf(sections);
    }

    /**
     * The pinned 26.3 column as {@link SectionSnapshot#readModel} reads it, sharing block storage with the sections
     * pooled at the column in {@code dimension}; null, with the reader index restored, when it does not decode.
     */
    static List<SectionSnapshot> modelSections(
            ByteBuf input, int count, RegistryNames names, String dimension, int chunkX, int chunkZ) {
        int start = input.readerIndex();
        ByteBuf column = input;
        if (input.readableBytes() <= COLUMN_SCRATCH) {
            column = columnBytes().clear();
            column.writeBytes(input, start, input.readableBytes());
        }
        try {
            int biomes = names.size("minecraft:worldgen/biome");
            var counts = new SectionPool.ReadCounts();
            var sections = new SectionSnapshot[count];
            for (int i = 0; i < count; i++) {
                var pool = SectionPool.existing(dimension, chunkX, i, chunkZ);
                sections[i] = SectionSnapshot.readModel(column, biomes, pool, counts);
            }
            return List.of(sections);
        } catch (RuntimeException malformed) {
            input.readerIndex(start);
            return null;
        }
    }

    /** This thread's column scratch; only the calling decode reads it. */
    private static ByteBuf columnBytes() {
        WeakReference<ByteBuf> reference = COLUMN_BYTES.get();
        ByteBuf bytes = reference == null ? null : reference.get();
        if (bytes == null) {
            bytes = Unpooled.wrappedBuffer(new byte[COLUMN_SCRATCH]);
            COLUMN_BYTES.set(new WeakReference<>(bytes));
        }
        return bytes;
    }

    static List<SectionSnapshot> decodedBiomes(
            ByteBuf input, ProtocolVersion version, int count, Registries registries) {
        var sections = new ArrayList<SectionSnapshot>(count);
        for (int i = 0; i < count; i++)
            sections.add(SectionSnapshot.readBiomes(
                    input, version, registries.sourceBiomes(), registries.modelBiomes(), registries.biomes()));
        return List.copyOf(sections);
    }

    static byte[] sections(ByteBuf input, ProtocolVersion version, int count, Registries registries) {
        ByteBuf output = Unpooled.buffer();
        try {
            for (int section = 0; section < count; section++) {
                short nonEmpty = input.readShort();
                short fluids = version.atLeast(ProtocolVersion.V26_1) ? input.readShort() : 0;
                var states = PalettedSection.readBlocks(
                        input, version, registries.sourceStates(), registries.states(), registries.modelStates());
                var biomes = PalettedSection.readBiomes(
                        input, version, registries.sourceBiomes(), registries.biomes(), registries.modelBiomes());
                output.writeShort(nonEmpty);
                output.writeShort(fluids);
                states.write(output);
                biomes.write(output);
            }
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
        }
    }

    static byte[] biomes(ByteBuf input, ProtocolVersion version, int count, Registries registries) {
        ByteBuf output = Unpooled.buffer();
        try {
            for (int section = 0; section < count; section++) {
                PalettedSection.readBiomes(
                                input,
                                version,
                                registries.sourceBiomes(),
                                registries.biomes(),
                                registries.modelBiomes())
                        .write(output);
            }
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
        }
    }
}
