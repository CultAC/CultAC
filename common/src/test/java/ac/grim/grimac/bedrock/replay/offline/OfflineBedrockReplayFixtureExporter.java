package ac.grim.grimac.bedrock.replay.offline;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

final class OfflineBedrockReplayFixtureExporter {
    private static final String AIR = "minecraft:air";

    private OfflineBedrockReplayFixtureExporter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException("usage: <world-region-dir> <scenario-dir> <minX> <minY> <minZ> <maxX> <maxY> <maxZ>");
        }
        bootstrapMinecraft();
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

    static void export(
            Path regionDir,
            Path scenarioDir,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) throws IOException {
        bootstrapMinecraft();
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

        CompoundTag blocks = new CompoundTag();
        CompoundTag paletteTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : palette.entrySet()) {
            paletteTag.putInt(entry.getKey(), entry.getValue());
        }
        blocks.put("Palette", paletteTag);
        blocks.putByteArray("Data", data.toByteArray());

        CompoundTag schematic = new CompoundTag();
        schematic.putShort("Width", (short) width);
        schematic.putShort("Height", (short) height);
        schematic.putShort("Length", (short) length);
        schematic.putIntArray("Offset", new int[]{minX, minY, minZ});
        schematic.put("Blocks", blocks);
        NbtIo.writeCompressed(schematic, scenarioDir.resolve("region.schem"));

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

    private static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    private static final class ChunkCache implements AutoCloseable {
        private final Path regionDir;
        private final Map<Long, CompoundTag> chunks = new LinkedHashMap<>();
        private final Map<Long, RawRegionFile> regions = new LinkedHashMap<>();

        private ChunkCache(Path regionDir) {
            this.regionDir = regionDir;
        }

        private String blockState(int x, int y, int z) throws IOException {
            CompoundTag chunk = chunk(x >> 4, z >> 4);
            if (chunk == null) {
                return AIR;
            }
            int sectionY = Math.floorDiv(y, 16);
            CompoundTag section = section(chunk, sectionY);
            if (section == null) {
                return AIR;
            }
            CompoundTag blockStates = section.getCompoundOrEmpty("block_states");
            ListTag palette = blockStates.getListOrEmpty("palette");
            if (palette.isEmpty()) {
                return AIR;
            }
            int localIndex = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            int paletteIndex = paletteIndex(blockStates, palette.size(), localIndex);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                return AIR;
            }
            return stateString(palette.getCompoundOrEmpty(paletteIndex));
        }

        private CompoundTag chunk(int chunkX, int chunkZ) throws IOException {
            long key = ChunkPos.pack(chunkX, chunkZ);
            if (chunks.containsKey(key)) {
                return chunks.get(key);
            }
            RawRegionFile region = region(chunkX >> 5, chunkZ >> 5);
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            CompoundTag chunk = region.read(pos);
            chunks.put(key, chunk);
            return chunk;
        }

        private RawRegionFile region(int regionX, int regionZ) throws IOException {
            long key = ChunkPos.pack(regionX, regionZ);
            RawRegionFile region = regions.get(key);
            if (region != null) {
                return region;
            }
            Path path = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
            region = new RawRegionFile(path);
            regions.put(key, region);
            return region;
        }

        private static CompoundTag section(CompoundTag chunk, int sectionY) {
            for (Tag tag : chunk.getListOrEmpty("sections")) {
                CompoundTag section = tag.asCompound().orElse(null);
                if (section != null && section.getByteOr("Y", (byte) 127) == (byte) sectionY) {
                    return section;
                }
            }
            return null;
        }

        private static int paletteIndex(CompoundTag blockStates, int paletteSize, int localIndex) {
            long[] packed = blockStates.getLongArray("data").orElse(null);
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

        private static String stateString(CompoundTag state) {
            String name = state.getStringOr("Name", AIR);
            CompoundTag properties = state.getCompoundOrEmpty("Properties");
            if (properties.isEmpty()) {
                return name;
            }
            StringBuilder builder = new StringBuilder(name).append('[');
            boolean first = true;
            for (String key : properties.keySet().stream().sorted().toList()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(key).append('=').append(properties.getStringOr(key, ""));
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

        private CompoundTag read(ChunkPos pos) throws IOException {
            int index = (pos.x() & 31) + (pos.z() & 31) * 32;
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
                throw new IOException("invalid chunk length " + length + " in " + pos);
            }
            int compression = file.readUnsignedByte();
            byte[] payload = new byte[length - 1];
            file.readFully(payload);
            try (DataInputStream input = new DataInputStream(decompressed(compression, payload))) {
                return NbtIo.read(input, NbtAccounter.unlimitedHeap());
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
