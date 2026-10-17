package ac.cult.cultac.cloud.capture;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An overworld of simulation-world sections with model block states, the same for the same column every time:
 * deepslate and stone with ores and caves, dirt and grass on rolling ground, water in the hollows, trees, air above.
 * The columns it has loaded are the simulation's; the player's position is where a test puts it.
 */
final class SyntheticTerrain implements Sections.Terrain {
    static final int MIN_Y = -64;
    static final int HEIGHT = 384;
    static final int SECTIONS = HEIGHT / 16;

    private static final int SEA_LEVEL = 62;
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();

    // Default states in the model block-state registry.
    static final int AIR = state("minecraft:air");
    static final int ANDESITE = state("minecraft:andesite");
    static final int BEDROCK = state("minecraft:bedrock");
    static final int COAL_ORE = state("minecraft:coal_ore");
    static final int DEEPSLATE = state("minecraft:deepslate");
    static final int DEEPSLATE_DIAMOND_ORE = state("minecraft:deepslate_diamond_ore");
    static final int DEEPSLATE_IRON_ORE = state("minecraft:deepslate_iron_ore");
    static final int DIAMOND_BLOCK = state("minecraft:diamond_block");
    static final int DIRT = state("minecraft:dirt");
    static final int GOLD_BLOCK = state("minecraft:gold_block");
    static final int GRASS_BLOCK = state("minecraft:grass_block");
    static final int GRAVEL = state("minecraft:gravel");
    static final int IRON_ORE = state("minecraft:iron_ore");
    static final int OAK_LEAVES = state("minecraft:oak_leaves");
    static final int OAK_LOG = state("minecraft:oak_log");
    static final int SAND = state("minecraft:sand");
    static final int STONE = state("minecraft:stone");
    static final int TUFF = state("minecraft:tuff");
    static final int WATER = state("minecraft:water");

    private final Map<Long, CachedSection[]> columns = new HashMap<>();
    private final Set<Long> loaded = new HashSet<>();
    private long position;

    /** Puts the player at block {@code x}, {@code y}, {@code z}. */
    void at(double x, double y, double z) {
        position = Sections.pack((int) Math.floor(x) >> 4, (int) Math.floor(y) >> 4, (int) Math.floor(z) >> 4);
    }

    void load(int x, int z) {
        column(x, z);
        loaded.add(pack(x, z));
    }

    void unload(int x, int z) {
        loaded.remove(pack(x, z));
    }

    boolean loaded(int x, int z) {
        return loaded.contains(pack(x, z));
    }

    /** A column's chunk x and z in one long. */
    static long pack(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    static int state(String block) {
        return REGISTRY.block(block).defaultState();
    }

    static boolean isAir(int state) {
        return REGISTRY.facts(state).has(StateFacts.AIR);
    }

    /** Sets a block as the simulation would; returns its state's id in the model registry. */
    int set(int x, int y, int z, int state) {
        CachedSection[] column = column(x >> 4, z >> 4);
        int index = (y - MIN_Y) >> 4;
        if (column[index] == null) column[index] = CachedSection.createAirSection();
        column[index].setStateId(CachedChunk.index(x & 15, (y - MIN_Y) & 15, z & 15), state);
        return state;
    }

    int get(int x, int y, int z) {
        CachedSection section = column(x >> 4, z >> 4)[(y - MIN_Y) >> 4];
        return section == null ? AIR : section.getStateId(CachedChunk.index(x & 15, (y - MIN_Y) & 15, z & 15));
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public int minSection() {
        return MIN_Y >> 4;
    }

    @Override
    public int maxSection() {
        return ((MIN_Y + HEIGHT) >> 4) - 1;
    }

    @Override
    public byte[] blocks(int x, int y, int z) {
        if (!loaded(x, z)) return null;
        int index = y - minSection();
        if (index < 0 || index >= SECTIONS) return null;
        return SectionBytes.of(column(x, z)[index]);
    }

    /**
     * A {@code level_chunk_with_light} of protocol 777 for the column, as the server writes it without light: two
     * heightmaps of 37 longs, each section's non-air and fluid counts, block states and a single-value biome, no block
     * entities.
     */
    byte[] levelChunk(int id, int x, int z) {
        CachedSection[] column = column(x, z);
        Writer data = new Writer();
        for (CachedSection section : column) {
            data.int16(count(section, false));
            data.int16(count(section, true));
            data.bytes(SectionBytes.of(section));
            data.u8(0);
            data.varInt(1);
        }
        Writer out = new Writer();
        out.varInt(id);
        out.int32(x);
        out.int32(z);
        out.varInt(2);
        for (int type : new int[] {1, 4}) {
            out.varInt(type);
            out.varInt(37);
            for (int i = 0; i < 37; i++) out.int64(hash(x, type, z, i));
        }
        byte[] sections = data.toByteArray();
        out.varInt(sections.length);
        out.bytes(sections);
        out.varInt(0);
        for (int i = 0; i < 6; i++) out.u8(0);
        return out.toByteArray();
    }

    /** A section's non-air blocks, or its blocks holding a fluid. */
    static int count(CachedSection section, boolean fluids) {
        if (section == null) return 0;
        int count = 0;
        for (int i = 0; i < 4096; i++) {
            int state = section.getStateId(i);
            if (fluids ? !"minecraft:empty".equals(REGISTRY.facts(state).fluid()) : !isAir(state)) count++;
        }
        return count;
    }

    /** The column's sections, generated on first use. */
    CachedSection[] sections(int x, int z) {
        return column(x, z);
    }

    private CachedSection[] column(int cx, int cz) {
        return columns.computeIfAbsent(pack(cx, cz), ignored -> generate(cx, cz));
    }

    private static CachedSection[] generate(int cx, int cz) {
        CachedSection[] sections = new CachedSection[SECTIONS];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = cx * 16 + lx;
                int z = cz * 16 + lz;
                int ground = surface(x, z);
                for (int y = MIN_Y; y <= Math.max(ground, SEA_LEVEL); y++) {
                    int state = state(x, y, z, ground);
                    if (!isAir(state)) put(sections, lx, y, lz, state);
                }
                if (ground > SEA_LEVEL && Math.floorMod(hash(x, 7, z, 0), 97) == 0) tree(sections, lx, ground + 1, lz);
            }
        }
        return sections;
    }

