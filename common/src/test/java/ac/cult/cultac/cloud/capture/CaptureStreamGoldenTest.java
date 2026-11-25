package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The exact bytes one player's capture sends for a fixed tap sequence: server tick ends, worlds, lagging and missing
 * verdicts, a refused frame and an overflowing one.
 */
class CaptureStreamGoldenTest {
    private static final UUID PLAYER = new UUID(3, 4);
    private static final int TICK_END = 1;
    private static final int PONG = 2;
    private static final int MOVE = 3;
    private static final int PING = 4;
    private static final int ENTITY = 6;
    private static final int RESPAWN = 7;
    private static final int TICKS = 400;
    private static final String EXPECTED = "48e240467e1eb926cf5db8c919a0c15fee0f2449fe7a404dc6a241d1bb6087f2";

    private static final class Digesting implements CaptureStream.Sink {
        final MessageDigest digest;
        int sends;
        long bytes;

        Digesting() throws NoSuchAlgorithmException {
            digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public boolean send(byte[] record) {
            sends++;
            if (sends % 97 == 0) return false;
            digest.update(ByteBuffer.allocate(4).putInt(record.length).array());
            digest.update(record);
            bytes += record.length;
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
            return 1 << 20;
        }

        @Override
        public CloudConnection.Terms terms() {
            return CloudConnection.Terms.sections(0, 0);
        }
    }

    private static ByteBuf packet(int id, int bodyBytes, SplittableRandom random) {
        byte[] bytes = new byte[1 + bodyBytes];
        bytes[0] = (byte) id;
        for (int i = 1; i < bytes.length; i++) bytes[i] = (byte) random.nextInt(256);
        return Unpooled.wrappedBuffer(bytes);
    }

    private static ByteBuf probe(int id, int wireId) {
        return Unpooled.wrappedBuffer(
                ByteBuffer.allocate(5).put((byte) id).putInt(wireId).array());
    }

    private static void tap(
            CaptureStream stream, int kind, int id, ByteBuf body, long nanos, CaptureStream.Session session, int ping) {
        if (stream.wants(kind, id)) stream.tap(kind, id, body, nanos, -64, 384, session, ping);
        body.release();
    }

    @Test
    void aFixedTapSequenceSendsTheSameBytes() throws NoSuchAlgorithmException {
        SplittableRandom random = new SplittableRandom(7);
        Digesting sink = new Digesting();
        CaptureStream.Session overworld = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
        CaptureStream.Session nether = new CaptureStream.Session(7, 1, "minecraft:the_nether", true);
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.pingPacket(PING);
        stream.pongPacket(PONG);
        stream.join(sink, IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0), 0, overworld);
        stream.capture(new int[] {TICK_END, PONG, MOVE}, new int[] {PING, ENTITY}, new int[] {RESPAWN});
        long nanos = 0;
        int wireId = 1;
        long serverTick = 100;
        long verdicted = 0;
        for (int t = 1; t <= TICKS; t++) {
            int entities = t == 250 ? 200_000 : 3 + random.nextInt(40);
            for (int i = 0; i < entities; i++) {
                nanos += 1_000 + random.nextInt(400_000);
                int size = t == 250 ? 40 : random.nextInt(10) == 0 ? 300 + random.nextInt(600) : 4 + random.nextInt(60);
                tap(stream, IntakeWire.CLIENTBOUND, ENTITY, packet(ENTITY, size, random), nanos, overworld, 0);
                if (random.nextInt(8) == 0) {
                    stream.endOfTick(serverTick++);
                    nanos += 7_000;
                    tap(
                            stream,
                            IntakeWire.CLIENTBOUND,
                            PING,
                            probe(PING, wireId++),
                            nanos,
                            overworld,
                            IntakeWire.PING_END_OF_TICK);
                }
            }
            if (t % 60 == 0) {
                nanos += 3_000;
                tap(stream, IntakeWire.CLIENTBOUND, RESPAWN, packet(RESPAWN, 30, random), nanos, nether, 0);
            }
            nanos += 20_000;
            tap(stream, IntakeWire.CLIENTBOUND, PING, probe(PING, wireId++), nanos, overworld, IntakeWire.PING_FOREIGN);
            nanos += 20_000;
            tap(stream, IntakeWire.SERVERBOUND, PONG, probe(PONG, wireId - 2), nanos, overworld, 0);
            tap(stream, IntakeWire.SERVERBOUND, MOVE, packet(MOVE, 25, random), nanos, overworld, 0);
            tap(stream, IntakeWire.SERVERBOUND, TICK_END, packet(TICK_END, 0, random), nanos, overworld, 0);
            stream.cut(nanos);
            if (t % 5 != 0 || random.nextBoolean()) {
                long upTo = t - random.nextInt(4);
                for (long index = verdicted + 1; index <= upTo; index++) {
                    if (index % 11 != 0) stream.verdict(index, index, 0, List.of(), null, List.of(), null);
                }
                verdicted = Math.max(verdicted, upTo);
            }
        }
        stream.leave(nanos + 1_000);
        String hash = HexFormat.of().formatHex(sink.digest.digest());
        System.out.println("CAPTURE-GOLDEN " + hash + " " + sink.sends + " sends " + sink.bytes + " B");
        assertEquals(EXPECTED, hash);
    }

