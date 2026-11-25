package ac.cult.cultac.protocol.codec.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.CodecState;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A position sync read into a sink against the record read before sinks existed, on generated packets and every
 * truncation of them: a linear sync's sunk values are the record's components, a stepped sync is the same record, and a
 * malformed sync fails with the same exception at the same reader index without reaching the sink. A sink that
 * declines, or a state that is no sink, gets the same record.
 */
class PositionSyncSinkEquivalenceTest {
    private static final ProtocolVersion[] VERSIONS = {
        ProtocolVersion.V1_21_11, ProtocolVersion.V26_2, ProtocolVersion.V26_3
    };
    private static final double[] SPECIAL = {
        0.0, -0.0, Double.NaN, Double.longBitsToDouble(0x7ff8_0000_0000_0001L), Double.POSITIVE_INFINITY, -1e-300
    };

    /** A test sink that keeps the last sync it took as the record it stands for. */
    private static final class RecordingSink implements CodecState, EntityPositionSyncCodec.Sink {
        boolean sinks = true;
        ClientboundEntityPositionSync sunk;
        long[] bits;
        int syncs;

        @Override
        public <T> T require(Class<T> type) {
            return CodecState.EMPTY.require(type);
        }

        @Override
        public boolean sinksPositionSync() {
            return sinks;
        }

        @Override
        public void positionSync(int entityId, double x, double y, double z, float yaw, float pitch, boolean onGround) {
            syncs++;
            bits = new long[] {
                Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z)
            };
            sunk = new ClientboundEntityPositionSync(
                    entityId, new PositionPath.Linear(new Vec3d(x, y, z)), yaw, pitch, onGround);
        }
    }

    @Test
    void sunkSyncsCarryTheRecordsValuesAndFailuresMatch() {
        Random random = new Random(59);
        int sunk = 0, stepped = 0, failed = 0;
        for (ProtocolVersion version : VERSIONS) {
            ProtocolContext plain = EntityListCodecEquivalenceTest.context(
                    ClientboundPackets.ENTITY_POSITION_SYNC, ProtocolData.load(version), 0);
            for (int n = 0; n < 300; n++) {
                ByteBuf bytes = packet(random, version);
                try {
                    for (int length = bytes.writerIndex(); length >= 0; length--) {
                        String expected =
                                outcome(bytes, length, plain, EntityListCodecEquivalenceTest::basePositionSync);
                        var sink = new RecordingSink();
                        var sinking = plain.withState(sink);
                        String actual = outcome(bytes, length, sinking, (input, context) -> {
                            Object read = new EntityPositionSyncCodec().read(input, context);
                            if (read != EntityPositionSyncCodec.SUNK) return read;
                            assertEquals(1, sink.syncs);
                            return sink.sunk;
                        });
                        assertEquals(expected, actual, version + " length " + length);
                        if (sink.syncs > 0) {
                            sunk++;
                            assertRawBits(bytes, length, plain, sink.bits);
                        } else if (expected.startsWith("Stepped")) stepped++;
                        else if (!expected.startsWith("Linear")) failed++;
                        assertTrue(sink.syncs <= 1);

                        sink.sinks = false;
                        int before = sink.syncs;
                        assertEquals(expected, outcome(bytes, length, sinking, new EntityPositionSyncCodec()::read));
                        assertEquals(before, sink.syncs, "a declining sink takes no sync");
                        assertEquals(expected, outcome(bytes, length, plain, new EntityPositionSyncCodec()::read));
                    }
                } finally {
                    bytes.release();
                }
            }
        }
        assertTrue(sunk > 400 && stepped > 40 && failed > 5000, sunk + " sunk, " + stepped + " stepped, " + failed);
    }

    /** The sunk doubles carry the record's bits, NaN payloads included. */
    private static void assertRawBits(ByteBuf bytes, int length, ProtocolContext plain, long[] bits) {
        ByteBuf input = bytes.retainedSlice(0, length);
        try {
            var sync = (ClientboundEntityPositionSync) EntityListCodecEquivalenceTest.basePositionSync(input, plain);
            Vec3d end = sync.position().endPosition();
            assertEquals(Double.doubleToRawLongBits(end.x()), bits[0]);
            assertEquals(Double.doubleToRawLongBits(end.y()), bits[1]);
            assertEquals(Double.doubleToRawLongBits(end.z()), bits[2]);
        } finally {
            input.release();
        }
    }

    private interface Read {
        Object read(ByteBuf input, ProtocolContext context);
    }

    private static String outcome(ByteBuf bytes, int length, ProtocolContext context, Read read) {
        ByteBuf input = bytes.retainedSlice(0, length);
        try {
            Object packet = read.read(input, context);
            String path = packet instanceof ClientboundEntityPositionSync sync
                    ? sync.position().getClass().getSimpleName()
                    : "?";
            return path + " " + packet + " left " + input.readableBytes();
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage() + " at " + input.readerIndex();
        } finally {
            input.release();
        }
    }

    private static ByteBuf packet(Random random, ProtocolVersion version) {
        ByteBuf bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, random.nextInt(1 << 20));
        if (version.atLeast(ProtocolVersion.V26_3)) {
            boolean stepped = random.nextInt(4) == 0;
            Wire.writeVarInt(bytes, stepped ? 1 : random.nextInt(3) == 0 ? 2 + random.nextInt(200) : 0);
            if (stepped) {
                int steps = random.nextInt(8) == 0 ? random.nextInt(2) - 1 : 1 + random.nextInt(4);
                if (random.nextInt(40) == 0) steps = 1000;
                Wire.writeVarInt(bytes, steps);
                for (int i = 0; i < Math.max(0, Math.min(steps, 4)); i++) {
                    Wire.writeVec3(bytes, vec(random));
                    Wire.writeVarInt(bytes, random.nextInt(4));
                }
            } else Wire.writeVec3(bytes, vec(random));
        } else {
            Wire.writeVec3(bytes, vec(random));
            for (int i = 0; i < 24; i++) bytes.writeByte(random.nextInt(256));
        }
        bytes.writeFloat(random.nextInt(10) == 0 ? Float.NaN : random.nextFloat() * 360 - 180)
                .writeFloat(random.nextFloat() * 180 - 90)
                .writeBoolean(random.nextBoolean());
        if (random.nextInt(10) == 0) bytes.writeByte(random.nextInt(256));
        return bytes;
    }

    private static Vec3d vec(Random random) {
        return new Vec3d(coordinate(random), coordinate(random), coordinate(random));
    }

    private static double coordinate(Random random) {
        return random.nextInt(8) == 0 ? SPECIAL[random.nextInt(SPECIAL.length)] : random.nextDouble() * 6.0e7 - 3.0e7;
    }
}
