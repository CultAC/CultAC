package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.latency.PalettedSection;
import ac.cult.cultac.utils.latency.SectionPool;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Terrain records written straight into a frame's storage are the records built as arrays of their own: every storage
 * shape of a section (a lone value, each palette width, the registry's own ids, storage shared with a copy, a store or
 * a canonical read, read from heap, direct, pooled, read-only and little-endian buffers), block and section-gone
 * records, and whole streams of them through refused frames, spent budgets, frames too small for a section and copies
 * past what a frame's chunks hold.
 */
class TerrainInPlaceTest {
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int[] EDGES = {
        0, 1, -1, 63, 64, -64, 8191, -8192, 1 << 20, -(1 << 21), Integer.MAX_VALUE, Integer.MIN_VALUE
    };

    /** A section's states as the vanilla write puts them on the wire. */
    private static byte[] wire(PalettedSection states) {
        ByteBuf buffer = Unpooled.buffer();
        states.write(buffer);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    /** {@code bytes} as the readable bytes of a buffer of the given kind. */
    private static ByteBuf buffer(byte[] bytes, int kind) {
        return switch (kind) {
            case 0 ->
                Unpooled.buffer(bytes.length + 3)
                        .writeBytes(new byte[] {7, 7, 7})
                        .writeBytes(bytes)
                        .readerIndex(3);
            case 1 -> Unpooled.directBuffer(bytes.length).writeBytes(bytes);
            case 2 -> PooledByteBufAllocator.DEFAULT.directBuffer(bytes.length).writeBytes(bytes);
            case 3 -> Unpooled.directBuffer(bytes.length).writeBytes(bytes).order(ByteOrder.LITTLE_ENDIAN);
            case 4 -> Unpooled.directBuffer(bytes.length).writeBytes(bytes).asReadOnly();
            default -> Unpooled.wrappedBuffer(bytes).order(ByteOrder.LITTLE_ENDIAN);
        };
    }

    /** Sections of every storage shape. */
    static List<CachedSection> shapes(SplittableRandom random) {
        List<CachedSection> shapes = new ArrayList<>();
        shapes.add(null);
        shapes.add(CachedSection.createAirSection());
        for (int id : new int[] {0, 1, 127, 128, 300, 16_383, 16_384, STATES - 1}) {
            ByteBuf lone = Unpooled.buffer().writeByte(0);
            int value = id;
            while ((value & ~0x7F) != 0) {
                lone.writeByte(value & 0x7F | 0x80);
                value >>>= 7;
            }
            lone.writeByte(value);
            byte[] single = new byte[lone.readableBytes()];
            lone.readBytes(single);
            shapes.add(new CachedSection(PalettedSection.readBlocks(Unpooled.wrappedBuffer(single))));
            shapes.add(CachedSection.received(
                    PalettedSection.readCanonicalBlocks(
                            Unpooled.wrappedBuffer(single), null, new SectionPool.ReadCounts()),
                    4096,
                    0));
        }
        for (int distinct : new int[] {2, 15, 16, 17, 32, 33, 64, 65, 128, 129, 256, 257, 1000, 4096}) {
            int[] ids = random.ints(distinct * 2L, 1, STATES).distinct().limit(distinct).toArray();
            PalettedSection states = PalettedSection.blocks();
            for (int i = 0; i < 4096; i++) {
                states.getAndSet(i, ids[i < ids.length ? i : random.nextInt(ids.length)]);
            }
            shapes.add(new CachedSection(states));
            shapes.add(new CachedSection(states.copyOnWrite()));
            byte[] bytes = wire(states);
            for (int kind = 0; kind < 6; kind++) {
                ByteBuf read = buffer(bytes, kind);
                shapes.add(new CachedSection(PalettedSection.readBlocks(read)));
                read.release();
                read = buffer(bytes, kind);
                shapes.add(new CachedSection(PalettedSection.readBlocks(read, (SectionPool) null)));
                read.release();
            }
            CachedSection mutated = new CachedSection(states.copyOnWrite());
            mutated.setStateId(random.nextInt(4096), ids[0]);
            shapes.add(mutated);
        }
        return shapes;
    }

    private static int coordinate(SplittableRandom random) {
        return random.nextInt(3) == 0 ? EDGES[random.nextInt(EDGES.length)] : random.nextInt(-40_000, 40_000);
    }

    @Test
    void sectionRecordsWrittenInPlaceAreTheirArrays() {
        SplittableRandom random = new SplittableRandom(31);
        List<CachedSection> shapes = shapes(random);
        int checked = 0;
        for (CachedSection section : shapes) {
            if (section != null) {
                PalettedSection states = statesOf(section);
                byte[] expected = wire(states);
                assertEquals(expected.length, states.writtenBytes());
                assertEquals(expected.length, section.statesBytes());
                int offset = random.nextInt(40);
                byte[] into = noise(offset + expected.length + 40, random);
                byte[] before = into.clone();
                assertEquals(offset + expected.length, section.writeStates(into, offset));
                assertArrayEquals(expected, Arrays.copyOfRange(into, offset, offset + expected.length));
                assertUntouched(before, into, offset, expected.length);
                assertArrayEquals(expected, SectionBytes.of(section));
            }
            for (int pass = 0; pass < 12; pass++) {
                int x = coordinate(random);
                int y = coordinate(random);
                int z = coordinate(random);
                byte[] expected = SectionBytes.record(x, y, z, section);
                assertEquals(expected.length, SectionBytes.recordLength(x, y, z, section));
                int offset = random.nextInt(100);
                byte[] into = noise(offset + expected.length + random.nextInt(100), random);
                byte[] before = into.clone();
                SectionBytes.record(x, y, z, section, into, offset);
                assertArrayEquals(expected, Arrays.copyOfRange(into, offset, offset + expected.length));
                assertUntouched(before, into, offset, expected.length);
                checked++;
            }
        }
        assertTrue(checked > 1000, checked + " records");
    }

    @Test
    void blockAndGoneRecordsWrittenInPlaceAreTheirArrays() {
        SplittableRandom random = new SplittableRandom(37);
        for (int pass = 0; pass < 20_000; pass++) {
            int x = coordinate(random);
            int y = coordinate(random);
            int z = coordinate(random);
            int state = random.nextInt(4) == 0 ? EDGES[random.nextInt(EDGES.length)] : random.nextInt(STATES);
            byte[] block = IntakeWire.block(x, y, z, state);
            assertEquals(block.length, IntakeWire.blockLength(x, y, z, state));
            int offset = random.nextInt(20);
            byte[] into = noise(offset + block.length + 20, random);
            byte[] before = into.clone();
            IntakeWire.block(x, y, z, state, into, offset);
            assertArrayEquals(block, Arrays.copyOfRange(into, offset, offset + block.length));
            assertUntouched(before, into, offset, block.length);

            byte[] gone = IntakeWire.sectionGone(x, y, z);
            assertEquals(gone.length, IntakeWire.sectionGoneLength(x, y, z));
            into = noise(offset + gone.length + 20, random);
            before = into.clone();
            IntakeWire.sectionGone(x, y, z, into, offset);
            assertArrayEquals(gone, Arrays.copyOfRange(into, offset, offset + gone.length));
            assertUntouched(before, into, offset, gone.length);

            int blocks = random.nextInt(9000);
            byte[] states = noise(blocks, random);
            byte[] record = IntakeWire.section(x, y, z, states);
            assertEquals(record.length, IntakeWire.sectionLength(x, y, z, blocks));
            into = noise(offset + record.length, random);
            int at = IntakeWire.sectionHead(x, y, z, blocks, into, offset);
            System.arraycopy(states, 0, into, at, blocks);
            assertEquals(offset + record.length, at + blocks);
            assertArrayEquals(record, Arrays.copyOfRange(into, offset, offset + record.length));
        }
    }

    private static PalettedSection statesOf(CachedSection section) {
        ByteBuf buffer = Unpooled.buffer();
        section.writeStates(buffer);
        return PalettedSection.readBlocks(buffer);
    }

    private static byte[] noise(int length, SplittableRandom random) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static void assertUntouched(byte[] before, byte[] after, int offset, int length) {
        assertArrayEquals(Arrays.copyOfRange(before, 0, offset), Arrays.copyOfRange(after, 0, offset));
        assertArrayEquals(
                Arrays.copyOfRange(before, offset + length, before.length),
                Arrays.copyOfRange(after, offset + length, after.length));
    }

    /**
     * A socket that keeps a copy of each frame it takes, refuses every {@code refuseEvery}th and spends the budget at
     * every {@code spendEvery}th hold; a reusing one hands each frame's array out again, poisoned, once sent.
     */
    private static final class Socket implements CaptureStream.Sink {
        final List<byte[]> frames = new ArrayList<>();
        final boolean reusing;
        final int maxFrameBytes;
        final ArrayDeque<byte[]> spare = new ArrayDeque<>();
        int sent;
        int holds;

        Socket(boolean reusing, int maxFrameBytes) {
            this.reusing = reusing;
            this.maxFrameBytes = maxFrameBytes;
        }

        @Override
        public boolean send(byte[] record) {
            return send(record, record.length, 0);
        }

        @Override
        public byte[] frame(int length) {
            if (!reusing) return new byte[length];
            byte[] array = spare.poll();
            if (array == null || array.length < length) array = new byte[length + 64];
            Arrays.fill(array, (byte) 0x5A);
            return array;
        }

        @Override
        public boolean send(byte[] record, int length, long resolvedExtra) {
            if (reusing) spare.add(record);
            if (++sent % 23 == 0) return false;
            frames.add(Arrays.copyOf(record, length));
            if (reusing) Arrays.fill(record, (byte) 0xA5);
            return true;
        }

        @Override
        public boolean hold(long bytes) {
            return ++holds % 29 != 0;
        }

        @Override
        public void release(long bytes) {}

        @Override
        public int maxFrameBytes() {
            return maxFrameBytes;
        }

        @Override
        public CloudConnection.Terms terms() {
            return CloudConnection.Terms.sections(3, 2);
        }
    }

    private static final int TICK_END = 1;
    private static final int PONG = 2;
    private static final int MOVE = 3;
    private static final int PING = 4;
    private static final int ENTITY = 6;
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);

