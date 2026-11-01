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
import java.lang.reflect.Array;
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
    void everyVersionLeavesUnretainedPluginMessagesUndecoded() {
        for (var version : ProtocolVersion.values()) {
            var wire = ProtocolRuntime.create(ProtocolData.load(version));
            try (var packets = new ProjectedPackets(wire, MODEL, () -> {
                throw new AssertionError();
            })) {
                for (String channel : List.of("minecraft:brand", "cultac:bridge")) {
                    ByteBuf input = frame(wire, PLAY, CLIENTBOUND, "custom_payload");
                    Wire.writeString(input, channel, 32767);
                    input.writeByte(1);
                    try {
                        var values = packets.read(PLAY, CLIENTBOUND, input, CodecState.EMPTY, ignored -> true);
                        assertEquals(channel.equals("cultac:bridge") ? 1 : 0, values.size(), version + " " + channel);
                        assertEquals(0, input.readerIndex());
                    } finally {
                        input.release();
                    }
                }
            }
        }
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

    /** A reader of the value may reuse whatever the predicate looked up for the type it accepted. */
    @Test
    void aValueCarriesTheOneTypeTheConsumedPredicateAccepted() {
        var adapted = new AtomicInteger();
        var adapter = new PacketValueAdapter() {
            public Object read(ByteBuf input, ProtocolContext context) {
                input.skipBytes(input.readableBytes());
                Object value = defaultRecord(context.type().recordClass());
                adapted.incrementAndGet();
                return value;
            }

            public void write(ByteBuf output, ProtocolContext context, Object value) {
                throw new AssertionError();
            }
        };
        int values = 0;
        int translatedValues = 0;
        for (var version : ProtocolVersion.values()) {
            var wire = ProtocolRuntime.create(ProtocolData.load(version));
            try (var packets = new ProjectedPackets(wire, MODEL, () -> adapter)) {
                for (var phase : ConnectionPhase.values()) {
                    for (var direction : PacketDirection.values()) {
                        int ids = wire.data().packets(phase, direction).size();
                        for (int id = -1; id <= ids; id++) {
                            for (boolean accept : new boolean[] {true, false}) {
                                List<PacketType<?>> tested = new ArrayList<>();
                                var input = Unpooled.buffer();
                                Wire.writeVarInt(input, id);
                                input.writeZero(64);
                                Object value = null;
                                int adaptedBefore = adapted.get();
                                try {
                                    value = packets.readOne(phase, direction, input, CodecState.EMPTY, type -> {
                                        tested.add(type);
                                        return accept;
                                    });
                                } catch (RuntimeException ignored) {
                                    // Zero payloads are malformed for many families; the predicate is what matters.
                                } finally {
                                    input.release();
                                }
                                String where = version + "/" + phase + "/" + direction + "/" + id;
                                assertTrue(tested.size() <= 1, where);
                                if (id >= 0 && id < ids) {
                                    var expected = packets.type(phase, direction, id);
                                    assertEquals(expected == null ? List.of() : List.of(expected), tested, where);
                                }
                                if (value != null) {
                                    assertTrue(accept, where);
                                    assertTrue(tested.getFirst().recordClass().isInstance(value), where);
                                    values++;
                                    if (adapted.get() > adaptedBefore) translatedValues++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(values > 0);
        assertTrue(translatedValues > 0);
    }

    /** A record of {@code type} built from zero, false and null components. */
    private static Object defaultRecord(Class<?> type) {
        if (!type.isRecord()) throw new UnsupportedOperationException(type.getName());
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            arguments[i] = types[i].isPrimitive() ? Array.get(Array.newInstance(types[i], 1), 0) : null;
        }
        try {
            var constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException(type.getName(), e);
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
    void declinedValuesPassOnlyWhenNotRequired() {
        var wire = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V1_21_11));
        var adapter = new PacketValueAdapter() {
            public Object read(ByteBuf input, ProtocolContext context) {
                return null;
            }

            public void write(ByteBuf output, ProtocolContext context, Object value) {
                throw new AssertionError();
            }
        };
        CodecState optional = new CodecState() {
            public <T> T require(Class<T> type) {
                return CodecState.EMPTY.require(type);
            }

            public boolean requiresValues() {
                return false;
            }
        };
        try (var packets = new ProjectedPackets(wire, MODEL, () -> adapter)) {
            var input = frame(wire, CONFIGURATION, CLIENTBOUND, "registry_data");
            try {
                assertEquals(List.of(), packets.read(CONFIGURATION, CLIENTBOUND, input, optional, ignored -> true));
                assertThrows(
                        ProtocolResolutionException.class,
                        () -> packets.read(CONFIGURATION, CLIENTBOUND, input, CodecState.EMPTY, ignored -> true));
            } finally {
                input.release();
            }
        }
    }

    @Test
    void runtimeDecodeDeclinesOnlyForStatesThatDoNotRequireValues() {
        var declined = new PacketType<>(
                "clientbound.registry_data",
                ModelValue.class,
                CLIENTBOUND,
                Set.of(CONFIGURATION),
                List.of("minecraft:registry_data"),
                ProtocolVersion.V26_3,
                new PacketCodec<ModelValue>() {
                    public ModelValue read(ByteBuf input, ProtocolContext context) {
                        return null;
                    }
                });
        var catalog = new ArrayList<>(Packets.all());
        catalog.add(declined);
        var runtime = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3), catalog);
        CodecState optional = new CodecState() {
            public <T> T require(Class<T> type) {
                return CodecState.EMPTY.require(type);
            }

            public boolean requiresValues() {
                return false;
            }
        };
        int id = runtime.data().packets(CONFIGURATION, CLIENTBOUND).id("minecraft:registry_data");
        var input = Unpooled.buffer();
        try {
            assertNull(runtime.decode(CONFIGURATION, CLIENTBOUND, id, input, optional));
            assertThrows(
                    ProtocolResolutionException.class,
                    () -> runtime.decode(CONFIGURATION, CLIENTBOUND, id, input, CodecState.EMPTY));
        } finally {
            input.release();
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

    @Test
    void borrowedFrameRestoresIndicesAndMarksAfterFailedReads() {
        var wire = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
        try (var packets = new ProjectedPackets(wire, MODEL, () -> {
            throw new AssertionError();
        })) {
            var input = Unpooled.buffer();
            try {
                input.writeByte(99);
                input.readerIndex(1).markReaderIndex();
                Wire.writeVarInt(input, wire.data().packets(PLAY, SERVERBOUND).id("minecraft:accept_teleportation"));
                Wire.writeVarInt(input, 42); // Missing the 26.3 position payload.
                int end = input.writerIndex();
                byte[] original = ByteBufUtil.getBytes(input, 0, end);
                assertThrows(
                        MalformedPacketException.class,
                        () -> packets.read(PLAY, SERVERBOUND, input, CodecState.EMPTY, ignored -> true));
                assertEquals(1, input.readerIndex());
                assertEquals(end, input.writerIndex());
                input.skipBytes(1).resetReaderIndex();
                assertEquals(1, input.readerIndex());
                assertEquals(1, input.refCnt());
                assertArrayEquals(original, ByteBufUtil.getBytes(input, 0, end));
                assertThrows(
                        IllegalStateException.class,
                        () -> packets.read(PLAY, SERVERBOUND, input, CodecState.EMPTY, ignored -> {
                            throw new IllegalStateException();
                        }));
                assertEquals(1, input.readerIndex());
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
