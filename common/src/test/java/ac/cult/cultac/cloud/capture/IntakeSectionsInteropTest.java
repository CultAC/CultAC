package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.BlockRecord;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.SectionGone;
import ac.cult.cultac.cloud.intake.v3.SectionRecord;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ProtocolData;
import com.github.luben.zstd.ZstdDecompressCtx;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * One scripted protocol 5 session as the real {@link CaptureStream}, {@link Sections}, {@link SectionBytes} and
 * {@link IntakeWire.StreamWriter} write it, and what a receiver applying it ends with. The written file is the messages
 * back to back, each a 4-byte big-endian length and then the message as it goes on the socket: the flags byte
 * ({@code 0x01}) and one flush of the socket's zstd frame.
 *
 * <p>One player on client protocol {@value #CLIENT_PROTOCOL}, the server on {@value #SERVER_PROTOCOL}, terms chunk
 * radius {@value #CHUNK_RADIUS} and section radius {@value #SECTION_RADIUS}, the terrain {@link SyntheticTerrain}'s.
 * Each tick is a frame: a move, an end-of-tick ping (a marker), the client's tick end.
 * <ol>
 *   <li>JOIN at block (8, 70, 8): the 125 sections around chunk (0, 0), section 4.
 *   <li>Walks east into chunks 1, 2 and 3: each crossing sends the new edge, and from chunk 2 on the column two
 *       behind goes as {@code section_gone}.
 *   <li>Rises into sections 5 and 6: the new layers, then the bottom one goes.
 *   <li>A block in range turns to diamond (a {@code block} record) and one 5 chunks away to gold (no record).
 *   <li>Teleports to block (648, 90, -472): every held section goes, the 125 there are sent.
 *   <li>The column at chunk (40, -29) loads again: its 5 sections in range go again.
 * </ol>
 * Then LEAVE.
 */
class IntakeSectionsInteropTest {
    static final UUID PLAYER = UUID.fromString("5c1f3a2e-7b4d-4e8f-9a0b-1c2d3e4f5a6c");
    static final int CLIENT_PROTOCOL = 774;
    static final int SERVER_PROTOCOL = 777;
    static final int CHUNK_RADIUS = 2;
    static final int SECTION_RADIUS = 2;
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    static final long BASE_NANOS = 1_000_000_000L;
    static final long TICK_NANOS = 50_000_000L;

    private static final CaptureStream.Session SESSION =
            new CaptureStream.Session(4242, 0, "minecraft:overworld", false);

    @Test
    void theSameSessionWritesTheSameBytes() {
        assertArrayEquals(session().file(), session().file());
    }

    @Test
    void theFileDecodesBackToTheMessagesAsWritten() throws IOException {
        Recorder recorder = session();
        List<byte[]> decoded = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(recorder.file()));
                ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            while (in.available() > 0) {
                byte[] message = new byte[in.readInt()];
                in.readFully(message);
                decoded.add(decompress(context, message));
            }
        }
        assertEquals(recorder.plain.size(), decoded.size());
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int i = 0; i < decoded.size(); i++) {
            assertArrayEquals(recorder.plain.get(i), decoded.get(i), "message " + i);
            all.writeBytes(decoded.get(i));
        }
        List<IntakeMessage> messages = Intake.parseFrom(all.toByteArray()).getMessagesList();
        assertEquals(IntakeMessage.KindCase.JOIN, messages.get(0).getKindCase());
        assertEquals(SERVER_PROTOCOL, messages.get(0).getJoin().getServerProtocol());
        assertEquals(
                IntakeMessage.KindCase.LEAVE, messages.get(messages.size() - 1).getKindCase());
        assertTrue(messages.stream()
                .anyMatch(message -> message.hasFrame()
                        && message.getFrame().getRecordsList().stream().anyMatch(Record::hasSection)));
    }

    /** One message of the socket's zstd frame, from its bytes and the messages before it alone. */
    private static byte[] decompress(ZstdDecompressCtx context, byte[] message) {
        assertEquals(IntakeWire.COMPRESSED, message[0]);
        ByteBuffer in = ByteBuffer.allocateDirect(message.length - 1);
        in.put(message, 1, message.length - 1).flip();
        ByteBuffer out = ByteBuffer.allocateDirect(64 * 1024);
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        while (true) {
            out.clear();
            assertFalse(context.decompressDirectByteBufferStream(out, in), "a message never ends the frame");
            out.flip();
            byte[] chunk = new byte[out.remaining()];
            out.get(chunk);
            records.writeBytes(chunk);
            if (!in.hasRemaining() && chunk.length < out.capacity()) return records.toByteArray();
        }
    }

    @Test
    void theSessionCoversEveryTerrainCase() {
        Recorder recorder = session();
        assertEquals(125, recorder.atJoin);
        assertTrue(recorder.gone > 125, "walking, rising and the teleport let sections go");
        assertEquals(1, recorder.blocks.size(), "only the block in range goes out");
        assertEquals(Sections.pack(40, 5, -30), recorder.teleportedTo);
        assertEquals(125, recorder.held.size());
        for (long section : recorder.held.keySet()) {
            assertTrue(Math.abs(Sections.x(section) - 40) <= 2 && Math.abs(Sections.z(section) - -30) <= 2);
        }
        assertEquals(5, recorder.reloaded);
        assertFalse(recorder.anyChunkPacket);
    }

    private Recorder session() {
        Ids ids = new Ids(CLIENT_PROTOCOL);
        SyntheticTerrain terrain = new SyntheticTerrain();
        for (int x = -7; x <= 10; x++) for (int z = -7; z <= 7; z++) terrain.load(x, z);
        Recorder recorder = new Recorder(ids);
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.pingPacket(ids.ping);
        stream.pongPacket(ids.pong);
        stream.chunks(new ChunkIds(ids.levelChunk, ids.blockUpdate, ids.sectionUpdate, ids.forget));
        stream.terrain(terrain);
        terrain.at(8, 70, 8);
        stream.join(
                recorder.sink(),
                IntakeWire.join(PLAYER, CLIENT_PROTOCOL, "vanilla", -64, 384, facts(), "Sections_A", SERVER_PROTOCOL),
                BASE_NANOS,
                SESSION);
        recorder.flush();
        stream.capture(ids.serverbound(), ids.clientbound(), ids.dimension());
        long tick = 0;
        tick(recorder, stream, ids, ++tick);
        recorder.atJoin = recorder.held.size();

        for (double x : new double[] {24, 40, 56}) {
            terrain.at(x, 70, 8);
            tick(recorder, stream, ids, ++tick);
        }
        for (double y : new double[] {86, 102}) {
            terrain.at(56, y, 8);
            tick(recorder, stream, ids, ++tick);
        }

        long at = BASE_NANOS + tick * TICK_NANOS + 5_000;
        stream.blockChanged(57, 100, 9, terrain.set(57, 100, 9, SyntheticTerrain.DIAMOND_BLOCK), at);
        stream.blockChanged(57 + 80, 64, 9, terrain.set(57 + 80, 64, 9, SyntheticTerrain.GOLD_BLOCK), at);
        tick(recorder, stream, ids, ++tick);

        for (int x = 33; x <= 47; x++) {
            for (int z = -37; z <= -23; z++) {
                terrain.load(x, z);
                stream.columnLoaded(x, z, BASE_NANOS + tick * TICK_NANOS + 7_000);
            }
        }
        byte[] teleport = ByteBuffer.allocate(62)
                .put((byte) ids.teleport)
                .put((byte) 1)
                .putDouble(648)
                .putDouble(90)
                .putDouble(-472)
                .array();
        stream.tap(
                IntakeWire.CLIENTBOUND,
                ids.teleport,
                teleport,
                BASE_NANOS + tick * TICK_NANOS + 9_000,
                -64,
                384,
                SESSION);
        terrain.at(648, 90, -472);
        recorder.teleportedTo = terrain.position();
        tick(recorder, stream, ids, ++tick);

        recorder.reloadFrom = recorder.sectionRecords;
        stream.columnLoaded(40, -29, BASE_NANOS + tick * TICK_NANOS + 3_000);
        tick(recorder, stream, ids, ++tick);
        recorder.reloaded = recorder.sectionRecords - recorder.reloadFrom;

        stream.leave(BASE_NANOS + (tick + 1) * TICK_NANOS);
        recorder.flush();
        return recorder;
    }

    private static IntakeWire.Facts facts() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("update-equipment-on-player-actions", "true");
        settings.put("skip-vanilla-damage-tick-when-shield-blocked", "false");
        settings.put("keepalive-interval-ms", "15000");
        Map<String, List<String>> registries = new LinkedHashMap<>();
        registries.put(
                "minecraft:damage_type", List.of("minecraft:arrow", "minecraft:generic", "minecraft:player_attack"));
        return new IntakeWire.Facts(
                4242, 2, "minecraft:overworld", 1_700_000_000_123L, 50_000, true, "Paper 26.3", settings, registries);
    }

    /** One client tick: a move, an end-of-tick ping, the tick end, its cut and verdict, then a flush. */
    private static void tick(Recorder recorder, CaptureStream stream, Ids ids, long tick) {
        long at = BASE_NANOS + tick * TICK_NANOS;
        stream.tap(IntakeWire.SERVERBOUND, ids.move, new byte[] {(byte) ids.move, 1, 2, 3}, at - 20_000, 0, 0, null);
        stream.endOfTick(100 + tick);
        byte[] ping =
                ByteBuffer.allocate(5).put((byte) ids.ping).putInt((int) tick).array();
        stream.tap(IntakeWire.CLIENTBOUND, ids.ping, ping, at - 10_000, -64, 384, SESSION, IntakeWire.PING_END_OF_TICK);
        stream.tap(IntakeWire.SERVERBOUND, ids.tickEnd, new byte[] {(byte) ids.tickEnd}, at - 1000, 0, 0, null);
        stream.cut(at - 1000);
        stream.verdict(tick, tick, 0, List.of(), null, List.of(), null);
        recorder.flush();
    }

    /** Client protocol {@value #CLIENT_PROTOCOL}'s packet ids, as cultac's protocol data has them. */
    private record Ids(
            int levelChunk,
            int blockUpdate,
            int sectionUpdate,
            int forget,
            int teleport,
            int ping,
            int pong,
            int tickEnd,
            int move,
            int login,
            int respawn) {
        Ids(int protocol) {
            this(
                    clientbound(protocol, "minecraft:level_chunk_with_light"),
                    clientbound(protocol, "minecraft:block_update"),
                    clientbound(protocol, "minecraft:section_blocks_update"),
                    clientbound(protocol, "minecraft:forget_level_chunk"),
                    clientbound(protocol, "minecraft:player_position"),
                    clientbound(protocol, "minecraft:ping"),
                    serverbound(protocol, "minecraft:pong"),
                    serverbound(protocol, "minecraft:client_tick_end"),
                    serverbound(protocol, "minecraft:move_player_pos"),
                    clientbound(protocol, "minecraft:login"),
                    clientbound(protocol, "minecraft:respawn"));
        }

        int[] serverbound() {
            return new int[] {tickEnd, move, pong};
        }

        int[] clientbound() {
            return new int[] {teleport, ping};
        }

        int[] dimension() {
            return new int[] {login, respawn};
        }

        private static int clientbound(int protocol, String packet) {
            return packets(protocol, PacketDirection.CLIENTBOUND).id(packet);
        }

        private static int serverbound(int protocol, String packet) {
            return packets(protocol, PacketDirection.SERVERBOUND).id(packet);
        }

        private static IdTable packets(int protocol, PacketDirection direction) {
            return ProtocolData.load(ProtocolVersion.of(protocol)).packets(ConnectionPhase.PLAY, direction);
        }
    }

    /** One socket: every record written at each flush as one zstd message; and what a receiver applying them holds. */
    private static final class Recorder {
        final Ids ids;
        final List<byte[]> queued = new ArrayList<>();
        /** Each message's records as written, before compression. */
        final List<byte[]> plain = new ArrayList<>();

        final IntakeWire.StreamWriter writer = new IntakeWire.StreamWriter();
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(file);
        /** The sections a receiver holds, each its blocks as last sent. */
        final Map<Long, byte[]> held = new LinkedHashMap<>();

        final List<BlockRecord> blocks = new ArrayList<>();
        int atJoin;
        int gone;
        int sectionRecords;
        int reloadFrom;
        int reloaded;
        long teleportedTo;
        boolean anyChunkPacket;

        Recorder(Ids ids) {
            this.ids = ids;
        }

        CaptureStream.Sink sink() {
            return new CaptureStream.Sink() {
                @Override
                public boolean send(byte[] record) {
                    queued.add(record);
                    return true;
                }

                @Override
                public boolean hold(long bytes) {
                    return true;
                }

                @Override
                public void release(long bytes) {}

                @Override
                public int maxFrameBytes() {
                    return MAX_MESSAGE_BYTES - 1 - MAX_MESSAGE_BYTES / 1024 - 64 - 64;
                }

                @Override
                public CloudConnection.Terms terms() {
                    return CloudConnection.Terms.sections(CHUNK_RADIUS, SECTION_RADIUS);
                }
            };
        }

        void flush() {
            if (queued.isEmpty()) return;
            int bytes = 0;
            ByteArrayOutputStream records = new ByteArrayOutputStream();
            for (byte[] record : queued) {
                apply(record);
                bytes += record.length;
                records.writeBytes(record);
            }
            plain.add(records.toByteArray());
            ByteBuffer written = writer.write(new ArrayList<>(queued), bytes, true);
            queued.clear();
            try {
                out.writeInt(written.remaining());
                out.write(written.array(), written.arrayOffset() + written.position(), written.remaining());
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private void apply(byte[] entry) {
            IntakeMessage message = TestSink.message(entry);
            if (message.getKindCase() != IntakeMessage.KindCase.FRAME) return;
            for (Record record : message.getFrame().getRecordsList()) {
                if (record.hasSection()) {
                    SectionRecord section = record.getSection();
                    held.put(
                            Sections.pack(section.getX(), section.getY(), section.getZ()),
                            section.getBlocks().toByteArray());
                    sectionRecords++;
                } else if (record.hasSectionGone()) {
                    SectionGone section = record.getSectionGone();
                    assertTrue(held.remove(Sections.pack(section.getX(), section.getY(), section.getZ())) != null);
                    gone++;
                } else if (record.hasBlock()) {
                    BlockRecord block = record.getBlock();
                    long section = Sections.pack(block.getX() >> 4, block.getY() >> 4, block.getZ() >> 4);
                    assertTrue(held.containsKey(section), "a block names a held section");
                    blocks.add(block);
                } else if (record.hasClientbound()) {
                    int id = IntakeWire.packetId(record.getClientbound().toByteArray());
                    anyChunkPacket |= id == ids.levelChunk
                            || id == ids.blockUpdate
                            || id == ids.sectionUpdate
                            || id == ids.forget;
                }
            }
        }

        byte[] file() {
            return file.toByteArray();
        }
    }
}