    /** One player's two streams: terrain handed out as arrays of their own, and written into the frame's storage. */
    private static final class Player {
        final Socket arraysSocket;
        final Socket inPlaceSocket;
        final SyntheticTerrain arraysTerrain = new SyntheticTerrain();
        final SyntheticTerrain inPlaceTerrain = new SyntheticTerrain();
        final CaptureStream arrays;
        final CaptureStream inPlace;
        long pooled;

        Player(int index, int maxFrameBytes) {
            UUID player = new UUID(index, 17);
            arraysTerrain.inPlace = false;
            arraysSocket = new Socket(false, maxFrameBytes);
            inPlaceSocket = new Socket(true, maxFrameBytes);
            arrays = stream(player, arraysTerrain);
            inPlace = stream(player, inPlaceTerrain);
        }

        void both(Consumer<CaptureStream> action) {
            action.accept(arrays);
            action.accept(inPlace);
        }

        void terrain(Consumer<SyntheticTerrain> action) {
            action.accept(arraysTerrain);
            action.accept(inPlaceTerrain);
        }

        void join() {
            byte[] join = IntakeWire.join(new UUID(0, 0), 773, "vanilla", -64, 384, null, null, 0);
            arrays.join(arraysSocket, join, 0, SESSION);
            inPlace.join(inPlaceSocket, join.clone(), 0, SESSION);
        }

