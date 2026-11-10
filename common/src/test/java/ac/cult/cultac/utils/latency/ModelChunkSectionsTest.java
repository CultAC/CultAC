package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.latency.CompensatedWorld.ClientboundDimensionData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A pinned 26.3 chunk whose sections the codec decodes gives the world reader what the section bytes give it: the same
 * received sections, field by field, biomes, block entities, ticker candidates, heightmaps and pool counters, or the
 * same failure at the same stage, with the frame zeroed and released before the reader runs.
 */
class ModelChunkSectionsTest {
    private static final RegistryNames NAMES = ModelRegistryNamesState.defaults();
    private static final int STATES = DataTables.defaults().registry().stateCount();
    static final int BIOMES = NAMES.size("minecraft:worldgen/biome");
    private static final int SECTIONS = 24;
    private static final MethodHandle CHUNK = chunkReader();
    private static final AtomicInteger DIMENSIONS = new AtomicInteger();

    @Test
    void wellFormedColumnsReceiveTheSameSections() {
        var random = new SplittableRandom(0x26_03L);
        for (int round = 0; round < 300; round++) {
            var column = column(random);
            byte[] packet = packet(random, column, 3, true);
            int held = round % 3;
            var base = outcome(packet, column, held, false);
            var model = outcome(packet, column, held, true);
            assertEquals(base, model, "round " + round);
            assertTrue(!base.contains("failed") && !base.contains("trailing"), base);
        }
    }

    @Test
    void malformedColumnsFailAsTheSectionBytesFail() {
        var random = new SplittableRandom(0xBAD_C0L);
        int failures = 0;
        for (int round = 0; round < 1200; round++) {
            var column = column(random);
            int kind = round % 9;
            byte[] packet = switch (kind) {
                case 0 -> packet(random, cut(random, column), 3, true);
                case 1 -> packet(random, withByte(column, sectionStart(column, random), 4, (byte) 0x7F), 3, true);
                case 2 -> packet(random, withByte(column, sectionStart(column, random), 4, (byte) 0xF0), 3, true);
                case 3 -> packet(random, oversizedPalette(random, column), 3, true);
                case 4 -> packet(random, concat(column, new byte[1 + random.nextInt(9)]), 3, true);
                case 5 -> concat(packet(random, column, 3, true), new byte[1 + random.nextInt(4)]);
                case 6 -> packet(random, new byte[0], 3, random.nextBoolean());
                case 7 -> Arrays.copyOf(packet(random, column, 3, true), 9 + random.nextInt(column.length + 300));
                default -> packet(random, flipped(random, column), 3, true);
            };
            int held = round % 2;
            var base = outcome(packet, column, held, false);
            var model = outcome(packet, column, held, true);
            assertEquals(base, model, "round " + round + " kind " + kind);
            if (base.contains("failed") || base.contains("trailing")) failures++;
        }
        assertTrue(failures > 600, "malformed rounds that fail: " + failures);
    }

    @Test
    void aDecodeTheReaderNeverTakesCountsNothing() {
        var random = new SplittableRandom(0x5EE_D5L);
        for (int round = 0; round < 60; round++) {
            var column = column(random);
            var dimension = new ClientboundDimensionData("test:untaken-" + DIMENSIONS.incrementAndGet(), -64, 320);
            populate(dimension.dimension(), 3, column, 1 + round % 2);
            long[] before = counters();
            var chunk = read(Unpooled.wrappedBuffer(packet(random, column, 3, true)), dimension);
            assertNotNull(chunk.decodedSections(), "round " + round);
            assertEquals(Arrays.toString(before), Arrays.toString(counters()), "round " + round);
        }
    }

    @Test
    void aDimensionWithoutSectionsReceivesNone() {
        var random = new SplittableRandom(7);
        for (byte[] sections : new byte[][] {new byte[0], column(random)}) {
            byte[] packet = packet(random, sections, 3, true);
            var dimension = new ClientboundDimensionData("test:empty", 0, 0);
            assertEquals(stages(packet, null, dimension, false), stages(packet, null, dimension, true));
        }
    }

    @Test
    void onlyTheFirstTakeHandsOverTheDecodedStorage() {
        var random = new SplittableRandom(11);
        var column = column(random);
        var input = Unpooled.wrappedBuffer(packet(random, column, 3, true));
        var chunk = read(input, new ClientboundDimensionData("test:take", -64, 320));
        assertNotNull(chunk.decodedSections());
        var mixed = chunk.decodedSections().get(1);
        var taken = mixed.takeBlocks();
        assertNotSame(taken, mixed.takeBlocks());
        var stone = chunk.decodedSections().get(0).takeBlocks();
        assertTrue(stone.isCanonical());
        assertNotSame(SectionPool.canonicalAir(), CachedSection.received(stone, 4096, 0));
    }

