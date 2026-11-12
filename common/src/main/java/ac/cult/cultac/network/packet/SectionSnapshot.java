package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.PalettedSection;
import ac.cult.cultac.utils.latency.SectionPool;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.ApiStatus;

/**
 * Decoded palettes; a section pool may share their block storage across connections, copy-on-write, and a
 * single-entry palette is a canonical section that every thread shares. Mutable consumers copy storage, except the world
 * reader, which {@link #takeBlocks} hands a {@link #readModel} section's own storage, whose later writes this snapshot
 * then shows; no frame or encoded copy is retained.
 */
public final class SectionSnapshot {
    private static final int COPIES = -1;

    private final short nonEmpty, fluids;
    /** The fluid count when the wire (26.1+) or a pooled section with these contents supplied it; -1 otherwise. */
    private final int knownFluids;

    private final PalettedSection blocks, biomes;
    /** Counts the next {@link #takeBlocks} adds as it hands over {@link #blocks}, else COPIES; owner thread only. */
    private int heldCounts = COPIES;

    private SectionSnapshot(
            short nonEmpty, short fluids, int knownFluids, PalettedSection blocks, PalettedSection biomes) {
        this.nonEmpty = nonEmpty;
        this.fluids = fluids;
        this.knownFluids = knownFluids;
        this.blocks = blocks;
        this.biomes = biomes;
    }

    public static SectionSnapshot read(
            ByteBuf input,
            ProtocolVersion version,
            int sourceStates,
            int modelStates,
            int sourceBiomes,
            int modelBiomes,
            IntUnaryOperator states,
            IntUnaryOperator biomes) {
        return read(input, version, sourceStates, modelStates, sourceBiomes, modelBiomes, states, biomes, null);
    }

    /**
     * As the read without a pool, sharing the block storage of a section in {@code pool} that the wire holds.
     * {@code states} must be pure and non-blocking: it runs under the pool's lock, possibly once per pooled candidate.
     */
    public static SectionSnapshot read(
            ByteBuf input,
            ProtocolVersion version,
            int sourceStates,
            int modelStates,
            int sourceBiomes,
            int modelBiomes,
            IntUnaryOperator states,
            IntUnaryOperator biomes,
            SectionPool pool) {
        short nonEmpty = input.readShort();
        boolean wireFluids = version.atLeast(ProtocolVersion.V26_1);
        short fluids = wireFluids ? input.readShort() : 0;
        var blocks = PalettedSection.readCanonicalBlocksPooled(input, version, sourceStates, states, modelStates, pool);
        return new SectionSnapshot(
                nonEmpty,
                fluids,
                wireFluids ? fluids : blocks.pooledFluidCount(),
                blocks.states(),
                PalettedSection.readCanonicalBiomes(input, version, sourceBiomes, biomes, modelBiomes));
    }

    public static SectionSnapshot readBiomes(
            ByteBuf input, ProtocolVersion version, int sourceBiomes, int modelBiomes, IntUnaryOperator biomes) {
        return new SectionSnapshot(
                (short) 0,
                (short) 0,
                -1,
                null,
                PalettedSection.readCanonicalBiomes(input, version, sourceBiomes, biomes, modelBiomes));
    }

    /**
     * For the chunk codec only: a pinned 26.3 section as the model world reads it, block states as
     * {@link PalettedSection#readCanonicalBlocks} with {@code pool}, both counts from the wire, biomes in their wire
     * registry of {@code biomeRegistrySize}; {@code counts} holds its pool counts until {@link #takeBlocks}.
     */
    @ApiStatus.Internal
    public static SectionSnapshot readModel(
            ByteBuf input, int biomeRegistrySize, SectionPool pool, SectionPool.ReadCounts counts) {
        short nonEmpty = input.readShort();
        short fluids = input.readShort();
        var blocks = PalettedSection.readCanonicalBlocks(input, pool, counts);
        var snapshot = new SectionSnapshot(
                nonEmpty, fluids, fluids, blocks, PalettedSection.readCanonicalBiomes(input, biomeRegistrySize));
        snapshot.heldCounts = counts.take();
        return snapshot;
    }

    public PalettedSection copyBlocks() {
        return blocks.copyOnWrite();
    }

    /**
     * For the world reader only; a listener that runs before it uses {@link #copyBlocks}. For a {@link #readModel}
     * section the first call adds its pool counts and gives its own storage, whose later writes this snapshot shows;
     * otherwise, and on later calls, a canonical section itself, which nothing writes, else {@link #copyBlocks}.
     */
    @ApiStatus.Internal
    public PalettedSection takeBlocks() {
        int counts = heldCounts;
        if (counts == COPIES) return blocks.isCanonical() ? blocks : copyBlocks();
        heldCounts = COPIES;
        SectionPool.ReadCounts.add(counts);
        return blocks;
    }

    /** The non-air block count the wire carried. */
    public int nonEmptyBlockCount() {
        return nonEmpty;
    }

    /** The wire's fluid count (26.1+), else a pooled section's with the same contents; -1 when neither is known. */
    public int knownFluidCount() {
        return knownFluids;
    }

    /** The biome of every cell when the palette holds a single entry; -1 otherwise. */
    public int uniformBiome() {
        return biomes.singleValue();
    }

    /** The biome of every cell in {@link PalettedSection#sharedValues}' array, which no caller may write. */
    @ApiStatus.Internal
    public int[] biomeValues() {
        return biomes.sharedValues();
    }

    public int[] copyBiomes() {
        int[] values = new int[64];
        for (int i = 0; i < values.length; i++) values[i] = biomes.get(i);
        return values;
    }

    /** Compatibility encoding is generated on demand and never cached alongside the palettes. */
    public static ByteBuffer encode(List<SectionSnapshot> sections) {
        var output = Unpooled.buffer();
        try {
            for (var section : sections) {
                if (section.blocks != null) {
                    output.writeShort(section.nonEmpty);
                    output.writeShort(section.fluids);
                    section.blocks.write(output);
                }
                section.biomes.write(output);
            }
            return ByteBuffer.wrap(ByteBufUtil.getBytes(output)).asReadOnlyBuffer();
        } finally {
            output.release();
        }
    }
}
