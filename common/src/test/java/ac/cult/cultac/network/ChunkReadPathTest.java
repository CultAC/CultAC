package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.FrameLease;
import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.SectionPool;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A chunk frame read through the connection's packet values takes the path the section bytes took: only the encoder's
 * lent read for an attached player in a resolved dimension decodes its sections, and the world reader then builds the
 * same model, pool counts included. A read without the lease, in an unresolved dimension, without a player, the packet
 * logger's read and a translated connection's read give the section bytes or the translated sections as before.
 */
class ChunkReadPathTest {
    private static final String BIOME = "minecraft:worldgen/biome";
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int BIOMES = ModelRegistryNamesState.defaults().size(BIOME);
    private static final ProtocolVersion OLDER = ProtocolVersion.V1_21_11;
    private static final AtomicInteger LEVELS = new AtomicInteger();

    private enum Read {
        LENT,
        UNLENT,
        LOGGER
    }

    @Test
    void onlyTheLentReadOfAnAttachedResolvedPlayerDecodes() throws Exception {
        for (long seed = 1; seed <= 4; seed++) {
            var lent = native26(seed, Read.LENT, true, false);
            var unlent = native26(seed, Read.UNLENT, true, false);
            assertTrue(lent.decoded() && lent.held(), "the lent read decodes natively");
            assertTrue(!unlent.decoded(), "a read without the lease keeps the section bytes");
            assertEquals(unlent.model(), lent.model(), "seed " + seed);
            assertTrue(lent.model().contains("hits 1"), "the resent column shares pooled storage: " + lent.model());

            var logger = native26(seed, Read.LOGGER, true, false);
            assertTrue(!logger.decoded(), "the packet logger's read keeps the section bytes");
            assertEquals(unlent.value(), logger.value());
            assertArrayEquals(unlent.bytes(), logger.bytes());

            for (boolean player : new boolean[] {true, false}) {
                boolean unresolved = player;
                var lentOther = native26(seed, Read.LENT, player, unresolved);
                var unlentOther = native26(seed, Read.UNLENT, player, unresolved);
                assertTrue(!lentOther.decoded() && !unlentOther.decoded(), "player " + player);
                assertEquals(unlentOther.value(), lentOther.value());
                assertArrayEquals(unlentOther.bytes(), lentOther.bytes());
                assertArrayEquals(unlent.bytes(), lentOther.bytes());
            }
        }
    }

    @Test
    void aTranslatedConnectionReadsTheSameWithOrWithoutTheLease() throws Exception {
        var lent = translated(Read.LENT);
        var unlent = translated(Read.UNLENT);
        assertTrue(lent.decoded() && !lent.held(), "translated sections are copied by the reader");
        assertTrue(unlent.decoded() && !unlent.held());
        assertEquals(unlent.value(), lent.value());
        assertEquals(unlent.model(), lent.model());
    }

    /** The value read, and the model and pool counts after the world reader applies it twice when it is received. */
    private record Outcome(boolean decoded, boolean held, String value, byte[] bytes, String model) {}

