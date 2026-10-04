package ac.cult.cultac.protocol;

import static ac.cult.cultac.protocol.ConnectionPhase.*;
import static ac.cult.cultac.protocol.PacketDirection.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.*;
import ac.cult.cultac.protocol.packet.clientbound.*;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectedPacketsTest {
    record ModelValue(int id) implements ClientboundPacket {}

    private static final PacketType<ModelValue> REGISTRY = new PacketType<>(
            "clientbound.registry_data",
            ModelValue.class,
            CLIENTBOUND,
            Set.of(CONFIGURATION),
            List.of("minecraft:registry_data"),
            ProtocolVersion.V26_3,
            new WritablePacketCodec<>() {
                public boolean requiresModelValues() {
                    return true;
                }

                public ModelValue read(ByteBuf input, ProtocolContext context) {
                    assertEquals(ProtocolVersion.V26_3, context.version());
                    return new ModelValue(Wire.readVarInt(input));
                }

                public void write(ByteBuf output, ProtocolContext context, ModelValue value) {
                    assertEquals(ProtocolVersion.V26_3, context.version());
                    Wire.writeVarInt(output, value.id());
                }
            });
    private static final ProtocolRuntime MODEL = model();

    private static ProtocolRuntime model() {
        var catalog = new ArrayList<>(Packets.all());
        catalog.add(REGISTRY);
        return ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3), catalog);
    }

    @Test
    void everyVersionReadsPurePacketsWithoutInvokingValueDecoder() {
        for (var version : ProtocolVersion.values()) {
            var wire = ProtocolRuntime.create(ProtocolData.load(version));
            try (var packets = new ProjectedPackets(wire, MODEL, () -> {
                throw new AssertionError();
            })) {
                ByteBuf input = frame(wire, PLAY, SERVERBOUND, "accept_teleportation");
                Wire.writeVarInt(input, 42);
                if (version == ProtocolVersion.V26_3) input.writeZero(32);
                byte[] original = ByteBufUtil.getBytes(input);
                try {
                    var values = packets.read(PLAY, SERVERBOUND, input, CodecState.EMPTY, ignored -> true);
                    assertEquals(1, values.size());
                    assertSame(
                            ServerboundPackets.ACCEPT_TELEPORTATION,
                            values.get(0).type());
                    assertEquals(
                            new ServerboundAcceptTeleportation(
                                    42,
                                    version == ProtocolVersion.V26_3
                                            ? new ac.cult.cultac.protocol.value.Vec3d(0, 0, 0)
                                            : null,
                                    0,
                                    0),
                            values.get(0).packet());
                    assertEquals(0, input.readerIndex());
                    assertEquals(1, input.refCnt());
                    assertArrayEquals(original, ByteBufUtil.getBytes(input));
                    var encoded = packets.encode(
                            PLAY,
                            ClientboundPackets.PING,
                            new ClientboundPing(123),
                            UnpooledByteBufAllocator.DEFAULT,
                            CodecState.EMPTY);
                    try {
                        var output = encoded.frames().get(0);
                        assertEquals(
                                wire.data().packets(PLAY, CLIENTBOUND).id("minecraft:ping"), Wire.readVarInt(output));
                        assertEquals(123, output.readInt());
                        assertFalse(output.isReadable());
                    } finally {
                        encoded.frames().forEach(ByteBuf::release);
                    }
                } finally {
                    input.release();
                }
            }
        }
    }

    @Test
    void consumedValuesUseObservedSchemaAndPreservePhysicalFrame() {
        for (var version : ProtocolVersion.values()) {
            var wire = ProtocolRuntime.create(ProtocolData.load(version));
            var calls = new AtomicInteger();
            var adapter = new PacketValueAdapter() {
                public Object read(ByteBuf input, ProtocolContext context) {
                    calls.incrementAndGet();
                    assertEquals(version, context.version());
                    return new ModelValue(Wire.readVarInt(input));
                }

                public void write(ByteBuf output, ProtocolContext context, Object value) {
                    calls.incrementAndGet();
                    assertEquals(version, context.version());
                    Wire.writeVarInt(output, ((ModelValue) value).id());
                }
            };
            // The native runtime includes the value catalog; older runtimes intentionally do not.
            try (var packets =
                    new ProjectedPackets(version == ProtocolVersion.V26_3 ? MODEL : wire, MODEL, () -> adapter)) {
                ByteBuf input = frame(wire, CONFIGURATION, CLIENTBOUND, "registry_data");
                Wire.writeVarInt(input, 17);
                byte[] original = ByteBufUtil.getBytes(input);
                try {
                    assertTrue(packets.read(CONFIGURATION, CLIENTBOUND, input, CodecState.EMPTY, ignored -> false)
                            .isEmpty());
                    assertEquals(0, calls.get());
                    var values = packets.read(CONFIGURATION, CLIENTBOUND, input, CodecState.EMPTY, ignored -> true);
                    assertEquals(List.of(new ProjectedPackets.Value(REGISTRY, new ModelValue(17))), values);
                    assertArrayEquals(original, ByteBufUtil.getBytes(input));
                    assertEquals(0, input.readerIndex());
                    var encoded = packets.encode(
                            CONFIGURATION,
                            REGISTRY,
                            new ModelValue(18),
                            UnpooledByteBufAllocator.DEFAULT,
                            CodecState.EMPTY);
                    try {
                        assertEquals(1, encoded.frames().size());
                        var output = encoded.frames().get(0);
                        assertEquals(
                                wire.data().packets(CONFIGURATION, CLIENTBOUND).id("minecraft:registry_data"),
                                Wire.readVarInt(output));
                        assertEquals(18, Wire.readVarInt(output));
                        assertFalse(output.isReadable());
                    } finally {
                        encoded.frames().forEach(ByteBuf::release);
                    }
                    assertEquals(version == ProtocolVersion.V26_3 ? 0 : 2, calls.get());
                } finally {
                    input.release();
                }
            }
        }
    }

    @Test
    void finishConfigurationIsOneOriginalAction() {
        var wire = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V1_21_3));
        try (var packets = new ProjectedPackets(wire, MODEL, () -> {
            throw new AssertionError();
        })) {
            var input = frame(wire, CONFIGURATION, CLIENTBOUND, "finish_configuration");
            try {
                var values = packets.read(CONFIGURATION, CLIENTBOUND, input, CodecState.EMPTY, ignored -> true);
                assertEquals(
                        List.of(ClientboundPackets.FINISH_CONFIGURATION),
                        values.stream().map(ProjectedPackets.Value::type).toList());
            } finally {
                input.release();
            }
        }
    }

    private static ByteBuf frame(
            ProtocolRuntime runtime, ConnectionPhase phase, PacketDirection direction, String name) {
        var bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, runtime.data().packets(phase, direction).id("minecraft:" + name));
        return bytes;
    }
}
