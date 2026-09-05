package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class SpongeSchematicCompensatedWorldLoader {
    private SpongeSchematicCompensatedWorldLoader() {
    }

    static LoadedSchematic load(Path schematic, CompensatedWorld world) throws IOException {
        bootstrapMinecraft();
        CompoundTag root = NbtIo.readCompressed(schematic, NbtAccounter.unlimitedHeap());
        CompoundTag schematicRoot = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        CompoundTag blocks = schematicRoot.getCompoundOrEmpty("Blocks");
        int width = schematicRoot.getShortOr("Width", (short) 0);
        int height = schematicRoot.getShortOr("Height", (short) 0);
        int length = schematicRoot.getShortOr("Length", (short) 0);
        int[] offset = schematicRoot.getIntArray("Offset").orElse(new int[]{0, 0, 0});
        int originX = offset.length > 0 ? offset[0] : 0;
        int originY = offset.length > 1 ? offset[1] : 0;
        int originZ = offset.length > 2 ? offset[2] : 0;
        return load(blocks, width, height, length, originX, originY, originZ, world);
    }

    static LoadedSchematic load(OfflineBedrockReplayScenario scenario, CompensatedWorld world) throws IOException {
        bootstrapMinecraft();
        CompoundTag root = NbtIo.readCompressed(scenario.schematicPath(), NbtAccounter.unlimitedHeap());
        CompoundTag schematicRoot = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        CompoundTag blocks = schematicRoot.getCompoundOrEmpty("Blocks");
        int width = schematicRoot.getShortOr("Width", (short) 0);
        int height = schematicRoot.getShortOr("Height", (short) 0);
        int length = schematicRoot.getShortOr("Length", (short) 0);
        return load(blocks, width, height, length, scenario.minX(), scenario.minY(), scenario.minZ(), world);
    }

    private static LoadedSchematic load(
            CompoundTag blocks,
            int width,
            int height,
            int length,
            int originX,
            int originY,
            int originZ,
            CompensatedWorld world
    ) throws IOException {
        Map<Integer, BlockState> palette = palette(blocks.getCompoundOrEmpty("Palette"));
        byte[] data = blocks.getByteArray("Data").orElseThrow(() -> new IOException("schematic has no Blocks/Data"));
        VarIntReader reader = new VarIntReader(data);
        int applied = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int paletteId = reader.read();
                    BlockState state = palette.getOrDefault(paletteId, Blocks.AIR.defaultBlockState());
                    if (!state.isAir()) {
                        setBlock(world, originX + x, originY + y, originZ + z, state);
                        applied++;
                    }
                }
            }
        }
        return new LoadedSchematic(width, height, length, originX, originY, originZ, applied);
    }

    private static void setBlock(CompensatedWorld world, int x, int y, int z, BlockState state) {
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
            section.setState(CompensatedWorld.CachedChunk.index(x & 0xF, y & 0xF, z & 0xF), state);
        }
    }

    private static Map<Integer, BlockState> palette(CompoundTag paletteTag) throws IOException {
        Map<Integer, BlockState> states = new HashMap<>();
        for (Map.Entry<String, Tag> entry : paletteTag.entrySet()) {
            int id = paletteTag.getInt(entry.getKey()).orElseThrow();
            states.put(id, OfflineBlockStateParser.parse(entry.getKey()));
        }
        return states;
    }

    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    record LoadedSchematic(int width, int height, int length, int originX, int originY, int originZ, int nonAirBlocks) {
    }

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