    private static Outcome native26(long seed, Read read, boolean player, boolean unresolved) throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, player)) {
            String level = "test:read-path-" + LEVELS.incrementAndGet();
            if (player) {
                var world = fixture.player.compensatedWorld;
                world.setLastClientboundDimension(
                        level,
                        fixture.player
                                .getWorldRegistries()
                                .dimension("minecraft:overworld")
                                .dimension());
                if (unresolved) world.setLastClientboundUnresolved(level);
            }
            int sections = 24;
            byte[] body = nativeBody(new SplittableRandom(seed), sections);
            return outcome(fixture, body, read, player && !unresolved);
        }
    }

    private static Outcome translated(Read read) throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(OLDER)) {
            fixture.phase(ConnectionPhase.CONFIGURATION);
            var registry = frame(fixture, ConnectionPhase.CONFIGURATION, "registry_data");
            identifier(registry, BIOME);
            Wire.writeVarInt(registry, 3);
            for (var name : List.of("minecraft:plains", "minecraft:desert", "minecraft:forest")) {
                identifier(registry, name);
                registry.writeBoolean(false);
            }
            try {
                fixture.connection
                        .packets()
                        .read(
                                ConnectionPhase.CONFIGURATION,
                                PacketDirection.CLIENTBOUND,
                                registry,
                                fixture.connection,
                                type -> true);
            } finally {
                registry.release();
            }
            fixture.phase(ConnectionPhase.PLAY);
            var world = fixture.player.compensatedWorld;
            world.setLastClientboundDimension(
                    "test:read-path-" + LEVELS.incrementAndGet(),
                    fixture.player
                            .getWorldRegistries()
                            .dimension("minecraft:overworld")
                            .dimension());
            return outcome(fixture, olderBody(world.getLastClientboundSectionCount()), read, true);
        }
    }

    private static Outcome outcome(RecordReceiveFixture fixture, byte[] body, Read read, boolean applies) {
        long[] before = counters();
        var first = readChunk(fixture, body, read);
        boolean decoded = first.decodedSections() != null;
        boolean held = decoded && first.decodedSections().stream().allMatch(ChunkReadPathTest::holdsCounts);
        var chunk = first;
        var model = new StringBuilder();
        if (applies) {
            model.append(apply(fixture, first));
            // The same column again matches the sections the first one pooled.
            chunk = readChunk(fixture, body, read);
            model.append(apply(fixture, chunk));
        }
        long[] after = counters();
        model.append("\ncounters");
        for (int i = 0; i < after.length; i++) model.append(' ').append(after[i] - before[i]);
        model.append("\nhits ").append(after[1] - before[1] > 0 ? 1 : 0);
        var value = "chunk " + first.x() + ',' + first.z() + " heights " + Arrays.toString(first.motionBlocking())
                + " entities " + first.blockEntities() + " tickers " + first.tickerCandidates() + " sections "
                + (decoded ? first.decodedSections().size() : -1);
        return new Outcome(
                decoded,
                held,
                value,
                decoded ? null : ByteBufUtil.getBytes(Unpooled.wrappedBuffer(first.sections())),
                model.toString());
    }

    /** The chunk value of one frame read as {@code read} reads it; the frame is zeroed once its lease ends. */
    private static WorldPackets.Chunk readChunk(RecordReceiveFixture fixture, byte[] body, Read read) {
        var connection = fixture.connection;
        ByteBuf frame =
                frame(fixture, ConnectionPhase.PLAY, "level_chunk_with_light").writeBytes(body);
        var lender = connection.frameLender();
        try {
            FrameLease lease = read == Read.UNLENT ? null : lender.open(frame);
            Object value;
            try {
                value = connection
                        .packets()
                        .readOne(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, frame, connection, type -> true);
            } finally {
                if (lease != null) lender.close(lease);
            }
            if (read == Read.LOGGER) {
                // The encoder's observer reads the frame after the dispatch read closes the lease.
                var values = connection
                        .packets()
                        .observe(
                                ConnectionPhase.PLAY,
                                PacketDirection.CLIENTBOUND,
                                frame,
                                connection,
                                type -> !type.isOpaque() || type.codec().readsEntirePayload());
                assertEquals(1, values.size());
                value = values.getFirst().packet();
            }
            if (lease != null) lender.end(lease);
            assertNotNull(value);
            return (WorldPackets.Chunk) value;
        } finally {
            frame.setZero(0, frame.capacity());
            frame.release();
        }
    }

    /** The 26.x world reader on {@code chunk}, its pings answered; the column the model then holds. */
    @SuppressWarnings("unchecked")
    private static String apply(RecordReceiveFixture fixture, WorldPackets.Chunk chunk) {
        var player = fixture.player;
        var type = (PacketType<WorldPackets.Chunk>)
                fixture.registrar.runtime().typeForKey("clientbound.level_chunk_with_light");
        var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, type, chunk, false);
        new PacketWorldReaderTwentySix().handleMapChunk(player, event, chunk);
        event.tasksAfter().forEach(Runnable::run);
        for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
        var column = player.compensatedWorld.getChunk(chunk.x(), chunk.z());
        assertNotNull(column);
        var out = new StringBuilder();
        dump(column, out, Collections.newSetFromMap(new IdentityHashMap<>()));
        return out.append('\n').toString();
    }

    private static boolean holdsCounts(SectionSnapshot section) {
        try {
            Field field = SectionSnapshot.class.getDeclaredField("heldCounts");
            field.setAccessible(true);
            return field.getInt(section) >= 0;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Every instance field reachable through anticheat objects and arrays; the mutation clock is left out. */
    private static void dump(Object value, StringBuilder out, Set<Object> seen) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof long[] longs) {
            out.append(Arrays.toString(longs));
            return;
        }
        if (value instanceof int[] ints) {
            out.append(Arrays.toString(ints));
            return;
        }
        if (value instanceof byte[] bytes) {
            out.append(Arrays.toString(bytes));
            return;
        }
        if (value instanceof Object[] array) {
            out.append('[');
            for (Object element : array) {
                dump(element, out, seen);
                out.append(',');
            }
            out.append(']');
            return;
        }
        Class<?> type = value.getClass();
        if (!type.getName().startsWith("ac.cult.") || type.isEnum() || type.isRecord()) {
            out.append(value);
            return;
        }
        if (!seen.add(value)) {
            out.append("<seen ").append(type.getSimpleName()).append('>');
            return;
        }
        out.append(type.getSimpleName()).append('{');
        for (Class<?> declaring = type; declaring != Object.class; declaring = declaring.getSuperclass()) {
            for (Field field : declaring.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("lastMutatedNanos")) continue;
                field.setAccessible(true);
                out.append(field.getName()).append('=');
                try {
                    dump(field.get(value), out, seen);
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException(failure);
                }
                out.append(' ');
            }
        }
        out.append('}');
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

    /**
     * A pinned 26.3 chunk body at (2, -3): heightmaps, {@code sections} sections of air, single states, local palettes
     * of widths 4-8 and global ones with single, local and global biomes, a ticker candidate and a chest, empty light.
     */
    private static byte[] nativeBody(SplittableRandom random, int sections) {
        var names = ModelRegistryNamesState.defaults();
        ByteBuf column = Unpooled.buffer();
        ByteBuf out = Unpooled.buffer();
        try {
            for (int i = 0; i < sections; i++) {
                int[] states = new int[4096];
                int kind = i % 7;
                if (kind == 1) Arrays.fill(states, 1 + random.nextInt(STATES - 1));
                else if (kind >= 2) {
                    int distinct = kind == 6 ? 300 : 2 + random.nextInt(1 << (kind + 1));
                    int[] mix = new int[distinct];
                    for (int j = 0; j < distinct; j++) mix[j] = random.nextInt(STATES);
                    for (int j = 0; j < states.length; j++)
                        states[j] = j < distinct ? mix[j] : mix[random.nextInt(distinct)];
                }
                int nonEmpty = 0;
                for (int state : states) if (state != 0) nonEmpty++;
                column.writeShort(nonEmpty);
                column.writeShort(kind == 0 ? 0 : random.nextInt(4097));
                container(column, states, 4, 8, STATES);
                int[] biomes = new int[64];
                Arrays.fill(biomes, random.nextInt(BIOMES));
                if (i % 3 == 1) for (int j = 32; j < 64; j++) biomes[j] = random.nextInt(BIOMES);
                if (i % 3 == 2) for (int j = 0; j < 64; j++) biomes[j] = random.nextInt(BIOMES);
                container(column, biomes, 1, 3, BIOMES);
            }
            out.writeInt(2);
            out.writeInt(-3);
            Wire.writeVarInt(out, 1);
            Wire.writeVarInt(out, 4);
            Wire.writeVarInt(out, 37);
            for (int i = 0; i < 37; i++) out.writeLong(random.nextLong());
            Wire.writeVarInt(out, column.readableBytes());
            out.writeBytes(column);
            Wire.writeVarInt(out, 2);
            for (String type : new String[] {"minecraft:potent_sulfur", "minecraft:chest"}) {
                out.writeByte(random.nextInt(256));
                out.writeShort(-64 + random.nextInt(384));
                Wire.writeVarInt(out, names.id("minecraft:block_entity_type", type));
                out.writeByte(10);
                out.writeByte(0);
            }
            for (int i = 0; i < 6; i++) out.writeByte(0);
            return ByteBufUtil.getBytes(out);
        } finally {
            column.release();
            out.release();
        }
    }

    /** A 1.21.11 chunk body at (0, 0): a local palette, a single state and air, in the older host's registries. */
    private static byte[] olderBody(int sections) {
        var source = ModelRegistryData.load(OLDER);
        int[] palette = {
            source.blockStateId("minecraft:obsidian"),
            source.blockStateId("minecraft:stone"),
            source.blockStateId("minecraft:dirt")
        };
        ByteBuf column = Unpooled.buffer();
        ByteBuf out = Unpooled.buffer();
        try {
            for (int i = 0; i < sections; i++) {
                int[] states = new int[4096];
                if (i == 0) for (int j = 0; j < states.length; j++) states[j] = palette[j % 3];
                if (i == 1) Arrays.fill(states, palette[0]);
                column.writeShort(i < 2 ? 4096 : 0);
                container(column, states, 4, 8, 1 << 15);
                int[] biomes = new int[64];
                for (int j = 0; j < 64; j++) biomes[j] = i == 0 ? j % 3 : 2;
                container(column, biomes, 1, 3, 3);
            }
            out.writeInt(0).writeInt(0);
            Wire.writeVarInt(out, 0);
            Wire.writeVarInt(out, column.readableBytes());
            out.writeBytes(column);
            Wire.writeVarInt(out, 0);
            for (int i = 0; i < 6; i++) Wire.writeVarInt(out, 0);
            return ByteBufUtil.getBytes(out);
        } finally {
            column.release();
            out.release();
        }
    }

    /** A paletted container as a vanilla server writes it: first-seen palette at the minimal width, or global. */
    private static void container(ByteBuf out, int[] values, int minimumBits, int maximumBits, int registry) {
        var palette = new LinkedHashMap<Integer, Integer>();
        for (int value : values) palette.putIfAbsent(value, palette.size());
        if (palette.size() == 1) {
            out.writeByte(0);
            Wire.writeVarInt(out, values[0]);
            return;
        }
        int bits = Math.max(minimumBits, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        boolean global = bits > maximumBits;
        if (global) bits = 32 - Integer.numberOfLeadingZeros(registry - 1);
        out.writeByte(bits);
        if (!global) {
            Wire.writeVarInt(out, palette.size());
            for (int value : palette.keySet()) Wire.writeVarInt(out, value);
        }
        int perWord = 64 / bits;
        long[] words = new long[(values.length + perWord - 1) / perWord];
        for (int i = 0; i < values.length; i++) {
            long code = global ? values[i] : palette.get(values[i]);
            words[i / perWord] |= code << (i % perWord * bits);
        }
        for (long word : words) out.writeLong(word);
    }

    private static ByteBuf frame(RecordReceiveFixture fixture, ConnectionPhase phase, String name) {
        var frame = Unpooled.buffer();
        int id = fixture.data.packets(phase, PacketDirection.CLIENTBOUND).id("minecraft:" + name);
        assertTrue(id >= 0, name);
        Wire.writeVarInt(frame, id);
        return frame;
    }

    private static void identifier(ByteBuf output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Wire.writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }
}
