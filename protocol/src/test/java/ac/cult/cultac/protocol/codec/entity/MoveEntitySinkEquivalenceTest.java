package ac.cult.cultac.protocol.codec.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.CodecState;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A move read into a sink against the record read before sinks existed, on generated packets and every truncation of
 * them: a linear move's sunk values are the record's components, a stepped move is the same record, and a malformed
 * move fails with the same exception at the same reader index without reaching the sink. A sink that declines, or a
 * state that is no sink, gets the same record.
 */
class MoveEntitySinkEquivalenceTest {
    private static final ProtocolVersion[] VERSIONS = {
        ProtocolVersion.V1_21_11, ProtocolVersion.V26_2, ProtocolVersion.V26_3
    };

    /** A test sink that keeps the last move it took as the record it stands for. */
    private static final class RecordingSink implements CodecState, MoveEntityCodec.Sink {
        boolean sinks = true;
        ClientboundMoveEntity sunk;
        int moves;

        @Override
        public <T> T require(Class<T> type) {
            return CodecState.EMPTY.require(type);
        }

        @Override
        public boolean sinksMove() {
            return sinks;
        }

        @Override
        public void move(
                int entityId,
                short x,
                short y,
                short z,
                float yaw,
                float pitch,
                boolean onGround,
                boolean hasPosition,
                boolean hasRotation) {
            moves++;
            EntityDelta delta = hasPosition ? new EntityDelta.Linear(x, y, z) : EntityDelta.ZERO;
            sunk = new ClientboundMoveEntity(entityId, delta, yaw, pitch, onGround, hasPosition, hasRotation);
        }
    }

    @Test
    void sunkMovesCarryTheRecordsValuesAndFailuresMatch() {
        Random random = new Random(53);
        int sunk = 0, stepped = 0, failed = 0;
        for (ProtocolVersion version : VERSIONS) {
            ProtocolData data = ProtocolData.load(version);
            for (int variant = 0; variant < 3; variant++) {
                ProtocolContext plain =
                        EntityListCodecEquivalenceTest.context(ClientboundPackets.MOVE_ENTITY, data, variant);
                for (int n = 0; n < 300; n++) {
                    ByteBuf bytes = packet(random, version, variant);
                    try {
                        for (int length = bytes.writerIndex(); length >= 0; length--) {
                            String expected = outcome(bytes, length, plain, EntityListCodecEquivalenceTest::baseMove);
                            var sink = new RecordingSink();
                            var sinking = plain.withState(sink);
                            String actual = outcome(bytes, length, sinking, (input, context) -> {
                                Object read = new MoveEntityCodec().read(input, context);
                                if (read != MoveEntityCodec.SUNK) return read;
                                assertEquals(1, sink.moves);
                                return sink.sunk;
                            });
                            assertEquals(expected, actual, version + " variant " + variant + " length " + length);
                            if (sink.moves > 0) sunk++;
                            else if (expected.startsWith("Stepped")) stepped++;
                            else if (!expected.startsWith("Linear")) failed++;
                            assertTrue(sink.moves <= 1);

                            sink.sinks = false;
                            int before = sink.moves;
                            assertEquals(expected, outcome(bytes, length, sinking, new MoveEntityCodec()::read));
                            assertEquals(before, sink.moves, "a declining sink takes no move");
                            assertEquals(expected, outcome(bytes, length, plain, new MoveEntityCodec()::read));
                        }
                    } finally {
                        bytes.release();
                    }
                }
            }
        }
        assertTrue(sunk > 1000 && stepped > 100 && failed > 1000, sunk + " sunk, " + stepped + " stepped, " + failed);
    }

    @Test
    void aMoveWithoutPositionIsSunkWithAZeroDelta() {
        var sink = new RecordingSink();
        ProtocolContext context = EntityListCodecEquivalenceTest.context(
                        ClientboundPackets.MOVE_ENTITY, ProtocolData.load(ProtocolVersion.V26_3), 2)
                .withState(sink);
        ByteBuf bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, 9);
        bytes.writeBoolean(true).writeByte(64).writeByte(32);
        try {
            assertSame(MoveEntityCodec.SUNK, new MoveEntityCodec().read(bytes, context));
            assertEquals(EntityListCodecEquivalenceTest.baseMove(bytes.readerIndex(0), context), sink.sunk);
            assertSame(EntityDelta.ZERO, sink.sunk.delta());
        } finally {
            bytes.release();
        }
    }

    private interface Read {
        Object read(ByteBuf input, ProtocolContext context);
    }

    private static String outcome(ByteBuf bytes, int length, ProtocolContext context, Read read) {
        ByteBuf input = bytes.retainedSlice(0, length);
        try {
            Object packet = read.read(input, context);
            String delta = packet instanceof ClientboundMoveEntity move
                    ? move.delta().getClass().getSimpleName()
                    : "?";
            return delta + " " + packet + " left " + input.readableBytes();
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage() + " at " + input.readerIndex();
        } finally {
            input.release();
        }
    }

    private static ByteBuf packet(Random random, ProtocolVersion version, int variant) {
        ByteBuf bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, random.nextInt(1 << 20));
        boolean position = variant != 2;
        boolean rotation = variant != 0;
        if (version.atLeast(ProtocolVersion.V26_3)) {
            if (position) {
                int steps = random.nextInt(3) == 0 ? random.nextInt(4) : 0;
                if (random.nextInt(40) == 0) steps = 1000;
                Wire.writeVarInt(bytes, steps << 1 | random.nextInt(2));
                for (int i = 0; i < (steps == 0 ? 0 : Math.min(steps, 4)); i++) {
                    Wire.writeVarInt(bytes, 1 + random.nextInt(3));
                    shorts(bytes, random);
                }
                if (steps == 0) shorts(bytes, random);
            } else bytes.writeBoolean(random.nextBoolean());
        } else if (position) shorts(bytes, random);
        if (rotation) bytes.writeByte(random.nextInt(256)).writeByte(random.nextInt(256));
        if (!version.atLeast(ProtocolVersion.V26_3)) bytes.writeBoolean(random.nextBoolean());
        if (random.nextInt(10) == 0) bytes.writeByte(random.nextInt(256));
        return bytes;
    }

    private static void shorts(ByteBuf bytes, Random random) {
        for (int i = 0; i < 3; i++) bytes.writeShort(random.nextInt(65536));
    }
}