    /** Both stages of the packet as base reads it (section bytes) or as the model wire now reads it. */
    private static String outcome(byte[] packet, byte[] column, int held, boolean model) {
        String dimension = "test:column-" + DIMENSIONS.incrementAndGet();
        Consumer<ClientboundDimensionData> pools = data -> populate(data.dimension(), 3, column, held);
        return stages(packet, pools, new ClientboundDimensionData(dimension, -64, 320), model);
    }

    private static String stages(
            byte[] packet,
            Consumer<ClientboundDimensionData> pools,
            ClientboundDimensionData dimension,
            boolean model) {
        if (pools != null) pools.accept(dimension);
        long[] before = counters();
        ByteBuf frame = PooledByteBufAllocator.DEFAULT.directBuffer(packet.length);
        frame.writeBytes(packet);
        WorldPackets.Chunk chunk;
        var result = new StringBuilder();
        try {
            chunk = read(frame, model ? dimension : null);
            result.append("chunk ")
                    .append(chunk.x())
                    .append(',')
                    .append(chunk.z())
                    .append(" read to ")
                    .append(frame.readerIndex())
                    .append(" heights ")
                    .append(Arrays.toString(chunk.motionBlocking()))
                    .append(" entities ")
                    .append(chunk.blockEntities())
                    .append(" tickers ")
                    .append(chunk.tickerCandidates())
                    .append('\n');
        } catch (RuntimeException failure) {
            chunk = null;
            result.append("codec failed ")
                    .append(failure.getClass().getName())
                    .append(": ")
                    .append(failure.getMessage());
        } finally {
            frame.setZero(0, frame.capacity());
            frame.release();
        }
        try {
            // The protocol runtime refuses a payload the codec does not read whole, before any handler.
            if (chunk != null && frame.readerIndex() != packet.length) result.append("trailing bytes");
            else if (chunk != null) received(chunk, dimension, result);
        } catch (RuntimeException failure) {
            result.append("reader failed ")
                    .append(failure.getClass().getName())
                    .append(": ")
                    .append(failure.getMessage());
        }
        long[] after = counters();
        result.append("\ncounters");
        for (int i = 0; i < after.length; i++) result.append(' ').append(after[i] - before[i]);
        return result.toString();
    }

    /** The section loop of the 26.x world reader, both branches. */
    private static void received(WorldPackets.Chunk packet, ClientboundDimensionData dimension, StringBuilder out) {
        var chunks = new CachedSection[dimension.sectionCount()];
        int[][] biomes = new int[chunks.length][];
        if (packet.decodedSections() != null) {
            if (packet.decodedSections().size() != chunks.length)
                throw new IllegalArgumentException("Chunk section count differs from the received dimension");
            for (int i = 0; i < chunks.length; i++) {
                var section = packet.decodedSections().get(i);
                chunks[i] = CachedSection.received(
                        section.takeBlocks(), section.nonEmptyBlockCount(), section.knownFluidCount());
                int biome = section.uniformBiome();
                biomes[i] = biome < 0 ? section.copyBiomes() : CachedChunk.uniformBiomes(biome);
            }
        } else {
            var bytes = Unpooled.wrappedBuffer(packet.sections());
            try {
                for (int i = 0; i < chunks.length; i++) {
                    int nonEmpty = bytes.readShort();
                    int fluids = bytes.readShort();
                    var pool = SectionPool.existing(dimension.dimension(), packet.x(), i, packet.z());
                    chunks[i] = CachedSection.received(PalettedSection.readBlocks(bytes, pool), nonEmpty, fluids);
                    biomes[i] = PalettedSection.readBiomeValues(bytes, BIOMES);
                }
            } finally {
                bytes.release();
            }
        }
        for (int i = 0; i < chunks.length; i++)
            out.append(i)
                    .append(chunks[i] == SectionPool.canonicalAir() ? " air " : " ")
                    .append(fields(chunks[i]))
                    .append(" biomes ")
                    .append(Arrays.toString(biomes[i]))
                    .append('\n');
        // Later writes to a received section reach no other section.
        for (var section : chunks) {
            if (section.isShared()) continue;
            section.setStateId(0, 1);
            section.setStateId(4095, 2);
        }
        for (int i = 0; i < chunks.length; i++)
            out.append(i).append(' ').append(fields(chunks[i])).append('\n');
    }

