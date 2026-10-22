package ac.cult.cultac.utils.latency;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PalettedSectionDivisionTest {
    private static final int SECTION_SIZE = 4096;

    @Test
    public void magicDivisionMatchesDivisionForEveryWidthAndIndex() {
        // Every width a section can hold: local palettes, global registries and the
        // 1-value-per-word widths above 32 bits.
        for (int bits = 1; bits <= 64; bits++) {
            int valuesPerLong = 64 / bits;
            for (int index = 0; index < SECTION_SIZE; index++) {
                int word = PalettedSection.magicDivide(index, valuesPerLong);
                assertEquals("bits " + bits + " index " + index, index / valuesPerLong, word);
                assertEquals(
                        "bits " + bits + " index " + index,
                        (index % valuesPerLong) * bits,
                        (index - word * valuesPerLong) * bits);
            }
        }
    }

    @Test
    public void magicDivisionMatchesDivisionForEveryValuesPerLong() {
        for (int valuesPerLong = 1; valuesPerLong <= 64; valuesPerLong++) {
            for (int index = 0; index < SECTION_SIZE; index++) {
                assertEquals(
                        "values " + valuesPerLong + " index " + index,
                        index / valuesPerLong,
                        PalettedSection.magicDivide(index, valuesPerLong));
            }
        }
    }

    @Test
    public void sectionRoundTripsAcrossEveryGrowthWidth() {
        // Grows through the 4..8 bit local palettes into the global width, checking every
        // index after each write so a wrong word or shift shows up as a corrupted neighbour.
        PalettedSection section = PalettedSection.blocks();
        int[] expected = new int[SECTION_SIZE];
        int distinct = 300;
        for (int index = 0; index < SECTION_SIZE; index++) {
            int state = (index * 7919) % distinct;
            assertEquals(expected[index], section.getAndSet(index, state));
            expected[index] = state;
            if ((index & 255) == 255 || index < 2 * distinct) {
                for (int check = 0; check < SECTION_SIZE; check++) assertEquals(expected[check], section.get(check));
            }
        }
        PalettedSection copy = section.copy();
        for (int index = 0; index < SECTION_SIZE; index++) assertEquals(expected[index], copy.get(index));
    }
}
