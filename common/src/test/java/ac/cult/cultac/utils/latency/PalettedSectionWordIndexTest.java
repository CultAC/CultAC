package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PalettedSectionWordIndexTest {
    @Test
    void reciprocalWordIndexEqualsDivisionForEveryWidthAndIndex() {
        for (int bits = 1; bits <= 32; bits++) {
            int perWord = 64 / bits;
            for (int index = 0; index < 65536; index++) {
                assertEquals(index / perWord, PalettedSection.wordIndex(index, bits), bits + " " + index);
            }
        }
    }

    @Test
    void storedStatesReadBackThroughEveryPaletteGrowth() {
        var random = new SplittableRandom(0x9A1EL);
        var section = PalettedSection.blocks();
        int[] expected = new int[4096];
        for (int distinct = 1; distinct <= 600; distinct++) {
            for (int write = 0; write < 40; write++) {
                int index = random.nextInt(4096);
                int state = random.nextInt(distinct) * 37 % 20000;
                section.getAndSet(index, state);
                expected[index] = state;
            }
            for (int index = 0; index < 4096; index++) assertEquals(expected[index], section.get(index));
        }
    }
}
