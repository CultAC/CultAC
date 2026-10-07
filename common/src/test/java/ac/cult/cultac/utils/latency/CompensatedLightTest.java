package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.network.packet.LightValues;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompensatedLightTest {
    @Test
    void packetLayersPreserveLightAcrossNegativeHeightAndDimensionTypes() {
        var light = new CompensatedLight(24);
        byte[] sky = new byte[2048];
        java.util.Arrays.fill(sky, (byte) 255);
        byte[] block = new byte[2048];
        set(sky, 3, 0, 5, 6);
        set(sky, 3, 1, 5, 4);
        set(block, 3, 1, 5, 11);
        light.apply(new LightValues(bits(5), bits(5), new BitSet(), new BitSet(), List.of(sky), List.of(block)));
        assertEquals(11, light.brightness(3, 1, 5, -64, true));
        assertEquals(11, light.brightness(3, 1, 5, -64, false));
        assertEquals(4, light.skyBrightness(3, 1, 5, -64, true));
        assertEquals(0, light.skyBrightness(3, 1, 5, -64, false));
        assertEquals(6, light.brightness(3, -20, 5, -64, true));
        assertEquals(6, light.skyBrightness(3, -20, 5, -64, true));
        assertEquals(0, light.brightness(3, -20, 5, -64, false));
        assertEquals(15, light.brightness(3, 320, 5, -64, true));
        assertEquals(15, light.skyBrightness(3, 320, 5, -64, true));
        light.apply(new LightValues(new BitSet(), new BitSet(), bits(5), bits(5), List.of(), List.of()));
        assertEquals(0, light.brightness(3, 1, 5, -64, true));
        assertEquals(0, light.brightness(3, 1, 5, -64, false));
    }

    private static void set(byte[] layer, int x, int y, int z, int value) {
        int index = (y << 8) | (z << 4) | x;
        int shift = (index & 1) * 4;
        layer[index >> 1] = (byte) ((layer[index >> 1] & ~(15 << shift)) | (value << shift));
    }

    private static BitSet bits(int index) {
        var bits = new BitSet();
        bits.set(index);
        return bits;
    }
}