    private static final String TERRAIN_EXPECTED = "1961016d5edab473bebbc0e1f1ea76860ed83760db9eb3c32d2848166015ab79";
    private static final int STATES = DataTables.defaults().registry().stateCount();

    /** A socket for terrain: sections within three columns and one index, frames of 64 KiB, budget refused at times. */
    private static final class TerrainSink implements CaptureStream.Sink {
        final Digesting digest;
        final int maxFrameBytes;
        int holds;

        TerrainSink(Digesting digest, int maxFrameBytes) {
            this.digest = digest;
            this.maxFrameBytes = maxFrameBytes;
        }

        @Override
        public boolean send(byte[] record) {
            return digest.send(record);
        }

        @Override
        public boolean hold(long bytes) {
            return ++holds % 41 != 0;
        }

        @Override
        public void release(long bytes) {}

        @Override
        public int maxFrameBytes() {
            return maxFrameBytes;
        }

        @Override
        public CloudConnection.Terms terms() {
            return CloudConnection.Terms.sections(3, 1);
        }
    }

    @Test
    void aFixedWalkSendsTheSameTerrain() throws NoSuchAlgorithmException {
        SplittableRandom random = new SplittableRandom(23);
        Digesting digest = new Digesting();
        CaptureStream.Session overworld = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
        for (int maxFrameBytes : new int[] {64 * 1024, 12 * 1024, 1 << 20}) {
            TerrainSink sink = new TerrainSink(digest, maxFrameBytes);
            SyntheticTerrain terrain = new SyntheticTerrain();
            CaptureStream stream = new CaptureStream(PLAYER);
            stream.start(0);
            stream.pingPacket(PING);
            stream.pongPacket(PONG);
            stream.chunks(new ChunkIds(200, 201, 202, 203));
            stream.terrain(terrain);
            terrain.at(8, 70, 8);
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) terrain.load(x, z);
            // A section of every palette width, and past the widest into the registry's own ids.
            int[] widths = {2, 9, 17, 33, 65, 129, 300, 1200};
            for (int i = 0; i < widths.length; i++) {
                for (int b = 0; b < 4096; b++) {
                    int id = 1 + (b * 7919 + i) % widths[i];
                    terrain.set(16 * (i - 4) + (b & 15), 64 + (b >> 8), b >> 4 & 15, id);
                }
            }
            stream.join(sink, IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0), 0, overworld);
            stream.capture(new int[] {TICK_END, PONG, MOVE}, new int[] {PING, ENTITY}, new int[] {RESPAWN});
            long nanos = 0;
            int wireId = 1;
            for (int t = 1; t <= TICKS; t++) {
                for (int i = 0; i < 3 + random.nextInt(6); i++) {
                    nanos += 1_000 + random.nextInt(200_000);
                    ByteBuf entity = packet(ENTITY, 4 + random.nextInt(60), random);
                    tap(stream, IntakeWire.CLIENTBOUND, ENTITY, entity, nanos, overworld, 0);
                }
                for (int i = random.nextInt(4) == 0 ? 1 + random.nextInt(30) : 0; i > 0; i--) {
                    int x = random.nextInt(-40, 40);
                    int y = random.nextInt(40, 100);
                    int z = random.nextInt(-40, 40);
                    stream.blockChanged(x, y, z, terrain.set(x, y, z, random.nextInt(1, STATES)), nanos);
                }
                if (t % 37 == 0) {
                    int x = random.nextInt(-5, 6);
                    int z = random.nextInt(-5, 6);
                    if (terrain.loaded(x, z)) {
                        terrain.unload(x, z);
                        stream.columnUnloaded(x, z, nanos);
                    } else {
                        terrain.load(x, z);
                        stream.columnLoaded(x, z, nanos);
                    }
                }
                if (t % 9 == 0) {
                    terrain.at(16 * random.nextInt(-3, 4) + 8, random.nextInt(40, 110), 16 * random.nextInt(-3, 4));
                }
                nanos += 20_000;
                ByteBuf ping = probe(PING, wireId++);
                tap(stream, IntakeWire.CLIENTBOUND, PING, ping, nanos, overworld, IntakeWire.PING_FOREIGN);
                tap(stream, IntakeWire.SERVERBOUND, PONG, probe(PONG, wireId - 1), nanos, overworld, 0);
                tap(stream, IntakeWire.SERVERBOUND, MOVE, packet(MOVE, 25, random), nanos, overworld, 0);
                tap(stream, IntakeWire.SERVERBOUND, TICK_END, packet(TICK_END, 0, random), nanos, overworld, 0);
                stream.cut(nanos);
                long verdict = t - random.nextInt(3);
                if (verdict > 0) stream.verdict(verdict, verdict, 0, List.of(), null, List.of(), null);
            }
            stream.leave(nanos + 1_000);
        }
        String hash = HexFormat.of().formatHex(digest.digest.digest());
        System.out.println("CAPTURE-TERRAIN-GOLDEN " + hash + " " + digest.sends + " sends " + digest.bytes + " B");
        assertEquals(TERRAIN_EXPECTED, hash);
    }
}
