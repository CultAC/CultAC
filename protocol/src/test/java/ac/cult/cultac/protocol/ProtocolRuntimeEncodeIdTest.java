package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ProtocolRuntimeEncodeIdTest {
    @Test
    void precomputedIdsMatchTheFirstBoundNameInEveryPhase() {
        int compared = 0;
        for (ProtocolVersion version : ProtocolVersion.values()) {
            var runtime = ProtocolRuntime.create(ProtocolData.load(version));
            for (PacketType<?> type : Packets.all()) {
                if (type.codec() instanceof VariantCodec<?>) continue;
                for (ConnectionPhase phase : ConnectionPhase.values()) {
                    assertEquals(
                            firstBoundId(runtime, phase, type),
                            runtime.encodeId(phase, type),
                            version + " " + phase + " " + type);
                    compared++;
                }
            }
        }
        assertTrue(compared > 0);
    }

    @Test
    void writableEmptyFamiliesEncodeTheSameBytesAndFailuresAsTheNameScan() {
        int encoded = 0;
        for (ProtocolVersion version : ProtocolVersion.values()) {
            var runtime = ProtocolRuntime.create(ProtocolData.load(version));
            for (PacketType<?> type : Packets.all()) {
                if (!type.isOpaque() || type.codec() instanceof VariantCodec<?>) continue;
                for (ConnectionPhase phase : ConnectionPhase.values()) {
                    String expected = outcome(() -> scanEncode(runtime, phase, opaque(type)));
                    String actual = outcome(() -> {
                        var output = Unpooled.buffer();
                        runtime.encode(phase, opaque(type), opaque(type).opaqueValue(), output);
                        return ByteBufUtil.hexDump(output);
                    });
                    assertEquals(expected, actual, version + " " + phase + " " + type);
                    if (!actual.startsWith("!")) encoded++;
                }
            }
        }
        assertTrue(encoded > 0);
    }

    @SuppressWarnings("unchecked") // Opaque families carry Opaque records.
    private static PacketType<Opaque> opaque(PacketType<?> type) {
        return (PacketType<Opaque>) type;
    }

    /** The encode routine before the per-phase table: scan the declared names for the first bound to the family. */
    private static String scanEncode(ProtocolRuntime runtime, ConnectionPhase phase, PacketType<Opaque> type) {
        if (!runtime.supports(type)) throw new UnsupportedOnVersionException("Unsupported type " + type);
        if (!type.writable()) throw new UnsupportedOnVersionException("Read-only packet " + type);
        int id = firstBoundId(runtime, phase, type);
        var bound = runtime.binding(phase, type.direction(), id);
        if (bound == null || bound.type() != type) {
            throw new UnsupportedOnVersionException(type + " is unavailable in " + phase);
        }
        var output = Unpooled.buffer();
        Wire.writeVarInt(output, id);
        type.codec().write(output, CodecState.EMPTY.bind(bound.context()), type.opaqueValue());
        return ByteBufUtil.hexDump(output);
    }

    private static int firstBoundId(ProtocolRuntime runtime, ConnectionPhase phase, PacketType<?> type) {
        var names = type.wireNames();
        var packets = runtime.data().packets(phase, type.direction());
        for (int i = 0; i < names.size(); i++) {
            int candidateId = packets.id(names.get(i));
            var candidate = runtime.binding(phase, type.direction(), candidateId);
            if (candidate != null && candidate.type() == type) return candidateId;
        }
        return -1;
    }

    private static String outcome(Supplier<String> body) {
        try {
            return body.get();
        } catch (RuntimeException failure) {
            return "!" + failure.getClass().getName() + ": " + failure.getMessage();
        }
    }
}
