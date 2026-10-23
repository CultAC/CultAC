package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BundleDelimiterEncodeTest {
    private static final ByteBufAllocator ALLOCATOR = new PooledByteBufAllocator(false);

    @Test
    void everyCallEncodesTheGenericBytesIntoItsOwnBuffer() {
        var model = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
        int encoded = 0;
        for (ProtocolVersion version : ProtocolVersion.values()) {
            var observed = ProtocolRuntime.create(ProtocolData.load(version));
            try (var cached = new ProjectedPackets(observed, model, () -> null);
                    var generic = new ProjectedPackets(observed, model, () -> null)) {
                for (int pass = 0; pass < 3; pass++) {
                    for (ConnectionPhase phase : ConnectionPhase.values()) {
                        String label = version + " " + phase + " pass " + pass;
                        String expected = outcome(() -> generic.encode(
                                phase,
                                ClientboundPackets.BUNDLE_DELIMITER,
                                ClientboundPackets.BUNDLE_DELIMITER.opaqueValue(),
                                ALLOCATOR,
                                CodecState.EMPTY));
                        String actual = outcome(() -> delimiter(cached, phase));
                        assertEquals(expected, actual, label);
                        if (!actual.startsWith("!")) encoded++;
                    }
                }
                var first = delimiter(cached, ConnectionPhase.PLAY);
                var second = delimiter(cached, ConnectionPhase.PLAY);
                ByteBuf a = first.frames().getFirst(), b = second.frames().getFirst();
                assertNotSame(a, b);
                a.setByte(0, a.getByte(0) ^ 0x55);
                assertTrue(a.release());
                assertEquals(1, b.refCnt());
                assertEquals(
                        describe(generic.encode(
                                ConnectionPhase.PLAY,
                                ClientboundPackets.BUNDLE_DELIMITER,
                                ClientboundPackets.BUNDLE_DELIMITER.opaqueValue(),
                                ALLOCATOR,
                                CodecState.EMPTY)),
                        describe(second),
                        version.toString());
                cached.close();
                generic.close();
                assertEquals(
                        outcome(() -> generic.encode(
                                ConnectionPhase.PLAY,
                                ClientboundPackets.BUNDLE_DELIMITER,
                                ClientboundPackets.BUNDLE_DELIMITER.opaqueValue(),
                                ALLOCATOR,
                                CodecState.EMPTY)),
                        outcome(() -> delimiter(cached, ConnectionPhase.PLAY)));
            }
        }
        assertTrue(encoded > 0);
    }

    private static ProjectedPackets.Encoded delimiter(ProjectedPackets packets, ConnectionPhase phase) {
        return new ProjectedPackets.Encoded(List.of(packets.encodeBundleDelimiter(phase, ALLOCATOR, CodecState.EMPTY)));
    }

    private static String outcome(Supplier<ProjectedPackets.Encoded> body) {
        try {
            return describe(body.get());
        } catch (RuntimeException failure) {
            return "!" + failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    /** Releases the frames after recording their bytes, indices, capacity and allocator; leak sampling wraps some. */
    private static String describe(ProjectedPackets.Encoded encoded) {
        var result = new StringBuilder().append(encoded.frames().size());
        for (ByteBuf frame : encoded.frames()) {
            result.append(' ')
                    .append(ByteBufUtil.hexDump(frame))
                    .append('/')
                    .append(frame.readerIndex())
                    .append('/')
                    .append(frame.writerIndex())
                    .append('/')
                    .append(frame.capacity())
                    .append('/')
                    .append(frame.alloc() == ALLOCATOR)
                    .append('/')
                    .append(frame.isDirect())
                    .append('/')
                    .append(frame.refCnt());
            frame.release();
        }
        return result.toString();
    }
}
