package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class OfflineBedrockReplayFixtureExporter {
    private static final String AIR = "minecraft:air";

    private OfflineBedrockReplayFixtureExporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException(
                    "usage: <world-region-dir> <scenario-dir> <minX> <minY> <minZ> <maxX> <maxY> <maxZ>");
        }
        Path regionDir = Path.of(args[0]);
        Path scenarioDir = Path.of(args[1]);
        int minX = Integer.parseInt(args[2]);
        int minY = Integer.parseInt(args[3]);
        int minZ = Integer.parseInt(args[4]);
        int maxX = Integer.parseInt(args[5]);
        int maxY = Integer.parseInt(args[6]);
        int maxZ = Integer.parseInt(args[7]);
        export(regionDir, scenarioDir, minX, minY, minZ, maxX, maxY, maxZ);
    }

    static void export(Path regionDir, Path scenarioDir, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
            throws IOException {
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int length = maxZ - minZ + 1;
        Map<String, Integer> palette = new LinkedHashMap<>();
        ByteArrayBuilder data = new ByteArrayBuilder(width * height * length);
        ChunkCache chunks = new ChunkCache(regionDir);
        try {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        String state = chunks.blockState(x, y, z);
                        int id = palette.computeIfAbsent(state, ignored -> palette.size());
                        data.writeVarInt(id);
                    }
                }
            }
        } finally {
            chunks.close();
        }

        var paletteValues = new LinkedHashMap<String, NbtValue>();
        palette.forEach((state, id) -> paletteValues.put(state, new NbtValue.Numeric(NbtValue.Kind.INT, id)));
        var blockData = new java.util.ArrayList<Long>();
        for (byte value : data.toByteArray()) blockData.add((long) value);
        var blocks = new NbtValue.Compound(Map.of(
                "Palette", new NbtValue.Compound(paletteValues),
                "Data", new NbtValue.PrimitiveArray(NbtValue.Kind.BYTE_ARRAY, blockData)));
        var schematic = new NbtValue.Compound(Map.of(
                "Width", new NbtValue.Numeric(NbtValue.Kind.SHORT, (short) width),
                "Height", new NbtValue.Numeric(NbtValue.Kind.SHORT, (short) height),
                "Length", new NbtValue.Numeric(NbtValue.Kind.SHORT, (short) length),
                "Offset",
                        new NbtValue.PrimitiveArray(
                                NbtValue.Kind.INT_ARRAY, List.of((long) minX, (long) minY, (long) minZ)),
                "Blocks", blocks));
        OfflineNbt.writeCompressed(schematic, scenarioDir.resolve("region.schem"));

        Properties properties = new Properties();
        properties.setProperty("scenario", scenarioDir.getFileName().toString());
        properties.setProperty("spawn-x", Double.toString(spawnCoordinate(minX, maxX)));
        properties.setProperty("spawn-y", Integer.toString(minY + 6));
        properties.setProperty("spawn-z", Double.toString(spawnCoordinate(minZ, maxZ)));
        properties.setProperty("min-x", Integer.toString(minX));
        properties.setProperty("min-y", Integer.toString(minY));
        properties.setProperty("min-z", Integer.toString(minZ));
        try (var writer = Files.newBufferedWriter(scenarioDir.resolve("fixture.properties"))) {
            properties.store(writer, "Generated from actest world for offline Bedrock replay");
        }
    }

    private static double spawnCoordinate(int min, int max) {
        return min + (max - min + 1) / 2.0D;
    }

    private static List<NbtValue> sequence(NbtValue.Compound parent, String key) {
        return parent.values().get(key) instanceof NbtValue.Sequence value ? value.values() : List.of();
    }

    private static String text(NbtValue.Compound parent, String key, String fallback) {
        return parent.values().get(key) instanceof NbtValue.Text value ? value.value() : fallback;
    }

    private static final class ChunkCache implements AutoCloseable {
        private final Path regionDir;
        private final Map<Long, NbtValue.Compound> chunks = new LinkedHashMap<>();
        private final Map<Long, RawRegionFile> regions = new LinkedHashMap<>();

        private ChunkCache(Path regionDir) {
            this.regionDir = regionDir;
        }

        private String blockState(int x, int y, int z) throws IOException {
            NbtValue.Compound chunk = chunk(x >> 4, z >> 4);
            if (chunk == null) {
                return AIR;
            }
            int sectionY = Math.floorDiv(y, 16);
            NbtValue.Compound section = section(chunk, sectionY);
            if (section == null) {
                return AIR;
            }
            NbtValue.Compound blockStates = OfflineNbt.compound(section, "block_states");
            var palette = sequence(blockStates, "palette");
            if (palette.isEmpty()) {
                return AIR;
            }
            int localIndex = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            int paletteIndex = paletteIndex(blockStates, palette.size(), localIndex);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                return AIR;
            }
            return stateString(
                    palette.get(paletteIndex) instanceof NbtValue.Compound state
                            ? state
                            : new NbtValue.Compound(Map.of()));
        }

        private NbtValue.Compound chunk(int chunkX, int chunkZ) throws IOException {
            long key = CompensatedWorld.chunkPositionToLong(chunkX, chunkZ);
            if (chunks.containsKey(key)) {
                return chunks.get(key);
            }
            RawRegionFile region = region(chunkX >> 5, chunkZ >> 5);
            NbtValue.Compound chunk = region.read(chunkX, chunkZ);
            chunks.put(key, chunk);
            return chunk;
        }

        private RawRegionFile region(int regionX, int regionZ) throws IOException {
            long key = CompensatedWorld.chunkPositionToLong(regionX, regionZ);
            RawRegionFile region = regions.get(key);
            if (region != null) {
                return region;
            }
            Path path = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
            region = new RawRegionFile(path);
            regions.put(key, region);
            return region;
        }

        private static NbtValue.Compound section(NbtValue.Compound chunk, int sectionY) {
            for (var tag : sequence(chunk, "sections")) {
                if (tag instanceof NbtValue.Compound section
                        && section.values().get("Y") instanceof NbtValue.Numeric number
                        && number.value().byteValue() == (byte) sectionY) {
                    return section;
                }
            }
            return null;
        }

        private static int paletteIndex(NbtValue.Compound blockStates, int paletteSize, int localIndex) {
            long[] packed = blockStates.values().get("data") instanceof NbtValue.PrimitiveArray array
                            && array.kind() == NbtValue.Kind.LONG_ARRAY
                    ? array.values().stream().mapToLong(Long::longValue).toArray()
                    : null;
            if (packed == null || packed.length == 0 || paletteSize <= 1) {
                return 0;
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            int valuesPerLong = 64 / bits;
            int longIndex = localIndex / valuesPerLong;
            int bitOffset = (localIndex % valuesPerLong) * bits;
            if (longIndex < 0 || longIndex >= packed.length) {
                return 0;
            }
            return (int) ((packed[longIndex] >>> bitOffset) & ((1L << bits) - 1L));
        }

        private static String stateString(NbtValue.Compound state) {
            String name = text(state, "Name", AIR);
            NbtValue.Compound properties = OfflineNbt.compound(state, "Properties");
            if (properties.values().isEmpty()) {
                return name;
            }
            StringBuilder builder = new StringBuilder(name).append('[');
            boolean first = true;
            for (String key : properties.values().keySet().stream().sorted().toList()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(key).append('=').append(text(properties, key, ""));
            }
            return builder.append(']').toString();
        }

        @Override
        public void close() throws IOException {
            IOException thrown = null;
            for (RawRegionFile region : regions.values()) {
                try {
                    region.close();
                } catch (IOException exception) {
                    if (thrown == null) {
                        thrown = exception;
                    } else {
                        thrown.addSuppressed(exception);
                    }
                }
            }
            if (thrown != null) {
                throw thrown;
            }
        }
    }

    private static final class RawRegionFile implements AutoCloseable {
        private static final int SECTOR_BYTES = 4096;
        private final RandomAccessFile file;

        private RawRegionFile(Path path) throws IOException {
            this.file = new RandomAccessFile(path.toFile(), "r");
        }

        private NbtValue.Compound read(int chunkX, int chunkZ) throws IOException {
            int index = (chunkX & 31) + (chunkZ & 31) * 32;
            file.seek(index * 4L);
            int location = file.readInt();
            int sectorOffset = location >>> 8;
            int sectorCount = location & 0xFF;
            if (sectorOffset == 0 || sectorCount == 0) {
                return null;
            }
            long byteOffset = (long) sectorOffset * SECTOR_BYTES;
            file.seek(byteOffset);
            int length = file.readInt();
            if (length <= 1 || length > sectorCount * SECTOR_BYTES) {
                throw new IOException("invalid chunk length " + length + " in [" + chunkX + ", " + chunkZ + "]");
            }
            int compression = file.readUnsignedByte();
            byte[] payload = new byte[length - 1];
            file.readFully(payload);
            try (DataInputStream input = new DataInputStream(decompressed(compression, payload))) {
                return OfflineNbt.read(input);
            }
        }

        private static InputStream decompressed(int compression, byte[] payload) throws IOException {
            InputStream input = new BufferedInputStream(new ByteArrayInputStream(payload));
            return switch (compression) {
                case 1 -> new GZIPInputStream(input);
                case 2 -> new InflaterInputStream(input);
                case 3 -> input;
                default -> throw new IOException("unsupported region compression " + compression);
            };
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    private static final class ByteArrayBuilder {
        private byte[] data;
        private int size;

        private ByteArrayBuilder(int initialCapacity) {
            this.data = new byte[Math.max(32, initialCapacity)];
        }

        private void writeVarInt(int value) {
            do {
                int part = value & 0x7F;
                value >>>= 7;
                if (value != 0) {
                    part |= 0x80;
                }
                write((byte) part);
            } while (value != 0);
        }

        private void write(byte value) {
            if (size == data.length) {
                byte[] expanded = new byte[data.length * 2];
                System.arraycopy(data, 0, expanded, 0, data.length);
                data = expanded;
            }
            data[size++] = value;
        }

        private byte[] toByteArray() {
            byte[] result = new byte[size];
            System.arraycopy(data, 0, result, 0, size);
            return result;
        }
    }
}
