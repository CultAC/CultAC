package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.BlockRecord;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.SectionGone;
import ac.cult.cultac.cloud.intake.v3.SectionRecord;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Intake protocol 5 in {@link CaptureStream}: the simulation world's sections in range, as the held set has them. */
class CaptureStreamSectionsTest {
    private static final UUID PLAYER = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
    private static final CaptureStream.Session NETHER = new CaptureStream.Session(7, 0, "minecraft:the_nether", true);
    private static final int PROTOCOL = 777;
    private static final int TICK_END = 1;
    private static final int MOVE = 2;
    private static final int RESPAWN = 3;
    private static final int PING = 4;
    private static final int LEVEL_CHUNK = 44;
    private static final int BLOCK_UPDATE = 8;
    private static final int SECTION_UPDATE = 82;
    private static final int FORGET = 37;
    private static final int TELEPORT = 65;
    private static final int CHUNK_RADIUS = 2;
    private static final int SECTION_RADIUS = 2;
    /** Overworld sections: y -64 to 319. */
    private static final int MIN_SECTION = -4;

    private static final int MAX_SECTION = 19;

    private final FakeTerrain terrain = new FakeTerrain();
    private final Sink sink = new Sink();
    private final Set<Long> received = new LinkedHashSet<>();
    private long ticks;
    private long nanos = 1_000_000_000L;

    @Test
    void joinSendsEveryLoadedSectionInRangeAndNothingElse() {
        loadAroundOrigin(6);
        CaptureStream stream = joined();
        tick(stream);

        assertEquals(inRange(0, 4, 0), received);
        assertEquals(125, received.size());
        assertEquals(received.size(), stream.sections().held());
    }

    @Test
    void walkingSendsTheNewEdge() {
        terrain.loadAround(0, 0, 12);
        terrain.at(0, 4, 0);
        CaptureStream stream = joined();
        tick(stream);
        int[][] steps = {{1, 0}, {1, 0}, {-1, 0}, {-1, 0}, {-1, 0}, {-1, 0}, {0, 1}, {0, 1}, {0, -1}, {0, -1}, {0, -1}};
        int x = 0;
        int z = 0;
        for (int[] step : steps) {
            x += step[0];
            z += step[1];
            walkTo(stream, x, 4, z);
            assertTrue(received.containsAll(inRange(x, 4, z)), "every section in range is held at " + x + ", " + z);
            for (long section : received) assertTrue(withinBand(section, x, 4, z), "nothing two beyond is held");
            assertEquals(received.size(), stream.sections().held());
        }
        walkTo(stream, 3, 4, 0);
        Set<Long> gone = sink.gone();
        for (int dz = -2; dz <= 2; dz++) {
            for (int y = 2; y <= 6; y++) assertTrue(gone.contains(Sections.pack(-1, y, z + dz)), "x -1 goes");
        }
    }

    @Test
    void verticalCrossingsSendTheNewLayerAndLetTheFarOneGo() {
        loadAroundOrigin(6);
        CaptureStream stream = joined();
        tick(stream);

        walkTo(stream, 0, 5, 0);
        assertEquals(25, sink.sent().size());
        for (long section : sink.sent()) assertEquals(7, Sections.y(section));
        assertTrue(sink.gone().isEmpty());

        walkTo(stream, 0, 6, 0);
        for (long section : sink.sent()) assertEquals(8, Sections.y(section));
        assertEquals(25, sink.gone().size());
        for (long section : sink.gone()) assertEquals(2, Sections.y(section));

        walkTo(stream, 0, 3, 0);
        assertTrue(received.containsAll(inRange(0, 3, 0)));
        for (long section : received) assertTrue(withinBand(section, 0, 3, 0));
    }

    @Test
    void aTeleportLetsEveryHeldSectionGoAndSendsTheRangeItLandsIn() {
        terrain.loadAround(0, 0, 3);
        terrain.loadAround(100, -40, 3);
        terrain.at(0, 4, 0);
        CaptureStream stream = joined();
        tick(stream);
        Set<Long> before = new HashSet<>(received);

        walkTo(stream, 100, 8, -40);

        assertEquals(before, sink.gone());
        assertEquals(inRange(100, 8, -40), received);
    }