    /** Every instance field of {@code value}, sections and arrays included. */
    private static String fields(Object value) {
        if (value == null) return "null";
        if (value instanceof long[] longs) return Arrays.toString(longs);
        if (value instanceof int[] ints) return Arrays.toString(ints);
        if (!(value instanceof CachedSection) && !(value instanceof PalettedSection)) return String.valueOf(value);
        var out = new StringBuilder(value.getClass().getSimpleName()).append('{');
        for (Field field : value.getClass().getDeclaredFields()) {
            // The mutation clock reads System.nanoTime, which no two runs share.
            if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("lastMutatedNanos")) continue;
            field.setAccessible(true);
            try {
                out.append(field.getName())
                        .append('=')
                        .append(fields(field.get(value)))
                        .append(' ');
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        }
        return out.append('}').toString();
    }

    private static long[] counters() {
        return new long[] {
            SectionPool.wireOfferCounter.sum(),
            SectionPool.wireHitCounter.sum(),
            SectionPool.wireCandidateCounter.sum(),
            SectionPool.multiValueFullReadCounter.sum(),
            SectionPool.sharedCloneCounter.sum()
        };
    }

    /** Pools at the column's positions; {@code held} 1 holds its mixed sections, 2 also a section that differs. */
    static void populate(String dimension, int chunkX, byte[] column, int held) {
        if (held == 0) return;
        var input = Unpooled.wrappedBuffer(column);
        for (int i = 0; i < SECTIONS && input.isReadable(); i++) {
            var pool = SectionPool.forChunk(dimension, chunkX, i, -5);
            try {
                input.skipBytes(4);
                var blocks = PalettedSection.readBlocks(input);
                PalettedSection.readBiomes(input, BIOMES);
                if (blocks.singleValue() >= 0) continue;
                if (held == 2) {
                    var other = blocks.copy();
                    other.getAndSet(7, 1);
                    pool.internRetained(new CachedSection(other));
                }
                pool.internRetained(new CachedSection(blocks));
            } catch (RuntimeException malformed) {
                return;
            }
        }
    }

