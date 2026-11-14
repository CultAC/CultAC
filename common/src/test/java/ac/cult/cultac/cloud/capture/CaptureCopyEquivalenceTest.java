package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Packets tapped from wire buffers go out as the same frames as the same packets tapped as arrays of their own: several
 * players, each a scripted stream of packets from empty to a few KiB, bursts past what a frame's copies hold, packets
 * whose text is cut, pings and pongs, terrain sections, verdicts that lag, refused frames and a policy that answers
 * late.
 */
class CaptureCopyEquivalenceTest {
    private static final int PROTOCOL = 777;
    private static final int TICK_END = 1;
    private static final int PONG = 2;
    private static final int MOVE = 3;
    private static final int PING = 4;
    private static final int LEVEL_CHUNK = 200;
    private static final int PLAYERS = 3;
    private static final int TICKS = 120;
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);

    private record Drawn(boolean clientbound, int id, byte[] packet) {}

    /** A socket that keeps a copy of each frame it takes; a reusing one hands each frame's array out again once sent. */
    private static final class Socket implements CaptureStream.Sink {
        final List<byte[]> frames = new ArrayList<>();
        final boolean reusing;
        final ArrayDeque<byte[]> spare = new ArrayDeque<>();
        int sent;
        int refuseEvery;

        Socket(boolean reusing, int refuseEvery) {
            this.reusing = reusing;
            this.refuseEvery = refuseEvery;
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
            if (refuseEvery > 0 && ++sent % refuseEvery == 0) return false;
            frames.add(Arrays.copyOf(record, length));
            if (reusing) Arrays.fill(record, (byte) 0xA5);
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
            return 96 * 1024;
        }

        @Override
        public CloudConnection.Terms terms() {
            return CloudConnection.Terms.sections(2, 1);
        }
    }

    /** One player's two streams, fed the same packets: as arrays of their own, and from a reused wire buffer. */
    private static final class Player {
        final Socket arraysSocket;
        final Socket wireSocket;
        final CaptureStream arrays;
        final CaptureStream wire;
        final SyntheticTerrain arraysTerrain = new SyntheticTerrain();
        final SyntheticTerrain wireTerrain = new SyntheticTerrain();
        final ByteBuf buffer;

        Player(int index) {
            UUID player = new UUID(index, index + 1);
            arraysSocket = new Socket(false, 11 + index);
            wireSocket = new Socket(true, 11 + index);
            arrays = stream(player, arraysSocket, arraysTerrain);
            wire = stream(player, wireSocket, wireTerrain);
            buffer = index % 2 == 0
                    ? PooledByteBufAllocator.DEFAULT.directBuffer(8192)
                    : PooledByteBufAllocator.DEFAULT.heapBuffer(8192);
        }

        void tap(int kind, int id, byte[] packet, long nanos) {
            int minY = kind == IntakeWire.CLIENTBOUND ? -64 : 0;
            int height = kind == IntakeWire.CLIENTBOUND ? 384 : 0;
            CaptureStream.Session session = kind == IntakeWire.CLIENTBOUND ? SESSION : null;
            int pingKind = id == PING ? IntakeWire.PING_END_OF_TICK : 0;
            arrays.tap(kind, id, packet.clone(), nanos, minY, height, session, pingKind);
            buffer.clear().writeBytes(new byte[] {9, 9, 9}).writeBytes(packet).readerIndex(3);
            wire.tap(kind, id, buffer, nanos, minY, height, session, pingKind);
            buffer.setZero(0, buffer.capacity());
        }

        void both(Consumer<CaptureStream> action) {
            action.accept(arrays);
            action.accept(wire);
        }

        void at(double x, double y, double z) {
            arraysTerrain.at(x, y, z);
            wireTerrain.at(x, y, z);
        }

        void load(int x, int z) {
            arraysTerrain.load(x, z);
            wireTerrain.load(x, z);
        }
    }

    private static CaptureStream stream(UUID player, Socket socket, SyntheticTerrain terrain) {
        CaptureStream stream = new CaptureStream(player);
        stream.start(0);
        stream.pingPacket(PING);
        stream.pongPacket(PONG);
        stream.chunks(new ChunkIds(LEVEL_CHUNK, 201, 202, 203));
        stream.terrain(terrain);
        stream.text(PlayerText.of(PROTOCOL));
        stream.join(socket, IntakeWire.join(player, PROTOCOL, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        return stream;
    }

    private static void answer(CaptureStream stream) {
        int[] all = IntStream.range(0, 128).filter(id -> id != LEVEL_CHUNK).toArray();
        stream.capture(all, all, new int[0]);
    }

    @Test
    void wireTapsSendTheFramesArrayTapsSend() throws IOException {
        List<Drawn> drawn = corpus();
        SplittableRandom random = new SplittableRandom(11);
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            Player player = new Player(i);
            player.at(0, 70, 0);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    player.load(x, z);
                    int cx = x;
                    int cz = z;
                    player.both(stream -> stream.columnLoaded(cx, cz, 0));
                }
            }
            if (i != 1) player.both(CaptureCopyEquivalenceTest::answer);
            players.add(player);
        }
        long nanos = 1_000_000_000L;
        int next = 0;
        int pingId = 0;
        try {
            for (int tick = 1; tick <= TICKS; tick++) {
                for (int p = 0; p < PLAYERS; p++) {
                    Player player = players.get(p);
                    if (p == 1 && tick == 10) player.both(CaptureCopyEquivalenceTest::answer);
                    int packets = tick % 9 == p ? 400 : 40 + random.nextInt(40);
                    for (int i = 0; i < packets; i++) {
                        nanos += 1 + random.nextInt(200_000);
                        int pick = random.nextInt(16);
                        if (pick < 6) {
                            Drawn packet = drawn.get(next++ % drawn.size());
                            int kind = packet.clientbound() ? IntakeWire.CLIENTBOUND : IntakeWire.SERVERBOUND;
                            player.tap(kind, packet.id(), packet.packet(), nanos);
                        } else if (pick < 8) {
                            int id = ++pingId;
                            byte[] ping = {PING, (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id};
                            player.tap(IntakeWire.CLIENTBOUND, PING, ping, nanos);
                            if (random.nextBoolean()) {
                                byte[] pong = ping.clone();
                                pong[0] = PONG;
                                if (random.nextInt(4) == 0) pong = Arrays.copyOf(pong, 7);
                                player.tap(IntakeWire.SERVERBOUND, PONG, pong, nanos + 1);
                            }
                        } else {
                            int length = pick == 15 ? 513 + random.nextInt(4000) : random.nextInt(pick == 14 ? 513 : 64);
                            byte[] packet = new byte[length];
                            random.nextBytes(packet);
                            if (length > 0) packet[0] = (byte) (pick % 2 == 0 ? MOVE : 5 + random.nextInt(60));
                            int kind = pick % 2 == 0 ? IntakeWire.SERVERBOUND : IntakeWire.CLIENTBOUND;
                            player.tap(kind, length == 0 ? MOVE : packet[0], packet, nanos);
                        }
                    }
                    if (tick % 15 == 0) player.at(16 * (tick / 15 % 3) - 16, 70 + tick % 40, 0);
                    nanos += 1_000_000;
                    long end = nanos;
                    player.tap(IntakeWire.SERVERBOUND, TICK_END, new byte[] {TICK_END}, end);
                    player.both(stream -> stream.cut(end));
                    long verdict = tick - 3;
                    if (verdict > 0)
                        player.both(stream -> stream.verdict(verdict, verdict, 0, List.of(), null, List.of(), null));
                }
            }
            for (Player player : players) {
                for (long verdict = TICKS - 2; verdict <= TICKS; verdict++) {
                    long settled = verdict;
                    player.both(stream -> stream.verdict(settled, settled, 0, List.of(), null, List.of(), null));
                }
            }
            int[] seen = new int[5];
            for (Player player : players) {
                List<byte[]> expected = player.arraysSocket.frames;
                List<byte[]> actual = player.wireSocket.frames;
                assertTrue(expected.size() > TICKS / 2, expected.size() + " frames");
                assertEquals(expected.size(), actual.size());
                for (int i = 0; i < expected.size(); i++) {
                    assertArrayEquals(expected.get(i), actual.get(i), "frame " + i);
                    IntakeMessage message = TestSink.message(expected.get(i));
                    if (!message.hasFrame()) continue;
                    for (Record record : message.getFrame().getRecordsList()) {
                        if (record.hasSection()) seen[0]++;
                        if (record.hasPing() || record.hasPong()) seen[1]++;
                        if (record.hasClientbound() && record.getClientbound().size() > 512) seen[2]++;
                        if (record.hasServerbound() && record.getServerbound().size() <= 512) seen[3]++;
                    }
                }
            }
            assertTrue(Arrays.stream(seen, 0, 4).allMatch(count -> count > 20), Arrays.toString(seen));
            PlayerText text = PlayerText.of(PROTOCOL);
            PlayerText.TeamNames teams = new PlayerText.TeamNames(new byte[32]);
            long cut = drawn.stream()
                    .filter(d -> {
                        byte[] kept = d.clientbound()
                                ? text.clientbound(d.id(), d.packet(), true, teams)
                                : text.serverbound(d.id(), d.packet(), true);
                        return kept != null && kept != d.packet();
                    })
                    .count();
            assertTrue(cut > 20, cut + " packets lose text");
        } finally {
            for (Player player : players) player.buffer.release();
        }
    }

    /** The corpus's packets up to 8000 bytes, but for those a team name is hashed in, which no two streams share. */
    private static List<Drawn> corpus() throws IOException {
        PlayerText text = PlayerText.of(PROTOCOL);
        PlayerText.TeamNames one = new PlayerText.TeamNames(new byte[32]);
        PlayerText.TeamNames other = new PlayerText.TeamNames(new byte[] {1});
        List<Drawn> drawn = new ArrayList<>();
        try (InputStream resource =
                        CaptureCopyEquivalenceTest.class.getResourceAsStream("player-text/" + PROTOCOL + ".bin.gz");
                DataInputStream in =
                        new DataInputStream(new ByteArrayInputStream(new GZIPInputStream(resource).readAllBytes()))) {
            while (in.available() > 0) {
                boolean clientbound = in.readUnsignedByte() == 1;
                int id = varInt(in);
                byte[] packet = in.readNBytes(varInt(in));
                if (in.readUnsignedByte() == 2) in.skipNBytes(varInt(in));
                if (packet.length > 8000) continue;
                if (clientbound
                        && !Arrays.equals(
                                text.clientbound(id, packet, true, one), text.clientbound(id, packet, true, other)))
                    continue;
                drawn.add(new Drawn(clientbound, id, packet));
            }
        }
        return drawn;
    }

    private static int varInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
        }
    }
}