    private static int state(int x, int y, int z, int ground) {
        long roll = Math.floorMod(hash(x, y, z, 1), 1000);
        if (y == MIN_Y) return BEDROCK;
        if (y > ground) return WATER;
        if (y == ground) return ground < SEA_LEVEL ? SAND : GRASS_BLOCK;
        if (y > ground - 4) return DIRT;
        if (y < ground - 8 && roll < 40) return AIR;
        if (y < 0) {
            if (roll < 45) return DEEPSLATE_IRON_ORE;
            if (roll < 48) return DEEPSLATE_DIAMOND_ORE;
            if (roll < 60) return TUFF;
            return DEEPSLATE;
        }
        if (roll < 52) return COAL_ORE;
        if (roll < 60) return IRON_ORE;
        if (roll < 75) return ANDESITE;
        if (roll < 90) return GRAVEL;
        return STONE;
    }

    private static void tree(CachedSection[] sections, int lx, int base, int lz) {
        for (int y = base; y < base + 5; y++) put(sections, lx, y, lz, OAK_LOG);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = lx + dx;
                int z = lz + dz;
                if (x < 0 || x > 15 || z < 0 || z > 15 || dx == 0 && dz == 0) continue;
                for (int y = base + 3; y < base + 6; y++) {
                    put(sections, x, y, z, OAK_LEAVES);
                }
            }
        }
    }

    private static void put(CachedSection[] sections, int lx, int y, int lz, int state) {
        int index = (y - MIN_Y) >> 4;
        if (index < 0 || index >= SECTIONS) return;
        if (sections[index] == null) sections[index] = CachedSection.createAirSection();
        sections[index].setStateId(CachedChunk.index(lx, (y - MIN_Y) & 15, lz), state);
    }

    /** Rolling ground from 56 to 76. */
    private static int surface(int x, int z) {
        double height = 66 + 6 * Math.sin(x / 23.0) * Math.cos(z / 31.0) + 4 * Math.sin((x + z) / 11.0);
        return (int) Math.floor(height);
    }

    private static long hash(int x, int y, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L
                ^ y * 0xC2B2AE3D27D4EB4FL
                ^ z * 0x165667B19E3779F9L
                ^ salt * 0x27D4EB2F165667C5L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ h >>> 32;
    }

    /** A big-endian writer with Minecraft's varints. */
    private static final class Writer extends ByteArrayOutputStream {
        void u8(int value) {
            write(value);
        }

        void int16(int value) {
            write(value >>> 8);
            write(value);
        }

        void int32(int value) {
            int16(value >>> 16);
            int16(value);
        }

        void int64(long value) {
            int32((int) (value >>> 32));
            int32((int) value);
        }

        void varInt(int value) {
            while ((value & ~0x7F) != 0) {
                write(value & 0x7F | 0x80);
                value >>>= 7;
            }
            write(value);
        }

        void bytes(byte[] bytes) {
            write(bytes, 0, bytes.length);
        }
    }
}
