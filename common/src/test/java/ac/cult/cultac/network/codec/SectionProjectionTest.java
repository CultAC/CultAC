package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.PalettedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/** LevelChunkSection framing for every supported wire, read into the pinned 26.3 sections. */
class SectionProjectionTest {
    private static final int MODEL_STATES = DataTables.defaults().registry().stateCount();
    private static final ProtocolVersion[] FRAMINGS = {
        ProtocolVersion.V1_21_3, ProtocolVersion.V1_21_5, ProtocolVersion.V26_1, ProtocolVersion.V26_3
    };

    @Test
    void localPalettesKeepTheirLayoutUnderEveryFraming() {
        for (var version : FRAMINGS) {
            var mapping = ModelIdMappings.project(version, ProtocolVersion.V26_3);
            int[] blocks = {0, 10};
            int[] codes = new int[4096];
            for (int i = 0; i < codes.length; i++) codes[i] = i % 2;
            var input = Unpooled.buffer();
            try {
                header(input, version, 4096, 0);
                palette(input, version, 4, blocks, codes);
                single(input, version, 1);
                var output = project(input, version, 1, mapping::blockState, 60, 64, id -> id + 2);
                assertFalse(input.isReadable(), version.toString());
                assertEquals(4096, output.getShort(0));
                var states = PalettedSection.readBlocks(header(output));
                for (int i = 0; i < 4096; i++)
                    assertEquals(mapping.blockState(blocks[i % 2]), states.get(i), version.toString());
                assertArrayEquals(filled(64, 3), PalettedSection.readBiomes(output, 64), version.toString());
                assertFalse(output.isReadable());
                output.release();
            } finally {
                input.release();
            }
        }
    }

    @Test
    void hashMapPaletteAndSingleValueSectionsProjectEachEntry() {
        var version = ProtocolVersion.V1_21_5;
        var mapping = ModelIdMappings.project(version, ProtocolVersion.V26_3);
        int[] blocks = new int[40];
        for (int i = 0; i < blocks.length; i++) blocks[i] = i * 7;
        int[] codes = new int[4096];
        for (int i = 0; i < codes.length; i++) codes[i] = i % blocks.length;
        var input = Unpooled.buffer();
        try {
            header(input, version, 4096, 0);
            palette(input, version, 6, blocks, codes);
            single(input, version, 0);
            header(input, version, 0, 0);
            single(input, version, 0);
            single(input, version, 5);
            var output = project(input, version, 2, mapping::blockState, 64, 64, IntUnaryOperator.identity());
            var states = PalettedSection.readBlocks(header(output));
            for (int i = 0; i < 4096; i++) assertEquals(mapping.blockState(blocks[i % blocks.length]), states.get(i));
            PalettedSection.readBiomes(output, 64);
            var air = PalettedSection.readBlocks(header(output));
            assertEquals(mapping.blockState(0), air.get(4095));
            assertArrayEquals(filled(64, 5), PalettedSection.readBiomes(output, 64));
            output.release();
        } finally {
            input.release();
        }
    }

    @Test
    void globalBlockPalettesWidenToTheModelRegistry() {
        var version = ProtocolVersion.V1_21_3;
        var mapping = ModelIdMappings.project(version, ProtocolVersion.V26_3);
        int sourceStates = ModelRegistryData.load(version).blockStates().size();
        int sourceBits = bits(sourceStates);
        assertEquals(15, sourceBits);
        assertEquals(16, bits(MODEL_STATES));
        int[] codes = new int[4096];
        for (int i = 0; i < codes.length; i++) codes[i] = (i * 31) % sourceStates;
        var input = Unpooled.buffer();
        try {
            header(input, version, 4096, 0);
            palette(input, version, sourceBits, null, codes);
            single(input, version, 0);
            var output = project(input, version, 1, mapping::blockState, 64, 64, IntUnaryOperator.identity());
            assertEquals(16, header(output).getByte(output.readerIndex()));
            var states = PalettedSection.readBlocks(output);
            for (int i = 0; i < 4096; i++) assertEquals(mapping.blockState(codes[i]), states.get(i));
            output.release();
        } finally {
            input.release();
        }
    }

