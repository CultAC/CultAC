package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.FrameLease;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
import ac.cult.cultac.utils.latency.SectionStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

/**
 * Players whose world readers share block storage through the section store each keep the world their own packets
 * built: a write one of them receives reaches no other player and no stored entry, and a world built with the store
 * warm equals the world built with it cold.
 */
class SectionStoreWorldTest {
    private static final String BIOME = "minecraft:worldgen/biome";
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int BIOMES = ModelRegistryNamesState.defaults().size(BIOME);
    private static final int SECTIONS = 24, MIN_Y = -64;
    private static final AtomicInteger LEVELS = new AtomicInteger();

    private enum Write {
        BLOCK,
        SECTION,
        RESEND,
        RELOAD,
        BULK,
        BIOMES
    }

    @Test
    void aWriteOnePlayerReceivesReachesNoOtherPlayerAndNoEntry() throws Exception {
        for (var write : Write.values()) {
            for (boolean lent : new boolean[] {false, true}) {
                SectionStore.clear();
                try (var services = new RecordConsumerServices()) {
                    var random = new SplittableRandom(write.ordinal() * 31L + (lent ? 7 : 0));
                    byte[] body = body(random, 2, -3);
                    var players = new ArrayList<Player>();
                    for (int i = 0; i < 3; i++) players.add(new Player(lent));
                    long hits = SectionStore.hitCounter.sum();
                    for (var player : players) player.receive(body);
                    assertTrue(SectionStore.hitCounter.sum() - hits >= 2 * 10, "the later players share storage");
                    var original = players.get(1).observable(2, -3);
                    for (var player : players) assertEquals(original, player.observable(2, -3), write.name());
                    var entries = entries();
                    for (int writer = 0; writer < players.size(); writer++) {
                        var before = new ArrayList<String>();
                        for (var player : players) before.add(player.full(2, -3));
                        players.get(writer).write(write, random, body);
                        for (int other = 0; other < players.size(); other++) {
                            if (other == writer) continue;
                            assertEquals(before.get(other), players.get(other).full(2, -3), write + " by " + writer);
                        }
                        if (write != Write.BIOMES)
                            assertNotEquals(
                                    original,
                                    players.get(writer).observable(2, -3),
                                    write + " by " + writer + " " + lent);
                        assertUnchanged(entries);
                    }
                    var later = new Player(lent);
                    later.receive(body);
                    assertEquals(original, later.observable(2, -3), write.name());
                    for (var player : players) player.close();
                    later.close();
                }
            }
        }
    }

    @Test
    void aStreamBuildsTheSameWorldWithTheStoreColdAndWarm() throws Exception {
        for (boolean lent : new boolean[] {false, true}) {
            try (var services = new RecordConsumerServices()) {
                SectionStore.clear();
                long coldHits = SectionStore.hitCounter.sum();
                String cold = replay(lent, 0x51EAL);
                coldHits = SectionStore.hitCounter.sum() - coldHits;
                SectionStore.clear();
                replay(lent, 0x51EAL);
                long warmHits = SectionStore.hitCounter.sum();
                String warm = replay(lent, 0x51EAL);
                warmHits = SectionStore.hitCounter.sum() - warmHits;
                assertEquals(cold, warm, "lent " + lent);
                assertTrue(warmHits > coldHits + 50, "warm " + warmHits + " cold " + coldHits);
            }
        }
    }

    @Test
    void playersSharingALevelBuildTheSameWorldsWithTheStoreColdAndWarm() throws Exception {
        for (boolean lent : new boolean[] {false, true}) {
            try (var services = new RecordConsumerServices()) {
                SectionStore.clear();
                String cold = sharedLevel(lent, 0x5EA1L, false);
                SectionStore.clear();
                long hits = SectionStore.hitCounter.sum();
                String warm = sharedLevel(lent, 0x5EA1L, true);
                hits = SectionStore.hitCounter.sum() - hits;
                assertEquals(cold, warm, "lent " + lent);
                assertTrue(hits > 20, "warm hits " + hits);
            }
        }
    }

