package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The exact bytes one player's capture sends when every packet arrives in a wire buffer: heap, direct, pooled,
 * composite, read-only, sliced and little-endian buffers, every length up to past the copy pools' largest, and pings
 * read from them.
 */
class CaptureStreamWireGoldenTest {
    private static final UUID PLAYER = new UUID(5, 6);
    private static final int TICK_END = 1;
    private static final int PONG = 2;
    private static final int MOVE = 3;
    private static final int PING = 4;
    private static final int ENTITY = 6;
    private static final int TICKS = 300;
    private static final int KINDS = 8;
    private static final String EXPECTED = "64d0c2d7817cfc6146143283eb9730cedccf6760f6ecb96be3961e6c042e53d3";

    private static final class Digesting implements CaptureStream.Sink {
        final MessageDigest digest;
        int sends;
        long bytes;

        Digesting() throws NoSuchAlgorithmException {
            digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public boolean send(byte[] record) {
            return send(record, record.length, 0);
        }

        @Override
        public boolean send(byte[] record, int length, long resolvedExtra) {
            sends++;
            if (sends % 53 == 0) return false;
            digest.update(ByteBuffer.allocate(12).putInt(length).putLong(resolvedExtra).array());
            digest.update(record, 0, length);
            bytes += length;
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

    /** {@code packet}'s bytes as the readable bytes of a buffer of the given kind. */
    private static ByteBuf wire(byte[] packet, int kind) {
        int half = packet.length / 2;
        return switch (kind) {
            case 0 ->
                Unpooled.buffer(packet.length + 3)
                        .writeBytes(new byte[] {9, 9, 9})
                        .writeBytes(packet)
                        .readerIndex(3);
            case 1 -> Unpooled.directBuffer(packet.length).writeBytes(packet);
            case 2 -> PooledByteBufAllocator.DEFAULT.directBuffer(packet.length).writeBytes(packet);
            case 3 -> PooledByteBufAllocator.DEFAULT.heapBuffer(packet.length).writeBytes(packet);
            case 4 -> Unpooled.directBuffer(packet.length).writeBytes(packet).order(ByteOrder.LITTLE_ENDIAN);
            case 5 ->
                Unpooled.compositeBuffer()
                        .addComponents(
                                true,
                                Unpooled.directBuffer(half).writeBytes(packet, 0, half),
                                Unpooled.buffer(packet.length - half).writeBytes(packet, half, packet.length - half));
            case 6 -> Unpooled.directBuffer(packet.length).writeBytes(packet).asReadOnly();
            default -> {
                ByteBuf whole = PooledByteBufAllocator.DEFAULT.directBuffer(packet.length + 10);
                whole.writeBytes(new byte[5]).writeBytes(packet).writeBytes(new byte[5]);
                yield whole.slice(5, packet.length);
            }
        };
    }

    private static void tap(
            CaptureStream stream,
            int kind,
            int id,
            byte[] packet,
            int bufferKind,
            long nanos,
            CaptureStream.Session session,
            int ping) {
        ByteBuf body = wire(packet, bufferKind);
        if (stream.wants(kind, id)) stream.tap(kind, id, body, nanos, -64, 384, session, ping);
        body.release();
    }

    private static byte[] packet(int id, int length, SplittableRandom random) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        if (length > 0) bytes[0] = (byte) id;
        return bytes;
    }

    private static byte[] probe(int id, int wireId) {
        return ByteBuffer.allocate(5).put((byte) id).putInt(wireId).array();
    }

    @Test
    void wireBuffersOfEveryKindAndLengthSendTheSameBytes() throws NoSuchAlgorithmException {
        SplittableRandom random = new SplittableRandom(13);
        Digesting sink = new Digesting();
        CaptureStream.Session overworld = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.pingPacket(PING);
        stream.pongPacket(PONG);
        stream.join(sink, IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0), 0, overworld);
        stream.capture(new int[] {TICK_END, PONG, MOVE}, new int[] {PING, ENTITY}, new int[0]);
        long nanos = 0;
        int wireId = 1;
        long serverTick = 100;
        long verdicted = 0;
        int n = 0;
        for (int t = 1; t <= TICKS; t++) {
            int packets = t % 50 == 0 ? 400 : 32;
            for (int i = 0; i < packets; i++, n++) {
                nanos += 1_000 + random.nextInt(300_000);
                int length = i % 4 == 3 ? n % 1101 : n % 513;
                int bufferKind = (n + n / 1101) % KINDS;
                if (i % 5 == 4) {
                    tap(stream, IntakeWire.SERVERBOUND, MOVE, packet(MOVE, length, random), bufferKind, nanos, null, 0);
                } else {
                    byte[] entity = packet(ENTITY, length, random);
                    tap(stream, IntakeWire.CLIENTBOUND, ENTITY, entity, bufferKind, nanos, overworld, 0);
                }
                if (random.nextInt(8) == 0) {
                    stream.endOfTick(serverTick++);
                    nanos += 7_000;
                    byte[] ping = probe(PING, wireId++);
                    int end = IntakeWire.PING_END_OF_TICK;
                    tap(stream, IntakeWire.CLIENTBOUND, PING, ping, n % KINDS, nanos, overworld, end);
                    if (random.nextBoolean()) {
                        byte[] pong = probe(PONG, wireId - 1);
                        tap(stream, IntakeWire.SERVERBOUND, PONG, pong, n % KINDS, nanos + 1, null, 0);
                    }
                }
            }
            nanos += 20_000;
            byte[] foreign = probe(PING, wireId++);
            tap(stream, IntakeWire.CLIENTBOUND, PING, foreign, t % KINDS, nanos, overworld, IntakeWire.PING_FOREIGN);
            nanos += 20_000;
            tap(stream, IntakeWire.SERVERBOUND, PONG, probe(PONG, wireId - 2), (t + 3) % KINDS, nanos, null, 0);
            tap(stream, IntakeWire.SERVERBOUND, TICK_END, packet(TICK_END, 1, random), t % KINDS, nanos, null, 0);
            stream.cut(nanos);
            long upTo = t - random.nextInt(4);
            for (long index = verdicted + 1; index <= upTo; index++) {
                stream.verdict(index, index, 0, List.of(), null, List.of(), null);
            }
            verdicted = Math.max(verdicted, upTo);
        }
        for (long index = verdicted + 1; index <= TICKS; index++) {
            stream.verdict(index, index, 0, List.of(), null, List.of(), null);
        }
        stream.leave(nanos + 1_000);
        String hash = HexFormat.of().formatHex(sink.digest.digest());
        System.out.println("CAPTURE-WIRE-GOLDEN " + hash + " " + sink.sends + " sends " + sink.bytes + " B");
        assertEquals(EXPECTED, hash);
    }
}