    static WorldPackets.Chunk read(ByteBuf input, ClientboundDimensionData dimension) {
        try {
            return (WorldPackets.Chunk) CHUNK.invokeExact(input, NAMES, dimension);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static MethodHandle chunkReader() {
        try {
            Class<?> values = Class.forName("ac.cult.cultac.network.codec.ModelWorldValues");
            return MethodHandles.privateLookupIn(values, MethodHandles.lookup())
                    .findStatic(
                            values,
                            "chunk",
                            MethodType.methodType(
                                    WorldPackets.Chunk.class,
                                    ByteBuf.class,
                                    RegistryNames.class,
                                    ClientboundDimensionData.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A packet body at column ({@code chunkX}, -5): heightmaps, {@code sections}, two block entities, empty light. */
    static byte[] packet(SplittableRandom random, byte[] sections, int chunkX, boolean entities) {
        ByteBuf out = Unpooled.buffer();
        try {
            out.writeInt(chunkX);
            out.writeInt(-5);
            Wire.writeVarInt(out, 2);
            for (int type : new int[] {1, 4}) {
                Wire.writeVarInt(out, type);
                Wire.writeVarInt(out, 37);
                for (int i = 0; i < 37; i++) out.writeLong(random.nextLong());
            }
            Wire.writeVarInt(out, sections.length);
            out.writeBytes(sections);
            Wire.writeVarInt(out, entities ? 2 : 0);
            if (entities) {
                for (String type : new String[] {"minecraft:potent_sulfur", "minecraft:chest"}) {
                    out.writeByte(random.nextInt(256));
                    out.writeShort(-64 + random.nextInt(384));
                    Wire.writeVarInt(out, NAMES.id("minecraft:block_entity_type", type));
                    out.writeByte(10);
                    out.writeByte(0);
                }
            }
            for (int i = 0; i < 6; i++) out.writeByte(0);
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    /**
     * 24 sections as a vanilla server writes them: air (some with nonzero counts), single-state, local palettes of
     * every width and global ones; biomes single, local and global.
     */
    private static byte[] column(SplittableRandom random) {
        ByteBuf out = Unpooled.buffer();
        try {
            for (int i = 0; i < SECTIONS; i++) {
                int[] states = new int[4096];
                int kind = random.nextInt(8);
                if (kind == 1 || kind == 2) Arrays.fill(states, 1 + random.nextInt(STATES - 1));
                else if (kind >= 3) {
                    int distinct = kind == 7 ? 257 + random.nextInt(200) : 2 + random.nextInt(1 << (kind + 1));
                    int[] mix = new int[distinct];
                    for (int j = 0; j < mix.length; j++) mix[j] = random.nextInt(STATES);
                    for (int j = 0; j < states.length; j++)
                        states[j] = j < mix.length
                                ? mix[j]
                                : random.nextInt(3) == 0 ? mix[random.nextInt(distinct)] : mix[0];
                }
                int nonEmpty = 0;
                for (int state : states) if (state != 0) nonEmpty++;
                if (kind == 0 && random.nextInt(4) == 0) nonEmpty = random.nextInt(5);
                out.writeShort(nonEmpty);
                out.writeShort(kind == 0 ? (random.nextInt(4) == 0 ? 1 : 0) : random.nextInt(4097));
                encode(out, states, 4, 8, STATES, kind == 6 && random.nextBoolean() ? 1 : 0);
                int[] biomes = new int[64];
                int biomeKind = random.nextInt(4);
                Arrays.fill(biomes, random.nextInt(BIOMES));
                if (biomeKind == 1) for (int j = 32; j < 64; j++) biomes[j] = random.nextInt(BIOMES);
                if (biomeKind == 2) for (int j = 0; j < 64; j++) biomes[j] = random.nextInt(BIOMES);
                encode(out, biomes, 1, 3, BIOMES, 0);
            }
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    /** A paletted container: first-seen palette at the minimal local width plus {@code wider}, or global. */
    private static void encode(ByteBuf out, int[] values, int minimumBits, int maximumBits, int registry, int wider) {
        var ids = new LinkedHashMap<Integer, Integer>();
        for (int value : values) ids.putIfAbsent(value, ids.size());
        int needed = 32 - Integer.numberOfLeadingZeros(ids.size() - 1);
        boolean global = needed > maximumBits;
        int bits = ids.size() == 1
                ? 0
                : global
                        ? 32 - Integer.numberOfLeadingZeros(registry - 1)
                        : Math.min(maximumBits, Math.max(minimumBits, needed) + wider);
        out.writeByte(bits);
        if (bits == 0) Wire.writeVarInt(out, values[0]);
        else if (!global) {
            Wire.writeVarInt(out, ids.size());
            for (int id : ids.keySet()) Wire.writeVarInt(out, id);
        }
        int perWord = bits == 0 ? 1 : 64 / bits;
        int words = bits == 0 ? 0 : (values.length + perWord - 1) / perWord;
        for (int word = 0, index = 0; word < words; word++) {
            long packed = 0;
            for (int slot = 0; slot < perWord && index < values.length; slot++, index++)
                packed |= (long) (global ? values[index] : ids.get(values[index])) << (slot * bits);
            out.writeLong(packed);
        }
    }

    /** The offset of a random section's block container within {@code column}. */
    private static int sectionStart(byte[] column, SplittableRandom random) {
        var input = Unpooled.wrappedBuffer(column);
        int target = random.nextInt(SECTIONS);
        for (int i = 0; i < target; i++) {
            input.skipBytes(4);
            PalettedSection.readBlocks(input);
            PalettedSection.readBiomes(input, BIOMES);
        }
        return input.readerIndex();
    }

    private static byte[] withByte(byte[] column, int sectionStart, int offset, byte value) {
        byte[] changed = column.clone();
        changed[sectionStart + offset] = value;
        return changed;
    }

    /** A section whose local palette claims more entries than its width or the bytes hold. */
    private static byte[] oversizedPalette(SplittableRandom random, byte[] column) {
        int start = sectionStart(column, random);
        var out = Unpooled.buffer();
        try {
            out.writeBytes(column, 0, start + 4);
            out.writeByte(4);
            Wire.writeVarInt(out, random.nextBoolean() ? 17 : 1 << 20);
            for (int i = 0; i < 17; i++) Wire.writeVarInt(out, i);
            out.writeBytes(column, start + 4, Math.min(column.length - start - 4, 64));
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    private static byte[] cut(SplittableRandom random, byte[] column) {
        return Arrays.copyOf(column, random.nextInt(column.length));
    }

    private static byte[] flipped(SplittableRandom random, byte[] column) {
        byte[] changed = column.clone();
        for (int i = 0; i < 1 + random.nextInt(4); i++)
            changed[random.nextInt(changed.length)] ^= (byte) (1 << random.nextInt(8));
        return changed;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
