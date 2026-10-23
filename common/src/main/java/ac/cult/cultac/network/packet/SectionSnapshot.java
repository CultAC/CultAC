package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.PalettedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Immutable owned palettes. Mutable consumers copy storage; no frame or encoded copy is retained. */
public final class SectionSnapshot {
    private final short nonEmpty, fluids;
    private final PalettedSection blocks, biomes;

    private SectionSnapshot(short nonEmpty, short fluids, PalettedSection blocks, PalettedSection biomes) {
        this.nonEmpty = nonEmpty;
        this.fluids = fluids;
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
        short nonEmpty = input.readShort();
        short fluids = version.atLeast(ProtocolVersion.V26_1) ? input.readShort() : 0;
        return new SectionSnapshot(
                nonEmpty,
                fluids,
                PalettedSection.readBlocks(input, version, sourceStates, states, modelStates),
                PalettedSection.readBiomes(input, version, sourceBiomes, biomes, modelBiomes));
    }

    public static SectionSnapshot readBiomes(
            ByteBuf input, ProtocolVersion version, int sourceBiomes, int modelBiomes, IntUnaryOperator biomes) {
        return new SectionSnapshot(
                (short) 0,
                (short) 0,
                null,
                PalettedSection.readBiomes(input, version, sourceBiomes, biomes, modelBiomes));
    }

    public PalettedSection copyBlocks() {
        return blocks.copyOnWrite();
    }

    /** The biome of every cell when the palette holds a single entry; -1 otherwise. */
    public int uniformBiome() {
        return biomes.singleValue();
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