    @Test
    void globalBiomePalettesUseTheReceivedRegistrySizes() {
        var version = ProtocolVersion.V26_1;
        int[] codes = new int[64];
        for (int i = 0; i < codes.length; i++) codes[i] = i;
        for (int modelBiomes : new int[] {100, 70}) {
            var input = Unpooled.buffer();
            try {
                header(input, version, 0, 0);
                single(input, version, 0);
                palette(input, version, bits(70), null, codes);
                var output = project(input, version, 1, IntUnaryOperator.identity(), 70, modelBiomes, id -> id + 1);
                PalettedSection.readBlocks(header(output));
                var biomes = PalettedSection.readBiomes(output, modelBiomes);
                for (int i = 0; i < 64; i++) assertEquals(i + 1, biomes[i]);
                output.release();
            } finally {
                input.release();
            }
        }
        // A model registry that fits a list palette receives a list palette.
        var input = Unpooled.buffer();
        try {
            for (int i = 0; i < codes.length; i++) codes[i] = i % 4;
            header(input, version, 0, 0);
            single(input, version, 0);
            palette(input, version, bits(70), null, codes);
            var output = project(input, version, 1, IntUnaryOperator.identity(), 70, 8, id -> id + 1);
            PalettedSection.readBlocks(header(output));
            assertTrue(output.getByte(output.readerIndex()) <= 3);
            var biomes = PalettedSection.readBiomes(output, 8);
            for (int i = 0; i < 64; i++) assertEquals(i % 4 + 1, biomes[i]);
            output.release();
        } finally {
            input.release();
        }
    }

    @Test
    void olderLengthPrefixesFollowTheClientReader() {
        var version = ProtocolVersion.V1_21_3;
        var input = Unpooled.buffer();
        try {
            header(input, version, 4096, 0);
            // PalettedContainer.read discards a differently sized array; storage stays index 0.
            input.writeByte(4);
            Wire.writeVarInt(input, 2);
            Wire.writeVarInt(input, 0);
            Wire.writeVarInt(input, 10);
            Wire.writeVarInt(input, 3);
            for (int i = 0; i < 3; i++) input.writeLong(-1L);
            single(input, version, 0);
            var output = project(input, version, 1, IntUnaryOperator.identity(), 64, 64, IntUnaryOperator.identity());
            assertFalse(input.isReadable());
            var states = PalettedSection.readBlocks(header(output));
            for (int i = 0; i < 4096; i++) assertEquals(0, states.get(i));
            output.release();
        } finally {
            input.release();
        }
        var truncated = Unpooled.buffer();
        try {
            header(truncated, version, 0, 0);
            truncated.writeByte(0);
            Wire.writeVarInt(truncated, 0);
            Wire.writeVarInt(truncated, 1000);
            assertThrows(MalformedPacketException.class, () -> project(
                    truncated, version, 1, IntUnaryOperator.identity(), 64, 64, IntUnaryOperator.identity()));
        } finally {
            truncated.release();
        }
    }

    private static ByteBuf project(
            ByteBuf input,
            ProtocolVersion version,
            int count,
            IntUnaryOperator states,
            int sourceBiomes,
            int modelBiomes,
            IntUnaryOperator biomes) {
        var registries = new SectionProjection.Registries(
                ModelRegistryData.load(version).blockStates().size(),
                MODEL_STATES,
                sourceBiomes,
                modelBiomes,
                states,
                biomes);
        return Unpooled.wrappedBuffer(SectionProjection.sections(input, version, count, registries));
    }

    /** Model sections always carry both counts, which the model reader recalculates. */
    private static ByteBuf header(ByteBuf model) {
        model.skipBytes(2 * Short.BYTES);
        return model;
    }

    private static void header(ByteBuf output, ProtocolVersion version, int nonEmpty, int fluids) {
        output.writeShort(nonEmpty);
        if (version.atLeast(ProtocolVersion.V26_1)) output.writeShort(fluids);
    }

    private static void single(ByteBuf output, ProtocolVersion version, int id) {
        output.writeByte(0);
        Wire.writeVarInt(output, id);
        if (!version.atLeast(ProtocolVersion.V1_21_5)) Wire.writeVarInt(output, 0);
    }

    /** SimpleBitStorage packing; a null palette writes global IDs. */
    private static void palette(ByteBuf output, ProtocolVersion version, int bits, int[] palette, int[] codes) {
        output.writeByte(bits);
        if (palette != null) {
            Wire.writeVarInt(output, palette.length);
            for (int id : palette) Wire.writeVarInt(output, id);
        }
        int perWord = 64 / bits;
        long[] words = new long[(codes.length + perWord - 1) / perWord];
        for (int i = 0; i < codes.length; i++)
            words[i / perWord] |= (long) codes[i] << ((i % perWord) * bits);
        if (!version.atLeast(ProtocolVersion.V1_21_5)) Wire.writeVarInt(output, words.length);
        for (long word : words) output.writeLong(word);
    }

    private static int bits(int count) {
        return 32 - Integer.numberOfLeadingZeros(count - 1);
    }

    private static int[] filled(int size, int value) {
        int[] values = new int[size];
        java.util.Arrays.fill(values, value);
        return values;
    }
}