    /**
     * Two players' worlds in one level after the same columns, on a store that a closed player in another level
     * filled when {@code primed}: the later player shares the earlier one's pooled sections, empties one section and
     * writes others, the earlier one writes too, and both reintern every written section.
     */
    private static String sharedLevel(boolean lent, long seed, boolean primed) {
        var random = new SplittableRandom(seed);
        int[][] columns = {{0, 0}, {1, 0}, {2, -3}};
        var bodies = new ArrayList<byte[]>();
        for (int[] column : columns) bodies.add(body(random, column[0], column[1]));
        if (primed) {
            var primer = new Player(lent);
            try {
                for (byte[] body : bodies) primer.receive(body);
            } finally {
                primer.close();
            }
        }
        String level = "test:store-shared-" + LEVELS.incrementAndGet();
        var first = new Player(lent, level);
        var second = new Player(lent, level);
        try {
            for (byte[] body : bodies) first.receive(body);
            for (byte[] body : bodies) second.receive(body);
            int emptied = MIN_Y + 16 * 2;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) second.block(x, emptied + y, z, 0);
            for (int i = 0; i < 40; i++) {
                int[] column = columns[random.nextInt(columns.length)];
                var writer = i % 2 == 0 ? first : second;
                writer.block(
                        column[0] * 16 + random.nextInt(16),
                        MIN_Y + random.nextInt(384),
                        column[1] * 16 + random.nextInt(16),
                        random.nextInt(STATES));
            }
            first.settle(columns);
            second.settle(columns);
            assertTrue(
                    second.fixture.player.compensatedWorld.getChunk(0, 0).getSection(2).isShared(),
                    "the emptied section reinterns");
            var out = new StringBuilder();
            for (var player : List.of(first, second))
                for (int[] column : columns)
                    out.append(player.observable(column[0], column[1])).append('\n');
            return out.toString();
        } finally {
            first.close();
            second.close();
        }
    }

    /**
     * One player's world after a stream of columns: first sends, a resend of the same bytes, block writes, a
     * resend with other bytes, an unload and reload, and biome changes; every column and every field but storage
     * sharing.
     */
    private static String replay(boolean lent, long seed) {
        var random = new SplittableRandom(seed);
        var player = new Player(lent);
        try {
            int[][] columns = {{0, 0}, {1, 0}, {0, 1}, {-1, -1}, {2, -3}};
            var bodies = new ArrayList<byte[]>();
            for (int[] column : columns) bodies.add(body(random, column[0], column[1]));
            for (byte[] body : bodies) player.receive(body);
            player.receive(bodies.get(1));
            for (int i = 0; i < 40; i++) {
                int[] column = columns[random.nextInt(columns.length)];
                player.block(
                        column[0] * 16 + random.nextInt(16),
                        MIN_Y + random.nextInt(384),
                        column[1] * 16 + random.nextInt(16),
                        random.nextInt(STATES));
            }
            player.receive(body(random, 0, 1));
            player.unload(2, -3);
            player.receive(bodies.get(4));
            player.biomes(-1, -1, random);
            var out = new StringBuilder();
            for (int[] column : columns)
                out.append(player.observable(column[0], column[1])).append('\n');
            return out.toString();
        } finally {
            player.close();
        }
    }

    /** A 26.3 connection with an attached player in a level of its own, so no section pool is shared. */
    private static final class Player {
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, true);
        final boolean lent;
        final String level;

        Player(boolean lent) {
            this(lent, "test:store-world-" + LEVELS.incrementAndGet());
        }

        /** A player in {@code level}, whose section pools every player in that level shares. */
        Player(boolean lent, String level) {
            this.lent = lent;
            this.level = level;
            var dimension = fixture.player.getWorldRegistries().dimension("minecraft:overworld");
            fixture.player.compensatedWorld.setLastClientboundDimension(level, dimension.dimension());
            fixture.player.compensatedWorld.setDimension(level, dimension);
        }

        @SuppressWarnings("unchecked")
        void receive(byte[] body) {
            var chunk = read(body);
            assertEquals(lent, chunk.decodedSections() != null);
            var type = (PacketType<WorldPackets.Chunk>)
                    fixture.registrar.runtime().typeForKey("clientbound.level_chunk_with_light");
            var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, type, chunk, false);
            new PacketWorldReaderTwentySix().handleMapChunk(fixture.player, event, chunk);
            event.tasksAfter().forEach(Runnable::run);
            answer();
        }

        private WorldPackets.Chunk read(byte[] body) {
            var connection = fixture.connection;
            ByteBuf frame = Unpooled.buffer();
            Wire.writeVarInt(
                    frame,
                    fixture.data
                            .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                            .id("minecraft:level_chunk_with_light"));
            frame.writeBytes(body);
            var lender = connection.frameLender();
            try {
                FrameLease lease = lent ? lender.open(frame) : null;
                Object value;
                try {
                    value = connection
                            .packets()
                            .readOne(
                                    ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, frame, connection, type -> true);
                } finally {
                    if (lease != null) lender.close(lease);
                }
                if (lease != null) lender.end(lease);
                assertNotNull(value);
                return (WorldPackets.Chunk) value;
            } finally {
                frame.setZero(0, frame.capacity());
                frame.release();
            }
        }

        private void answer() {
            var player = fixture.player;
            for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
        }

        void block(int x, int y, int z, int state) {
            fixture.player.compensatedWorld.applyBlockChangeRawDANGER(x, y, z, state);
        }

        /** Every written section of the loaded columns counts as stable, then a client tick ends and reinterns them. */
        void settle(int[][] columns) {
            long stable = System.nanoTime() - TimeUnit.SECONDS.toNanos(60);
            for (int[] at : columns) {
                var column = fixture.player.compensatedWorld.getChunk(at[0], at[1]);
                if (column == null) continue;
                for (int i = 0; i < column.sectionCount(); i++) {
                    var section = column.getSection(i);
                    if (section != null && !section.isShared())
                        section.setLastMutatedNanos(stable);
                }
            }
            fixture.player.compensatedWorld.onClientTickEnd();
        }

        void unload(int x, int z) {
            fixture.player.compensatedWorld.removeChunkLater(level, x, z, fixture.player.lastTransactionSent.get());
            answer();
            assertEquals(null, fixture.player.compensatedWorld.getChunk(x, z));
        }

        void biomes(int x, int z, SplittableRandom random) {
            int[][] biomes = new int[SECTIONS][64];
            for (int[] section : biomes) for (int i = 0; i < 64; i++) section[i] = random.nextInt(BIOMES);
            fixture.player.compensatedWorld.applyClientBiomes(level, x, z, biomes);
        }

        /** The write {@code write} names, received by this player only, on column (2, -3). */
        void write(Write write, SplittableRandom random, byte[] body) {
            int x = 32, z = -48;
            switch (write) {
                case BLOCK ->
                    block(x + 3, MIN_Y + 16 * 3 + 5, z + 7, changed(random, x + 3, MIN_Y + 16 * 3 + 5, z + 7));
                case SECTION -> {
                    for (int i = 0; i < 20; i++) {
                        int bx = x + random.nextInt(16),
                                by = MIN_Y + 16 * 4 + random.nextInt(16),
                                bz = z + random.nextInt(16);
                        block(bx, by, bz, changed(random, bx, by, bz));
                    }
                }
                case RESEND -> receive(body(random, 2, -3));
                case RELOAD -> {
                    unload(2, -3);
                    receive(body(random, 2, -3));
                }
                case BULK -> {
                    for (int i = 0; i < 600; i++) {
                        int bx = x + random.nextInt(16), by = MIN_Y + random.nextInt(384), bz = z + random.nextInt(16);
                        block(bx, by, bz, random.nextInt(4) == 0 ? 0 : changed(random, bx, by, bz));
                    }
                }
                case BIOMES -> biomes(2, -3, random);
            }
        }

        private int changed(SplittableRandom random, int x, int y, int z) {
            int now = fixture.player.compensatedWorld.getBlockStateIdAt(x, y, z);
            int state;
            do state = 1 + random.nextInt(STATES - 1);
            while (ViaClientBlockShapeMappings.clientBlockStateId(fixture.player, state) == now);
            return state;
        }

        /** Every field of the column but storage sharing, which no reader of a section sees. */
        String observable(int x, int z) {
            var column = fixture.player.compensatedWorld.getChunk(x, z);
            assertNotNull(column);
            var out = new StringBuilder();
            dump(column, out, Collections.newSetFromMap(new IdentityHashMap<>()), false);
            return out.toString();
        }

        /** Every field of the column. */
        String full(int x, int z) {
            var column = fixture.player.compensatedWorld.getChunk(x, z);
            assertNotNull(column);
            var out = new StringBuilder();
            dump(column, out, Collections.newSetFromMap(new IdentityHashMap<>()), true);
            return out.toString();
        }

        void close() {
            fixture.close();
        }
    }

    private record Held(List<Object> entries, List<String> contents) {}

    private static Held entries() {
        try {
            Field field = SectionStore.class.getDeclaredField("ENTRIES");
            field.setAccessible(true);
            var array = (AtomicReferenceArray<?>) field.get(null);
            var entries = new ArrayList<Object>();
            var contents = new ArrayList<String>();
            for (int i = 0; i < array.length(); i++) {
                Object entry = array.get(i);
                if (entry == null) continue;
                entries.add(entry);
                contents.add(contents(entry));
            }
            assertTrue(!entries.isEmpty());
            return new Held(entries, contents);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** An entry's fingerprint and every field of its section. */
    private static String contents(Object entry) {
        var out = new StringBuilder();
        try {
            for (var component : entry.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                out.append(component.getName()).append('=');
                dump(accessor.invoke(entry), out, Collections.newSetFromMap(new IdentityHashMap<>()), true);
                out.append(' ');
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return out.toString();
    }

    private static void assertUnchanged(Held held) {
        for (int i = 0; i < held.entries().size(); i++)
            assertEquals(held.contents().get(i), contents(held.entries().get(i)), "a stored section changed");
    }

    /** Every instance field reachable through anticheat objects and arrays; the mutation clock is left out. */
    private static void dump(Object value, StringBuilder out, Set<Object> seen, boolean sharing) {
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
                dump(element, out, seen, sharing);
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
                String name = field.getName();
                if (Modifier.isStatic(field.getModifiers()) || name.equals("lastMutatedNanos")) continue;
                if (!sharing
                        && (name.equals("sharedStorage")
                                || name.equals("sharedByPool")
                                || name.equals("sharedByStore"))) continue;
                field.setAccessible(true);
                out.append(name).append('=');
                try {
                    dump(field.get(value), out, seen, sharing);
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException(failure);
                }
                out.append(' ');
            }
        }
        out.append('}');
    }

    /**
     * A pinned 26.3 chunk body at ({@code x}, {@code z}): air, single states, local palettes of widths 4-8 and global
     * ones, single, local and global biomes, empty heightmap, no block entities, empty light.
     */
    private static byte[] body(SplittableRandom random, int x, int z) {
        ByteBuf column = Unpooled.buffer();
        ByteBuf out = Unpooled.buffer();
        try {
            for (int i = 0; i < SECTIONS; i++) {
                int[] states = new int[4096];
                int kind = i % 7;
                if (kind == 1) Arrays.fill(states, 1 + random.nextInt(STATES - 1));
                else if (kind >= 2) {
                    int distinct = kind == 6 ? 300 : 2 + random.nextInt(1 << (kind + 1));
                    int[] mix = new int[distinct];
                    for (int j = 0; j < distinct; j++) mix[j] = 1 + random.nextInt(STATES - 1);
                    for (int j = 0; j < states.length; j++)
                        states[j] = j < distinct ? mix[j] : mix[random.nextInt(distinct)];
                }
                int nonEmpty = 0;
                for (int state : states) if (state != 0) nonEmpty++;
                column.writeShort(nonEmpty);
                column.writeShort(0);
                container(column, states, 4, 8, STATES);
                int[] biomes = new int[64];
                Arrays.fill(biomes, random.nextInt(BIOMES));
                if (i % 3 == 1) for (int j = 32; j < 64; j++) biomes[j] = random.nextInt(BIOMES);
                container(column, biomes, 1, 3, BIOMES);
            }
            out.writeInt(x);
            out.writeInt(z);
            Wire.writeVarInt(out, 0);
            Wire.writeVarInt(out, column.readableBytes());
            out.writeBytes(column);
            Wire.writeVarInt(out, 0);
            for (int i = 0; i < 6; i++) out.writeByte(0);
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
}
