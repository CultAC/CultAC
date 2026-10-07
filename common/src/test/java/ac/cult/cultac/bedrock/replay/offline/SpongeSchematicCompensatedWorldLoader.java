package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class SpongeSchematicCompensatedWorldLoader {
    private SpongeSchematicCompensatedWorldLoader() {}

    static LoadedSchematic load(Path schematic, CompensatedWorld world) throws IOException {
        NbtValue.Compound root = OfflineNbt.readCompressed(schematic);
        NbtValue.Compound schematicRoot =
                root.values().containsKey("Schematic") ? OfflineNbt.compound(root, "Schematic") : root;
        NbtValue.Compound blocks = OfflineNbt.compound(schematicRoot, "Blocks");
        int width = OfflineNbt.integer(schematicRoot, "Width");
        int height = OfflineNbt.integer(schematicRoot, "Height");
        int length = OfflineNbt.integer(schematicRoot, "Length");
        int[] offset = schematicRoot.values().get("Offset") instanceof NbtValue.PrimitiveArray array
                        && array.kind() == NbtValue.Kind.INT_ARRAY
                ? array.values().stream().mapToInt(Long::intValue).toArray()
                : new int[] {0, 0, 0};
        int originX = offset.length > 0 ? offset[0] : 0;
        int originY = offset.length > 1 ? offset[1] : 0;
        int originZ = offset.length > 2 ? offset[2] : 0;
        return load(blocks, width, height, length, originX, originY, originZ, world);
    }

    static LoadedSchematic load(OfflineBedrockReplayScenario scenario, CompensatedWorld world) throws IOException {
        NbtValue.Compound root = OfflineNbt.readCompressed(scenario.schematicPath());
        NbtValue.Compound schematicRoot =
                root.values().containsKey("Schematic") ? OfflineNbt.compound(root, "Schematic") : root;
        NbtValue.Compound blocks = OfflineNbt.compound(schematicRoot, "Blocks");
        int width = OfflineNbt.integer(schematicRoot, "Width");
        int height = OfflineNbt.integer(schematicRoot, "Height");
        int length = OfflineNbt.integer(schematicRoot, "Length");
        return load(blocks, width, height, length, scenario.minX(), scenario.minY(), scenario.minZ(), world);
    }

    private static LoadedSchematic load(
            NbtValue.Compound blocks,
            int width,
            int height,
            int length,
            int originX,
            int originY,
            int originZ,
            CompensatedWorld world)
            throws IOException {
        Map<Integer, Integer> palette = palette(OfflineNbt.compound(blocks, "Palette"));
        if (!(blocks.values().get("Data") instanceof NbtValue.PrimitiveArray array)
                || array.kind() != NbtValue.Kind.BYTE_ARRAY) throw new IOException("schematic has no Blocks/Data");
        byte[] data = new byte[array.values().size()];
        for (int i = 0; i < data.length; i++) data[i] = array.values().get(i).byteValue();
        VarIntReader reader = new VarIntReader(data);
        int applied = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int paletteId = reader.read();
                    int state = palette.getOrDefault(
                            paletteId,
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:air")
                                    .defaultState());
                    if (!DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
                        setBlock(world, originX + x, originY + y, originZ + z, state);
                        applied++;
                    }
                }
            }
        }
        return new LoadedSchematic(width, height, length, originX, originY, originZ, applied);
    }

    private static void setBlock(CompensatedWorld world, int x, int y, int z, int state) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long chunkKey = CompensatedWorld.chunkPositionToLong(chunkX, chunkZ);
        CompensatedWorld.CachedChunk chunk = world.chunks.get(chunkKey);
        if (chunk == null) {
            chunk = new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0);
            world.chunks.put(chunkKey, chunk);
        }
        int sectionIndex = y >> 4;
        if (sectionIndex < 0 || sectionIndex >= chunk.sectionCount()) {
            return;
        }
        CompensatedWorld.CachedSection section = chunk.getOrCreateSection(sectionIndex);
        if (section != null) {
            section.setStateId(CompensatedWorld.CachedChunk.index(x & 0xF, y & 0xF, z & 0xF), state);
        }
    }

    private static Map<Integer, Integer> palette(NbtValue.Compound paletteTag) throws IOException {
        Map<Integer, Integer> states = new HashMap<>();
        for (var entry : paletteTag.values().entrySet()) {
            if (!(entry.getValue() instanceof NbtValue.Numeric number))
                throw new IOException("Invalid schematic palette ID");
            int id = number.value().intValue();
            states.put(id, OfflineBlockStateParser.parse(entry.getKey()));
        }
        return states;
    }

    record LoadedSchematic(
            int width, int height, int length, int originX, int originY, int originZ, int nonAirBlocks) {}

    private static final class VarIntReader {
        private final byte[] data;
        private int cursor;

        private VarIntReader(byte[] data) {
            this.data = data;
        }

        private int read() throws IOException {
            int value = 0;
            int shift = 0;
            while (shift < 35) {
                if (cursor >= data.length) {
                    throw new IOException("schematic block data ended mid-varint");
                }
                int next = data[cursor++] & 0xFF;
                value |= (next & 0x7F) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new IOException("schematic block palette varint is too large");
        }
    }
}