        void tap(int kind, int id, byte[] packet, long nanos, int ping) {
            arrays.tap(kind, id, packet.clone(), nanos, -64, 384, SESSION, ping);
            ByteBuf wire = PooledByteBufAllocator.DEFAULT.directBuffer(packet.length).writeBytes(packet);
            if (inPlace.wants(kind, id)) inPlace.tap(kind, id, wire, nanos, -64, 384, SESSION, ping);
            else inPlace.tap(kind, id, packet.clone(), nanos, -64, 384, SESSION, ping);
            wire.release();
            pooled = Math.max(pooled, inPlace.pooledBytes());
        }
    }

    private static CaptureStream stream(UUID player, SyntheticTerrain terrain) {
        CaptureStream stream = new CaptureStream(player);
        stream.start(0);
        stream.pingPacket(PING);
        stream.pongPacket(PONG);
        stream.chunks(new ChunkIds(200, 201, 202, 203));
        stream.terrain(terrain);
        return stream;
    }

    @Test
    void terrainWrittenInPlaceSendsTheFramesArrayTerrainSends() {
        SplittableRandom random = new SplittableRandom(41);
        List<CachedSection> shapes = shapes(new SplittableRandom(43));
        int[] frameSizes = {96 * 1024, 10 * 1024, 1 << 20, 40 * 1024};
        List<Player> players = new ArrayList<>();
        for (int p = 0; p < frameSizes.length; p++) {
            Player player = new Player(p, frameSizes[p]);
            player.terrain(terrain -> terrain.at(8, 70, 8));
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    int cx = x;
                    int cz = z;
                    player.terrain(terrain -> terrain.load(cx, cz));
                }
            }
            for (int i = 0; i < shapes.size(); i++) {
                CachedSection shape = shapes.get(i);
                int x = i % 11 - 5;
                int y = 2 + i / 11 % 5;
                int z = i / 55 - 2;
                player.terrain(terrain -> terrain.put(x, y, z, shape));
            }
            player.join();
            if (p != 2) player.both(stream -> stream.capture(new int[] {TICK_END, PONG, MOVE}, all(), new int[0]));
            players.add(player);
        }
        long nanos = 1_000_000_000L;
        int wireId = 1;
        for (int tick = 1; tick <= 360; tick++) {
            for (int p = 0; p < players.size(); p++) {
                Player player = players.get(p);
                if (p == 2 && tick == 25) {
                    player.both(stream -> stream.capture(new int[] {TICK_END, PONG, MOVE}, all(), new int[0]));
                }
                for (int i = 0; i < 4 + random.nextInt(20); i++) {
                    nanos += 1 + random.nextInt(100_000);
                    byte[] entity = noise(1 + random.nextInt(random.nextInt(10) == 0 ? 900 : 60), random);
                    entity[0] = ENTITY;
                    player.tap(IntakeWire.CLIENTBOUND, ENTITY, entity, nanos, 0);
                }
                for (int i = random.nextInt(3) == 0 ? random.nextInt(1, 60) : 0; i > 0; i--) {
                    int x = random.nextInt(-60, 60);
                    int y = random.nextInt(-64, 200);
                    int z = random.nextInt(-60, 60);
                    int state = random.nextInt(1, STATES);
                    player.terrain(terrain -> terrain.set(x, y, z, state));
                    long at = nanos;
                    player.both(stream -> stream.blockChanged(x, y, z, state, at));
                }
                if (tick % 13 == p) {
                    int x = random.nextInt(-6, 7);
                    int z = random.nextInt(-6, 7);
                    long at = nanos;
                    if (player.arraysTerrain.loaded(x, z)) {
                        player.terrain(terrain -> terrain.unload(x, z));
                        player.both(stream -> stream.columnUnloaded(x, z, at));
                    } else {
                        player.terrain(terrain -> terrain.load(x, z));
                        player.both(stream -> stream.columnLoaded(x, z, at));
                    }
                }
                if (tick % 7 == p) {
                    double x = 16 * random.nextInt(-4, 5) + 8;
                    double y = random.nextInt(-60, 200);
                    double z = 16 * random.nextInt(-4, 5) + 3;
                    player.terrain(terrain -> terrain.at(x, y, z));
                }
                nanos += 10_000;
                int id = wireId++;
                byte[] ping = {PING, (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id};
                player.tap(IntakeWire.CLIENTBOUND, PING, ping, nanos, IntakeWire.PING_END_OF_TICK);
                byte[] move = noise(25, random);
                move[0] = MOVE;
                player.tap(IntakeWire.SERVERBOUND, MOVE, move, nanos, 0);
                player.tap(IntakeWire.SERVERBOUND, TICK_END, new byte[] {TICK_END}, nanos, 0);
                long end = nanos;
                player.both(stream -> stream.cut(end));
                long verdict = tick - random.nextInt(3);
                if (verdict > 0) {
                    player.both(stream -> stream.verdict(verdict, verdict, 0, List.of(), null, List.of(), null));
                }
            }
        }
        int sections = 0;
        int large = 0;
        int blocks = 0;
        int gone = 0;
        for (Player player : players) {
            player.both(stream -> stream.leave(2_000_000_000_000L));
            List<byte[]> expected = player.arraysSocket.frames;
            List<byte[]> actual = player.inPlaceSocket.frames;
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i), "frame " + i);
                IntakeMessage message = TestSink.message(expected.get(i));
                if (!message.hasFrame()) continue;
                for (Record record : message.getFrame().getRecordsList()) {
                    if (record.hasSection()) {
                        sections++;
                        if (record.getSection().getBlocks().size() > 4096) large++;
                    }
                    if (record.hasBlock()) blocks++;
                    if (record.hasSectionGone()) gone++;
                }
            }
            assertTrue(player.pooled > CapturePacketCopies.TERRAIN_CHUNK_BYTES, player.pooled + " pooled");
        }
        assertTrue(sections > 2000 && large > 20 && blocks > 200 && gone > 50,
                sections + " sections, " + large + " large, " + blocks + " blocks, " + gone + " gone");
    }

    private static int[] all() {
        return IntStream.range(0, 128).filter(id -> id < 200).toArray();
    }
}