    @Test
    void aNewWorldEmptiesTheHeldSetAndResendsTheRange() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        tick(stream);

        terrain.unloadAll();
        terrain.loadAround(5, 5, 3);
        terrain.at(5, 2, 5);
        sink.clear();
        stream.observe(NETHER, 0, 128, nanos);
        stream.tap(IntakeWire.CLIENTBOUND, RESPAWN, new byte[] {RESPAWN, 1}, nanos, 0, 128, NETHER);
        assertEquals(0, stream.sections().held());
        tick(stream);

        List<Record> records = sink.records();
        assertEquals(IntakeWire.WORLD, records.get(0).getBodyCase().getNumber());
        assertTrue(sink.gone().isEmpty());
        assertEquals(inRange(5, 2, 5), sink.sent());
    }

    @Test
    void aColumnReloadSendsItsSectionsAgainHeldOrNot() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        tick(stream);

        sink.clear();
        stream.columnLoaded(1, -1, nanos);
        tick(stream);

        List<Long> sent = sink.sentInOrder();
        assertEquals(5, sent.size());
        for (int y = 2; y <= 6; y++) assertTrue(sent.contains(Sections.pack(1, y, -1)));
    }

    @Test
    void anUnloadedColumnIsSentAgainOnReload() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        tick(stream);

        terrain.unload(2, 2);
        sink.clear();
        stream.columnUnloaded(2, 2, nanos);
        tick(stream);
        assertEquals(5, sink.gone().size());
        for (long section : sink.gone()) assertEquals(SyntheticTerrain.pack(2, 2), column(section));
        assertFalse(stream.sections().holds(2, 4, 2));

        terrain.load(2, 2);
        sink.clear();
        stream.columnLoaded(2, 2, nanos);
        tick(stream);
        assertEquals(5, sink.sent().size());
        assertTrue(stream.sections().holds(2, 4, 2));
    }

    @Test
    void aSectionWhoseColumnIsNotLoadedWaitsForItsLoad() {
        terrain.loadAround(0, 0, 3);
        terrain.unload(-2, 1);
        terrain.at(0, 4, 0);
        CaptureStream stream = joined();
        tick(stream);
        assertFalse(received.contains(Sections.pack(-2, 4, 1)));
        assertEquals(120, received.size());

        terrain.load(-2, 1);
        sink.clear();
        stream.columnLoaded(-2, 1, nanos);
        tick(stream);
        assertEquals(inRange(0, 4, 0), received);
    }

    @Test
    void aBlockChangeGoesOutForAHeldSectionOnlyAndAfterIt() {
        loadAroundOrigin(6);
        CaptureStream stream = joined();
        stream.blockChanged(5, 70, 5, 33, nanos);
        tick(stream);

        List<Record> records = sink.records();
        int section = -1;
        int block = -1;
        for (int i = 0; i < records.size(); i++) {
            Record record = records.get(i);
            if (record.hasSection()
                    && record.getSection().getX() == 0
                    && record.getSection().getY() == 4) {
                if (record.getSection().getZ() == 0) section = i;
            }
            if (record.hasBlock()) block = i;
        }
        assertTrue(section >= 0 && block > section, "the block follows its section");
        BlockRecord sent = sink.blocks().get(0);
        assertEquals(List.of(5, 70, 5, 33), List.of(sent.getX(), sent.getY(), sent.getZ(), sent.getState()));

        sink.clear();
        stream.blockChanged(16 * 3, 70, 0, 34, nanos);
        stream.blockChanged(0, 16 * 7, 0, 35, nanos);
        stream.blockChanged(-1, 16 * 6 + 15, -1, 36, nanos);
        tick(stream);
        assertEquals(1, sink.blocks().size());
        assertEquals(36, sink.blocks().get(0).getState());
    }

    @Test
    void theRangeIsClampedAtTheWorldsBottomAndTop() {
        terrain.loadAround(0, 0, 3);
        terrain.at(0, MIN_SECTION, 0);
        CaptureStream stream = joined();
        tick(stream);
        assertEquals(75, received.size());
        for (long section : received) {
            assertTrue(Sections.y(section) >= MIN_SECTION && Sections.y(section) <= MIN_SECTION + 2);
        }

        walkTo(stream, 0, MAX_SECTION, 0);
        assertEquals(75, received.size());
        for (long section : received) {
            assertTrue(Sections.y(section) >= MAX_SECTION - 2 && Sections.y(section) <= MAX_SECTION);
        }

        walkTo(stream, 0, MAX_SECTION + 30, 0);
        assertTrue(received.isEmpty(), "nothing is in range above the world");
    }

    @Test
    void noChunkOrBlockPacketGoesOutEvenWhenThePolicyListsThem() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        byte[][] packets = {
            new SyntheticTerrain().levelChunk(LEVEL_CHUNK, 0, 0),
            blockUpdate(1, 70, 1, 9),
            sectionUpdate(0, 4, 0),
            forget(0, 0)
        };
        for (byte[] packet : packets) {
            assertFalse(stream.wants(IntakeWire.CLIENTBOUND, IntakeWire.packetId(packet)));
            stream.tap(IntakeWire.CLIENTBOUND, IntakeWire.packetId(packet), packet, nanos, -64, 384, SESSION);
        }
        byte[] ping = ByteBuffer.allocate(5).put((byte) PING).putInt(42).array();
        stream.tap(IntakeWire.CLIENTBOUND, PING, ping, nanos, -64, 384, SESSION, IntakeWire.PING_FOREIGN);
        tick(stream);

        for (Record record : sink.records()) {
            assertFalse(record.hasClientbound()
                    && isTerrainPacket(record.getClientbound().toByteArray()[0]));
            assertFalse(record.hasChunk() || record.hasChunkLost());
        }
        assertEquals(1, sink.records().stream().filter(Record::hasPing).count());
    }

    @Test
    void chunkPacketsAreNotEvenHeldBeforeTheServiceAnswers() {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.chunks(new ChunkIds(LEVEL_CHUNK, BLOCK_UPDATE, SECTION_UPDATE, FORGET));
        stream.join(sink, IntakeWire.join(PLAYER, PROTOCOL, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        for (int id : new int[] {LEVEL_CHUNK, BLOCK_UPDATE, SECTION_UPDATE, FORGET}) {
            assertFalse(stream.wants(IntakeWire.CLIENTBOUND, id));
        }
        assertTrue(stream.wants(IntakeWire.CLIENTBOUND, TELEPORT));
    }

    @Test
    void chunkPacketsOfTheInitialCaptureAreLeftOutOnceProtocol5Joins() {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.chunks(new ChunkIds(LEVEL_CHUNK, BLOCK_UPDATE, SECTION_UPDATE, FORGET));
        stream.prepare(sink);
        byte[] chunk = new SyntheticTerrain().levelChunk(LEVEL_CHUNK, 0, 0);
        byte[] update = blockUpdate(1, 70, 1, 9);
        stream.tap(IntakeWire.CLIENTBOUND, LEVEL_CHUNK, chunk, nanos, -64, 384, SESSION);
        stream.tap(IntakeWire.CLIENTBOUND, BLOCK_UPDATE, update, nanos, -64, 384, SESSION);
        stream.join(sink, IntakeWire.join(PLAYER, PROTOCOL, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        stream.capture(new int[] {TICK_END}, new int[] {LEVEL_CHUNK, BLOCK_UPDATE, TELEPORT}, new int[] {RESPAWN});
        tick(stream);

        for (Record record : sink.records()) {
            assertFalse(record.hasClientbound()
                    && isTerrainPacket(record.getClientbound().toByteArray()[0]));
        }
    }

    @Test
    void sectionRecordsCarryTheirClockButNoServerTick() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        stream.endOfTick(100);
        byte[] ping = ByteBuffer.allocate(5).put((byte) PING).putInt(1).array();
        stream.tap(IntakeWire.CLIENTBOUND, PING, ping, nanos, -64, 384, SESSION, IntakeWire.PING_END_OF_TICK);
        nanos += 2_000_000;
        stream.blockChanged(0, 64, 0, 9, nanos);
        stream.endOfTick(101);
        stream.tap(IntakeWire.CLIENTBOUND, PING, ping, nanos, -64, 384, SESSION, IntakeWire.PING_END_OF_TICK);
        tick(stream);

        boolean advanced = false;
        for (Record record : sink.records()) {
            if (!record.hasSection() && !record.hasBlock() && !record.hasSectionGone()) continue;
            assertEquals(0, record.getTickDelta());
            assertEquals(0, record.getServerTickDelta());
            advanced |= record.hasBlock() && record.getAdvanceNanos() == 2_000;
        }
        assertTrue(advanced, "a block record carries its clock's advance in microseconds");
        long pings = sink.records().stream()
                .filter(record -> record.hasPing() && record.getTickDelta() == 1)
                .count();
        assertEquals(1, pings, "the second ping's tick delta skips the terrain records between them");
    }

    @Test
    void sectionsOfAFrameTheSocketRefusedGoAgainAtTheNextCut() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        sink.refuse = true;
        tick(stream);
        assertTrue(received.isEmpty());

        sink.refuse = false;
        tick(stream);
        tick(stream);
        assertEquals(inRange(0, 4, 0), received);
    }

    @Test
    void aSectionGoneInARefusedUnloadFrameGoesAgain() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        tick(stream);

        terrain.unload(1, 1);
        stream.columnUnloaded(1, 1, nanos);
        sink.refuse = true;
        tick(stream);
        assertTrue(received.contains(Sections.pack(1, 4, 1)), "the receiver never got the gone");

        sink.refuse = false;
        tick(stream);
        for (int y = 2; y <= 6; y++) assertFalse(received.contains(Sections.pack(1, y, 1)));
        assertEquals(0, stream.sections().goneToSend());
    }

    @Test
    void sectionGonesInARefusedMoveFrameGoAgain() {
        terrain.loadAround(0, 0, 3);
        terrain.loadAround(50, 50, 3);
        terrain.at(0, 4, 0);
        CaptureStream stream = joined();
        tick(stream);

        terrain.at(50, 4, 50);
        sink.refuse = true;
        move(stream);
        tick(stream);
        sink.refuse = false;
        tick(stream);
        tick(stream);

        assertEquals(inRange(50, 4, 50), received);
    }

    @Test
    void aSectionGoneLeftOutOfAFrameItDidNotFitGoesAgain() {
        boolean leftOut = false;
        for (int big = 64 * 1024 - 64; big > 63 * 1024 && !leftOut; big -= 2) {
            leftOut = new CaptureStreamSectionsTest().goneLeftOutGoesAgain(big);
        }
        assertTrue(leftOut, "some frame left the gone out");
    }

    /** A frame that a packet of {@code bigBytes} nearly fills; true when its gone is left out and sent again. */
    private boolean goneLeftOutGoesAgain(int bigBytes) {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        tick(stream);

        sink.maxFrameBytes = 64 * 1024;
        byte[] big = new byte[bigBytes];
        big[0] = TELEPORT;
        stream.tap(IntakeWire.CLIENTBOUND, TELEPORT, big, nanos, -64, 384, SESSION);
        terrain.unload(-1, 0);
        stream.columnUnloaded(-1, 0, nanos);
        tick(stream);
        if (!received.contains(Sections.pack(-1, 4, 0))) return false;

        tick(stream);
        for (int y = 2; y <= 6; y++) assertFalse(received.contains(Sections.pack(-1, y, 0)));
        return true;
    }

    @Test
    void sectionsPastAFramesRoomGoInLaterFramesAndDeliveredOnesStay() {
        terrain.blockBytes = 100;
        loadAroundOrigin(3);
        sink.maxFrameBytes = 4096;
        CaptureStream stream = joined();
        int ticksToAll = -1;
        for (int t = 1; t <= 20; t++) {
            tick(stream);
            if (ticksToAll < 0 && received.equals(inRange(0, 4, 0))) ticksToAll = t;
        }

        assertEquals(inRange(0, 4, 0), received);
        assertTrue(ticksToAll > 0 && ticksToAll <= 12, "every section within 12 ticks, not " + ticksToAll);
        assertEquals(125, sink.sentInOrder().size(), "no delivered section goes again");
        assertTrue(sink.records().stream().noneMatch(Record::hasGap), "no frame leaves terrain out");
        assertEquals(125, stream.sections().held());
    }

    @Test
    void aFrameOutOfRoomReadsNoSectionOnLaterServerboundTaps() {
        terrain.blockBytes = 100;
        loadAroundOrigin(3);
        sink.maxFrameBytes = 4096;
        CaptureStream stream = joined();
        int reads = reads();
        for (int i = 0; i < 20; i++) move(stream);
        assertEquals(reads, reads());
        tick(stream);
        move(stream);
        assertTrue(reads() > reads, "the next frame carries on");
    }

    @Test
    void aBackloggedSocketGetsNoSectionsUntilItDrains() {
        loadAroundOrigin(3);
        sink.backlogged = true;
        CaptureStream stream = joined();
        tick(stream);
        assertEquals(0, reads());
        assertTrue(received.isEmpty());
        sink.backlogged = false;
        tick(stream);
        assertEquals(inRange(0, 4, 0), received);
    }

    @Test
    void aColumnLoadOnABackloggedSocketReadsNoSectionUntilItDrains() {
        loadAroundOrigin(3);
        CaptureStream stream = joined();
        tick(stream);

        sink.backlogged = true;
        sink.clear();
        int reads = reads();
        stream.columnLoaded(1, -1, nanos);
        tick(stream);
        assertEquals(reads, reads());
        assertTrue(sink.sentInOrder().isEmpty());

        sink.backlogged = false;
        tick(stream);
        List<Long> sent = sink.sentInOrder();
        for (int y = 2; y <= 6; y++) assertTrue(sent.contains(Sections.pack(1, y, -1)));
    }

    @Test
    void aColumnLoadIntoAFrameOutOfRoomReadsNoSection() {
        terrain.blockBytes = 100;
        loadAroundOrigin(3);
        sink.maxFrameBytes = 4096;
        CaptureStream stream = joined();
        move(stream);
        int reads = reads();
        stream.columnLoaded(1, -1, nanos);
        stream.columnLoaded(-1, 1, nanos);
        assertEquals(reads, reads());
    }

    private int reads() {
        return terrain.reads.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void terrainWaitsForASpentServerBudgetAndTheFrameStays() {
        terrain.blockBytes = CaptureStream.VERDICT_BYTES + 4096;
        loadAroundOrigin(3);
        sink.maxHold = CaptureStream.VERDICT_BYTES + 1024;
        CaptureStream stream = unanswered();
        move(stream);
        tick(stream);
        answer(stream);
        apply();

        assertTrue(received.isEmpty());
        assertTrue(sink.records().stream()
                .anyMatch(r -> r.hasServerbound() && r.getServerbound().byteAt(0) == MOVE));
        assertTrue(sink.records().stream().noneMatch(Record::hasGap));
        sink.maxHold = Long.MAX_VALUE;
        for (int t = 0; t < 5; t++) tick(stream);
        assertEquals(inRange(0, 4, 0), received);
    }

    @Test
    void aSectionLargerThanHalfAFrameGoesInAFrameOfItsOwn() {
        terrain.blockBytes = 100;
        terrain.sizes.put(Sections.pack(0, 2, 0), 2058);
        loadAroundOrigin(3);
        sink.maxFrameBytes = 4096;
        CaptureStream stream = joined();
        for (int t = 0; t < 20; t++) tick(stream);

        assertEquals(inRange(0, 4, 0), received);
        assertTrue(sink.records().stream().noneMatch(Record::hasGap), "no frame leaves terrain out");
    }

    @Test
    void aSectionNoFrameTakesIsLeftOutOnceAndTheRestArrive() {
        long tooLarge = Sections.tooLarge.get();
        long big = Sections.pack(0, 2, 0);
        terrain.blockBytes = 100;
        terrain.sizes.put(big, 10_000);
        loadAroundOrigin(3);
        sink.maxFrameBytes = 4096;
        CaptureStream stream = joined();
        for (int t = 0; t < 20; t++) tick(stream);

        Set<Long> expected = inRange(0, 4, 0);
        expected.remove(big);
        assertEquals(expected, received);
        assertFalse(stream.sections().holds(0, 2, 0));
        assertEquals(1, terrain.reads.get(big), "it is not read again every tick");
        assertEquals(tooLarge + 1, Sections.tooLarge.get());
    }

    @Test
    void sustainedRefusalKeepsOwedGonesBoundedAndStartsOverOnce() {
        loadAroundOrigin(2);
        CaptureStream stream = joined();
        tick(stream);
        sink.refuse = true;
        int moves = 0;
        int after = -1;
        for (int i = 1; i <= 80 && after < 2; i++) {
            terrain.loadAround(i * 10, 0, 2);
            terrain.at(i * 10, 4, 0);
            move(stream);
            tick(stream);
            moves = i;
            assertTrue(stream.sections().goneToSend() <= Sections.MAX_GONE);
            if (stream.sections().restarts() > 0) after++;
        }
        assertEquals(1, stream.sections().restarts(), "it started over once in " + moves + " refused moves");

        sink.refuse = false;
        sink.clear();
        tick(stream);
        tick(stream);
        assertEquals(1, sink.records().stream().filter(Record::hasWorld).count());
        assertEquals(inRange(moves * 10, 4, 0), received);
        assertEquals(0, stream.sections().goneToSend());
    }

    @Test
    void aProtocol3SocketKeepsItsChunkPacketsAndSendsNoSections() {
        loadAroundOrigin(3);
        sink.terms = CloudConnection.Terms.PROTOCOL_3;
        CaptureStream stream = joined();
        byte[] update = blockUpdate(1, 70, 1, 9);
        assertTrue(stream.wants(IntakeWire.CLIENTBOUND, BLOCK_UPDATE));
        stream.tap(IntakeWire.CLIENTBOUND, BLOCK_UPDATE, update, nanos, -64, 384, SESSION);
        stream.blockChanged(1, 70, 1, 9, nanos);
        tick(stream);

        assertTrue(sink.records().stream().noneMatch(r -> r.hasSection() || r.hasBlock()));
        assertTrue(sink.records().stream().anyMatch(Record::hasClientbound));
        assertFalse(stream.sections().active());
    }

    private CaptureStream joined() {
        CaptureStream stream = unanswered();
        answer(stream);
        return stream;
    }

    /** Joined, before the service answers the JOIN. */
    private CaptureStream unanswered() {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.pingPacket(PING);
        stream.chunks(new ChunkIds(LEVEL_CHUNK, BLOCK_UPDATE, SECTION_UPDATE, FORGET));
        stream.terrain(terrain);
        stream.join(sink, IntakeWire.join(PLAYER, PROTOCOL, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        return stream;
    }

    private static void answer(CaptureStream stream) {
        stream.capture(
                new int[] {TICK_END, MOVE},
                new int[] {LEVEL_CHUNK, BLOCK_UPDATE, SECTION_UPDATE, FORGET, TELEPORT, PING},
                new int[] {RESPAWN});
    }

    /** A {@code block_update} at a position packed x 26 bits, z 26, y 12. */
    private static byte[] blockUpdate(int x, int y, int z, int state) {
        return ByteBuffer.allocate(10)
                .put((byte) BLOCK_UPDATE)
                .putLong((x & 0x3FFFFFFL) << 38 | (z & 0x3FFFFFFL) << 12 | y & 0xFFFL)
                .put((byte) state)
                .array();
    }

    /** A {@code section_blocks_update} of one record, at a section position packed x 22 bits, z 22, y 20. */
    private static byte[] sectionUpdate(int sectionX, int sectionY, int sectionZ) {
        return ByteBuffer.allocate(12)
                .put((byte) SECTION_UPDATE)
                .putLong((sectionX & 0x3FFFFFL) << 42 | (sectionZ & 0x3FFFFFL) << 20 | sectionY & 0xFFFFFL)
                .put((byte) 1)
                .put((byte) 0x80)
                .put((byte) 0x20)
                .array();
    }

    /** A {@code forget_level_chunk}: z, then x. */
    private static byte[] forget(int x, int z) {
        return ByteBuffer.allocate(9).put((byte) FORGET).putInt(z).putInt(x).array();
    }

    /** Loads the columns within {@code radius} of the origin and puts the player at section 0, 4, 0. */
    private void loadAroundOrigin(int radius) {
        terrain.loadAround(0, 0, radius);
        terrain.at(0, 4, 0);
    }

    /** Moves the player to section {@code x}, {@code y}, {@code z} and ends a tick; the sink keeps that tick alone. */
    private void walkTo(CaptureStream stream, int x, int y, int z) {
        terrain.at(x, y, z);
        sink.clear();
        move(stream);
        tick(stream);
    }

    private void move(CaptureStream stream) {
        nanos += 1_000_000;
        stream.tap(IntakeWire.SERVERBOUND, MOVE, new byte[] {MOVE, 1}, nanos, 0, 0, null);
    }

    private void tick(CaptureStream stream) {
        nanos += 50_000_000;
        stream.tap(IntakeWire.SERVERBOUND, TICK_END, new byte[] {TICK_END}, nanos, 0, 0, null);
        stream.cut(nanos);
        ticks++;
        stream.verdict(ticks, ticks, 0, List.of(), null, List.of(), null);
        apply();
    }

    /** What a receiver holds after the records sent so far. */
    private void apply() {
        for (Record record : sink.unapplied()) {
            if (record.hasSection()) {
                SectionRecord section = record.getSection();
                received.add(Sections.pack(section.getX(), section.getY(), section.getZ()));
            } else if (record.hasSectionGone()) {
                SectionGone gone = record.getSectionGone();
                received.remove(Sections.pack(gone.getX(), gone.getY(), gone.getZ()));
            } else if (record.hasBlock()) {
                BlockRecord block = record.getBlock();
                assertTrue(received.contains(Sections.pack(block.getX() >> 4, block.getY() >> 4, block.getZ() >> 4)));
            } else if (record.hasWorld()) {
                received.clear();
            }
        }
    }

    private static Set<Long> inRange(int x, int y, int z) {
        Set<Long> sections = new LinkedHashSet<>();
        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                for (int dy = -SECTION_RADIUS; dy <= SECTION_RADIUS; dy++) {
                    if (y + dy < MIN_SECTION || y + dy > MAX_SECTION) continue;
                    sections.add(Sections.pack(x + dx, y + dy, z + dz));
                }
            }
        }
        return sections;
    }

    private static boolean withinBand(long section, int x, int y, int z) {
        return Math.abs(Sections.x(section) - x) <= CHUNK_RADIUS + 1
                && Math.abs(Sections.z(section) - z) <= CHUNK_RADIUS + 1
                && Math.abs(Sections.y(section) - y) <= SECTION_RADIUS + 1;
    }

    private static long column(long section) {
        return SyntheticTerrain.pack(Sections.x(section), Sections.z(section));
    }

    private static boolean isTerrainPacket(byte id) {
        return id == LEVEL_CHUNK || id == BLOCK_UPDATE || id == SECTION_UPDATE || id == FORGET;
    }

    /** A world of loaded columns, every section's blocks its own position, and the player's section. */
    private static final class FakeTerrain implements Sections.Terrain {
        private final Set<Long> loaded = new HashSet<>();
        private long position;
        /** Bytes each section's blocks take; 4 writes its position. */
        int blockBytes = 4;
        /** Sections whose blocks take other than {@link #blockBytes}. */
        final Map<Long, Integer> sizes = new HashMap<>();
        /** Times each section's blocks were read. */
        final Map<Long, Integer> reads = new HashMap<>();

        void at(int x, int y, int z) {
            position = Sections.pack(x, y, z);
        }

        void load(int x, int z) {
            loaded.add(SyntheticTerrain.pack(x, z));
        }

        void unload(int x, int z) {
            loaded.remove(SyntheticTerrain.pack(x, z));
        }

        void unloadAll() {
            loaded.clear();
        }

        void loadAround(int x, int z, int radius) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) load(x + dx, z + dz);
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public int minSection() {
            return MIN_SECTION;
        }

        @Override
        public int maxSection() {
            return MAX_SECTION;
        }

        @Override
        public byte[] blocks(int x, int y, int z) {
            if (!loaded.contains(SyntheticTerrain.pack(x, z))) return null;
            long section = Sections.pack(x, y, z);
            reads.merge(section, 1, Integer::sum);
            byte[] blocks = new byte[sizes.getOrDefault(section, blockBytes)];
            blocks[1] = (byte) x;
            blocks[2] = (byte) y;
            blocks[3] = (byte) z;
            return blocks;
        }
    }

    /** A protocol 5 socket that keeps every frame; {@link #records} are those since the last {@link #clear}. */
    private static final class Sink implements CaptureStream.Sink {
        final List<byte[]> frames = new ArrayList<>();
        CloudConnection.Terms terms = CloudConnection.Terms.sections(CHUNK_RADIUS, SECTION_RADIUS);
        boolean refuse;
        boolean backlogged;
        long maxHold = Long.MAX_VALUE;
        int maxFrameBytes = 16 << 20;
        int applied;
        private int from;

        @Override
        public boolean send(byte[] record) {
            if (refuse && TestSink.message(record).hasFrame()) return false;
            frames.add(record);
            return true;
        }

        @Override
        public boolean hold(long bytes) {
            return bytes <= maxHold;
        }

        @Override
        public void release(long bytes) {}

        @Override
        public int maxFrameBytes() {
            return maxFrameBytes;
        }

        @Override
        public CloudConnection.Terms terms() {
            return terms;
        }

        @Override
        public boolean backlogged() {
            return backlogged;
        }

        void clear() {
            from = frames.size();
        }

        /** Every record since the last clear. */
        List<Record> records() {
            return records(from);
        }

        /** The records the receiver has not applied yet. */
        List<Record> unapplied() {
            List<Record> records = records(applied);
            applied = frames.size();
            return records;
        }

        Set<Long> sent() {
            return new LinkedHashSet<>(sentInOrder());
        }

        List<Long> sentInOrder() {
            List<Long> sent = new ArrayList<>();
            for (Record record : records()) {
                if (!record.hasSection()) continue;
                SectionRecord section = record.getSection();
                sent.add(Sections.pack(section.getX(), section.getY(), section.getZ()));
                byte[] blocks = section.getBlocks().toByteArray();
                assertArrayEquals(
                        new byte[] {0, (byte) section.getX(), (byte) section.getY(), (byte) section.getZ()},
                        Arrays.copyOf(blocks, 4));
            }
            return sent;
        }

        Set<Long> gone() {
            Set<Long> gone = new LinkedHashSet<>();
            for (Record record : records()) {
                if (!record.hasSectionGone()) continue;
                SectionGone section = record.getSectionGone();
                gone.add(Sections.pack(section.getX(), section.getY(), section.getZ()));
            }
            return gone;
        }

        List<BlockRecord> blocks() {
            List<BlockRecord> blocks = new ArrayList<>();
            for (Record record : records()) if (record.hasBlock()) blocks.add(record.getBlock());
            return blocks;
        }

        private List<Record> records(int start) {
            List<Record> records = new ArrayList<>();
            for (int i = start; i < frames.size(); i++) {
                IntakeMessage message = TestSink.message(frames.get(i));
                if (message.hasFrame()) records.addAll(message.getFrame().getRecordsList());
            }
            return records;
        }
    }
}
