package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.packet.LightValues;
import java.util.BitSet;
import java.util.List;

/** Packet-owned light values. Uniform sections retain a nibble instead of a 2 KiB array. */
final class CompensatedLight {
    private final Layer[] sky;
    private final Layer[] block;

    CompensatedLight(int sections) {
        sky = new Layer[sections + 2];
        block = new Layer[sections + 2];
    }

    void apply(LightValues values) {
        apply(sky, values.skyYMask(), values.emptySkyYMask(), values.skyUpdates());
        apply(block, values.blockYMask(), values.emptyBlockYMask(), values.blockUpdates());
    }

    private static void apply(Layer[] layers, BitSet present, BitSet empty, List<byte[]> updates) {
        var incoming = updates.iterator();
        for (int i = 0; i < layers.length; i++) {
            if (present.get(i)) layers[i] = compact(incoming.next());
            else if (empty.get(i)) layers[i] = new Layer(null, 0);
        }
    }

    private static Layer compact(byte[] data) {
        if (data.length == 2048) {
            int first = data[0] & 255;
            if ((first & 15) == first >>> 4) {
                boolean uniform = true;
                for (byte value : data)
                    if ((value & 255) != first) {
                        uniform = false;
                        break;
                    }
                if (uniform) return new Layer(null, first & 15);
            }
        }
        return new Layer(data, 0);
    }

    /** Section coordinates use x + 16*z + 256*y, with the low nibble first. */
    private record Layer(byte[] data, int uniform) {
        Layer {
            if (data != null && data.length != 2048)
                throw new IllegalArgumentException("Light layer must contain 2048 bytes");
        }

        int get(int x, int y, int z) {
            if (data == null) return uniform;
            int index = (y << 8) | (z << 4) | x;
            return (data[index >> 1] >>> ((index & 1) * 4)) & 15;
        }
    }

    int brightness(int x, int y, int z, int minY, boolean hasSkyLight) {
        int section = (y >> 4) - (minY >> 4) + 1;
        int blockValue = section >= 0 && section < block.length && block[section] != null
                ? block[section].get(x & 15, y & 15, z & 15)
                : 0;
        return Math.max(blockValue, skyBrightness(x, y, z, minY, hasSkyLight));
    }

    /** LevelReader.canSeeSky reads the sky layer, independently of emitted block light. */
    int skyBrightness(int x, int y, int z, int minY, boolean hasSkyLight) {
        if (!hasSkyLight) return 0;
        int section = (y >> 4) - (minY >> 4) + 1;
        // SkyLightSectionStorage reads the bottom row of the next available section
        // when a layer is missing, and reads 15 above the column's top light section.
        int skyValue = 15;
        for (int i = Math.max(0, section); i < sky.length; i++) {
            if (sky[i] != null) {
                skyValue = sky[i].get(x & 15, i == section ? y & 15 : 0, z & 15);
                break;
            }
        }
        return skyValue;
    }
}
