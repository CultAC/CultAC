package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CachedChunkBiomesTest {
    @Test
    void retainedBiomesReadAsReceivedAndIgnoreLaterWritesToTheInput() {
        var random = new Random(5);
        for (int round = 0; round < 500; round++) {
            int sections = 1 + random.nextInt(24);
            int[][] input = new int[sections][];
            for (int i = 0; i < sections; i++) {
                input[i] = new int[64];
                int kind = random.nextInt(4);
                int biome = kind == 0 ? -1 - random.nextInt(3) : kind == 1 ? 1024 + random.nextInt(4000) : random.nextInt(80);
                Arrays.fill(input[i], biome);
                if (random.nextInt(3) == 0) input[i][random.nextInt(64)] = biome + 1;
            }
            int[][] expected = Arrays.stream(input).map(int[]::clone).toArray(int[][]::new);
            var chunk = new CachedChunk(new CachedSection[sections], 0, List.of(), input);
            for (int[] values : input) Arrays.fill(values, 7);
            for (int section = 0; section < sections; section++)
                for (int cell = 0; cell < 64; cell++)
                    assertEquals(
                            expected[section][cell], chunk.biomeAt(section, cell & 3, cell >> 4, (cell >> 2) & 3));
        }
    }

    @Test
    void uniformSectionsShareOneArrayPerBiome() {
        int[] plains = CachedChunk.uniformBiomes(3);
        int[] filled = new int[64];
        Arrays.fill(filled, 3);
        assertArrayEquals(filled, plains);
        assertSame(plains, CachedChunk.uniformBiomes(3));
        Arrays.fill(filled, 5000);
        assertArrayEquals(filled, CachedChunk.uniformBiomes(5000));
        Arrays.fill(filled, -2);
        assertArrayEquals(filled, CachedChunk.uniformBiomes(-2));
    }

    @Test
    void malformedBiomeInputFailsAsBefore() {
        var sections = new CachedSection[2];
        var mismatch = assertThrows(
                IllegalArgumentException.class, () -> new CachedChunk(sections, 0, List.of(), new int[3][64]));
        assertEquals("Biome section count mismatch", mismatch.getMessage());
        var shortSection = assertThrows(
                IllegalArgumentException.class,
                () -> new CachedChunk(sections, 0, List.of(), new int[][] {new int[64], new int[63]}));
        assertEquals("Biome section must contain 64 values", shortSection.getMessage());
    }

    @Test
    void singleValueMatchesEveryIndex() {
        var random = new Random(9);
        for (int round = 0; round < 2000; round++) {
            var wire = Unpooled.buffer();
            int registry = 2 + random.nextInt(100);
            int bits = random.nextInt(3) == 0 ? 0 : 1 + random.nextInt(3);
            wire.writeByte(bits);
            if (bits == 0) Wire.writeVarInt(wire, random.nextInt(registry));
            else {
                int entries = 1 + random.nextInt(1 << bits);
                Wire.writeVarInt(wire, entries);
                for (int i = 0; i < entries; i++) Wire.writeVarInt(wire, random.nextInt(registry));
                int perWord = 64 / bits;
                for (int word = 0; word < (64 + perWord - 1) / perWord; word++)
                    wire.writeLong(random.nextLong());
            }
            var section = PalettedSection.readBiomes(wire, ProtocolVersion.V26_3, registry);
            wire.release();
            int single = section.singleValue();
            if (bits != 0) {
                assertEquals(-1, single);
                continue;
            }
            for (int i = 0; i < 64; i++) assertEquals(section.get(i), single);
        }
    }
}
